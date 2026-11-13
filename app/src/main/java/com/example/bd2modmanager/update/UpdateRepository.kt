package com.example.bd2modmanager.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for BDroid_X and downloads the ABI-matched APK via
 * DownloadManager (ported from BD2ToolsBox).
 */
class UpdateRepository(private val appContext: Context) {

    companion object {
        private const val TAG = "UpdateRepository"
        private const val ENDPOINT_LATEST =
            "https://api.github.com/repos/Ark-Repoleved/BDroid_X/releases/latest"
        private const val ENDPOINT_LIST =
            "https://api.github.com/repos/Ark-Repoleved/BDroid_X/releases?per_page=10"
        private const val RELEASES_PAGE =
            "https://github.com/Ark-Repoleved/BDroid_X/releases"
        private const val UA = "Mozilla/5.0 (Linux; Android) BDroid_X"

        private const val KEY_DL_ID = "download_id"
        private const val KEY_DL_EXPECTED_BYTES = "download_expected_bytes"
        private const val KEY_DL_FILE_NAME = "download_file_name"
        private const val KEY_BETA_CHANNEL = "beta_channel_enabled"

        fun compare(a: String, b: String): Int {
            val cleanA = a.trim().removePrefix("v").removePrefix("V").trim()
            val cleanB = b.trim().removePrefix("v").removePrefix("V").trim()
            val mainA = cleanA.substringBefore('-')
            val mainB = cleanB.substringBefore('-')
            val pa = mainA.split('.')
            val pb = mainB.split('.')
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrNull(i).orEmpty().takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                val y = pb.getOrNull(i).orEmpty().takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                if (x != y) return x.compareTo(y)
            }
            val preA = cleanA.substringAfter('-', "")
            val preB = cleanB.substringAfter('-', "")
            if (preA.isEmpty() && preB.isEmpty()) return 0
            if (preA.isEmpty()) return 1
            if (preB.isEmpty()) return -1
            val numsA = Regex("\\d+").findAll(preA).mapNotNull { it.value.toIntOrNull() }.toList()
            val numsB = Regex("\\d+").findAll(preB).mapNotNull { it.value.toIntOrNull() }.toList()
            for (i in 0 until maxOf(numsA.size, numsB.size)) {
                val x = numsA.getOrNull(i) ?: 0
                val y = numsB.getOrNull(i) ?: 0
                if (x != y) return x.compareTo(y)
            }
            return preA.compareTo(preB, ignoreCase = true)
        }
    }

    data class Release(
        val version: String,
        val notes: String,
        val htmlUrl: String,
        val apkAssets: List<ApkAsset> = emptyList(),
        val isPrerelease: Boolean = false
    )

    data class ApkAsset(
        val name: String,
        val url: String,
        val bytes: Long
    )

    fun installedVersion(): String = try {
        appContext.packageManager
            .getPackageInfo(appContext.packageName, 0).versionName?.trim().orEmpty()
            .ifEmpty { "?" }
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read installed versionName", e)
        "?"
    }

    fun isNewerThanInstalled(version: String): Boolean = compare(version, installedVersion()) > 0

    fun isBetaChannelEnabled(): Boolean {
        val current = installedVersion()
        val defaultBeta = current.contains("-beta", ignoreCase = true) ||
            current.contains("-preview", ignoreCase = true)
        return dlPrefs().getBoolean(KEY_BETA_CHANNEL, defaultBeta)
    }

    fun setBetaChannelEnabled(enabled: Boolean) {
        dlPrefs().edit().putBoolean(KEY_BETA_CHANNEL, enabled).apply()
    }

    suspend fun fetchLatest(includeBeta: Boolean = isBetaChannelEnabled()): Release? =
        withContext(Dispatchers.IO) {
            if (includeBeta) {
                val listBody = httpGet(ENDPOINT_LIST)
                val newest = listBody?.let { parseReleaseList(it) }
                if (newest != null) return@withContext newest
            }
            val body = httpGet(ENDPOINT_LATEST) ?: return@withContext null
            parseRelease(body)
        }

    internal fun parseReleaseList(body: String): Release? = try {
        val arr = JSONArray(body)
        val releases = mutableListOf<Release>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optBoolean("draft", false)) continue
            parseReleaseObject(obj)?.let { releases.add(it) }
        }
        releases.maxWithOrNull { a, b -> compare(a.version, b.version) }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse release list", e)
        null
    }

    private fun parseRelease(body: String): Release? = try {
        parseReleaseObject(JSONObject(body))
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse release", e)
        null
    }

    private fun parseReleaseObject(root: JSONObject): Release? {
        val version = root.optString("tag_name")
            .trim().removePrefix("v").removePrefix("V").trim()
        if (version.isEmpty()) return null
        val isPre = root.optBoolean("prerelease", false) ||
            version.contains("-beta", ignoreCase = true) ||
            version.contains("-rc", ignoreCase = true)
        return Release(
            version = version,
            notes = root.optString("body"),
            htmlUrl = root.optString("html_url").takeIf { it.isNotBlank() } ?: RELEASES_PAGE,
            apkAssets = parseApkAssets(root),
            isPrerelease = isPre
        )
    }

    private fun parseApkAssets(root: JSONObject): List<ApkAsset> {
        return try {
            val arr = root.optJSONArray("assets") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString("name").takeIf { it.endsWith(".apk", ignoreCase = true) }
                        ?: continue
                    val url = obj.optString("browser_download_url").takeIf { it.isNotBlank() }
                        ?: continue
                    add(ApkAsset(name, url, obj.optLong("size", 0L)))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse APK assets", e)
            emptyList()
        }
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val status = conn.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP $status: $url")
                return null
            }
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            Log.w(TAG, "Request failed: $url (${e.message})")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun dlPrefs() =
        appContext.getSharedPreferences("update_download", Context.MODE_PRIVATE)

    fun currentDownloadId(): Long = dlPrefs().getLong(KEY_DL_ID, -1L)

    fun isDownloadSuccessful(id: Long): Boolean {
        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return false
        return try {
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                val col = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                col >= 0 && c.moveToFirst() && c.getInt(col) == DownloadManager.STATUS_SUCCESSFUL
            }
        } catch (e: Exception) {
            Log.w(TAG, "Download status query failed", e)
            false
        }
    }

    fun hasActiveDownload(): Boolean {
        val id = currentDownloadId()
        if (id == -1L) return false
        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return false
        return try {
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                val col = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (col < 0) return@use false
                var active = false
                while (c.moveToNext()) {
                    when (c.getInt(col)) {
                        DownloadManager.STATUS_PENDING,
                        DownloadManager.STATUS_RUNNING,
                        DownloadManager.STATUS_PAUSED -> active = true
                    }
                }
                active
            }
        } catch (_: Exception) {
            false
        }
    }

    fun startApkDownload(release: Release): ApkAsset? {
        val asset = pickApkAsset(release) ?: return null
        if (hasActiveDownload()) return null
        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return null
        updateDir().listFiles()?.forEach { it.delete() }
        val req = DownloadManager.Request(Uri.parse(asset.url))
            .setTitle(asset.name)
            .setDescription("BDroid X ${release.version}")
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalFilesDir(appContext, null, "updates/${asset.name}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        dlPrefs().edit()
            .putLong(KEY_DL_ID, dm.enqueue(req))
            .putLong(KEY_DL_EXPECTED_BYTES, asset.bytes)
            .putString(KEY_DL_FILE_NAME, asset.name)
            .apply()
        return asset
    }

    fun isApkDownloaded(release: Release): Boolean {
        if (hasActiveDownload()) return false
        val asset = pickApkAsset(release) ?: return false
        val f = File(updateDir(), asset.name)
        if (!f.isFile || f.length() <= 0) return false
        if (asset.bytes > 0 && f.length() != asset.bytes) return false
        val id = currentDownloadId()
        if (id != -1L && !isDownloadSuccessful(id)) return false
        return true
    }

    fun downloadedApkFile(): File? {
        if (hasActiveDownload()) return null
        val prefs = dlPrefs()
        val expectedBytes = prefs.getLong(KEY_DL_EXPECTED_BYTES, -1L)
        val expectedName = prefs.getString(KEY_DL_FILE_NAME, null)
        val candidate = if (!expectedName.isNullOrBlank()) {
            File(updateDir(), expectedName).takeIf { it.isFile }
        } else {
            updateDir().listFiles()?.firstOrNull { it.isFile && it.length() > 0 }
        } ?: return null
        if (candidate.length() <= 0) return null
        if (expectedBytes > 0 && candidate.length() != expectedBytes) return null
        return candidate
    }

    internal fun updateDir(): File =
        File(appContext.getExternalFilesDir(null), "updates").apply { mkdirs() }

    internal var supportedAbisProvider: () -> List<String> = {
        Build.SUPPORTED_ABIS?.toList().orEmpty()
    }

    /**
     * Picks the ABI-matching APK, falling back to a single universal/untagged APK
     * only if the release ships a single APK without ABI tokens.
     */
    private fun pickApkAsset(release: Release): ApkAsset? {
        ApkSelector.select(release.apkAssets, supportedAbisProvider())?.let { return it }
        if (release.apkAssets.size == 1) {
            val only = release.apkAssets.first()
            val hasAbiToken = listOf("arm64", "armeabi", "x86_64", "x86")
                .any { only.name.contains(it, ignoreCase = true) }
            if (!hasAbiToken) return only
        }
        return null
    }
}
