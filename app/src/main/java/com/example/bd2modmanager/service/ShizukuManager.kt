package com.example.bd2modmanager.service

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.example.bd2modmanager.IFileService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

object ShizukuManager {

    private const val GAME_UNITY_CACHE_PATH =
        "/storage/emulated/0/Android/data/com.neowizgames.game.browndust2/files/UnityCache/"
    private const val DOWNLOAD_SHARED_PATH =
        "/storage/emulated/0/Download/Shared"

    private var fileService: IFileService? = null
    private val bindMutex = Mutex()

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            "com.example.bd2modmanager",
            ShizukuFileService::class.java.name
        )
    )
        .daemon(false)
        .processNameSuffix("file_service")
        .debuggable(android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.R)
        .tag("file_service")
        .version(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            fileService = IFileService.Stub.asInterface(service)
            pendingBind?.complete(true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            fileService = null
        }
    }

    private var pendingBind: CompletableDeferred<Boolean>? = null

    /**
     * 檢查 Shizuku 是否安裝且運行中，並且已授權
     */
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 檢查 Shizuku 是否運行中（不檢查權限）
     */
    fun isRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 檢查是否已取得 Shizuku 權限
     */
    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 請求 Shizuku 權限
     */
    fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

    /**
     * 綁定 UserService，取得 IFileService 實例
     */
    private suspend fun ensureServiceBound(): IFileService? {
        if (fileService != null) return fileService

        return bindMutex.withLock {
            // Double-check after acquiring lock
            if (fileService != null) return@withLock fileService

            val deferred = CompletableDeferred<Boolean>()
            pendingBind = deferred

            try {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
                withContext(Dispatchers.IO) {
                    deferred.await()
                }
                fileService
            } catch (e: Exception) {
                e.printStackTrace()
                pendingBind = null
                null
            }
        }
    }

    /**
     * 將 Download/Shared 目錄搬移到遊戲的 UnityCache 目錄
     * @return Pair<成功, 錯誤訊息>
     */
    suspend fun moveDownloadToGame(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            if (!isAvailable()) {
                return@withContext Pair(false, "Shizuku is not available or not authorized.")
            }

            val service = ensureServiceBound()
                ?: return@withContext Pair(false, "Failed to connect to Shizuku file service.")

            val sourceDir = java.io.File(DOWNLOAD_SHARED_PATH)
            if (!sourceDir.exists() || !sourceDir.isDirectory) {
                return@withContext Pair(false, "Download/Shared directory not found.")
            }

            val success = service.copyDirectory(DOWNLOAD_SHARED_PATH, GAME_UNITY_CACHE_PATH + "Shared")

            if (success) {
                // 複製成功後刪除來源
                sourceDir.deleteRecursively()
                Pair(true, "Files moved to game directory successfully!")
            } else {
                Pair(false, "Failed to copy files to game directory.")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(false, "Error: ${e.message}")
        }
    }

    /**
     * 解綁 UserService
     */
    fun unbindService() {
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        fileService = null
    }
}
