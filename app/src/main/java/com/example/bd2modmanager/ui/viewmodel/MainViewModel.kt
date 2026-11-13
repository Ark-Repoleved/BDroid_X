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
import com.example.bd2modmanager.data.repository.AvatarRepository
import com.example.bd2modmanager.data.repository.CharacterMetaRepository
import com.example.bd2modmanager.data.repository.CharacterRepository
import com.example.bd2modmanager.data.repository.ModRepository
import com.example.bd2modmanager.service.InstallService
import com.example.bd2modmanager.service.ModdingService
import com.example.bd2modmanager.service.OperationJournalStore
import com.example.bd2modmanager.service.RecoveryNotice
import com.example.bd2modmanager.service.ShizukuManager
import com.example.bd2modmanager.ui.screens.CharacterEntry
import com.example.bd2modmanager.ui.screens.buildCharacterEntries
import com.example.bd2modmanager.utils.LogExporter
import com.example.bd2modmanager.utils.ModEntryUtils
import com.example.bd2modmanager.utils.SafMediaExclusion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

class MainViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {

    private fun shouldIgnoreModEntry(entryName: String?): Boolean {
        return ModEntryUtils.shouldIgnoreModEntry(entryName)
    }

    private lateinit var characterRepository: CharacterRepository
    private lateinit var modRepository: ModRepository
    private var avatarRepository: AvatarRepository? = null
    private var characterMetaRepository: CharacterMetaRepository? = null

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

    val installJobs: StateFlow<List<InstallJob>> = InstallService.installJobs

    private val _showInstallDialog = MutableStateFlow(false)
    val showInstallDialog: StateFlow<Boolean> = _showInstallDialog.asStateFlow()

    val finalInstallResult: StateFlow<FinalInstallResult?> = InstallService.finalResult

    val isInstallServiceRunning: StateFlow<Boolean> = InstallService.isRunning

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

    val moveState: StateFlow<MoveState> = InstallService.moveState

    private val _characterEntries = MutableStateFlow<List<CharacterEntry>>(emptyList())
    val characterEntries: StateFlow<List<CharacterEntry>> = _characterEntries.asStateFlow()

    private val _avatarSyncState = MutableStateFlow(AvatarRepository.SyncState.IDLE)
    val avatarSyncState: StateFlow<AvatarRepository.SyncState> = _avatarSyncState.asStateFlow()

    private val _recoveryNotices = MutableStateFlow<List<RecoveryNotice>>(emptyList())
    val recoveryNotices: StateFlow<List<RecoveryNotice>> = _recoveryNotices.asStateFlow()

    private val _staticPreviewImages = MutableStateFlow<List<String>>(emptyList())
    val staticPreviewImages: StateFlow<List<String>> = _staticPreviewImages.asStateFlow()
    private var staticPreviewTempDir: String? = null

    private var appContext: Context? = null

    private var initialized = false
    private var scanJob: Job? = null
    private var pendingScanUri: Uri? = null
    private val mediaExclusionCheckedUris = mutableSetOf<String>()

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        appContext = context.applicationContext
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        _useAstc.value = prefs.getBoolean("use_astc", false)
        _selectedQuality.value = prefs.getString("selected_quality", "HD") ?: "HD"

        characterRepository = CharacterRepository(context)
        modRepository = ModRepository(context, characterRepository)
        characterMetaRepository = CharacterMetaRepository.getInstance(context)
        avatarRepository = AvatarRepository.getInstance(context).also { repo ->
            viewModelScope.launch {
                repo.syncState.collect { state -> _avatarSyncState.value = state }
            }
            viewModelScope.launch(Dispatchers.IO) {
                repo.syncAll()
            }
        }

        if (InstallService.isRunning.value || InstallService.finalResult.value != null) {
            _showInstallDialog.value = true
        }
        checkRecoveryJournal(context)

        viewModelScope.launch {
            val hasLocalData = characterRepository.hasLocalCharactersJson() &&
                characterRepository.hasLocalCatalogIndex(_selectedQuality.value)

            if (hasLocalData) {
                characterRepository.loadLocalCharacterData()
                refreshCharacterEntries()
                modSourceDirectoryUri.value?.let { scanModSourceDirectory(it) }
                try {
                    val status = characterRepository.updateCharacterData(_selectedQuality.value)
                    refreshCharacterEntries()
                    if (status == "SUCCESS") {
                        modSourceDirectoryUri.value?.let { scanModSourceDirectory(it) }
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Background character/catalog update failed", e)
                }
            } else {
                refreshCharacterEntries()
                try {
                    _isUpdatingCharacters.value = true
                    characterRepository.updateCharacterData(_selectedQuality.value)
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error during initialization", e)
                }
                finishInitialization()
            }
        }
    }

    fun retryAvatarSync(context: Context) {
        val repo = avatarRepository ?: AvatarRepository.getInstance(context).also { avatarRepository = it }
        viewModelScope.launch(Dispatchers.IO) {
            repo.syncAll()
        }
    }

    private fun refreshCharacterEntries() {
        val (repoPlayable, npc) = if (::characterRepository.isInitialized) {
            characterRepository.getAllCharacterNames()
        } else {
            emptyList<String>() to emptyList()
        }
        val metaPlayable = characterMetaRepository?.allCharacterNames().orEmpty()
        val allPlayable = (repoPlayable + metaPlayable).distinct()
        _characterEntries.value = buildCharacterEntries(allPlayable, npc, _modsList.value)
    }

    private fun checkRecoveryJournal(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val sharedDir = File("/storage/emulated/0/Download/Shared")
            val hasStaged = sharedDir.isDirectory && (sharedDir.listFiles()?.isNotEmpty() == true)
            val journal = OperationJournalStore(
                File(context.filesDir, OperationJournalStore.JOURNAL_FILENAME)
            )
            val notices = if (InstallService.isRunning.value) emptyList() else journal.classify(hasStaged)
            _recoveryNotices.value = notices
        }
    }

    fun retryRecoveryMove(context: Context) {
        val started = InstallService.startMove(context.applicationContext)
        if (!started) {
            Toast.makeText(context, "Unable to start foreground install service.", Toast.LENGTH_LONG).show()
            return
        }
        viewModelScope.launch {
            InstallService.isRunning.first { !it }
            checkRecoveryJournal(context)
            when (val ms = InstallService.moveState.value) {
                is MoveState.Success -> Toast.makeText(context, ms.message, Toast.LENGTH_SHORT).show()
                is MoveState.Failed -> Toast.makeText(context, ms.error, Toast.LENGTH_LONG).show()
                else -> Unit
            }
        }
    }

    fun dismissRecoveryNotices(context: Context) {
        val appCtx = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            OperationJournalStore(
                File(appCtx.filesDir, OperationJournalStore.JOURNAL_FILENAME)
            ).clear()
            _recoveryNotices.value = emptyList()
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
            val appCtx = context.applicationContext
            viewModelScope.launch(Dispatchers.IO) {
                ensureMediaExclusionForUri(appCtx, uri, showNotice = true)
            }
            scanModSourceDirectory(uri)
        }
    }

    private suspend fun ensureMediaExclusionForUri(context: Context, uri: Uri, showNotice: Boolean) {
        val key = uri.toString()
        if (!showNotice && key in mediaExclusionCheckedUris) return
        mediaExclusionCheckedUris.add(key)
        val result = SafMediaExclusion.ensureMarker(context, uri)
        val msg = SafMediaExclusion.message(result)
        if (msg != null) {
            Log.w("MainViewModel", "Media exclusion notice for $key: $msg")
            if (showNotice) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun exportDiagnosticLogs(context: Context) {
        val appCtx = context.applicationContext
        viewModelScope.launch {
            val sharedUri = withContext(Dispatchers.IO) {
                val collected = LogExporter.collect(appCtx)
                try {
                    LogExporter.exportAndPrepareShareUri(appCtx, collected)
                } finally {
                    collected.delete()
                }
            }
            if (sharedUri != null) {
                Toast.makeText(appCtx, "Diagnostic log exported.", Toast.LENGTH_SHORT).show()
                LogExporter.share(appCtx, sharedUri)
            } else {
                Toast.makeText(appCtx, "Failed to export diagnostic log.", Toast.LENGTH_SHORT).show()
            }
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
        refreshCharacterEntries()
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

    fun toggleSelectAllForCharacter(characterName: String) {
        val modsForChar = filteredModsList.value
            .filter {
                it.character.trim() == characterName &&
                    it.resolutionState == ResolutionState.KNOWN
            }
            .map { it.uri }
            .toSet()
        val currentSelections = _selectedMods.value
        val charSelections = currentSelections.intersect(modsForChar)

        _selectedMods.value = if (charSelections.size == modsForChar.size && modsForChar.isNotEmpty()) {
            currentSelections - modsForChar
        } else {
            currentSelections + modsForChar
        }
    }

    fun selectAllForCharacter(characterName: String, select: Boolean) {
        val modsForChar = _modsList.value
            .filter {
                it.character.trim() == characterName &&
                    it.resolutionState == ResolutionState.KNOWN
            }
            .map { it.uri }
            .toSet()
        _selectedMods.value = if (select) {
            _selectedMods.value + modsForChar
        } else {
            _selectedMods.value - modsForChar
        }
    }

    fun initiateBatchRepack(context: Context) {
        val allMods = _modsList.value
        val selectedList = _selectedMods.value.mapNotNull { uri -> allMods.find { it.uri == uri } }
        initiateBatchRepackMods(context, selectedList)
    }

    fun initiateBatchRepackMods(context: Context, mods: List<ModInfo>) {
        if (InstallService.isRunning.value) {
            _showInstallDialog.value = true
            Toast.makeText(context, "A repack or install task is already running.", Toast.LENGTH_SHORT).show()
            return
        }
        val jobs = mods
            .filter {
                !it.targetHash.isNullOrBlank() &&
                    it.resolutionState == ResolutionState.KNOWN
            }
            .groupBy { it.targetHash!! }
            .map { (hash, groupMods) -> RepackJob(hash, groupMods) }

        if (jobs.isNotEmpty()) {
            val started = InstallService.startRepack(
                context = context.applicationContext,
                jobs = jobs,
                quality = _selectedQuality.value,
                useAstc = _useAstc.value
            )
            if (started) {
                _showInstallDialog.value = true
            } else {
                Toast.makeText(context, "Could not start background install service.", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun reopenInstallDialog() {
        if (InstallService.isRunning.value || InstallService.finalResult.value != null) {
            _showInstallDialog.value = true
        }
    }

    fun closeInstallDialog() {
        if (InstallService.isRunning.value) {
            _showInstallDialog.value = false
            return
        }
        _showInstallDialog.value = false
        InstallService.resetSessionState()
        _selectedMods.value = emptySet()
        appContext?.let { checkRecoveryJournal(it) }
    }

    fun moveFilesToGame() {
        val ctx = appContext ?: return
        InstallService.startMove(ctx)
    }

    fun resetMoveState() {
        InstallService.resetMoveState()
    }

    fun initiateUninstall(context: Context, hashedName: String) {
        InstallService.resetMoveState()
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
                    appContext?.let { ctx ->
                        ensureMediaExclusionForUri(ctx, currentUri, showNotice = false)
                    }
                    val mods = modRepository.scanMods(currentUri)
                    withContext(Dispatchers.Main) {
                        _modsList.value = mods
                        _selectedMods.value = emptySet()
                        refreshCharacterEntries()
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

    fun dismissStaticPreview() {
        _staticPreviewImages.value = emptyList()
        val dir = staticPreviewTempDir
        staticPreviewTempDir = null
        if (!dir.isNullOrBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { File(dir).deleteRecursively() }
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
            val pngPaths = mutableListOf<String>()

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
                            if (skelPath == null || !skelPath!!.endsWith(".skel", ignoreCase = true)) {
                                skelPath = destFile.absolutePath
                            }
                        } else if (lowerNorm.endsWith(".atlas")) {
                            atlasPath = destFile.absolutePath
                        } else if (lowerNorm.endsWith(".png")) {
                            pngPaths.add(destFile.absolutePath)
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
                                            if (skelPath == null || !skelPath!!.endsWith(".skel", ignoreCase = true)) {
                                                skelPath = destFile.absolutePath
                                            }
                                        } else if (lowerNorm.endsWith(".atlas")) {
                                            atlasPath = destFile.absolutePath
                                        } else if (lowerNorm.endsWith(".png")) {
                                            pngPaths.add(destFile.absolutePath)
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
                } else if (pngPaths.isNotEmpty()) {
                    val sortedPngs = pngPaths.sortedBy { File(it).name.lowercase() }
                    withContext(Dispatchers.Main) {
                        staticPreviewTempDir?.let { oldDir ->
                            viewModelScope.launch(Dispatchers.IO) { runCatching { File(oldDir).deleteRecursively() } }
                        }
                        staticPreviewTempDir = tempDir.absolutePath
                        _staticPreviewImages.value = sortedPngs
                    }
                } else {
                    tempDir.deleteRecursively()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "No valid Spine (.skel/.json + .atlas) or .png files found for preview.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                tempDir.deleteRecursively()
            }
        }
    }
}
