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
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import android.util.Log
import java.io.File

object ShizukuManager {

    private const val GAME_UNITY_CACHE_PATH =
        "/storage/emulated/0/Android/data/com.neowizgames.game.browndust2/files/UnityCache/"
    private const val DOWNLOAD_SHARED_PATH =
        "/storage/emulated/0/Download/Shared"
    private const val GAME_SHARED_PATH =
        "/storage/emulated/0/Android/data/com.neowizgames.game.browndust2/files/UnityCache/Shared"

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
     * Check whether Shizuku is running and authorized.
     */
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check whether Shizuku is running without checking authorization.
     */
    fun isRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check whether Shizuku authorization has been granted.
     */
    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Request Shizuku authorization.
     */
    fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

    /**
     * Bind the user service and obtain an IFileService instance.
     */
    private suspend fun ensureServiceBound(): IFileService? {
        if (fileService != null) return fileService

        return bindMutex.withLock {
            if (fileService != null) return@withLock fileService

            val deferred = CompletableDeferred<Boolean>()
            pendingBind = deferred

            try {
                Log.d("ShizukuManager", "Binding user service...")
                Shizuku.bindUserService(userServiceArgs, serviceConnection)

                // Add 5 second timeout to prevent hanging forever
                val bound = withTimeoutOrNull(5000) {
                    deferred.await()
                }

                if (bound == true) {
                    Log.d("ShizukuManager", "User service bound successfully")
                    fileService
                } else {
                    Log.d("ShizukuManager", "Timeout waiting for service bind")
                    pendingBind = null
                    null
                }
            } catch (e: Exception) {
                Log.e("ShizukuManager", "Failed to bind service", e)
                pendingBind = null
                null
            }
        }
    }

    /**
     * Move Download/Shared into the game UnityCache directory.
     * @return A pair containing success status and a message.
     */
    suspend fun moveDownloadToGame(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            if (!isRunning()) {
                return@withContext Pair(false, "Shizuku is not running. Please start Shizuku first.")
            }

            if (!hasPermission()) {
                withContext(Dispatchers.Main) {
                    requestPermission(1001)
                }
                return@withContext Pair(false, "Shizuku permission requested. Please grant permission and try again.")
            }

            val service = ensureServiceBound()
                ?: return@withContext Pair(false, "Failed to connect to Shizuku file service.")

            val sourceDir = java.io.File(DOWNLOAD_SHARED_PATH)
            if (!sourceDir.exists() || !sourceDir.isDirectory) {
                return@withContext Pair(false, "Download/Shared directory not found.")
            }

            Log.d("ShizukuManager", "Starting to copy using Shizuku")
            val success = service.copyDirectory(DOWNLOAD_SHARED_PATH, GAME_UNITY_CACHE_PATH + "Shared")

            if (success) {
                Log.d("ShizukuManager", "Copy successful, cleaning up source")
                if (!sourceDir.deleteRecursively()) {
                    return@withContext Pair(false, "Files were copied, but Download/Shared could not be fully removed. Check the remaining source files before retrying.")
                }
                Pair(true, "Files moved to game directory successfully!")
            } else {
                Log.e("ShizukuManager", "Copy failed in ShizukuFileService")
                Pair(false, "Failed to copy files to game directory.")
            }
        } catch (e: Exception) {
            Log.e("ShizukuManager", "Error during Shizuku file operation", e)
            Pair(false, "Error: ${e.message}")
        }
    }

    /**
     * Unbind the user service.
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
