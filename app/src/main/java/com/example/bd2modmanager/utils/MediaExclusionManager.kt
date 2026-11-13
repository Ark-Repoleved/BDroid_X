package com.example.bd2modmanager.utils

import java.io.File
import java.io.IOException

sealed interface MediaExclusionResult {
    object Created : MediaExclusionResult
    object AlreadyPresent : MediaExclusionResult
    data class Rejected(val reason: String) : MediaExclusionResult
    data class Failed(val reason: String) : MediaExclusionResult
}

/**
 * Directory-level media scanner exclusion via `.nomedia` (ported from BD2ToolsBox).
 */
object MediaExclusionManager {

    const val MARKER_NAME = ".nomedia"

    private val MIXED_USE_ROOTS = setOf(
        "dcim", "pictures", "movies", "download", "downloads",
        "documents", "sdcard", "storage", "emulated", "0"
    )

    fun isMarkerName(name: String): Boolean = name == MARKER_NAME

    fun isMixedUseRoot(dirName: String?): Boolean =
        dirName?.trim()?.lowercase() in MIXED_USE_ROOTS

    fun ensureMarker(dir: File, refuseMixedUseRoot: Boolean = true): MediaExclusionResult {
        if (!dir.isDirectory) {
            return MediaExclusionResult.Failed("Directory does not exist: ${dir.path}")
        }
        if (refuseMixedUseRoot && isMixedUseRoot(dir.name)) {
            return MediaExclusionResult.Rejected(
                "\"${dir.name}\" is a shared root folder; .nomedia was not created automatically."
            )
        }
        val marker = File(dir, MARKER_NAME)
        if (marker.exists()) {
            return if (marker.isFile) MediaExclusionResult.AlreadyPresent
            else MediaExclusionResult.Failed("Non-file entry already exists at ${marker.path}")
        }
        return try {
            if (marker.createNewFile()) MediaExclusionResult.Created
            else if (marker.isFile) MediaExclusionResult.AlreadyPresent
            else MediaExclusionResult.Failed("Could not create ${marker.path}")
        } catch (e: IOException) {
            MediaExclusionResult.Failed("Failed to create .nomedia: ${e.message.orEmpty()}")
        } catch (e: SecurityException) {
            MediaExclusionResult.Failed("Missing write permission: ${e.message.orEmpty()}")
        }
    }

    fun verifyProviderName(actualName: String?): Boolean = actualName == MARKER_NAME
}
