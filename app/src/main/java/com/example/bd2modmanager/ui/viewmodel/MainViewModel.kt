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
import com.example.bd2modmanager.SpinePreviewActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream

data class ModInfo(
    val name: String,
    val character: String,
    val costume: String,
    val type: String,
    val isEnabled: Boolean,
    val uri: Uri,
    val targetHashedName: String?,
    val isDirectory: Boolean
)

data class ModCacheInfo(
    val uriString: String,
    val lastModified: Long,
    val name: String,
    val character: String,
    val costume: String,
    val type: String,
    val targetHashedName: String?,
    val isDirectory: Boolean
)

data class CharacterInfo(val character: String, val costume: String, val type: String, val hashedName: String)

data class ModDetails(val fileId: String?, val fileNames: List<String>)

data class RepackJob(val hashedName: String, val modsToInstall: List<ModInfo>)

sealed class JobStatus {
    object Pending : JobStatus()
    data class Downloading(val progressMessage: String = "Waiting...") : JobStatus()
    data class Installing(val progressMessage: String = "Initializing...") : JobStatus()
    data class Finished(val publicUri: Uri) : JobStatus()
    data class Failed(val error: String) : JobStatus()
}

data class InstallJob(
    val job: RepackJob,
    val status: JobStatus = JobStatus.Pending
)

data class FinalInstallResult(
    val successfulJobs: Int,
    val failedJobs: Int,
    val command: String?
)

sealed class UninstallState {
    object Idle : UninstallState()
    data class Downloading(val hashedName: String, val progressMessage: String = "Initializing...") : UninstallState()
    data class Finished(val command: String) : UninstallState()
    data class Failed(val error: String) : UninstallState()
}

sealed class UnpackState {
    object Idle : UnpackState()
    data class Unpacking(val progressMessage: String = "Initializing...") : UnpackState()
    data class Finished(val message: String) : UnpackState()
    data class Failed(val error: String) : UnpackState()
}

class MainViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {

    companion object {
        private const val CHARACTERS_JSON_URL = "https://codeberg.org/kxdekxde/browndust2-mod-manager/raw/branch/main/characters.json"
        private const val CHARACTERS_JSON_FILENAME = "characters.json"
        private const val MOD_CACHE_FILENAME = "mod_cache.json"
    }

    private val gson = Gson()

    val modSourceDirectoryUri: StateFlow<Uri?> = savedStateHandle.getStateFlow("mod_source_dir_uri", null)

    private val _modsList = MutableStateFlow<List<ModInfo>>(emptyList())
    val modsList: StateFlow<List<ModInfo>> = _modsList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _selectedMods = MutableStateFlow<Set<Uri>>(emptySet())
    val selectedMods: StateFlow<Set<Uri>> = _selectedMods.asStateFlow()

    val useAstc: StateFlow<Boolean> = savedStateHandle.getStateFlow("use_astc", true)

    private val _installJobs = MutableStateFlow<List<InstallJob>>(emptyList())
    val installJobs: StateFlow<List<InstallJob>> = _installJobs.asStateFlow()

    private val _showInstallDialog = MutableStateFlow(false)
    val showInstallDialog: StateFlow<Boolean> = _showInstallDialog.asStateFlow()

    private val _finalInstallResult = MutableStateFlow<FinalInstallResult?>(null)
    val finalInstallResult: StateFlow<FinalInstallResult?> = _finalInstallResult.asStateFlow()

    private val _uninstallState = MutableStateFlow<UninstallState>(UninstallState.Idle)
    val uninstallState: StateFlow<UninstallState> = _uninstallState.asStateFlow()

    private val _unpackState = MutableStateFlow<UnpackState>(UnpackState.Idle)
    val unpackState: StateFlow<UnpackState> = _unpackState.asStateFlow()

    private val _unpackInputFile = MutableStateFlow<Uri?>(null)
    val unpackInputFile: StateFlow<Uri?> = _unpackInputFile.asStateFlow()

    private var characterLut: Map<String, List<CharacterInfo>> = emptyMap()

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
        }

        viewModelScope.launch {
            installJobs.collect { jobs ->
                if (jobs.isNotEmpty() && jobs.all { it.status is JobStatus.Finished || it.status is JobStatus.Failed }) {
                    summarizeResults()
                }
            }
        }
    }

    private suspend fun updateCharacterData(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                if (!Python.isStarted()) {
                    Python.start(com.chaquo.python.android.AndroidPlatform(context))
                }
                val py = Python.getInstance()
                val mainScript = py.getModule("main_script")
                val result = mainScript.callAttr("update_character_data", context.filesDir.absolutePath).asList()
                val success = result[0].toBoolean()
                val message = result[1].toString()

                if (success) {
                    println("Successfully ran scraper and saved characters.json")
                    val cacheFile = File(context.cacheDir, MOD_CACHE_FILENAME)
                    if (cacheFile.exists()) {
                        cacheFile.delete()
                        println("Deleted mod cache to force re-scan.")
                    }
                } else {
                    println("Scraper script failed: $message. Will use local version if available.")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                println("Failed to execute scraper python script, will use local version. Error: ${e.message}")
            }
            characterLut = parseCharacterJson(context)
        }
    }

    fun setModSourceDirectoryUri(context: Context, uri: Uri?) {
        savedStateHandle["mod_source_dir_uri"] = uri
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            scanModSourceDirectory(context, uri)
        }
    }

    fun setUseAstc(useAstc: Boolean) {
        savedStateHandle["use_astc"] = useAstc
    }

    fun toggleModSelection(modUri: Uri) {
        _selectedMods.value = if (modUri in _selectedMods.value) _selectedMods.value - modUri else _selectedMods.value + modUri
    }

    fun toggleSelectAll() {
        val allModUris = _modsList.value.map { it.uri }.toSet()
        val currentSelections = _selectedMods.value

        _selectedMods.value = if (currentSelections.size == allModUris.size) {
            emptySet()
        } else {
            allModUris
        }
    }

    fun toggleSelectAllForGroup(groupHash: String) {
        val modsInGroup = _modsList.value.filter { it.targetHashedName == groupHash }.map { it.uri }.toSet()
        val currentSelections = _selectedMods.value
        val groupSelections = currentSelections.intersect(modsInGroup)

        _selectedMods.value = if (groupSelections.size == modsInGroup.size) {
            currentSelections - modsInGroup
        } else {
            currentSelections + modsInGroup
        }
    }

    fun initiateBatchRepack(context: Context) {
        val allMods = _modsList.value
        val jobs = _selectedMods.value
            .mapNotNull { uri -> allMods.find { it.uri == uri } }
            .filter { !it.targetHashedName.isNullOrBlank() }
            .groupBy { it.targetHashedName!! }
            .map { (hash, mods) -> RepackJob(hash, mods) }

        if (jobs.isNotEmpty()) {
            _installJobs.value = jobs.map { InstallJob(it) }
            _finalInstallResult.value = null
            _showInstallDialog.value = true
            processInstallJobs(context)
        }
    }

    private fun processInstallJobs(context: Context) {
        viewModelScope.launch {
            if (!Python.isStarted()) {
                withContext(Dispatchers.IO) {
                    Python.start(com.chaquo.python.android.AndroidPlatform(context))
                }
            }

            val batchCacheKey = System.currentTimeMillis().toString()
            val semaphore = Semaphore(5)

            _installJobs.value.forEach { installJob ->
                launch(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        processSingleJob(context, installJob, batchCacheKey)
                    } finally {
                        semaphore.release()
                    }
                }
            }
        }
    }

    private suspend fun processSingleJob(context: Context, installJob: InstallJob, cacheKey: String) {
        val job = installJob.job
        val hashedName = job.hashedName

        try {
            updateJobStatus(hashedName, JobStatus.Downloading("Starting download..."))
            val (downloadSuccess, messageOrPath) = ModdingService.downloadBundle(hashedName, "HD", context.cacheDir.absolutePath, cacheKey) { progress ->
                updateJobStatus(hashedName, JobStatus.Downloading(progress))
            }

            if (!downloadSuccess) {
                throw Exception("Download failed: $messageOrPath")
            }
            val originalDataCache = File(messageOrPath)

            updateJobStatus(hashedName, JobStatus.Installing("Extracting mod files..."))
            val modAssetsDir = File(context.cacheDir, "temp_mod_assets_${hashedName}")

            if (modAssetsDir.exists()) modAssetsDir.deleteRecursively()
            modAssetsDir.mkdirs()

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

            updateJobStatus(hashedName, JobStatus.Installing("Repacking bundle..."))
            val repackedDataCache = File(context.cacheDir, "__${hashedName}")
            val (repackSuccess, repackMessage) = ModdingService.repackBundle(originalDataCache.absolutePath, modAssetsDir.absolutePath, repackedDataCache.absolutePath, useAstc.value) { progress ->
                updateJobStatus(hashedName, JobStatus.Installing(progress))
            }

            originalDataCache.delete()
            modAssetsDir.deleteRecursively()


            if (!repackSuccess) {
                throw Exception("Repack failed: $repackMessage")
            }

            val publicUri = saveFileToDownloads(context, repackedDataCache, "__${hashedName}")
            repackedDataCache.delete()

            if (publicUri != null) {
                updateJobStatus(hashedName, JobStatus.Finished(publicUri))
            } else {
                throw Exception("Failed to save file to Downloads folder.")
            }

        } catch (e: Exception) {
            e.printStackTrace()
            updateJobStatus(hashedName, JobStatus.Failed(e.message ?: "An unknown error occurred."))
        }
    }

    private fun summarizeResults() {
        val finishedJobs = _installJobs.value
        val successfulJobs = finishedJobs.filter { it.status is JobStatus.Finished }
        val failedJobs = finishedJobs.filter { it.status is JobStatus.Failed }

        val command = if (successfulJobs.isNotEmpty()) {
            successfulJobs.joinToString(" && ") {
                val hash = it.job.hashedName
                "mv -f */Download/__${hash} */Android/data/com.neowizgames.game.browndust2/files/UnityCache/Shared/$hash/*/__data"
            }
        } else null

        _finalInstallResult.value = FinalInstallResult(
            successfulJobs = successfulJobs.size,
            failedJobs = failedJobs.size,
            command = command
        )
    }

    @Synchronized
    private fun updateJobStatus(hashedName: String, newStatus: JobStatus) {
        viewModelScope.launch(Dispatchers.Main) {
            val currentJobs = _installJobs.value.toMutableList()
            val jobIndex = currentJobs.indexOfFirst { it.job.hashedName == hashedName }
            if (jobIndex != -1) {
                currentJobs[jobIndex] = currentJobs[jobIndex].copy(status = newStatus)
                _installJobs.value = currentJobs
            }
        }
    }

    fun closeInstallDialog() {
        _showInstallDialog.value = false
        _installJobs.value = emptyList()
        _finalInstallResult.value = null
        _selectedMods.value = emptySet()
    }

    fun initiateUninstall(context: Context, hashedName: String) {
        if (_uninstallState.value !is UninstallState.Idle) return

        viewModelScope.launch {
            _uninstallState.value = UninstallState.Downloading(hashedName, "Starting download...")
            val (success, messageOrPath) = withContext(Dispatchers.IO) {
                if (!Python.isStarted()) {
                    Python.start(com.chaquo.python.android.AndroidPlatform(context))
                }
                val cacheKey = "uninstall_${System.currentTimeMillis()}"
                ModdingService.downloadBundle(hashedName, "HD", context.cacheDir.absolutePath, cacheKey) { progress ->
                    viewModelScope.launch(Dispatchers.Main) {
                        _uninstallState.value = UninstallState.Downloading(hashedName, progress)
                    }
                }
            }

            if (success) {
                val downloadedFile = File(messageOrPath)
                val publicUri = saveFileToDownloads(context, downloadedFile, "__${hashedName}")
                downloadedFile.delete()

                if (publicUri != null) {
                    val command = "mv -f */Download/__${hashedName} */Android/data/com.neowizgames.game.browndust2/files/UnityCache/Shared/$hashedName/*/__data"
                    _uninstallState.value = UninstallState.Finished(command)
                } else {
                    _uninstallState.value = UninstallState.Failed("Failed to save original file to Downloads folder.")
                }
            } else {
                _uninstallState.value = UninstallState.Failed(messageOrPath)
            }
        }
    }

    fun resetUninstallState() {
        _uninstallState.value = UninstallState.Idle
    }

    fun setUnpackInputFile(uri: Uri?) {
        _unpackInputFile.value = uri
    }

    fun initiateUnpack(context: Context) {
        val inputFile = _unpackInputFile.value ?: return

        viewModelScope.launch {
            _unpackState.value = UnpackState.Unpacking("Starting unpack...")
            val (success, message) = withContext(Dispatchers.IO) {
                if (!Python.isStarted()) {
                    Python.start(com.chaquo.python.android.AndroidPlatform(context))
                }
                val tempOutputDir = File(context.cacheDir, "temp_unpack_output")
                if (tempOutputDir.exists()) tempOutputDir.deleteRecursively()
                tempOutputDir.mkdirs()

                var unpackResult: Pair<Boolean, String>

                try {
                    context.contentResolver.openFileDescriptor(inputFile, "r")?.use { pfd ->
                        val fd = pfd.detachFd()
                        unpackResult = ModdingService.unpackBundleByFd(fd, tempOutputDir.absolutePath) { progress ->
                            viewModelScope.launch(Dispatchers.Main) {
                                _unpackState.value = UnpackState.Unpacking(progress)
                            }
                        }
                    } ?: run {
                        unpackResult = Pair(false, "Failed to open input file.")
                    }

                    if (unpackResult.first) {
                        tempOutputDir.listFiles()?.forEach { file ->
                            saveFileToDownloads(context, file, file.name, "outputs")
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    unpackResult = Pair(false, "An error occurred: ${e.message}")
                } finally {
                    tempOutputDir.deleteRecursively()
                }
                unpackResult
            }

            _unpackState.value = if (success) {
                UnpackState.Finished("Unpacked files saved to Download/outputs folder.")
            } else {
                UnpackState.Failed(message)
            }
        }
    }

    fun resetUnpackState() {
        _unpackState.value = UnpackState.Idle
        _unpackInputFile.value = null
    }

    fun scanModSourceDirectory(context: Context, dirUri: Uri) {
        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val existingCache = loadModCache(context)
                val newCache = mutableMapOf<String, ModCacheInfo>()
                val tempModsList = mutableListOf<ModInfo>()
                val files = DocumentFile.fromTreeUri(context, dirUri)?.listFiles() ?: emptyArray()

                files.filter { it.isDirectory || it.name?.endsWith(".zip", ignoreCase = true) == true }
                    .forEach { file ->
                    val uriString = file.uri.toString()
                    val lastModified = file.lastModified()
                    val cachedInfo = existingCache[uriString]

                    val modInfo = if (cachedInfo != null && cachedInfo.lastModified == lastModified) {
                        newCache[uriString] = cachedInfo
                        ModInfo(
                            name = cachedInfo.name,
                            character = cachedInfo.character,
                            costume = cachedInfo.costume,
                            type = cachedInfo.type,
                            isEnabled = false,
                            uri = file.uri,
                            targetHashedName = cachedInfo.targetHashedName,
                            isDirectory = cachedInfo.isDirectory
                        )
                    } else {
                        val modName = file.name?.removeSuffix(".zip") ?: ""
                        val isDirectory = file.isDirectory
                        val modDetails = if (isDirectory) {
                            extractModDetailsFromDirectory(context, file.uri)
                        } else {
                            extractModDetailsFromUri(context, file.uri)
                        }
                        val bestMatch = findBestMatch(modDetails.fileId, modDetails.fileNames)

                        val newCacheInfo = ModCacheInfo(
                            uriString = uriString,
                            lastModified = lastModified,
                            name = modName,
                            character = bestMatch?.character ?: "Unknown",
                            costume = bestMatch?.costume ?: "Unknown",
                            type = bestMatch?.type ?: "idle",
                            targetHashedName = bestMatch?.hashedName,
                            isDirectory = isDirectory
                        )
                        newCache[uriString] = newCacheInfo

                        ModInfo(
                            name = modName,
                            character = bestMatch?.character ?: "Unknown",
                            costume = bestMatch?.costume ?: "Unknown",
                            type = bestMatch?.type ?: "idle",
                            isEnabled = false,
                            uri = file.uri,
                            targetHashedName = bestMatch?.hashedName,
                            isDirectory = isDirectory
                        )
                    }
                    tempModsList.add(modInfo)
                }

                saveModCache(context, newCache)

                withContext(Dispatchers.Main) {
                    _modsList.value = tempModsList.sortedBy { it.name }
                    _selectedMods.value = emptySet()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                }
            }
        }
    }

    private fun loadModCache(context: Context): Map<String, ModCacheInfo> {
        val cacheFile = File(context.cacheDir, MOD_CACHE_FILENAME)
        if (!cacheFile.exists()) return emptyMap()

        return try {
            val json = cacheFile.readText()
            val type = object : TypeToken<Map<String, ModCacheInfo>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        }
        catch (e: Exception) {
            e.printStackTrace()
            emptyMap()
        }
    }

    private fun saveModCache(context: Context, cache: Map<String, ModCacheInfo>) {
        try {
            val cacheFile = File(context.cacheDir, MOD_CACHE_FILENAME)
            val json = gson.toJson(cache)
            cacheFile.writeText(json)
        }
        catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun findBestMatch(fileId: String?, fileNames: List<String>): CharacterInfo? {
        if (fileId == null) return null

        val candidates = characterLut[fileId] ?: return null
        if (candidates.size == 1) return candidates.first()
        if (candidates.isEmpty()) return null

        val hasCutsceneKeyword = fileNames.any { it.contains("cutscene", ignoreCase = true) }
        if (hasCutsceneKeyword) {
            candidates.find { it.type == "cutscene" }?.let { return it }
        }

        val validHashCandidates = candidates.filter { !it.hashedName.isNullOrBlank() }
        if (validHashCandidates.size == 1) {
            return validHashCandidates.first()
        }

        candidates.find { it.type == "idle" }?.let { return it }

        return candidates.first()
    }

    private fun saveFileToDownloads(context: Context, file: File, displayName: String, subdirectory: String? = null): Uri? {
        val resolver = context.contentResolver
        val relativePath = if (subdirectory != null) {
            File(Environment.DIRECTORY_DOWNLOADS, subdirectory).path
        } else {
            Environment.DIRECTORY_DOWNLOADS
        }
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
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
        val fileNames = mutableListOf<String>()
        var fileId: String? = null
        try {
            context.contentResolver.openInputStream(zipUri)?.use { ZipInputStream(it).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    fileNames.add(entry.name)
                    if (fileId == null) fileId = extractFileId(entry.name)
                    entry = zis.nextEntry
                }
            }}
        } catch (e: Exception) { e.printStackTrace() }
        return ModDetails(fileId, fileNames)
    }

    private fun extractModDetailsFromDirectory(context: Context, dirUri: Uri): ModDetails {
        val fileNames = mutableListOf<String>()
        var fileId: String? = null
        fun findDetails(currentDir: DocumentFile) {
            currentDir.listFiles().forEach { file ->
                val entryName = file.name ?: ""
                fileNames.add(entryName)
                if (file.isDirectory) {
                    findDetails(file)
                } else {
                    if (fileId == null) fileId = extractFileId(entryName)
                }
            }
        }
        try { DocumentFile.fromTreeUri(context, dirUri)?.let { findDetails(it) } } catch (e: Exception) { e.printStackTrace() }
        return ModDetails(fileId, fileNames)
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
                        val fileId = obj.getString("file_id").lowercase()
                        val charInfo = CharacterInfo(obj.getString("character"), obj.getString("costume"), obj.getString("type"), obj.getString("hashed_name"))
                        lut.getOrPut(fileId) { mutableListOf() }.add(charInfo)
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }

            lut
        }
    }

    private fun extractFileId(entryName: String): String? {
        return "(char\\d{6}|illust_dating\\d+|illust_special\\d+|illust_talk\\d+|npc\\d+|specialillust\\w+|storypack\\w+|\\bRhythmHitAnim\\b)".toRegex(RegexOption.IGNORE_CASE).find(entryName)?.value?.lowercase()
    }

    fun prepareAndShowPreview(context: Context, modInfo: ModInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val tempDir = File(context.cacheDir, "spine_preview_${System.currentTimeMillis()}")
            if (!tempDir.mkdirs()) {
                return@launch
            }

            var skelPath: String? = null
            var atlasPath: String? = null

            try {
                if (modInfo.isDirectory) {
                    val sourceDir = DocumentFile.fromTreeUri(context, modInfo.uri)
                    sourceDir?.listFiles()?.forEach { file ->
                        val fileName = file.name ?: ""
                        if (fileName.endsWith(".skel") || fileName.endsWith(".json") || fileName.endsWith(".atlas") || fileName.endsWith(".png")) {
                            val destFile = File(tempDir, fileName)
                            context.contentResolver.openInputStream(file.uri)?.use { input ->
                                destFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            if (fileName.endsWith(".skel") || fileName.endsWith(".json")) {
                                skelPath = destFile.absolutePath
                            } else if (fileName.endsWith(".atlas")) {
                                atlasPath = destFile.absolutePath
                            }
                        }
                    }
                } else {
                    context.contentResolver.openInputStream(modInfo.uri)?.use { fis ->
                        ZipInputStream(fis).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                val fileName = entry.name.substringAfterLast('/')
                                if (fileName.endsWith(".skel") || fileName.endsWith(".json") || fileName.endsWith(".atlas") || fileName.endsWith(".png")) {
                                    val destFile = File(tempDir, fileName)
                                    destFile.outputStream().use { fos -> zis.copyTo(fos) }

                                    if (fileName.endsWith(".skel") || fileName.endsWith(".json")) {
                                        skelPath = destFile.absolutePath
                                    } else if (fileName.endsWith(".atlas")) {
                                        atlasPath = destFile.absolutePath
                                    }
                                }
                                entry = zis.nextEntry
                            }
                        }
                    }
                }

                if (skelPath != null && atlasPath != null) {
                    withContext(Dispatchers.Main) {
                        val intent = Intent(context, SpinePreviewActivity::class.java).apply {
                            putExtra("skelPath", skelPath)
                            putExtra("atlasPath", atlasPath)
                            putExtra("tempDirPath", tempDir.absolutePath)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    }
                } else {
                }
            } catch (e: Exception) {
                e.printStackTrace()
                tempDir.deleteRecursively()
            }
        }
    }
}