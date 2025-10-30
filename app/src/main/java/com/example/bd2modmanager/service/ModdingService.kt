package com.example.bd2modmanager.service

import com.chaquo.python.PyObject
import com.chaquo.python.Python

object ModdingService {

    fun downloadBundle(hashedName: String, quality: String, outputDir: String, cacheKey: String, onProgress: (String) -> Unit): Triple<Boolean, String, String?> {
        return try {
            val py = Python.getInstance()
            val mainScript = py.getModule("main_script")

            val result = mainScript.callAttr(
                "download_bundle",
                hashedName,
                quality,
                outputDir,
                cacheKey,
                PyObject.fromJava(onProgress)
            ).asList()

            val success = result[0].toBoolean()
            val messageOrPath = result[1].toString()
            val intermediateHash = result.getOrNull(2)?.toString()
            Triple(success, messageOrPath, intermediateHash)
        } catch (e: Exception) {
            e.printStackTrace()
            Triple(false, e.message ?: "An unknown error occurred in Kotlin during download.", null)
        }
    }

    fun repackBundle(originalBundlePath: String, moddedAssetsFolder: String, outputPath: String, useAstc: Boolean, onProgress: (String) -> Unit): Pair<Boolean, String> {
        return try {
            val py = Python.getInstance()
            val mainScript = py.getModule("main_script")

            // Pass the Kotlin lambda as a progress_callback to the Python function.
            val result = mainScript.callAttr(
                "main",
                originalBundlePath,
                moddedAssetsFolder,
                outputPath,
                useAstc,
                PyObject.fromJava(onProgress)
            ).asList()

            val success = result[0].toBoolean()
            val message = result[1].toString()
            Pair(success, message)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(false, e.message ?: "An unknown error occurred in Kotlin.")
        }
    }

    fun unpackBundle(bundlePath: String, outputDir: String, onProgress: (String) -> Unit): Pair<Boolean, String> {
        return try {
            val py = Python.getInstance()
            val mainScript = py.getModule("main_script")

            val result = mainScript.callAttr(
                "unpack_bundle",
                bundlePath,
                outputDir,
                PyObject.fromJava(onProgress)
            ).asList()

            val success = result[0].toBoolean()
            val message = result[1].toString()
            Pair(success, message)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(false, e.message ?: "An unknown error occurred in Kotlin during unpack.")
        }
    }

    fun mergeSpineAssets(modPath: String, onProgress: (String) -> Unit): Pair<Boolean, String> {
        return try {
            val py = Python.getInstance()
            val mainScript = py.getModule("main_script")

            val result = mainScript.callAttr(
                "merge_spine_assets",
                modPath,
                PyObject.fromJava(onProgress)
            ).asList()

            val success = result[0].toBoolean()
            val message = result[1].toString()
            Pair(success, message)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(false, e.message ?: "An unknown error occurred in Kotlin during spine merge.")
        }
    }
}
