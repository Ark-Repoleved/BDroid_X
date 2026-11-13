package com.example.bd2modmanager.utils

import java.io.File

object ModEntryUtils {

    private val IGNORED_EXACT_NAMES = setOf(
        ".ds_store",
        "thumbs.db",
        "desktop.ini",
        ".modfile",
        "mod.json",
        "info.json",
        "manifest.json",
        "metadata.json",
        "config.json",
        "readme.txt",
        "readme.md",
        "changelog.txt",
        "changelog.md",
        "license.txt",
        "license.md",
        "credits.txt"
    )

    private val IGNORED_DIR_SEGMENTS = setOf(
        "__macosx",
        ".git",
        ".old"
    )

    private val IGNORED_NON_MOD_EXTENSIONS = setOf(
        ".modfile",
        ".md",
        ".jpg",
        ".jpeg",
        ".webp",
        ".gif",
        ".bmp",
        ".url",
        ".lnk"
    )

    private val PREVIEW_IMAGE_BASENAMES = setOf(
        "preview.png",
        "cover.png",
        "thumb.png",
        "thumbnail.png"
    )

    fun shouldIgnoreModEntry(entryPath: String?): Boolean {
        val normalized = entryPath?.replace('\\', '/')?.trim() ?: return true
        if (normalized.isEmpty()) return true

        val segments = normalized.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return true

        if (segments.dropLast(1).any { it.lowercase() in IGNORED_DIR_SEGMENTS }) {
            return true
        }

        val name = segments.last().lowercase()
        if (name.isEmpty() || name in IGNORED_EXACT_NAMES || name in IGNORED_DIR_SEGMENTS) {
            return true
        }
        if (name.startsWith("._")) {
            return true
        }
        if (name in PREVIEW_IMAGE_BASENAMES) {
            return true
        }
        if (IGNORED_NON_MOD_EXTENSIONS.any { name.endsWith(it) }) {
            return true
        }
        if (name.startsWith("readme") && (name.endsWith(".txt") || name.endsWith(".md"))) {
            return true
        }
        return false
    }

    /**
     * Resolves [entryName] inside [destRoot] safely, returning null on Zip Slip traversal.
     */
    fun resolveZipEntrySafely(destRoot: File, entryName: String): File? {
        val canonicalRoot = destRoot.canonicalFile
        val candidate = File(canonicalRoot, entryName).canonicalFile
        val rootPrefix = canonicalRoot.path.let {
            if (it.endsWith(File.separator)) it else it + File.separator
        }
        return if (candidate.path.startsWith(rootPrefix)) candidate else null
    }
}
