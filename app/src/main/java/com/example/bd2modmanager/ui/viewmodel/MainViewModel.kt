package com.example.bd2modmanager.ui.viewmodel

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chaquo.python.Python
import com.example.bd2modmanager.service.ModdingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream

// Data class to hold information about a single mod
data class ModInfo(
    val name: String,
    val character: String,
    val costume: String,
    val type: String,
    val isEnabled: Boolean,
    val uri: Uri,
    val targetHashedName: String?,
    val isDirectory: Boolean // Flag to indicate if the mod is a directory
)

// Data class for the character lookup map
data class CharacterInfo(val character: String, val costume: String, val type: String, val hashedName: String)

data class ModDetails(val fileId: String?, val type: String)

// Data class for a batch repack job
data class RepackJob(val hashedName: String, val modsToInstall: List<ModInfo>)

// Represents the state of the installation process
sealed class InstallState {
    object Idle : InstallState()
    data class AwaitingOriginalFile(val job: RepackJob) : InstallState()
    data class Installing(val job: RepackJob) : InstallState()
    data class Finished(val publicUri: Uri, val job: RepackJob) : InstallState()
    data class Failed(val error: String) : InstallState()
}

class MainViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {

    companion object {
        private const val CHARACTERS_JSON_URL = "https://codeberg.org/kxdekxde/browndust2-mod-manager/raw/branch/main/characters.json"
        private const val CHARACTERS_JSON_FILENAME = "characters.json"
    }

    // --- STATE FLOWS ---
    val modSourceDirectoryUri: StateFlow<Uri?> = savedStateHandle.getStateFlow("mod_source_dir_uri", null)

    private val _groupedMods = MutableStateFlow<Map<String, List<ModInfo>>>(emptyMap())
    val groupedMods: StateFlow<Map<String, List<ModInfo>>> = _groupedMods.asStateFlow()

    private val _selectedMods = MutableStateFlow<Set<Uri>>(emptySet())
    val selectedMods: StateFlow<Set<Uri>> = _selectedMods.asStateFlow()

    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    private val _repackQueue = MutableStateFlow<List<RepackJob>>(emptyList())

    private var characterLut: Map<String, List<CharacterInfo>> = emptyMap()

    // --- INITIALIZATION ---
    fun initialize(context: Context) {
        viewModelScope.launch {
            val internalFile = File(context.filesDir, CHARACTERS_JSON_FILENAME)
            if (!internalFile.exists()) {
                try {
                    withContext(Dispatchers.IO) {
                        context.assets.open(CHARACTERS_JSON_FILENAME).use { i -> internalFile.outputStream().use { o -> i.copyTo(o) } }
                        println("Copied bundled characters.json to internal storage.")
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }

            updateCharacterData(context)
            modSourceDirectoryUri.value?.let { scanModSourceDirectory(context, it) }

            // Observer for the repack queue
            _repackQueue.collect { queue ->
                if (_installState.value is InstallState.Idle && queue.isNotEmpty()) {
                    _installState.value = InstallState.AwaitingOriginalFile(queue.first())
                }
            }
        }
    }

    private suspend fun updateCharacterData(context: Context) {
        withContext(Dispatchers.IO) {
            val localFile = File(context.filesDir, CHARACTERS_JSON_FILENAME)
            try {
                localFile.writeText(URL(CHARACTERS_JSON_URL).readText())
                println("Successfully downloaded and saved characters.json")
            } catch (e: Exception) {
                e.printStackTrace()
                println("Failed to download characters.json, will use local version.")
            }
            characterLut = parseCharacterJson(context)
        }
    }

    // --- PUBLIC ACTIONS ---
    fun setModSourceDirectoryUri(context: Context, uri: Uri?) {
        savedStateHandle["mod_source_dir_uri"] = uri
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            scanModSourceDirectory(context, uri)
        }
    }

    fun toggleModSelection(modUri: Uri) {
        _selectedMods.value = if (modUri in _selectedMods.value) _selectedMods.value - modUri else _selectedMods.value + modUri
    }

    fun initiateBatchRepack() {
        val allMods = _groupedMods.value.values.flatten()
        val jobs = _selectedMods.value
            .mapNotNull { uri -> allMods.find { it.uri == uri } }
            .filter { !it.targetHashedName.isNullOrBlank() }
            .groupBy { it.targetHashedName!! }
            .map { (hash, mods) -> RepackJob(hash, mods) }

        if (jobs.isNotEmpty()) {
            _repackQueue.value = jobs
        }
    }

    fun proceedWithInstall(context: Context, originalDataUri: Uri) {
        val currentState = _installState.value
        if (currentState !is InstallState.AwaitingOriginalFile) return
        val job = currentState.job

        viewModelScope.launch {
            _installState.value = InstallState.Installing(job)
            withContext(Dispatchers.IO) {
                try {
                    if (!Python.isStarted()) {
                        Python.start(com.chaquo.python.android.AndroidPlatform(context))
                    }

                    val cacheDir = context.cacheDir
                    val originalDataCache = File(cacheDir, "temp_original__data")
                    val modAssetsDir = File(cacheDir, "temp_mod_assets")

                    // Cleanup previous run
                    if (modAssetsDir.exists()) modAssetsDir.deleteRecursively()
                    modAssetsDir.mkdirs()

                    // Copy original __data file
                    context.contentResolver.openInputStream(originalDataUri)?.use { it.copyTo(originalDataCache.outputStream()) }

                    // Extract all selected mods for this job
                    job.modsToInstall.forEach { modInfo ->
                        if (modInfo.isDirectory) {
                            DocumentFile.fromTreeUri(context, modInfo.uri)?.let { copyDirectoryToCache(context, it, modAssetsDir) }
                        } else {
                            context.contentResolver.openInputStream(modInfo.uri)?.use { fis ->
                                ZipInputStream(fis).use { zis ->
                                    var entry = zis.nextEntry
                                    while (entry != null) {
                                        val newFile = File(modAssetsDir, entry.name)
                                        if (entry.isDirectory) newFile.mkdirs() else newFile.outputStream().use { fos -> zis.copyTo(fos) }
                                        entry = zis.nextEntry
                                    }
                                }
                            }
                        }
                    }

                    // Repack using Python script
                    val repackedDataCache = File(cacheDir, "__${job.hashedName}")
                    val (success, message) = ModdingService.repackBundle(originalDataCache.absolutePath, modAssetsDir.absolutePath, repackedDataCache.absolutePath)

                    // Cleanup
                    originalDataCache.delete()
                    modAssetsDir.deleteRecursively()

                    if (success) {
                        val publicUri = saveFileToDownloads(context, repackedDataCache)
                        repackedDataCache.delete()
                        if (publicUri != null) {
                            _installState.value = InstallState.Finished(publicUri, job)
                        } else {
                            _installState.value = InstallState.Failed("Failed to save file to public Downloads folder.")
                        }
                    } else {
                        repackedDataCache.delete()
                        _installState.value = InstallState.Failed(message)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    _installState.value = InstallState.Failed(e.message ?: "An unknown error occurred.")
                }
            }
        }
    }

    fun resetInstallState() {
        val currentState = _installState.value
        if (currentState is InstallState.Finished || currentState is InstallState.Failed) {
            _repackQueue.value = _repackQueue.value.drop(1)
        } else { // User cancelled
            _repackQueue.value = emptyList()
        }
        _installState.value = InstallState.Idle
    }

    // --- PRIVATE HELPERS ---
    private fun scanModSourceDirectory(context: Context, dirUri: Uri) {
        viewModelScope.launch {
            _groupedMods.value = emptyMap()
            val modsList = withContext(Dispatchers.IO) {
                DocumentFile.fromTreeUri(context, dirUri)?.listFiles()?.mapNotNull { file ->
                    when {
                        file.isFile && file.name?.endsWith(".zip", ignoreCase = true) == true -> {
                            val modName = file.name!!.removeSuffix(".zip")
                            val modDetails = extractModDetailsFromUri(context, file.uri)
                            val characterEntries = characterLut[modDetails.fileId]
                            val specificEntry = characterEntries?.find { it.type == modDetails.type }
                            ModInfo(modName, specificEntry?.character ?: "Unknown", specificEntry?.costume ?: "Unknown", modDetails.type, false, file.uri, specificEntry?.hashedName, false)
                        }
                        file.isDirectory -> {
                            val modName = file.name!!
                            val modDetails = extractModDetailsFromDirectory(context, file.uri)
                            val characterEntries = characterLut[modDetails.fileId]
                            val specificEntry = characterEntries?.find { it.type == modDetails.type }
                            ModInfo(modName, specificEntry?.character ?: "Unknown", specificEntry?.costume ?: "Unknown", modDetails.type, false, file.uri, specificEntry?.hashedName, true)
                        }
                        else -> null
                    }
                } ?: emptyList()
            }
            _groupedMods.value = modsList.sortedBy { it.name }.groupBy { it.targetHashedName ?: "Unknown" }
            _selectedMods.value = emptySet() // Clear selection on refresh
        }
    }

    private fun saveFileToDownloads(context: Context, file: File): Uri? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            try {
                resolver.openOutputStream(it)?.use { outputStream -> file.inputStream().use { inputStream -> inputStream.copyTo(outputStream) } }
                return it
            } catch (e: Exception) {
                e.printStackTrace()
                resolver.delete(it, null, null)
            }
        }
        return null
    }

    private fun extractModDetailsFromUri(context: Context, zipUri: Uri): ModDetails {
        var fileId: String? = null
        var modType = "idle"
        try {
            context.contentResolver.openInputStream(zipUri)?.use { ZipInputStream(it).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (fileId == null) fileId = extractFileId(entry.name)
                    if (entry.name.contains("cutscene", ignoreCase = true)) modType = "cutscene"
                    entry = zis.nextEntry
                }
            }}
        } catch (e: Exception) { e.printStackTrace() }
        return ModDetails(fileId, modType)
    }

    private fun extractModDetailsFromDirectory(context: Context, dirUri: Uri): ModDetails {
        var fileId: String? = null
        var modType = "idle"
        fun findDetails(currentDir: DocumentFile) {
            currentDir.listFiles().forEach { file ->
                if (file.isDirectory) findDetails(file)
                else {
                    val entryName = file.name ?: ""
                    if (fileId == null) fileId = extractFileId(entryName)
                    if (entryName.contains("cutscene", ignoreCase = true)) modType = "cutscene"
                }
            }
        }
        try { DocumentFile.fromTreeUri(context, dirUri)?.let { findDetails(it) } } catch (e: Exception) { e.printStackTrace() }
        return ModDetails(fileId, modType)
    }

    private fun copyDirectoryToCache(context: Context, sourceDir: DocumentFile, destinationDir: File) {
        if (!destinationDir.exists()) destinationDir.mkdirs()
        sourceDir.listFiles().forEach { file ->
            val destFile = File(destinationDir, file.name!!)
            if (file.isDirectory) {
                copyDirectoryToCache(context, file, destFile)
            } else {
                try {
                    context.contentResolver.openInputStream(file.uri)?.use { input -> destFile.outputStream().use { output -> input.copyTo(output) } }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    private suspend fun parseCharacterJson(context: Context): Map<String, List<CharacterInfo>> {
        return withContext(Dispatchers.IO) {
            val lut = mutableMapOf<String, MutableList<CharacterInfo>>()
            val internalFile = File(context.filesDir, CHARACTERS_JSON_FILENAME)
            if (!internalFile.exists()) return@withContext emptyMap()
            val jsonString = try { internalFile.readText() } catch (e: Exception) { e.printStackTrace(); "" }
            if (jsonString.isNotEmpty()) {
                try {
                    val jsonArray = JSONArray(jsonString)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val fileId = obj.getString("file_id")
                        val charInfo = CharacterInfo(obj.getString("character"), obj.getString("costume"), obj.getString("type"), obj.getString("hashed_name"))
                        lut.getOrPut(fileId) { mutableListOf() }.add(charInfo)
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
            lut
        }
    }

    private fun extractFileId(entryName: String): String? {
        return "(char\\d{6}|illust_\\w+|npc\\d+)".toRegex(RegexOption.IGNORE_CASE).find(entryName)?.value?.lowercase()
    }
}