package com.example.bd2modmanager.utils

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

data class SkeletonInspection(
    val version: String?,
    val isSpine42: Boolean,
    val isJson: Boolean
)

object SpineVersionUtils {

    private const val HEADER_PEEK_BYTES = 256

    fun inspectSkeleton(file: File): SkeletonInspection {
        if (!file.exists() || !file.isFile) {
            return SkeletonInspection(version = null, isSpine42 = false, isJson = false)
        }

        val header = ByteArray(HEADER_PEEK_BYTES)
        val bytesRead = try {
            FileInputStream(file).use { input ->
                var total = 0
                while (total < header.size) {
                    val read = input.read(header, total, header.size - total)
                    if (read <= 0) break
                    total += read
                }
                total
            }
        } catch (_: Exception) {
            return SkeletonInspection(version = null, isSpine42 = false, isJson = false)
        }

        if (bytesRead <= 0) {
            return SkeletonInspection(version = null, isSpine42 = false, isJson = false)
        }

        if (isLikelyJson(header, bytesRead)) {
            val version = readJsonSpineVersion(file)
            return SkeletonInspection(
                version = version,
                isSpine42 = isSpine42Version(version),
                isJson = true
            )
        }

        val version = readBinarySpine4xVersion(header, bytesRead)
        return SkeletonInspection(
            version = version,
            isSpine42 = isSpine42Version(version),
            isJson = false
        )
    }

    fun isSpine42Version(version: String?): Boolean {
        val parts = version?.trim()?.split('.') ?: return false
        if (parts.size < 2) return false
        val major = parts[0].toIntOrNull() ?: return false
        val minor = parts[1].takeWhile { it.isDigit() }.toIntOrNull() ?: return false
        return major > 4 || (major == 4 && minor >= 2)
    }

    internal fun isLikelyJson(bytes: ByteArray, length: Int = bytes.size): Boolean {
        var i = 0
        if (length >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            i = 3
        }
        while (i < length) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0x20 || b == 0x09 || b == 0x0D || b == 0x0A) {
                i++
            } else {
                return b == 0x7B // '{'
            }
        }
        return false
    }

    internal fun readBinarySpine4xVersion(header: ByteArray, length: Int = header.size): String? {
        if (length <= 8) return null
        var pos = 8
        var count = 0
        var shift = 0
        var validVarint = false

        for (step in 0 until 5) {
            if (pos >= length) break
            val b = header[pos++].toInt() and 0xFF
            count = count or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) {
                validVarint = true
                break
            }
            shift += 7
        }

        if (!validVarint || count < 2) return null
        val strLen = count - 1
        if (pos + strLen > length) return null

        val version = String(header, pos, strLen, Charsets.UTF_8)
        if (version.isEmpty() || !version.all { it.isDigit() || it == '.' }) {
            return null
        }
        val parts = version.split('.')
        if (parts.size < 2 || parts[0].toIntOrNull() == null || parts[1].toIntOrNull() == null) {
            return null
        }
        return version
    }

    private fun readJsonSpineVersion(file: File): String? {
        return try {
            FileInputStream(file).use { fis ->
                val reader = BufferedReader(InputStreamReader(fis, Charsets.UTF_8))
                reader.mark(1)
                if (reader.read() != 0xFEFF) {
                    reader.reset()
                }
                JsonReader(reader).use { json ->
                    json.isLenient = true
                    if (json.peek() != JsonToken.BEGIN_OBJECT) return null
                    json.beginObject()
                    while (json.hasNext()) {
                        if (json.nextName() == "skeleton") {
                            if (json.peek() != JsonToken.BEGIN_OBJECT) return null
                            json.beginObject()
                            while (json.hasNext()) {
                                if (json.nextName() == "spine") {
                                    return if (json.peek() == JsonToken.STRING) {
                                        json.nextString()
                                    } else {
                                        null
                                    }
                                } else {
                                    json.skipValue()
                                }
                            }
                            json.endObject()
                            return null
                        } else {
                            json.skipValue()
                        }
                    }
                    json.endObject()
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
