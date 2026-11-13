package com.example.bd2modmanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.bd2modmanager.MainActivity
import com.example.bd2modmanager.data.model.FailedJobInfo
import com.example.bd2modmanager.data.model.FinalInstallResult
import com.example.bd2modmanager.data.model.InstallJob
import com.example.bd2modmanager.data.model.JobStatus
import com.example.bd2modmanager.data.model.MoveState
import com.example.bd2modmanager.data.model.RepackJob
import com.example.bd2modmanager.utils.ModEntryUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Foreground service (dataSync) keeping batch downloads, UnityPy repacking, and
 * Shizuku file moves alive across Activity/screen switches (ported from BD2ToolsBox).
 */
class InstallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var lastNotifyMs = 0L

    data class RepackRequest(
        val operationId: String,
        val jobs: List<RepackJob>,
        val quality: String,
        val useAstc: Boolean
    )

    companion object {
        private const val TAG = "InstallService"
        const val CHANNEL_ID = "install_sync"
        const val NOTIFICATION_ID = 1002
        private const val MAX_BUNDLE_CANDIDATES = 3
        private const val NOTIFY_INTERVAL_MS = 700L

        private const val ACTION_RUN_REPACK = "com.example.bd2modmanager.action.RUN_REPACK"
        private const val ACTION_RUN_MOVE = "com.example.bd2modmanager.action.RUN_MOVE"

        @Volatile
        private var pendingRepackRequest: RepackRequest? = null

        @Volatile
        private var activeInstance: InstallService? = null

        @Volatile
        private var cancelRequested: Boolean = false

        private val _installJobs = MutableStateFlow<List<InstallJob>>(emptyList())
        val installJobs: StateFlow<List<InstallJob>> = _installJobs.asStateFlow()

        private val _finalResult = MutableStateFlow<FinalInstallResult?>(null)
        val finalResult: StateFlow<FinalInstallResult?> = _finalResult.asStateFlow()

        private val _moveState = MutableStateFlow<MoveState>(MoveState.Idle)
        val moveState: StateFlow<MoveState> = _moveState.asStateFlow()

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        @Volatile
        var currentOperationId: String? = null
            private set

        fun startRepack(
            context: Context,
            jobs: List<RepackJob>,
            quality: String,
            useAstc: Boolean
        ): Boolean {
            if (_isRunning.value) return false
            cancelRequested = false
            val opId = "op-${UUID.randomUUID().toString().take(8)}"
            currentOperationId = opId
            pendingRepackRequest = RepackRequest(opId, jobs, quality, useAstc)
            _moveState.value = MoveState.Idle
            _finalResult.value = null
            _installJobs.value = jobs.map { InstallJob(it) }

            val intent = Intent(context, InstallService::class.java).apply {
                action = ACTION_RUN_REPACK
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                pendingRepackRequest = null
                _installJobs.value = emptyList()
                Log.w(TAG, "Foreground service start rejected for repack: ${e.message}")
                false
            }
        }

        fun cancelCurrent(context: Context) {
            cancelRequested = true
            pendingRepackRequest = null
            val instance = activeInstance
            val activeWorker = instance?.worker
            if (activeWorker != null && activeWorker.isActive) {
                activeWorker.cancel()
                instance.getSystemService(NotificationManager::class.java)?.notify(
                    NOTIFICATION_ID,
                    instance.buildNotification("Cancelling...", 0, 0, "Stopping repack task...")
                )
            } else {
                _installJobs.value = emptyList()
                _finalResult.value = null
                _moveState.value = MoveState.Idle
                _isRunning.value = false
                cancelRequested = false
                runCatching {
                    OperationJournalStore(File(context.filesDir, OperationJournalStore.JOURNAL_FILENAME)).clear()
                }
            }
        }

        fun startMove(context: Context): Boolean {
            if (_isRunning.value) return false
            _moveState.value = MoveState.Moving
            val intent = Intent(context, InstallService::class.java).apply {
                action = ACTION_RUN_MOVE
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                _moveState.value = MoveState.Failed(
                    "Foreground service start rejected by OS: ${e.message}"
                )
                Log.w(TAG, "Foreground service start rejected for move: ${e.message}")
                false
            }
        }

        fun resetSessionState() {
            if (_isRunning.value) return
            _installJobs.value = emptyList()
            _finalResult.value = null
            _moveState.value = MoveState.Idle
        }

        fun resetMoveState() {
            if (_moveState.value !is MoveState.Moving) {
                _moveState.value = MoveState.Idle
            }
        }
    }

    private val repackSemaphore = Semaphore(1)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RUN_REPACK -> {
                val request = pendingRepackRequest
                pendingRepackRequest = null
                if (request == null) {
                    Log.w(TAG, "Received ACTION_RUN_REPACK without pending request")
                    stopForegroundCompat()
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (worker?.isActive == true) {
                    Log.w(TAG, "Repack/move worker already active")
                    return START_NOT_STICKY
                }
                _isRunning.value = true
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Repacking Mods", 0, request.jobs.size, "Preparing...")
                )
                worker = scope.launch { executeRepack(request) }
            }

            ACTION_RUN_MOVE -> {
                if (worker?.isActive == true) {
                    Log.w(TAG, "Worker already active, ignoring concurrent move")
                    _moveState.value = MoveState.Failed("Another install operation is currently running.")
                    return START_NOT_STICKY
                }
                _isRunning.value = true
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Moving to Game Directory", 0, 0, "Transferring via Shizuku...")
                )
                worker = scope.launch { executeMove() }
            }

            else -> {
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun executeRepack(request: RepackRequest) {
        val journal = OperationJournalStore(File(filesDir, OperationJournalStore.JOURNAL_FILENAME))
        val bundleNames = request.jobs.map { it.hashedName }
        journal.clear()
        journal.append(
            JournalEntry(
                operationId = request.operationId,
                stage = JournalStage.PLANNED.id,
                atMillis = System.currentTimeMillis(),
                bundleNames = bundleNames
            )
        )

        val startTimeMs = System.currentTimeMillis()
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(applicationContext))
            }

            val downloadSemaphore = Semaphore(4)
            coroutineScope {
                _installJobs.value.map { job ->
                    async(Dispatchers.IO) {
                        downloadSemaphore.withPermit {
                            processSingleJob(job, request.quality, request.useAstc, request.jobs.size)
                        }
                    }
                }.awaitAll()
            }

            val jobs = _installJobs.value
            val successfulJobs = jobs.filter { it.status is JobStatus.Finished }
            val failedJobs = jobs.filter { it.status is JobStatus.Failed }
            val elapsedMs = System.currentTimeMillis() - startTimeMs
            val shizukuAvailable = ShizukuManager.isRunning()
            val failedDetails = failedJobs.map {
                val status = it.status as JobStatus.Failed
                FailedJobInfo(it.job.hashedName, status.detailedLog)
            }
            val command = if (successfulJobs.isNotEmpty()) {
                "mv -f /storage/emulated/0/Download/Shared /storage/emulated/0/Android/data/com.neowizgames.game.browndust2/files/UnityCache/"
            } else null

            if (successfulJobs.isNotEmpty()) {
                journal.append(
                    JournalEntry(
                        operationId = request.operationId,
                        stage = JournalStage.STAGED.id,
                        atMillis = System.currentTimeMillis(),
                        bundleNames = successfulJobs.map { it.job.hashedName }
                    )
                )
            } else {
                journal.clear()
            }

            _finalResult.value = FinalInstallResult(
                successfulJobs = successfulJobs.size,
                failedJobs = failedJobs.size,
                command = command,
                elapsedTimeMs = elapsedMs,
                failedJobDetails = failedDetails,
                shizukuAvailable = shizukuAvailable
            )
        } catch (e: CancellationException) {
            Log.i(TAG, "Batch repack cancelled by user")
            journal.clear()
            _installJobs.value = emptyList()
            _finalResult.value = null
            _moveState.value = MoveState.Idle
        } catch (e: Exception) {
            Log.e(TAG, "Batch repack execution failed", e)
            journal.clear()
            _finalResult.value = FinalInstallResult(
                successfulJobs = 0,
                failedJobs = request.jobs.size,
                command = null,
                elapsedTimeMs = System.currentTimeMillis() - startTimeMs,
                failedJobDetails = listOf(FailedJobInfo("batch", e.message ?: "Unknown error")),
                shizukuAvailable = ShizukuManager.isRunning()
            )
        } finally {
            cancelRequested = false
            _isRunning.value = false
            stopForegroundCompat()
            stopSelf()
        }
    }

    private suspend fun executeMove() {
        val journal = OperationJournalStore(File(filesDir, OperationJournalStore.JOURNAL_FILENAME))
        val opId = currentOperationId ?: "move-${UUID.randomUUID().toString().take(8)}"
        val sharedDir = File("/storage/emulated/0/Download/Shared")
        val bundleNames = sharedDir.listFiles()?.map { it.name } ?: emptyList()

        journal.append(
            JournalEntry(
                operationId = opId,
                stage = JournalStage.TARGET_REPLACING.id,
                atMillis = System.currentTimeMillis(),
                bundleNames = bundleNames
            )
        )

        val token = ShizukuManager.acquireConnection()
        try {
            val (success, message) = ShizukuManager.moveDownloadToGame()
            if (success) {
                journal.append(
                    JournalEntry(
                        operationId = opId,
                        stage = JournalStage.TARGET_REPLACED.id,
                        atMillis = System.currentTimeMillis(),
                        bundleNames = bundleNames
                    )
                )
                journal.clear()
                _moveState.value = MoveState.Success(message)
            } else {
                _moveState.value = MoveState.Failed(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku move failed", e)
            _moveState.value = MoveState.Failed(e.message ?: "Move failed")
        } finally {
            ShizukuManager.releaseConnection(token)
            _isRunning.value = false
            stopForegroundCompat()
            stopSelf()
        }
    }

    private suspend fun processSingleJob(
        installJob: InstallJob,
        quality: String,
        useAstc: Boolean,
        totalJobs: Int
    ) {
        val job = installJob.job
        val hashedName = job.hashedName
        val repackedFiles = mutableListOf<File>()
        val modAssetsDir = File(cacheDir, "temp_mod_assets_$hashedName")
        var lastProgressUpdateMs = 0L

        fun updateProgressThrottled(statusFactory: (String) -> JobStatus, message: String, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (force || now - lastProgressUpdateMs >= 150L) {
                lastProgressUpdateMs = now
                updateJobStatus(hashedName, statusFactory(message), totalJobs, message)
            }
        }

        try {
            if (cancelRequested) throw CancellationException("Cancelled")
            coroutineScope { ensureActive() }
            updateJobStatus(hashedName, JobStatus.Installing("Extracting mod files..."), totalJobs, "Extracting $hashedName...")
            if (modAssetsDir.exists()) modAssetsDir.deleteRecursively()
            modAssetsDir.mkdirs()

            job.modsToInstall.forEach { modInfo ->
                if (modInfo.isDirectory) {
                    DocumentFile.fromTreeUri(applicationContext, modInfo.uri)?.let {
                        copyDirectoryToCache(it, modAssetsDir)
                    }
                } else {
                    contentResolver.openInputStream(modInfo.uri)?.use { fis ->
                        ZipInputStream(fis).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory && !ModEntryUtils.shouldIgnoreModEntry(entry.name)) {
                                    val newFile = ModEntryUtils.resolveZipEntrySafely(modAssetsDir, entry.name)
                                    if (newFile != null) {
                                        newFile.parentFile?.mkdirs()
                                        newFile.outputStream().use { fos -> zis.copyTo(fos) }
                                    } else {
                                        Log.w(TAG, "Skipped unsafe zip entry: ${entry.name}")
                                    }
                                }
                                entry = zis.nextEntry
                            }
                        }
                    }
                }
            }

            val candidates = (listOf(hashedName) + job.modsToInstall.flatMap { it.alternativeHashes })
                .distinct()
                .take(MAX_BUNDLE_CANDIDATES)

            var chosenRepoPath: String? = null
            var chosenRepackedFile: File? = null
            var bestUnmatchedCount = Int.MAX_VALUE
            var lastMessage: String? = null
            val candidateErrors = mutableListOf<String>()

            val bundleCacheDir = File(cacheDir, "bundle_cache").apply { mkdirs() }

            for ((index, candidate) in candidates.withIndex()) {
                if (cancelRequested) throw CancellationException("Cancelled")
                coroutineScope { ensureActive() }
                updateJobStatus(
                    hashedName,
                    JobStatus.Downloading(
                        if (index == 0) "Downloading bundle..."
                        else "Trying alternative bundle (${index + 1}/${candidates.size})..."
                    ),
                    totalJobs,
                    "Downloading $candidate..."
                )
                val (downloadSuccess, messageOrPath) = ModdingService.downloadBundle(
                    candidate,
                    quality,
                    bundleCacheDir.absolutePath,
                    filesDir.absolutePath
                ) { progress -> updateProgressThrottled({ JobStatus.Downloading(it) }, progress) }

                if (cancelRequested) throw CancellationException("Cancelled")
                if (!downloadSuccess) {
                    candidateErrors.add("$candidate: $messageOrPath")
                    continue
                }
                val originalDataCache = File(messageOrPath)
                val relativePath = originalDataCache.relativeTo(bundleCacheDir)

                updateJobStatus(hashedName, JobStatus.Installing("Waiting to repack..."), totalJobs, "Queued $candidate...")
                val repackedFile = File(cacheDir, "repacked/${relativePath.path}")
                if (repackedFile.exists()) repackedFile.delete()
                repackedFile.parentFile?.mkdirs()
                repackedFiles.add(repackedFile)

                val (repackSuccess, repackMessage, unmatched) = repackSemaphore.withPermit {
                    if (cancelRequested) throw CancellationException("Cancelled")
                    updateJobStatus(hashedName, JobStatus.Installing("Repacking bundle..."), totalJobs, "Repacking $candidate...")
                    ModdingService.repackBundle(
                        originalDataCache.absolutePath,
                        modAssetsDir.absolutePath,
                        repackedFile.absolutePath,
                        useAstc
                    ) { progress -> updateProgressThrottled({ JobStatus.Installing(it) }, progress) }
                }

                if (cancelRequested) throw CancellationException("Cancelled")

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
                    runCatching { if (originalDataCache.exists()) originalDataCache.delete() }
                }
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

            if (cancelRequested) throw CancellationException("Cancelled")
            val publicUri = saveFileToDownloads(repackedOutput, repoPath, "Shared")
            if (publicUri != null) {
                updateJobStatus(hashedName, JobStatus.Finished(repoPath, warningMessage), totalJobs, "Completed $hashedName")
            } else {
                throw Exception("Failed to save repacked file to Downloads/Shared.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val fullErrorMessage = e.message ?: "An unknown error occurred."
            val displayError = when {
                fullErrorMessage.contains("Download failed", ignoreCase = true) -> "Download failed. Check network or bundle name."
                fullErrorMessage.contains("Repack failed", ignoreCase = true) -> "Repack failed. Check mod files or logs."
                fullErrorMessage.contains("Failed to save", ignoreCase = true) -> "Failed to save to Downloads."
                else -> "An unexpected error occurred."
            }
            updateJobStatus(hashedName, JobStatus.Failed(displayError, fullErrorMessage), totalJobs, "Failed: $hashedName")
        } finally {
            if (modAssetsDir.exists()) modAssetsDir.deleteRecursively()
            repackedFiles.forEach { if (it.exists()) it.delete() }
        }
    }

    private fun updateJobStatus(hashedName: String, newStatus: JobStatus, totalJobs: Int, text: String) {
        _installJobs.update { jobs ->
            jobs.map { job -> if (job.job.hashedName == hashedName) job.copy(status = newStatus) else job }
        }
        val doneCount = _installJobs.value.count { it.status is JobStatus.Finished || it.status is JobStatus.Failed }
        val now = System.currentTimeMillis()
        if (now - lastNotifyMs >= NOTIFY_INTERVAL_MS || (totalJobs > 0 && doneCount >= totalJobs)) {
            lastNotifyMs = now
            getSystemService(NotificationManager::class.java)?.notify(
                NOTIFICATION_ID,
                buildNotification("Repacking Mods", doneCount, totalJobs, text)
            )
        }
    }

    private fun copyDirectoryToCache(
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
                if (depth < maxDepth && !ModEntryUtils.shouldIgnoreModEntry("$fileName/placeholder")) {
                    copyDirectoryToCache(file, File(destinationDir, fileName), depth + 1, maxDepth)
                }
            } else if (file.isFile && !ModEntryUtils.shouldIgnoreModEntry(fileName)) {
                val destFile = File(destinationDir, fileName)
                try {
                    contentResolver.openInputStream(file.uri)?.use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed copying $fileName to cache", e)
                }
            }
        }
    }

    private fun saveFileToDownloads(file: File, relativeDestPath: String, rootDir: String): Uri? {
        val resolver = contentResolver
        val finalRelativePath = File(rootDir, relativeDestPath)
        val relativePathWithSlash = File(Environment.DIRECTORY_DOWNLOADS, finalRelativePath.parent).path + File.separator
        val queryUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(relativePathWithSlash, finalRelativePath.name)

        fun findExistingUri(): Uri? {
            resolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    if (idCol != -1) return ContentUris.withAppendedId(queryUri, cursor.getLong(idCol))
                }
            }
            return null
        }

        fun writeToUri(targetUri: Uri): Boolean = try {
            resolver.openOutputStream(targetUri, "wt")?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            true
        } catch (_: Exception) {
            false
        }

        findExistingUri()?.let { existingUri ->
            if (writeToUri(existingUri)) return existingUri
            runCatching { resolver.delete(existingUri, null, null) }
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, finalRelativePath.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathWithSlash)
        }
        return try {
            val uri = resolver.insert(queryUri, contentValues)
            if (uri != null) {
                if (writeToUri(uri)) return uri
                runCatching { resolver.delete(uri, null, null) }
            }
            null
        } catch (_: Exception) {
            findExistingUri()?.takeIf { writeToUri(it) }
        }
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        stopForegroundCompat()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Mod Repack & Install",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while repacking or moving mods to game directory"
            }
        )
    }

    private fun buildNotification(title: String, done: Int, total: Int, text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or Intent.FLAG_ACTIVITY_NEW_TASK
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val displayTitle = if (total > 0) "$title ($done/$total)" else title
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(displayTitle)
            .setContentText(text.ifBlank { "Processing..." })
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(total.coerceAtLeast(1), done, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun stopForegroundCompat() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
