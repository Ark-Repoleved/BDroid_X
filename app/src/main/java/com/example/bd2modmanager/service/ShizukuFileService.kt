package com.example.bd2modmanager.service

import android.content.Context
import android.system.Os
import android.util.Log
import com.example.bd2modmanager.IFileService
import java.io.File
import java.io.IOException

class ShizukuFileService(context: Context? = null) : IFileService.Stub() {

    override fun copyDirectory(sourceDirPath: String, destDirPath: String): Boolean {
        return try {
            val sourceDir = File(sourceDirPath)
            if (!sourceDir.isDirectory) {
                Log.e(TAG, "Source directory does not exist: $sourceDirPath")
                return false
            }
            val destDir = File(destDirPath)
            copyDirRecursive(sourceDir, destDir)
            Log.d(TAG, "Successfully copied directory $sourceDirPath to $destDirPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error copying directory $sourceDirPath to $destDirPath", e)
            false
        }
    }

    private fun copyDirRecursive(source: File, dest: File) {
        if (!dest.exists()) dest.mkdirs()
        val children = source.listFiles()
            ?: throw IOException("Cannot list source directory: ${source.absolutePath}")
        children.forEach { file ->
            val target = File(dest, file.name)
            if (file.isDirectory) {
                copyDirRecursive(file, target)
            } else {
                try {
                    copyStreamAtomic(file, target, allowEmptySource = true)
                    restoreParentOwner(target)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy individual file: ${file.absolutePath} to ${target.absolutePath}", e)
                    throw e
                }
            }
        }
    }

    private fun copyStreamAtomic(source: File, dest: File, allowEmptySource: Boolean) {
        val expectedSize = source.length()
        AtomicFileWriter().write(
            target = dest,
            verifyBeforeCommit = { staged ->
                val written = staged.length()
                if (written != expectedSize) {
                    throw IOException(
                        "Staged file size mismatch (source=$expectedSize, staged=$written): ${source.path}"
                    )
                }
                if (written <= 0L && !allowEmptySource) {
                    throw IOException("Refusing empty file commit: ${source.path}")
                }
            }
        ) { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        }
    }

    private fun restoreParentOwner(dest: File) {
        try {
            val parent = dest.parentFile ?: return
            val st = Os.stat(parent.absolutePath)
            Os.chown(dest.absolutePath, st.st_uid, st.st_gid)
        } catch (e: Exception) {
            Log.w(TAG, "chown dest failed: ${dest.absolutePath}", e)
        }
    }

    override fun destroy() {
        System.exit(0)
    }

    private companion object {
        const val TAG = "ShizukuFileService"
    }
}
