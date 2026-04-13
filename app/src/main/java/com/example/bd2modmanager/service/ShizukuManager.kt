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
import org.json.JSONArray
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
     * 將 Download/Shared 目錄搬移到遊戲的 UnityCache 目錄
     * @return Pair<成功, 錯誤訊息>
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
                sourceDir.deleteRecursively()
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
     * 掃描遊戲本地快取的 bundle，建立 asset-to-bundle 索引。
     *
     * 三步驟流程：
     * 1. 透過 Shizuku 列舉 Shared/ 目錄取得 bundle 列表
     * 2. 比對快取，只掃描新增/更新的 bundle（逐一複製到 temp → Python 解析 → 刪除 temp）
     * 3. 合併快取與新掃描結果，儲存最終索引
     *
     * @param outputDir  App 可寫入的目錄（通常是 context.filesDir）
     * @param cacheDir   App 的暫存目錄（通常是 context.cacheDir）
     * @param onProgress 進度回報
     * @return Pair<成功, 訊息>
     */
    suspend fun scanLocalBundles(
        outputDir: String,
        cacheDir: String,
        onProgress: (String) -> Unit
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            if (!isAvailable()) {
                return@withContext Pair(false, "Shizuku is not available. Skipping local bundle scan.")
            }

            val service = ensureServiceBound()
                ?: return@withContext Pair(false, "Failed to connect to Shizuku file service.")

            // Step 1: List bundles via Shizuku
            onProgress("Listing local game bundles...")
            val bundleListJson = service.listBundleDirectory(GAME_SHARED_PATH)

            val bundleList = JSONArray(bundleListJson)
            if (bundleList.length() == 0) {
                onProgress("No bundles found in game directory.")
                return@withContext Pair(false, "No bundles found. Is the game installed and has been played?")
            }

            onProgress("Found ${bundleList.length()} bundles. Checking cache...")

            // Step 2: Check which bundles need scanning (Python side)
            val needsScanJson = ModdingService.checkScanNeeded(outputDir, bundleListJson, onProgress)
            val needsScan = JSONArray(needsScanJson)

            if (needsScan.length() == 0) {
                onProgress("All bundles are up to date. Finalizing...")
                val (finalSuccess, finalMsg) = ModdingService.finalizeScan(outputDir, onProgress)
                return@withContext Pair(finalSuccess, finalMsg)
            }

            onProgress("${needsScan.length()} bundles need scanning...")

            // Build hash lookup from the full bundle list
            val hashMap = mutableMapOf<String, String>()
            for (i in 0 until bundleList.length()) {
                val obj = bundleList.getJSONObject(i)
                hashMap[obj.getString("name")] = obj.getString("hash")
            }

            // Step 3: Scan each bundle one at a time
            val tempDir = File(cacheDir, "scan_temp")
            tempDir.mkdirs()
            val tempDataFile = File(tempDir, "__data")

            var scannedCount = 0
            var failedCount = 0

            for (i in 0 until needsScan.length()) {
                val bundleName = needsScan.getString(i)
                val bundleHash = hashMap[bundleName] ?: continue

                onProgress("Scanning ${i + 1}/${needsScan.length()}: $bundleName")

                // Copy __data from game dir to temp via Shizuku
                val gamePath = "$GAME_SHARED_PATH/$bundleName/$bundleHash/__data"
                val copySuccess = service.copyFile(gamePath, tempDataFile.absolutePath)

                if (!copySuccess) {
                    Log.w("ShizukuManager", "Failed to copy bundle $bundleName, skipping")
                    failedCount++
                    continue
                }

                // Scan via Python
                val (scanSuccess, _, _) = ModdingService.scanSingleBundle(
                    bundleName, bundleHash, tempDataFile.absolutePath, onProgress
                )

                if (scanSuccess) scannedCount++ else failedCount++

                // Clean up temp file immediately
                tempDataFile.delete()
            }

            // Clean up temp directory
            tempDir.deleteRecursively()

            // Step 4: Finalize and save index
            onProgress("Saving index... (scanned: $scannedCount, failed: $failedCount)")
            val (finalSuccess, finalMsg) = ModdingService.finalizeScan(outputDir, onProgress)
            Pair(finalSuccess, finalMsg)

        } catch (e: Exception) {
            Log.e("ShizukuManager", "Error scanning local bundles", e)
            Pair(false, "Error: ${e.message}")
        }
    }

    /**
     * 帶有詳細進度回報的 bundle 掃描。
     * onProgress 回報 (訊息, 目前進度, 總數)，讓 UI 可顯示進度條。
     */
    suspend fun scanLocalBundlesWithProgress(
        outputDir: String,
        cacheDir: String,
        onProgress: (String, Int, Int) -> Unit
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            if (!isAvailable()) {
                return@withContext Pair(false, "Shizuku is not available. Skipping local bundle scan.")
            }

            val service = ensureServiceBound()
                ?: return@withContext Pair(false, "Failed to connect to Shizuku file service.")

            // Step 1: List bundles via Shizuku
            onProgress("Listing local game bundles...", 0, 0)
            val bundleListJson = service.listBundleDirectory(GAME_SHARED_PATH)

            val bundleList = JSONArray(bundleListJson)
            if (bundleList.length() == 0) {
                onProgress("No bundles found in game directory.", 0, 0)
                return@withContext Pair(false, "No bundles found. Is the game installed and has been played?")
            }

            onProgress("Found ${bundleList.length()} bundles. Checking cache...", 0, bundleList.length())

            // Step 2: Check which bundles need scanning (Python side)
            val needsScanJson = ModdingService.checkScanNeeded(outputDir, bundleListJson) { msg ->
                onProgress(msg, 0, bundleList.length())
            }
            val needsScan = JSONArray(needsScanJson)

            if (needsScan.length() == 0) {
                onProgress("All bundles are up to date. Finalizing...", bundleList.length(), bundleList.length())
                val (finalSuccess, finalMsg) = ModdingService.finalizeScan(outputDir) { msg ->
                    onProgress(msg, bundleList.length(), bundleList.length())
                }
                return@withContext Pair(finalSuccess, finalMsg)
            }

            onProgress("${needsScan.length()} bundles need scanning...", 0, needsScan.length())

            // Build hash lookup from the full bundle list
            val hashMap = mutableMapOf<String, String>()
            for (i in 0 until bundleList.length()) {
                val obj = bundleList.getJSONObject(i)
                hashMap[obj.getString("name")] = obj.getString("hash")
            }

            // Step 3: Scan each bundle one at a time
            val tempDir = File(cacheDir, "scan_temp")
            tempDir.mkdirs()
            val tempDataFile = File(tempDir, "__data")

            var scannedCount = 0
            var failedCount = 0
            val totalToScan = needsScan.length()

            for (i in 0 until totalToScan) {
                val bundleName = needsScan.getString(i)
                val bundleHash = hashMap[bundleName] ?: continue

                onProgress("Scanning: $bundleName", i + 1, totalToScan)

                // Copy __data from game dir to temp via Shizuku
                val gamePath = "$GAME_SHARED_PATH/$bundleName/$bundleHash/__data"
                val copySuccess = service.copyFile(gamePath, tempDataFile.absolutePath)

                if (!copySuccess) {
                    Log.w("ShizukuManager", "Failed to copy bundle $bundleName, skipping")
                    failedCount++
                    continue
                }

                // Scan via Python
                val (scanSuccess, _, _) = ModdingService.scanSingleBundle(
                    bundleName, bundleHash, tempDataFile.absolutePath
                ) { msg ->
                    onProgress(msg, i + 1, totalToScan)
                }

                if (scanSuccess) scannedCount++ else failedCount++

                // Clean up temp file immediately
                tempDataFile.delete()
            }

            // Clean up temp directory
            tempDir.deleteRecursively()

            // Step 4: Finalize and save index
            onProgress("Saving index... (scanned: $scannedCount, failed: $failedCount)", totalToScan, totalToScan)
            val (finalSuccess, finalMsg) = ModdingService.finalizeScan(outputDir) { msg ->
                onProgress(msg, totalToScan, totalToScan)
            }
            Pair(finalSuccess, finalMsg)

        } catch (e: Exception) {
            Log.e("ShizukuManager", "Error scanning local bundles", e)
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
