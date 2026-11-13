package com.example.bd2modmanager.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.bd2modmanager.utils.BoundedImageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 3-tier character avatar / costume thumbnail loader (memory LRU -> disk -> GitHub/jsDelivr CDN).
 * Ported from BD2ToolsBox.
 */
class AvatarRepository private constructor(
    private val appContext: Context,
    private val meta: CharacterMetaRepository
) {
    enum class SyncState { IDLE, RUNNING, DONE, FAILED }

    companion object {
        private const val TAG = "AvatarRepository"
        private const val AVATAR_MEMORY_BUDGET_BYTES = 8L * 1024 * 1024
        private const val TARGET_WIDTH = 128
        private const val MAX_PARALLEL_DOWNLOADS = 4
        private const val BAIL_AFTER_FAILURES = 12
        private const val CACHE_DIR = "avatars_v2"

        @Volatile
        private var instance: AvatarRepository? = null

        fun getInstance(context: Context): AvatarRepository =
            instance ?: synchronized(this) {
                val appCtx = context.applicationContext
                instance ?: AvatarRepository(
                    appCtx,
                    CharacterMetaRepository.getInstance(appCtx)
                ).also { instance = it }
            }
    }

    private val downloadGate = Semaphore(MAX_PARALLEL_DOWNLOADS)
    private val syncMutex = Mutex()

    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _epoch = MutableStateFlow(0)
    val epoch: StateFlow<Int> = _epoch.asStateFlow()

    private val decoded = BoundedImageCache(AVATAR_MEMORY_BUDGET_BYTES)

    private val failed = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 512
    }

    private val cacheDir: File
        get() = File(appContext.externalCacheDir ?: appContext.cacheDir, CACHE_DIR)

    fun avatarFor(character: String): Pair<String, String>? =
        targetOf(meta.forCharacter(character)?.representative)

    fun peekAvatar(character: String): Pair<String, String>? =
        targetOf(meta.peekCharacter(character)?.representative)

    fun avatarTargets(): List<Pair<String, String>> =
        meta.allRepresentatives().mapNotNull { targetOf(it) }

    private fun targetOf(costume: CharacterMetaRepository.Costume?): Pair<String, String>? {
        val c = costume ?: return null
        val url = c.illustUrl ?: return null
        return c.illustImage to url
    }

    fun illustForFileId(fileId: String?): Pair<String, String>? {
        val c = meta.forFileId(fileId) ?: meta.forDatingId(fileId) ?: return null
        val url = c.illustUrl ?: return null
        return c.illustImage to url
    }

    fun peek(key: String?): ImageBitmap? = key?.let { decoded.get(it) }

    suspend fun load(key: String, url: String): ImageBitmap? {
        decoded.get(key)?.let { return it }
        synchronized(failed) { if (failed.containsKey(key)) return null }

        return withContext(Dispatchers.IO) {
            val file = File(cacheDir, key)
            if (file.exists() && file.length() > 0) {
                decodeFile(file)?.let {
                    decoded.put(key, it)
                    return@withContext it
                }
                file.delete()
            }

            val outcome = downloadGate.withPermit { download(url, file) }
            if (outcome != Outcome.OK) {
                synchronized(failed) { failed[key] = true }
                return@withContext null
            }
            val bmp = decodeFile(file)
            if (bmp == null) {
                file.delete()
                synchronized(failed) { failed[key] = true }
                return@withContext null
            }
            decoded.put(key, bmp)
            bmp
        }
    }

    suspend fun syncAll() {
        syncMutex.withLock {
            val targets = withContext(Dispatchers.IO) { avatarTargets() }
            synchronized(failed) { failed.clear() }
            val missing = withContext(Dispatchers.IO) { targets.filterNot { isCached(it.first) } }
            if (missing.isEmpty()) {
                _syncState.value = SyncState.DONE
                _epoch.value++
                return@withLock
            }

            _syncState.value = SyncState.RUNNING
            val networkFailures = AtomicInteger(0)
            val consecutive = AtomicInteger(0)

            coroutineScope {
                missing.map { (key, url) ->
                    async(Dispatchers.IO) {
                        if (consecutive.get() >= BAIL_AFTER_FAILURES) return@async
                        when (cacheOnly(key, url)) {
                            Outcome.OK -> {
                                consecutive.set(0)
                                _epoch.value++
                            }
                            Outcome.HTTP_ERROR -> consecutive.set(0)
                            Outcome.NETWORK_ERROR -> {
                                consecutive.incrementAndGet()
                                networkFailures.incrementAndGet()
                            }
                        }
                    }
                }.awaitAll()
            }

            val stillMissing = withContext(Dispatchers.IO) { targets.count { !isCached(it.first) } }
            _epoch.value++
            _syncState.value =
                if (networkFailures.get() > 0 && stillMissing > 0) SyncState.FAILED
                else SyncState.DONE
        }
    }

    private fun isCached(key: String): Boolean {
        val f = File(cacheDir, key)
        return f.exists() && f.length() > 0
    }

    private suspend fun cacheOnly(key: String, url: String): Outcome {
        if (decoded.contains(key) || isCached(key)) return Outcome.OK
        val file = File(cacheDir, key)
        return downloadGate.withPermit { download(url, file) }
    }

    private fun decodeFile(file: File): ImageBitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private enum class Outcome { OK, HTTP_ERROR, NETWORK_ERROR }

    private fun candidateUrls(primaryUrl: String): List<String> {
        val rawPrefix = "https://raw.githubusercontent.com/myssal/Brown-Dust-2-Asset/refs/heads/master/"
        if (!primaryUrl.startsWith(rawPrefix)) return listOf(primaryUrl)
        val relPath = primaryUrl.removePrefix(rawPrefix)
        return listOf(
            "https://fastly.jsdelivr.net/gh/myssal/Brown-Dust-2-Asset@master/$relPath",
            "https://gcore.jsdelivr.net/gh/myssal/Brown-Dust-2-Asset@master/$relPath",
            "https://cdn.jsdelivr.net/gh/myssal/Brown-Dust-2-Asset@master/$relPath",
            primaryUrl
        )
    }

    private fun download(url: String, dest: File): Outcome {
        var sawHttpError = false
        for (candidate in candidateUrls(url)) {
            when (downloadSingleUrl(candidate, dest)) {
                Outcome.OK -> return Outcome.OK
                Outcome.HTTP_ERROR -> sawHttpError = true
                Outcome.NETWORK_ERROR -> Unit
            }
        }
        return if (sawHttpError) Outcome.HTTP_ERROR else Outcome.NETWORK_ERROR
    }

    private fun downloadSingleUrl(url: String, dest: File): Outcome {
        var conn: HttpURLConnection? = null
        val part = File(dest.parentFile, "${dest.name}.${UUID.randomUUID()}.part")
        return try {
            dest.parentFile?.mkdirs()
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "BDroid_X")
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return if (code >= 500 || code == 408 || code == 429) Outcome.NETWORK_ERROR
                else Outcome.HTTP_ERROR
            }
            conn.inputStream.use { input ->
                part.outputStream().use { out -> input.copyTo(out) }
            }
            if (part.length() <= 0) {
                part.delete()
                return Outcome.HTTP_ERROR
            }
            dest.delete()
            if (part.renameTo(dest)) Outcome.OK else {
                part.delete()
                Outcome.HTTP_ERROR
            }
        } catch (e: IOException) {
            Log.w(TAG, "Avatar download network error: $url (${e.message})")
            part.delete()
            Outcome.NETWORK_ERROR
        } catch (e: Exception) {
            Log.w(TAG, "Avatar download failed: $url (${e.message})")
            part.delete()
            Outcome.HTTP_ERROR
        } finally {
            conn?.disconnect()
        }
    }
}
