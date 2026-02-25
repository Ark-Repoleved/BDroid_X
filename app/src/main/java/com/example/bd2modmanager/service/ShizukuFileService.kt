package com.example.bd2modmanager.service

import com.example.bd2modmanager.IFileService
import java.io.File
import android.util.Log

class ShizukuFileService : IFileService.Stub() {

    override fun copyFile(sourcePath: String, destPath: String): Boolean {
        return try {
            val source = File(sourcePath)
            val dest = File(destPath)
            dest.parentFile?.mkdirs()
            source.inputStream().use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.d("ShizukuFileService", "Successfully copied $sourcePath to $destPath")
            true
        } catch (e: Exception) {
            Log.e("ShizukuFileService", "Error copying $sourcePath to $destPath", e)
            false
        }
    }

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
                    throw e // Re-throw to be caught by the outer catch block
                }
            }
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
