package com.example.bd2modmanager.ui.viewmodel

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
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
import com.example.bd2modmanager.service.ShizukuManager
import com.example.bd2modmanager.utils.ModEntryUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

class MainViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {

    companion object {
        // Upper bound for install-time bundle fallback attempts.
        private const val MAX_BUNDLE_CANDIDATES = 3
    }

    private fun shouldIgnoreModEntry(entryName: String?): Boolean {
        return ModEntryUtils.shouldIgnoreModEntry(entryName)
    }

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

    private val _useAstc = MutableStateFlow(false)
    val useAstc: StateFlow<Boolean> = _useAstc.asStateFlow()

    private val _selectedQuality = MutableStateFlow("HD")
    val selectedQuality: StateFlow<String> = _selectedQuality.asStateFlow()

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

    private val _mergeState = MutableStateFlow<MergeState>(MergeState.Idle)
    val mergeState: StateFlow<MergeState> = _mergeState.asStateFlow()

    private val _showMergeDialog = MutableStateFlow(false)
    val showMergeDialog: StateFlow<Boolean> = _showMergeDialog.asStateFlow()

    private val _moveState = MutableStateFlow<MoveState>(MoveState.Idle)
    val moveState: StateFlow<MoveState> = _moveState.asStateFlow()

    private var appContext: Context? = null

    private var initialized = false
    private var scanJob: Job? = null
    private var pendingScanUri: Uri? = null

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        appContext = context.applicationContext
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        _useAstc.value = prefs.getBoolean("use_astc", false)
        _selectedQuality.value = prefs.getString("selected_quality", "HD") ?: "HD"

        characterRepository = CharacterRepository(context)
        modRepository = ModRepository(context, characterRepository)

        viewModelScope.launch {
            val hasLocalData = characterRepository.hasLocalCharactersJson() &&
                characterRepository.hasLocalCatalogIndex(_selectedQuality.value)

            if (hasLocalData) {
                characterRepository.loadLocalCharacterData()
                modSourceDirectoryUri.value?.let { scanModSourceDirectory(it) }
                try {
                    val status = characterRepository.updateCharacterData(_selectedQuality.value)
                    if (status == "SUCCESS") {
                        modSourceDirectoryUri.value?.let { scanModSourceDirectory(it) }
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Background character/catalog update failed", e)
                }
            } else {
                try {
                    _isUpdatingCharacters.value = true
                    // Refreshes characters.json and the catalog index (the single source
                    // of truth for mod resolution) for the current game version.
                    characterRepository.updateCharacterData(_selectedQuality.value)
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error during initialization", e)
                }
                finishInitialization()
            }
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
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                Log.w("MainViewModel", "Persistable URI permission skipped: ${e.message}")
            }
            scanModSourceDirectory(uri)
        }
    }

    fun setUseAstc(useAstc: Boolean) {
        _useAstc.value = useAstc
        appContext?.getSharedPreferences("app_settings", Context.MODE_PRIVATE)?.edit()?.putBoolean("use_astc", useAstc)?.apply()
    }

    fun setSelectedQuality(quality: String) {
        val previous = _selectedQuality.value
        _selectedQuality.value = quality
        appContext?.getSharedPreferences("app_settings", Context.MODE_PRIVATE)?.edit()?.putString("selected_quality", quality)?.apply()

        val prevNormalized = if (previous.equals("SD", ignoreCase = true)) "SD" else "HD"
        val nextNormalized = if (quality.equals("SD", ignoreCase = true)) "SD" else "HD"
        if (prevNormalized != nextNormalized && ::characterRepository.isInitialized) {
            viewModelScope.launch {
                try {
                    _isUpdatingCharacters.value = true
                    appContext?.let { ctx ->
                        val cacheFile = File(ctx.filesDir, "mod_cache_v2.json")
                        if (cacheFile.exists()) cacheFile.delete()
                    }
                    characterRepository.updateCharacterData(quality)
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error refreshing catalog for quality $quality", e)
                } finally {
                    _isUpdatingCharacters.value = false
                    modSourceDirectoryUri.value?.let { scanModSourceDirectory(it) }
                }
            }
        }
    }

    private fun finishInitialization() {
        _isUpdatingCharacters.value = false
        modSourceDirectoryUri.value?.let { scanModSourceDirectory(it) }
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
        val filteredModUris = filteredModsList.value
            .filter { it.resolutionState == ResolutionState.KNOWN }
            .map { it.uri }
            .toSet()
        val currentSelections = _selectedMods.value
        val selectedFilteredUris = currentSelections.intersect(filteredModUris)

        _selectedMods.value = if (selectedFilteredUris.size == filteredModUris.size && filteredModUris.isNotEmpty()) {
            currentSelections - filteredModUris
        } else {
            currentSelections + filteredModUris
        }
    }

    fun toggleSelectAllForGroup(groupHash: String) {
        val modsInGroup = filteredModsList.value
            .filter {
                it.targetHash == groupHash &&
                    it.resolutionState == ResolutionState.KNOWN
            }
            .map { it.uri }
            .toSet()
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
            .filter {
                !it.targetHash.isNullOrBlank() &&
                    it.resolutionState == ResolutionState.KNOWN
            }
            .groupBy { it.targetHash!! }
            .map { (hash, mods) -> RepackJob(hash, mods) }

        if (jobs.isNotEmpty()) {
            _moveState.value = MoveState.Idle
            batchStartTimeMs = System.currentTimeMillis()  // 記錄開始時間
            _installJobs.value = jobs.map { InstallJob(it) }
            _finalInstallResult.value = null
            _showInstallDialog.value = true
            processInstallJobs(context)
        }
    }

    private val repackSemaphore = Semaphore(1)

    private fun processInstallJobs(context: Context) {
        viewModelScope.launch {
            if (!Python.isStarted()) {
                withContext(Dispatchers.IO) {
                    Python.start(com.chaquo.python.android.AndroidPlatform(context))
                }
            }

            val downloadSemaphore = Semaphore(4)

            _installJobs.value.forEach { installJob ->
                launch(Dispatchers.IO) {
                    downloadSemaphore.withPermit {
                        processSingleJob(context, installJob)
                    }
                }
            }
        }
    }

    private suspend fun processSingleJob(context: Context, installJob: InstallJob) {
        val job = installJob.job
        val hashedName = job.hashedName
        var originalDataCache: File? = null
        val repackedFiles = mutableListOf<File>()
        val modAssetsDir = File(context.cacheDir, "temp_mod_assets_${hashedName}")
        var lastProgressUpdateMs = 0L

        fun updateProgressThrottled(statusFactory: (String) -> JobStatus, message: String, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (force || now - lastProgressUpdateMs >= 150L) {
                lastProgressUpdateMs = now
                updateJobStatus(hashedName, statusFactory(message))
            }
        }

        try {
            updateJobStatus(hashedName, JobStatus.Installing("Extracting mod files..."))

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
                                if (!entry.isDirectory && !shouldIgnoreModEntry(entry.name)) {
                                    val newFile = ModEntryUtils.resolveZipEntrySafely(modAssetsDir, entry.name)
                                    if (newFile != null) {
                                        newFile.parentFile?.mkdirs()
                                        newFile.outputStream().use { fos -> zis.copyTo(fos) }
                                    } else {
                                        Log.w("MainViewModel", "Skipped unsafe zip entry: ${entry.name}")
                                    }
                                }
                                entry = zis.nextEntry
                            }
                        }
                    }
                }
            }

            // The catalog is authoritative, but a family can be bundled in several
            // places. Try the best candidate first and fall back to alternatives when
            // some mod files do not exist in the downloaded bundle.
            val candidates = (listOf(hashedName) + job.modsToInstall.flatMap { it.alternativeHashes })
                .distinct()
                .take(MAX_BUNDLE_CANDIDATES)

            var chosenRepoPath: String? = null
            var chosenRepackedFile: File? = null
            var bestUnmatchedCount = Int.MAX_VALUE
            var lastMessage: String? = null
            val candidateErrors = mutableListOf<String>()

            val bundleCacheDir = File(context.cacheDir, "bundle_cache").apply { mkdirs() }

            for ((index, candidate) in candidates.withIndex()) {
                updateJobStatus(hashedName, JobStatus.Downloading(
                    if (index == 0) "Downloading bundle..."
                    else "Trying alternative bundle (${index + 1}/${candidates.size})..."
                ))
                val (downloadSuccess, messageOrPath) = ModdingService.downloadBundle(
                    candidate, selectedQuality.value, bundleCacheDir.absolutePath,
                    context.filesDir.absolutePath
                ) { progress -> updateProgressThrottled({ JobStatus.Downloading(it) }, progress) }

                if (!downloadSuccess) {
                    candidateErrors.add("$candidate: $messageOrPath")
                    continue
                }
                originalDataCache = File(messageOrPath)
                val relativePath = originalDataCache.relativeTo(bundleCacheDir)

                updateJobStatus(hashedName, JobStatus.Installing("Waiting to repack..."))
                val repackedFile = File(context.cacheDir, "repacked/${relativePath.path}")
                if (repackedFile.exists()) repackedFile.delete()
                repackedFile.parentFile?.mkdirs()
                repackedFiles.add(repackedFile)

                val (repackSuccess, repackMessage, unmatched) = repackSemaphore.withPermit {
                    updateJobStatus(hashedName, JobStatus.Installing("Repacking bundle..."))
                    ModdingService.repackBundle(
                        originalDataCache.absolutePath,
                        modAssetsDir.absolutePath,
                        repackedFile.absolutePath,
                        useAstc.value
                    ) { progress -> updateProgressThrottled({ JobStatus.Installing(it) }, progress) }
                }

                lastMessage = repackMessage
                if (repackSuccess) {
                    if (unmatched.isEmpty()) {
                        chosenRepoPath = relativePath.path
                        chosenRepackedFile = repackedFile
                        break
                    }
                    if (unmatched.size < bestUnmatchedCount) {
                        bestUnmatchedCount = unmatched.size
                        chosenRepoPath = relativePath.path
                        chosenRepackedFile = repackedFile
                    }
                } else {
                    candidateErrors.add("$candidate: $repackMessage")
                    // Evict possibly corrupt cached original bundle so retry fetches fresh
                    try {
                        originalDataCache.takeIf { it.exists() }?.delete()
                    } catch (_: Exception) {}
                }

                originalDataCache = null
            }

            val repoPath = chosenRepoPath
            val repackedOutput = chosenRepackedFile
            if (repoPath == null || repackedOutput == null || !repackedOutput.exists()) {
                val detail = candidateErrors.ifEmpty { listOfNotNull(lastMessage) }.joinToString("\n")
                throw Exception("Repack failed: $detail")
            }

            val warningMessage = if (bestUnmatchedCount != Int.MAX_VALUE && bestUnmatchedCount > 0) {
                "Finished with warning: $bestUnmatchedCount file(s) did not match this bundle."
            } else null

            val publicUri = saveFileToDownloads(context, repackedOutput, repoPath, "Shared")

            if (publicUri != null) {
                updateJobStatus(hashedName, JobStatus.Finished(repoPath, warning = warningMessage))
            } else {
                throw Exception("Failed to save file to Downloads folder.")
            }

        } catch (e: Exception) {
            e.printStackTrace()
            val fullError = e.message ?: "An unknown error occurred."
            updateJobStatus(hashedName, JobStatus.Failed(displayMessage = fullError, detailedLog = fullError))
        } finally {
            repackedFiles.forEach { file ->
                try {
                    if (file.exists()) file.delete()
                } catch (_: Exception) {}
            }
            try {
                if (modAssetsDir.exists()) modAssetsDir.deleteRecursively()
            } catch (_: Exception) {}
        }
    }

    private fun summarizeResults() {
        val finishedJobs = _installJobs.value
        val successfulJobs = finishedJobs.filter { it.status is JobStatus.Finished }
        val failedJobs = finishedJobs.filter { it.status is JobStatus.Failed }
        
        // 計算總耗時
        val elapsedTimeMs = System.currentTimeMillis() - batchStartTimeMs
        
        // 收集失敗任務的詳細資訊
        val failedJobDetails = failedJobs.map { job ->
            val failedStatus = job.status as JobStatus.Failed
            FailedJobInfo(hashedName = job.job.hashedName, error = failedStatus.detailedLog)
        }

        val shizukuAvailable = ShizukuManager.isRunning()

        val command = if (successfulJobs.isNotEmpty()) {
            "mv -f /storage/emulated/0/Download/Shared /storage/emulated/0/Android/data/com.neowizgames.game.browndust2/files/UnityCache/"
        } else null

        _finalInstallResult.value = FinalInstallResult(
            successfulJobs = successfulJobs.size,
            failedJobs = failedJobs.size,
            command = command,
            elapsedTimeMs = elapsedTimeMs,
            failedJobDetails = failedJobDetails,
            shizukuAvailable = shizukuAvailable
        )
    }

    private fun updateJobStatus(hashedName: String, newStatus: JobStatus) {
        _installJobs.update { jobs ->
            jobs.map { job ->
                if (job.job.hashedName == hashedName) job.copy(status = newStatus) else job
            }
        }
    }

    fun closeInstallDialog() {
        _showInstallDialog.value = false
        _installJobs.value = emptyList()
        _finalInstallResult.value = null
        _selectedMods.value = emptySet()
        _moveState.value = MoveState.Idle
    }

    fun moveFilesToGame() {
        viewModelScope.launch {
            _moveState.value = MoveState.Moving
            val (success, message) = ShizukuManager.moveDownloadToGame()
            _moveState.value = if (success) {
                MoveState.Success(message)
            } else {
                MoveState.Failed(message)
            }
        }
    }

    fun resetMoveState() {
        _moveState.value = MoveState.Idle
    }

    fun initiateUninstall(context: Context, hashedName: String) {
        _moveState.value = MoveState.Idle
        if (_uninstallState.value !is UninstallState.Idle) return

        viewModelScope.launch {
            _uninstallState.value = UninstallState.Downloading(hashedName, "Starting download...")
            val bundleCacheDir = File(context.cacheDir, "bundle_cache").apply { mkdirs() }
            val (success, messageOrPath) = withContext(Dispatchers.IO) {
                if (!Python.isStarted()) {
                    Python.start(com.chaquo.python.android.AndroidPlatform(context))
                }
                ModdingService.downloadBundle(hashedName, selectedQuality.value, bundleCacheDir.absolutePath, context.filesDir.absolutePath) { progress ->
                    viewModelScope.launch(Dispatchers.Main) {
                        _uninstallState.value = UninstallState.Downloading(hashedName, progress)
                    }
                }
            }

            if (success) {
                val downloadedFile = File(messageOrPath)
                val relativePath = downloadedFile.relativeTo(bundleCacheDir)
                val publicUri = saveFileToDownloads(context, downloadedFile, relativePath.path, "Shared")

                if (publicUri != null) {
                    val shizukuAvailable = ShizukuManager.isRunning()
                    val command = "mv -f /storage/emulated/0/Download/Shared /storage/emulated/0/Android/data/com.neowizgames.game.browndust2/files/UnityCache/"
                    _uninstallState.value = UninstallState.Finished(command, shizukuAvailable)
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
                val stamp = System.currentTimeMillis()
                val tempInputFile = File(context.cacheDir, "temp_unpack_input_$stamp.bundle")
                val tempOutputDir = File(context.cacheDir, "temp_unpack_output_$stamp")
                try {
                    if (!Python.isStarted()) {
                        Python.start(com.chaquo.python.android.AndroidPlatform(context))
                    }

                    val inputStream = context.contentResolver.openInputStream(inputFile)
                        ?: return@withContext Pair(false, "Could not open the selected file.")
                    var copiedBytes = 0L
                    inputStream.use { input ->
                        tempInputFile.outputStream().use { output ->
                            copiedBytes = input.copyTo(output)
                        }
                    }
                    if (copiedBytes <= 0L || !tempInputFile.exists()) {
                        return@withContext Pair(false, "Selected file is empty or unreadable.")
                    }

                    if (tempOutputDir.exists()) tempOutputDir.deleteRecursively()
                    tempOutputDir.mkdirs()

                    var lastUpdateMs = 0L
                    val unpackResult = ModdingService.unpackBundle(tempInputFile.absolutePath, tempOutputDir.absolutePath) { progress ->
                        val now = System.currentTimeMillis()
                        if (now - lastUpdateMs >= 150L) {
                            lastUpdateMs = now
                            _unpackState.value = UnpackState.Unpacking(progress)
                        }
                    }

                    if (!unpackResult.first) {
                        return@withContext unpackResult
                    }

                    var savedCount = 0
                    var failedSaves = 0
                    tempOutputDir.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            if (saveFileToDownloads(context, file, file.name, "outputs") != null) {
                                savedCount++
                            } else {
                                failedSaves++
                            }
                        }
                    }

                    if (failedSaves > 0) {
                        Pair(false, "Saved $savedCount file(s), but failed to save $failedSaves file(s) to Download/outputs.")
                    } else {
                        Pair(true, "Unpacked $savedCount file(s) to Download/outputs folder.")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Pair(false, e.message ?: "Unpack failed.")
                } finally {
                    try { tempInputFile.delete() } catch (_: Exception) {}
                    try { tempOutputDir.deleteRecursively() } catch (_: Exception) {}
                }
            }

            _unpackState.value = if (success) {
                UnpackState.Finished(message)
            } else {
                UnpackState.Failed(message)
            }
        }
    }

    fun resetUnpackState() {
        _unpackState.value = UnpackState.Idle
        _unpackInputFile.value = null
    }

    fun initiateMerge(context: Context) {
        if (selectedMods.value.size != 1) return

        val modUri = selectedMods.value.first()
        val modInfo = _modsList.value.find { it.uri == modUri } ?: return
        if (!modInfo.isDirectory) {
            _showMergeDialog.value = true
            _mergeState.value = MergeState.Failed("Spine merge only supports unzipped mod folders.")
            return
        }

        _showMergeDialog.value = true
        _mergeState.value = MergeState.Merging("Preparing files...")

        viewModelScope.launch {
            val tempDir = File(context.cacheDir, "temp_merge_${System.currentTimeMillis()}")
            try {
                val (success, message) = withContext(Dispatchers.IO) {
                    if (!Python.isStarted()) {
                        Python.start(com.chaquo.python.android.AndroidPlatform(context))
                    }

                    if (tempDir.exists()) tempDir.deleteRecursively()
                    tempDir.mkdirs()

                    val sourceDoc = DocumentFile.fromTreeUri(context, modInfo.uri)
                        ?: return@withContext Pair(false, "Failed to access original mod directory.")

                    copyDirectoryToCache(context, sourceDoc, tempDir, maxDepth = 0)

                    var lastUpdateMs = 0L
                    val mergeResult = ModdingService.mergeSpineAssets(tempDir.absolutePath) { progress ->
                        val now = System.currentTimeMillis()
                        if (now - lastUpdateMs >= 150L) {
                            lastUpdateMs = now
                            _mergeState.value = MergeState.Merging(progress)
                        }
                    }

                    if (!mergeResult.first) {
                        return@withContext mergeResult
                    }

                    val filesToDelete = sourceDoc.listFiles().filter {
                        val n = it.name?.lowercase() ?: ""
                        it.isFile && (n.endsWith(".png") || n.endsWith(".atlas") || n.endsWith(".atlas.txt"))
                    }
                    filesToDelete.forEach { it.delete() }

                    tempDir.listFiles()?.forEach { file ->
                        val lower = file.name.lowercase()
                        if (file.isFile && (lower.endsWith(".png") || lower.endsWith(".atlas") || lower.endsWith(".atlas.txt"))) {
                            val newFile = sourceDoc.createFile("application/octet-stream", file.name)
                            newFile?.let {
                                context.contentResolver.openOutputStream(it.uri)?.use { output ->
                                    file.inputStream().use { input -> input.copyTo(output) }
                                }
                            }
                        } else if (file.isDirectory && file.name == ".old") {
                            val oldDirDoc = sourceDoc.findFile(".old")?.takeIf { it.isDirectory }
                                ?: sourceDoc.createDirectory(".old")
                            oldDirDoc?.let { copyDirectoryToSaf(context, file, it) }
                        }
                    }
                    Pair(true, "Successfully merged mod in-place!")
                }

                _mergeState.value = if (success) {
                    MergeState.Finished(message)
                } else {
                    MergeState.Failed(message)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _mergeState.value = MergeState.Failed(e.message ?: "An unknown error occurred.")
            } finally {
                withContext(Dispatchers.IO) {
                    if (tempDir.exists()) tempDir.deleteRecursively()
                }
            }
        }
    }

    fun resetMergeState() {
        _mergeState.value = MergeState.Idle
        _showMergeDialog.value = false
    }

    private fun copyDirectoryToSaf(context: Context, sourceDir: File, destinationDoc: DocumentFile) {
        sourceDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                if (shouldIgnoreModEntry(file.name)) return@forEach
                val newFile = destinationDoc.createFile("application/octet-stream", file.name)
                newFile?.let {
                    context.contentResolver.openOutputStream(it.uri)?.use { output ->
                        file.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                }
            } else if (file.isDirectory) {
                val newDir = destinationDoc.createDirectory(file.name)
                newDir?.let {
                    copyDirectoryToSaf(context, file, it)
                }
            }
        }
    }



    fun scanModSourceDirectory(dirUri: Uri) {
        pendingScanUri = dirUri
        if (scanJob?.isActive == true) return

        scanJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val currentUri = pendingScanUri ?: break
                pendingScanUri = null

                withContext(Dispatchers.Main) {
                    _isLoading.value = true
                }

                isUpdatingCharacters.first { !it } // Wait for character data to be ready
                try {
                    val mods = modRepository.scanMods(currentUri)
                    withContext(Dispatchers.Main) {
                        _modsList.value = mods
                        _selectedMods.value = emptySet()
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        _isLoading.value = false
                    }
                }

                if (pendingScanUri == null) break
            }
        }
    }

    private fun saveFileToDownloads(context: Context, file: File, relativeDestPath: String, rootDir: String): Uri? {
        val resolver = context.contentResolver
        val finalRelativePath = File(rootDir, relativeDestPath)

        val relativePathWithSlash = File(Environment.DIRECTORY_DOWNLOADS, finalRelativePath.parent).path + File.separator

        val queryUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(relativePathWithSlash, finalRelativePath.name)

        // Helper: find existing MediaStore entry for this path
        fun findExistingUri(): Uri? {
            resolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    if (idColumn != -1) {
                        return ContentUris.withAppendedId(queryUri, cursor.getLong(idColumn))
                    }
                }
            }
            return null
        }

        // Helper: write file content to a MediaStore URI
        fun writeToUri(targetUri: Uri): Boolean {
            return try {
                resolver.openOutputStream(targetUri, "wt")?.use { outputStream ->
                    file.inputStream().use { inputStream -> inputStream.copyTo(outputStream) }
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

        // Strategy 1: Try to overwrite an existing entry directly
        findExistingUri()?.let { existingUri ->
            if (writeToUri(existingUri)) return existingUri
            // If write failed, try deleting and re-inserting
            try { resolver.delete(existingUri, null, null) } catch (_: Exception) {}
        }

        // Strategy 2: Insert a new entry
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, finalRelativePath.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathWithSlash)
        }

        try {
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                if (writeToUri(uri)) return uri
                // Clean up if write fails
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            // UNIQUE constraint failure from a concurrent insert — fall back to overwrite
            Log.w("MainViewModel", "MediaStore insert conflict, falling back to overwrite: ${e.message}")
            findExistingUri()?.let { existingUri ->
                if (writeToUri(existingUri)) return existingUri
            }
        }

        return null
    }

    private fun copyDirectoryToCache(
        context: Context,
        sourceDir: DocumentFile,
        destinationDir: File,
        depth: Int = 0,
        maxDepth: Int = 5
    ) {
        if (depth > maxDepth) return
        if (!destinationDir.exists()) destinationDir.mkdirs()
        sourceDir.listFiles().forEach { file ->
            val fileName = file.name ?: return@forEach
            if (file.isDirectory) {
                if (depth < maxDepth && !shouldIgnoreModEntry("$fileName/placeholder")) {
                    val subDest = File(destinationDir, fileName)
                    copyDirectoryToCache(context, file, subDest, depth + 1, maxDepth)
                }
            } else if (file.isFile && !shouldIgnoreModEntry(fileName)) {
                val destFile = File(destinationDir, fileName)
                try {
                    context.contentResolver.openInputStream(file.uri)?.use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun normalizePreviewAssetName(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".skel.bytes") -> fileName.dropLast(6)
            lower.endsWith(".skel.txt") -> fileName.dropLast(4)
            lower.endsWith(".atlas.txt") -> fileName.dropLast(4)
            else -> fileName
        }
    }

    private fun isPreviewAssetFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".skel") ||
            lower.endsWith(".skel.bytes") ||
            lower.endsWith(".skel.txt") ||
            lower.endsWith(".json") ||
            lower.endsWith(".atlas") ||
            lower.endsWith(".atlas.txt") ||
            lower.endsWith(".png")
    }

    fun prepareAndShowPreview(context: Context, modInfo: ModInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val tempDir = File(context.cacheDir, "spine_preview_${System.currentTimeMillis()}")
            if (!tempDir.mkdirs()) {
                return@launch
            }

            var skelPath: String? = null
            var atlasPath: String? = null

            fun copyPreviewDocDir(dir: DocumentFile, depth: Int) {
                if (depth > 5) return
                dir.listFiles().forEach { file ->
                    val rawName = file.name ?: return@forEach
                    if (file.isDirectory) {
                        if (!shouldIgnoreModEntry("$rawName/placeholder")) {
                            copyPreviewDocDir(file, depth + 1)
                        }
                    } else if (!shouldIgnoreModEntry(rawName) && isPreviewAssetFile(rawName)) {
                        val normalizedName = normalizePreviewAssetName(rawName)
                        val destFile = ModEntryUtils.resolveZipEntrySafely(tempDir, normalizedName) ?: return@forEach
                        context.contentResolver.openInputStream(file.uri)?.use { input ->
                            destFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        val lowerNorm = normalizedName.lowercase()
                        if (lowerNorm.endsWith(".skel") || lowerNorm.endsWith(".json")) {
                            skelPath = destFile.absolutePath
                        } else if (lowerNorm.endsWith(".atlas")) {
                            atlasPath = destFile.absolutePath
                        }
                    }
                }
            }

            try {
                if (modInfo.isDirectory) {
                    DocumentFile.fromTreeUri(context, modInfo.uri)?.let { copyPreviewDocDir(it, 0) }
                } else {
                    context.contentResolver.openInputStream(modInfo.uri)?.use { fis ->
                        ZipInputStream(fis).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                val fileName = entry.name.substringAfterLast('/')
                                if (!entry.isDirectory && !shouldIgnoreModEntry(entry.name) && isPreviewAssetFile(fileName)) {
                                    val normalizedName = normalizePreviewAssetName(fileName)
                                    val destFile = ModEntryUtils.resolveZipEntrySafely(tempDir, normalizedName)
                                    if (destFile != null) {
                                        destFile.outputStream().use { fos -> zis.copyTo(fos) }
                                        val lowerNorm = normalizedName.lowercase()
                                        if (lowerNorm.endsWith(".skel") || lowerNorm.endsWith(".json")) {
                                            skelPath = destFile.absolutePath
                                        } else if (lowerNorm.endsWith(".atlas")) {
                                            atlasPath = destFile.absolutePath
                                        }
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
                    tempDir.deleteRecursively()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "No valid Spine (.skel/.json + .atlas) files found for preview.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                tempDir.deleteRecursively()
            }
        }
    }
}
