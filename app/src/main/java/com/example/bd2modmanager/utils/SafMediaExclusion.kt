package com.example.bd2modmanager.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile

/**
 * SAF tree-URI `.nomedia` media exclusion helper (ported from BD2ToolsBox).
 */
object SafMediaExclusion {

    private const val TAG = "SafMediaExclusion"
    private const val MARKER_MIME = "application/octet-stream"

    fun ensureMarker(
        context: Context,
        treeUri: Uri,
        refuseMixedUseRoot: Boolean = true
    ): MediaExclusionResult {
        val dir = try {
            DocumentFile.fromTreeUri(context, treeUri)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open tree URI: $treeUri", e)
            null
        } ?: return MediaExclusionResult.Failed("Cannot open selected directory")

        if (!dir.isDirectory) return MediaExclusionResult.Failed("Selected path is not a directory")
        if (!dir.canWrite()) {
            return MediaExclusionResult.Failed("Selected directory is read-only; cannot write .nomedia")
        }
        if (refuseMixedUseRoot && MediaExclusionManager.isMixedUseRoot(dir.name)) {
            return MediaExclusionResult.Rejected(
                "\"${dir.name}\" is a shared root folder; .nomedia was skipped to avoid hiding personal media."
            )
        }

        dir.findFile(MediaExclusionManager.MARKER_NAME)?.let { existing ->
            return if (existing.isFile) MediaExclusionResult.AlreadyPresent
            else MediaExclusionResult.Failed("Non-file entry exists at ${existing.uri}")
        }

        val created = try {
            dir.createFile(MARKER_MIME, MediaExclusionManager.MARKER_NAME)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create .nomedia in $treeUri", e)
            null
        } ?: return MediaExclusionResult.Failed("Provider refused creating .nomedia")

        return if (MediaExclusionManager.verifyProviderName(created.name)) {
            MediaExclusionResult.Created
        } else {
            MediaExclusionResult.Failed(
                "Provider renamed marker to \"${created.name}\"; media exclusion ineffective"
            )
        }
    }

    fun message(result: MediaExclusionResult): String? = when (result) {
        MediaExclusionResult.Created, MediaExclusionResult.AlreadyPresent -> null
        is MediaExclusionResult.Rejected -> result.reason
        is MediaExclusionResult.Failed -> "Could not create .nomedia: ${result.reason}"
    }
}
