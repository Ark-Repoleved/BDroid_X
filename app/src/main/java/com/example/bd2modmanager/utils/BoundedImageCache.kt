package com.example.bd2modmanager.utils

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Byte-budgeted LRU cache for decoded Compose [ImageBitmap]s (ported from BD2ToolsBox).
 */
class BoundedImageCache(private val maxBytes: Long) {

    private val lock = Any()
    private val entries = LinkedHashMap<String, ImageBitmap>(16, 0.75f, true)
    private var bytes = 0L

    fun get(key: String): ImageBitmap? = synchronized(lock) {
        entries[key]
    }

    fun put(key: String, value: ImageBitmap) {
        if (maxBytes <= 0) return
        val size = sizeOf(value)
        if (size > maxBytes) {
            synchronized(lock) {
                entries.remove(key)?.let { bytes -= sizeOf(it) }
            }
            return
        }
        synchronized(lock) {
            entries.remove(key)?.let { bytes -= sizeOf(it) }
            entries[key] = value
            bytes += size
            trimLocked()
        }
    }

    fun contains(key: String): Boolean = synchronized(lock) { entries.containsKey(key) }

    fun clear() = synchronized(lock) {
        entries.clear()
        bytes = 0L
    }

    private fun trimLocked() {
        val it = entries.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val e = it.next()
            bytes -= sizeOf(e.value)
            it.remove()
        }
    }

    companion object {
        fun sizeOf(bitmap: ImageBitmap): Long =
            bitmap.width.toLong() * bitmap.height.toLong() * 4L
    }
}
