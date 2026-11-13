package com.example.bd2modmanager.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Collects current-PID logcat (including Chaquopy python.stdout / python.stderr),
 * exports it to Downloads (API 29+) or app external files, and opens the share sheet.
 */
object LogExporter {

    private const val TAG = "LogExporter"
    private const val LOGCAT_TIMEOUT_MS = 10_000L

    fun collect(context: Context): File {
        val header = buildHeader(context)
        val out = File(context.cacheDir, "bdroidx_log_${System.currentTimeMillis()}.txt")
        val log = try {
            runLogcat(arrayOf("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}"))
        } catch (e: Exception) {
            Log.w(TAG, "logcat collection failed", e)
            "(logcat collection failed: ${e.message})"
        }
        out.writeText(header + "\n" + log)
        return out
    }

    fun exportAndPrepareShareUri(context: Context, content: File): Uri? {
        val fileName = "BDroidX_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var uri: Uri? = null
            return try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator)
                }
                uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                val written = context.contentResolver.openOutputStream(uri)?.use { os ->
                    content.inputStream().use { it.copyTo(os) }
                    true
                } ?: false
                if (!written) {
                    throw IOException("Cannot open output stream for $uri")
                }
                uri
            } catch (e: Exception) {
                Log.w(TAG, "Export to Downloads failed", e)
                uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                null
            }
        }

        return try {
            val exportDir = File(context.cacheDir, "logs").apply { mkdirs() }
            val exportedFile = File(exportDir, fileName)
            content.copyTo(exportedFile, overwrite = true)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exportedFile)
        } catch (e: Exception) {
            Log.w(TAG, "FileProvider export failed", e)
            null
        }
    }

    fun share(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(
                Intent.createChooser(intent, "Share Diagnostic Log")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open share sheet", e)
        }
    }

    private fun buildHeader(context: Context): String {
        val version = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            "versionName=${info.versionName} versionCode=$code"
        } catch (_: Exception) {
            "version=unknown"
        }
        return """
            ===== BDroid X Diagnostic Log =====
            $version
            device=${Build.MANUFACTURER} ${Build.MODEL}
            android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
            time=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}
            pid=${Process.myPid()}
            ===================================

        """.trimIndent()
    }

    private fun runLogcat(command: Array<String>): String {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        val buffer = StringBuilder()
        val readerThread = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { reader ->
                    val chars = CharArray(4096)
                    while (true) {
                        val n = reader.read(chars)
                        if (n < 0) break
                        synchronized(buffer) { buffer.append(chars, 0, n) }
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "bdroidx-logcat-reader"
            start()
        }
        return try {
            val finished = process.waitFor(LOGCAT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            readerThread.join(1_000L)
            val output = synchronized(buffer) { buffer.toString() }
            if (!finished) "$output\n(logcat timed out and was truncated)" else output
        } finally {
            process.destroy()
        }
    }
}
