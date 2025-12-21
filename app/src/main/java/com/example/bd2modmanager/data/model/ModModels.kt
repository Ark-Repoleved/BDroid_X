package com.example.bd2modmanager.data.model

import android.net.Uri

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
    data class Finished(val relativePath: String) : JobStatus()
    data class Failed(val error: String) : JobStatus()
}

data class InstallJob(
    val job: RepackJob,
    val status: JobStatus = JobStatus.Pending
)

data class FailedJobInfo(
    val hashedName: String,
    val error: String
)

data class FinalInstallResult(
    val successfulJobs: Int,
    val failedJobs: Int,
    val command: String?,
    val elapsedTimeMs: Long = 0L,  // 總耗時（毫秒）
    val failedJobDetails: List<FailedJobInfo> = emptyList()  // 失敗任務的詳細資訊
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

sealed class MergeState {
    object Idle : MergeState()
    data class Merging(val progressMessage: String = "Initializing...") : MergeState()
    data class Finished(val message: String) : MergeState()
    data class Failed(val error: String) : MergeState()
}
