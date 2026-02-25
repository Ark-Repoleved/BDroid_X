package com.example.bd2modmanager.service

import com.example.bd2modmanager.IFileService
import java.io.File

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
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun copyDirectory(sourceDirPath: String, destDirPath: String): Boolean {
        return try {
            val sourceDir = File(sourceDirPath)
            val destDir = File(destDirPath)
            copyDirRecursive(sourceDir, destDir)
            true
        } catch (e: Exception) {
            e.printStackTrace()
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
                file.inputStream().use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
