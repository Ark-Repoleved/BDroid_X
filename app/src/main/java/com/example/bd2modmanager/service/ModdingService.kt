package com.example.bd2modmanager.service

import com.chaquo.python.PyObject
import com.chaquo.python.Python

object ModdingService {

    fun repackBundle(originalBundlePath: String, moddedAssetsFolder: String, outputPath: String, onProgress: (String) -> Unit): Pair<Boolean, String> {
        return try {
            val py = Python.getInstance()
            val mainScript = py.getModule("main_script")

            // Pass the Kotlin lambda as a progress_callback to the Python function.
            val result = mainScript.callAttr(
                "main",
                originalBundlePath,
                moddedAssetsFolder,
                outputPath,
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
}
