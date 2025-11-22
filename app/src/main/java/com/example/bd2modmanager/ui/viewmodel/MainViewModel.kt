package com.example.bd2modmanager.ui.viewmodel

import android.content.ContentUris
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
import com.example.bd2modmanager.SpinePreviewActivity
import com.example.bd2modmanager.data.model.*
import com.example.bd2modmanager.data.repository.CharacterRepository
import com.example.bd2modmanager.data.repository.ModRepository
import com.example.bd2modmanager.service.ModdingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

class MainViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {

    private lateinit var characterRepository: CharacterRepository
    private lateinit var modRepository: ModRepository

    val modSourceDirectoryUri: StateFlow<Uri?> = savedStateHandle.getStateFlow("mod_source_dir_uri", null)

    private val _modsList = MutableStateFlow<List<ModInfo>>(emptyList())
    val modsList: StateFlow<List<ModInfo>> = _modsList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isUpdatingCharacters = MutableStateFlow(false)
    val isUpdatingCharacters: StateFlow<Boolean> = _isUpdatingCharacters.asStateFlow()

    val showShimmer: StateFlow<Boolean> =
        combine(_modsList, _isLoading, _isUpdatingCharacters) { mods, isScanning, isUpdating ->
            (isScanning || isUpdating) && mods.isEmpty()
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), false)

    private val _selectedMods = MutableStateFlow<Set<Uri>>(emptySet())
    val selectedMods: StateFlow<Set<Uri>> = _selectedMods.asStateFlow()

    val useAstc: StateFlow<Boolean> = savedStateHandle.getStateFlow("use_astc", false)

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val filteredModsList: StateFlow<List<ModInfo>> =
        combine(_modsList, _searchQuery) { mods, query ->
            if (query.isBlank()) {
                mods
            } else {
                val keywords = query.split(" ").filter { it.isNotBlank() }
                mods.filter { modInfo ->
                    keywords.all { keyword ->
                        modInfo.name.contains(keyword, ignoreCase = true) ||
                        modInfo.character.contains(keyword, ignoreCase = true) ||
                        modInfo.costume.contains(keyword, ignoreCase = true) ||
                        modInfo.type.contains(keyword, ignoreCase = true)
                    }
                }
            }
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    private val _installJobs = MutableStateFlow<List<InstallJob>>(emptyList())
    val installJobs: StateFlow<List<InstallJob>> = _installJobs.asStateFlow()

    private val _showInstallDialog = MutableStateFlow(false)
    val showInstallDialog: StateFlow<Boolean> = _showInstallDialog.asStateFlow()

    private val _finalInstallResult = MutableStateFlow<FinalInstallResult?>(null)
    val finalInstallResult: StateFlow<FinalInstallResult?> = _finalInstallResult.asStateFlow()
    
    // 批次處理開始時間（用於計算總耗時）
    private var batchStartTimeMs: Long = 0L

    private val _uninstallState = MutableStateFlow<UninstallState>(UninstallState.Idle)
    val uninstallState: StateFlow<UninstallState> = _uninstallState.asStateFlow()

    private val _unpackState = MutableStateFlow<UnpackState>(UnpackState.Idle)
    val unpackState: StateFlow<UnpackState> = _unpackState.asStateFlow()

    private val _unpackInputFile = MutableStateFlow<Uri?>(null)
    val unpackInputFile: StateFlow<Uri?> = _unpackInputFile.asStateFlow()


    fun initialize(context: Context) {
        characterRepository = CharacterRepository(context)
        modRepository = ModRepository(context, characterRepository)

        viewModelScope.launch {
            try {
                _isUpdatingCharacters.value = true
                characterRepository.updateCharacterData()
            } finally {
                _isUpdatingCharacters.value = false
            }

            modSourceDirectoryUri.value?.let { scanModSourceDirectory(it) }
        }

        viewModelScope.launch {
            installJobs.collect { jobs ->
                if (jobs.isNotEmpty() && jobs.all { it.status is JobStatus.Finished || it.status is JobStatus.Failed }) {
                    summarizeResults()
                }
            }
        }
    }

    fun setModSourceDirectoryUri(context: Context, uri: Uri?) {
        savedStateHandle["mod_source_dir_uri"] = uri
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            scanModSourceDirectory(uri)
        }
    }

    fun setUseAstc(useAstc: Boolean) {
        savedStateHandle["use_astc"] = useAstc
    }

    fun setSearchActive(isActive: Boolean) {
        _isSearchActive.value = isActive
        if (!isActive) {
            _searchQuery.value = ""
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun toggleModSelection(modUri: Uri) {
        _selectedMods.value = if (modUri in _selectedMods.value) _selectedMods.value - modUri else _selectedMods.value + modUri
    }

    fun toggleSelectAll() {
        val filteredModUris = filteredModsList.value.map { it.uri }.toSet()
        val currentSelections = _selectedMods.value
        val selectedFilteredUris = currentSelections.intersect(filteredModUris)

        _selectedMods.value = if (selectedFilteredUris.size == filteredModUris.size && filteredModUris.isNotEmpty()) {
            currentSelections - filteredModUris
        } else {
            currentSelections + filteredModUris
        }
    }

    fun toggleSelectAllForGroup(groupHash: String) {
        val modsInGroup = filteredModsList.value.filter { it.targetHashedName == groupHash }.map { it.uri }.toSet()
        val currentSelections = _selectedMods.value
        val groupSelections = currentSelections.intersect(modsInGroup)

        _selectedMods.value = if (groupSelections.size == modsInGroup.size && modsInGroup.isNotEmpty()) {
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
            batchStartTimeMs = System.currentTimeMillis()  // 記錄開始時間
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
            val relativePath = originalDataCache.relativeTo(context.cacheDir)


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
            val repackedDataCache = File(context.cacheDir, "repacked/${relativePath.path}")
            repackedDataCache.parentFile?.mkdirs()

            val (repackSuccess, repackMessage) = ModdingService.repackBundle(originalDataCache.absolutePath, modAssetsDir.absolutePath, repackedDataCache.absolutePath, useAstc.value) { progress ->
                updateJobStatus(hashedName, JobStatus.Installing(progress))
            }

            originalDataCache.delete()
            modAssetsDir.deleteRecursively()

            if (!repackSuccess) {
                throw Exception("Repack failed: $repackMessage")
            }

            val publicUri = saveFileToDownloads(context, repackedDataCache, relativePath.path, "Shared")
            repackedDataCache.delete()

            if (publicUri != null) {
                updateJobStatus(hashedName, JobStatus.Finished(relativePath.path))
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
        
        // 計算總耗時
        val elapsedTimeMs = System.currentTimeMillis() - batchStartTimeMs

        val command = if (successfulJobs.isNotEmpty()) {
            "mv -f /storage/emulated/0/Download/Shared /storage/emulated/0/Android/data/com.neowizgames.game.browndust2/files/UnityCache/"
        } else null

        _finalInstallResult.value = FinalInstallResult(
            successfulJobs = successfulJobs.size,
            failedJobs = failedJobs.size,
            command = command,
            elapsedTimeMs = elapsedTimeMs
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
                val relativePath = downloadedFile.relativeTo(context.cacheDir)
                val publicUri = saveFileToDownloads(context, downloadedFile, relativePath.path, "Shared")
                downloadedFile.delete()

                if (publicUri != null) {
                    val command = "mv -f /storage/emulated/0/Download/Shared /storage/emulated/0/Android/data/com.neowizgames.game.browndust2/files/UnityCache/"
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
                val tempInputFile = File(context.cacheDir, "temp_unpack_input.bundle")
                context.contentResolver.openInputStream(inputFile)?.use { input ->
                    tempInputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val tempOutputDir = File(context.cacheDir, "temp_unpack_output")
                if (tempOutputDir.exists()) tempOutputDir.deleteRecursively()
                tempOutputDir.mkdirs()

                val unpackResult = ModdingService.unpackBundle(tempInputFile.absolutePath, tempOutputDir.absolutePath) { progress ->
                    viewModelScope.launch(Dispatchers.Main) {
                        _unpackState.value = UnpackState.Unpacking(progress)
                    }
                }

                if (unpackResult.first) {
                    tempOutputDir.listFiles()?.forEach { file ->
                        saveFileToDownloads(context, file, file.name, "outputs")
                    }
                }

                tempInputFile.delete()
                tempOutputDir.deleteRecursively()

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



    fun scanModSourceDirectory(dirUri: Uri) {
        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            isUpdatingCharacters.first { !it } // Wait for character data to be ready
            try {
                val mods = modRepository.scanMods(dirUri)
                withContext(Dispatchers.Main) {
                    _modsList.value = mods
                    _selectedMods.value = emptySet()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                }
            }
        }
    }

    private fun saveFileToDownloads(context: Context, file: File, relativeDestPath: String, rootDir: String): Uri? {
        val resolver = context.contentResolver
        val finalRelativePath = File(rootDir, relativeDestPath)

        val relativePathWithSlash = File(Environment.DIRECTORY_DOWNLOADS, finalRelativePath.parent).path + File.separator

        // Check if file exists and delete it first to ensure overwrite
        val queryUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(relativePathWithSlash, finalRelativePath.name)

        resolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                if (idColumn != -1) {
                    val id = cursor.getLong(idColumn)
                    val existingUri = ContentUris.withAppendedId(queryUri, id)
                    try {
                        resolver.delete(existingUri, null, null)
                    } catch (e: Exception) {
                        // Ignore deletion errors, e.g., file is locked
                        e.printStackTrace()
                    }
                }
            }
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, finalRelativePath.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathWithSlash)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            try {
                resolver.openOutputStream(it)?.use { outputStream -> file.inputStream().use { inputStream -> inputStream.copyTo(outputStream) } }
                return it
            } catch (e: Exception) {
                e.printStackTrace()
                // Clean up the new entry if writing fails
                resolver.delete(it, null, null)
            }
        }
        return null
    }

    private fun copyDirectoryToCache(context: Context, sourceDir: DocumentFile, destinationDir: File) {
        if (!destinationDir.exists()) destinationDir.mkdirs()
        sourceDir.listFiles().forEach { file ->
            if (file.isFile) {
                val destFile = File(destinationDir, file.name!!)
                try {
                    context.contentResolver.openInputStream(file.uri)?.use { input -> destFile.outputStream().use { output -> input.copyTo(output) } }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
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
