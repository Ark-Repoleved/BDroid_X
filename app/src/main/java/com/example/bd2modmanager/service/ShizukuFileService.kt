package com.example.bd2modmanager.service

import android.content.Context
import android.util.Log
import com.example.bd2modmanager.IFileService
import java.io.File

class ShizukuFileService(context: Context? = null) : IFileService.Stub() {

    override fun copyDirectory(sourceDirPath: String, destDirPath: String): Boolean {
        return try {
            val sourceDir = File(sourceDirPath)
            val destDir = File(destDirPath)
            copyDirRecursive(sourceDir, destDir)
            Log.d("ShizukuFileService", "Successfully copied directory $sourceDirPath to $destDirPath")
            true
        } catch (e: Exception) {
            Log.e("ShizukuFileService", "Error copying directory $sourceDirPath to $destDirPath", e)
            false
        }
    }

    private fun copyDirRecursive(source: File, dest: File) {
        if (!dest.exists()) dest.mkdirs()
        source.listFiles()?.forEach { file ->
            val target = File(dest, file.name)
            if (file.isDirectory) {
                copyDirRecursive(file, target)
            } else {
                try {
                    file.inputStream().use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ShizukuFileService", "Failed to copy individual file: ${file.absolutePath} to ${target.absolutePath}", e)
                    throw e
                }
            }
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
