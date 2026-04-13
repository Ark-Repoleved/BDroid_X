package com.example.bd2modmanager.service

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.example.bd2modmanager.IFileService
import com.example.bd2modmanager.data.model.BundleCheckResult
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
     * Phase 1: 列舉遊戲目錄並檢查哪些 bundle 需要掃描。
     * 不執行實際掃描，只回傳結果讓呼叫端決定是否繼續。
     *
     * @param outputDir  App 可寫入的目錄（通常是 context.filesDir）
     * @param onProgress 進度回報
     * @return BundleCheckResult? — null 表示失敗或無 bundle
     */
    suspend fun checkLocalBundles(
        outputDir: String,
        onProgress: (String) -> Unit
    ): BundleCheckResult? = withContext(Dispatchers.IO) {
        try {
            if (!isAvailable()) {
                onProgress("Shizuku is not available. Skipping local bundle scan.")
                return@withContext null
            }

            val service = ensureServiceBound()
            if (service == null) {
                onProgress("Failed to connect to Shizuku file service.")
                return@withContext null
            }

            // Step 1: List bundles via Shizuku
            onProgress("Listing local game bundles...")
            val bundleListJson = service.listBundleDirectory(GAME_SHARED_PATH)

            val bundleList = JSONArray(bundleListJson)
            if (bundleList.length() == 0) {
                onProgress("No bundles found in game directory.")
                return@withContext null
            }

            onProgress("Found ${bundleList.length()} bundles. Checking cache...")

            // Step 2: Check which bundles need scanning (Python side)
            val needsScanJson = ModdingService.checkScanNeeded(outputDir, bundleListJson, onProgress)
            val needsScan = JSONArray(needsScanJson)

            // Build hash lookup from the full bundle list
            val hashMap = mutableMapOf<String, String>()
            for (i in 0 until bundleList.length()) {
                val obj = bundleList.getJSONObject(i)
                hashMap[obj.getString("name")] = obj.getString("hash")
            }

            BundleCheckResult(
                bundleListJson = bundleListJson,
                needsScanJson = needsScanJson,
                hashMap = hashMap,
                needsScanCount = needsScan.length()
            )
        } catch (e: Exception) {
            Log.e("ShizukuManager", "Error checking local bundles", e)
            onProgress("Error: ${e.message}")
            null
        }
    }

    /**
     * Phase 2: 執行實際的 bundle 掃描並儲存索引。
     * 應在使用者確認後呼叫。
     *
     * @param outputDir         App 可寫入的目錄
     * @param cacheDir          App 的暫存目錄
     * @param checkResult       Phase 1 回傳的結果
     * @param onBundleProgress  每個 bundle 掃描時的進度 callback
     * @return Triple<成功, 掃描數量, 失敗數量>
     */
    suspend fun executeBundleScan(
        outputDir: String,
        cacheDir: String,
        checkResult: BundleCheckResult,
        onBundleProgress: (currentIndex: Int, total: Int, bundleName: String, message: String) -> Unit
    ): Triple<Boolean, Int, Int> = withContext(Dispatchers.IO) {
        try {
            val service = ensureServiceBound()
                ?: return@withContext Triple(false, 0, 0)

            val needsScan = JSONArray(checkResult.needsScanJson)
            val total = needsScan.length()

            if (total == 0) {
                // Nothing to scan, just finalize with cached data
                ModdingService.finalizeScan(outputDir) { }
                return@withContext Triple(true, 0, 0)
            }

            val tempDir = File(cacheDir, "scan_temp")
            tempDir.mkdirs()
            val tempDataFile = File(tempDir, "__data")

            var scannedCount = 0
            var failedCount = 0

            for (i in 0 until total) {
                val bundleName = needsScan.getString(i)
                val bundleHash = checkResult.hashMap[bundleName] ?: continue

                onBundleProgress(i, total, bundleName, "Copying $bundleName...")

                // Copy __data from game dir to temp via Shizuku
                val gamePath = "$GAME_SHARED_PATH/$bundleName/$bundleHash/__data"
                val copySuccess = service.copyFile(gamePath, tempDataFile.absolutePath)

                if (!copySuccess) {
                    Log.w("ShizukuManager", "Failed to copy bundle $bundleName, skipping")
                    failedCount++
                    continue
                }

                onBundleProgress(i, total, bundleName, "Scanning $bundleName...")

                // Scan via Python
                val (scanSuccess, _, _) = ModdingService.scanSingleBundle(
                    bundleName, bundleHash, tempDataFile.absolutePath
                ) { msg -> onBundleProgress(i, total, bundleName, msg) }

                if (scanSuccess) scannedCount++ else failedCount++

                // Clean up temp file immediately
                tempDataFile.delete()
            }

            // Clean up temp directory
            tempDir.deleteRecursively()

            // Finalize and save index
            onBundleProgress(total, total, "", "Saving index...")
            val (finalSuccess, _) = ModdingService.finalizeScan(outputDir) { msg ->
                onBundleProgress(total, total, "", msg)
            }

            Triple(finalSuccess, scannedCount, failedCount)
        } catch (e: Exception) {
            Log.e("ShizukuManager", "Error scanning local bundles", e)
            Triple(false, 0, 0)
        }
    }

    /**
     * 便捷方法：一次完成列舉 + 掃描 + 儲存。
     * 保留向後兼容，內部使用分段 API。
     */
    suspend fun scanLocalBundles(
        outputDir: String,
        cacheDir: String,
        onProgress: (String) -> Unit
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val checkResult = checkLocalBundles(outputDir, onProgress)
            ?: return@withContext Pair(false, "Failed to check local bundles.")

        if (checkResult.needsScanCount == 0) {
            onProgress("All bundles are up to date. Finalizing...")
            return@withContext ModdingService.finalizeScan(outputDir, onProgress)
        }

        onProgress("${checkResult.needsScanCount} bundles need scanning...")
        val (success, scanned, failed) = executeBundleScan(outputDir, cacheDir, checkResult) { idx, total, name, msg ->
            onProgress("Scanning ${idx + 1}/$total: $name - $msg")
        }

        Pair(success, "Scanned: $scanned, Failed: $failed")
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
