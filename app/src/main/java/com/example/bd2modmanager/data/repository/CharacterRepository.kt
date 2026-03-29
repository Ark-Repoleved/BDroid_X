package com.example.bd2modmanager.data.repository

import android.content.Context
import com.chaquo.python.Python
import com.example.bd2modmanager.data.model.CharacterInfo
import com.example.bd2modmanager.data.model.FileCandidate
import com.example.bd2modmanager.data.model.FileCandidateKind
import com.example.bd2modmanager.data.model.FileCandidateTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

class CharacterRepository(private val context: Context) {

    companion object {
        private const val CHARACTERS_JSON_FILENAME = "characters.json"
        private const val MOD_CACHE_FILENAME = "mod_cache.json"

        private val NOISE_EXTENSIONS = setOf(
            "gif", "mp4", "webm", "txt", "md", "json.bak", "db", "nomedia", "ini", "log"
        )

        private val NOISE_NAME_PARTS = listOf(
            "readme", "preview", "thumb", "cover", "icon", "banner", "sample", "example", "backup", ".old"
        )

        private val KNOWN_PATTERN =
            "^(cutscene_char\\d{6}(?:_[a-z0-9]+)?|char\\d{6}(?:_[a-z0-9]+)?|illust_dating\\d+|illust_special\\d+|illust_talk\\d+|npc\\d+|specialillust[\\w-]+|storypack\\w+|rhythmhitanim|bg_idcard_bg_cutscene_\\d+(?:_\\d+)?)$"
                .toRegex(RegexOption.IGNORE_CASE)

        private val SACTX_PATTERN =
            "^sactx-\\d+-[\\dx]+-[^-]+-(.+)-[a-f0-9]+$".toRegex(RegexOption.IGNORE_CASE)
    }

    private var characterLut: Map<String, List<CharacterInfo>> = emptyMap()

    suspend fun updateCharacterData(): Boolean {
        return withContext(Dispatchers.IO) {
            var success = false
            try {
                if (!Python.isStarted()) {
                    Python.start(com.chaquo.python.android.AndroidPlatform(context))
                }
                val py = Python.getInstance()
                val mainScript = py.getModule("main_script")
                val result = mainScript.callAttr("update_character_data", context.filesDir.absolutePath).asList()
                val status = result[0].toString() // SUCCESS, SKIPPED, FAILED
                val message = result[1].toString()

                when (status) {
                    "SUCCESS" -> {
                        println("Successfully ran scraper and saved characters.json: $message")
                        val cacheFile = File(context.cacheDir, MOD_CACHE_FILENAME)
                        if (cacheFile.exists()) {
                            cacheFile.delete()
                            println("Deleted mod cache to force re-scan.")
                        }
                        success = true
                    }
                    "SKIPPED" -> {
                        println("Scraper skipped: $message")
                        success = true
                    }
                    "FAILED" -> {
                        println("Scraper script failed: $message. Will use local version if available.")
                        success = false
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                println("Failed to execute scraper python script, will use local version. Error: ${e.message}")
                success = false
            }
            characterLut = parseCharacterJson()
            success
        }
    }

    private suspend fun parseCharacterJson(): Map<String, List<CharacterInfo>> {
        return withContext(Dispatchers.IO) {
            val lut = mutableMapOf<String, MutableList<CharacterInfo>>()
            val internalFile = File(context.filesDir, CHARACTERS_JSON_FILENAME)
            if (!internalFile.exists()) return@withContext emptyMap()
            val jsonString = try { internalFile.readText() } catch (e: Exception) { e.printStackTrace(); "" }
            if (jsonString.isNotEmpty()) {
                try {
                    val rootObject = org.json.JSONObject(jsonString)
                    val jsonArray = rootObject.getJSONArray("characters")
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val fileId = obj.getString("file_id").lowercase()
                        val charInfo = CharacterInfo(obj.getString("character"), obj.getString("costume"), obj.getString("type"), obj.getString("hashed_name"))
                        lut.getOrPut(fileId) { mutableListOf() }.add(charInfo)
                    }
                } catch (e: org.json.JSONException) {
                    try {
                        val jsonArray = JSONArray(jsonString)
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val fileId = obj.getString("file_id").lowercase()
                            val charInfo = CharacterInfo(obj.getString("character"), obj.getString("costume"), obj.getString("type"), obj.getString("hashed_name"))
                            lut.getOrPut(fileId) { mutableListOf() }.add(charInfo)
                        }
                    } catch (e2: Exception) {
                        e2.printStackTrace()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            lut
        }
    }

    fun findBestMatch(candidates: List<FileCandidate>, fileNames: List<String>): CharacterInfo? {
        if (candidates.isEmpty()) return null

        val prioritizedCandidates = prioritizeCandidates(candidates)
        val exactPreferredFileIds = findExactPreferredFileIds(prioritizedCandidates)
        val scoreByFileId = linkedMapOf<String, Int>()
        for (candidate in prioritizedCandidates) {
            scoreByFileId[candidate.fileId] = (scoreByFileId[candidate.fileId] ?: 0) + candidate.confidence
        }

        val rankedFileIds = scoreByFileId.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { exactPreferredFileIds.contains(it.key) }
                    .thenByDescending { it.value }
            )
            .map { it.key }
        val hasCutsceneKeyword = fileNames.any { it.contains("cutscene", ignoreCase = true) }

        for (fileId in rankedFileIds) {
            val matches = characterLut[fileId] ?: continue
            if (matches.isEmpty()) continue
            if (matches.size == 1) return matches.first()

            if (hasCutsceneKeyword) {
                matches.find { it.type == "cutscene" }?.let { return it }
            }

            val validHashCandidates = matches.filter { !it.hashedName.isNullOrBlank() }
            if (validHashCandidates.size == 1) {
                return validHashCandidates.first()
            }

            matches.find { it.type == "idle" }?.let { return it }
            return matches.first()
        }

        return null
    }

    fun extractFileCandidate(entryName: String): FileCandidate? {
        val normalizedEntryName = entryName.replace('\\', '/').trim()
        if (normalizedEntryName.isBlank()) return null

        val fileName = normalizedEntryName.substringAfterLast('/')
        val loweredFileName = fileName.lowercase()

        if (shouldIgnoreFile(loweredFileName)) return null

        val baseName = extractBaseName(loweredFileName) ?: return null
        if (baseName.length < 2) return null

        val extension = loweredFileName.substringAfterLast('.', "")
        val normalizedBaseName = normalizeMatchingBaseName(baseName)
        val knownMatch = KNOWN_PATTERN.matchEntire(normalizedBaseName)
        if (knownMatch != null) {
            val matched = knownMatch.groupValues[1].lowercase()
            val normalizedFileId = if (matched.startsWith("cutscene_")) {
                matched.substringAfter("_")
            } else {
                matched
            }

            val kind = when {
                loweredFileName.endsWith(".skel.bytes") || loweredFileName.endsWith(".skel") -> FileCandidateKind.SPINE_SKEL
                loweredFileName.endsWith(".atlas.txt") || loweredFileName.endsWith(".atlas") -> FileCandidateKind.SPINE_ATLAS
                extension == "json" -> FileCandidateKind.SPINE_JSON
                else -> FileCandidateKind.GENERIC
            }

            val confidence = when (kind) {
                FileCandidateKind.SPINE_SKEL -> 120
                FileCandidateKind.SPINE_ATLAS -> 100
                FileCandidateKind.SPINE_JSON -> 95
                else -> when {
                    baseName != normalizedBaseName -> 72
                    else -> 70
                }
            }

            return FileCandidate(
                fileId = normalizedFileId,
                sourceName = entryName,
                kind = kind,
                confidence = confidence,
                tier = when {
                    normalizedBaseName == baseName -> FileCandidateTier.EXACT
                    else -> FileCandidateTier.NORMALIZED
                }
            )
        }

        val sactxMatch = SACTX_PATTERN.matchEntire(baseName)
        if (sactxMatch != null) {
            val fileId = sactxMatch.groupValues[1].lowercase()
            return FileCandidate(fileId, entryName, FileCandidateKind.SACTX, 80)
        }

        if (isTextureExtension(extension)) {
            return FileCandidate(baseName, entryName, FileCandidateKind.TEXTURE, 35)
        }

        val genericConfidence = when {
            loweredFileName.endsWith(".skel.bytes") || loweredFileName.endsWith(".skel") -> 75
            loweredFileName.endsWith(".atlas.txt") || loweredFileName.endsWith(".atlas") -> 70
            extension == "json" -> 65
            else -> 45
        }

        return FileCandidate(baseName, entryName, FileCandidateKind.GENERIC, genericConfidence, FileCandidateTier.FALLBACK)
    }

    private fun shouldIgnoreFile(loweredFileName: String): Boolean {
        val extension = loweredFileName.substringAfterLast('.', "")
        if (extension in NOISE_EXTENSIONS) return true
        return NOISE_NAME_PARTS.any { loweredFileName.contains(it) }
    }

    private fun extractBaseName(loweredFileName: String): String? {
        var baseName = loweredFileName
        for (ext in listOf(".skel.bytes", ".atlas.txt")) {
            if (baseName.endsWith(ext)) {
                return baseName.dropLast(ext.length)
            }
        }

        if (baseName.contains('.')) {
            baseName = baseName.substringBeforeLast('.')
        }

        return baseName.ifBlank { null }
    }

    private fun isTextureExtension(extension: String): Boolean {
        return extension in setOf("png", "jpg", "jpeg", "webp")
    }

    private fun prioritizeCandidates(candidates: List<FileCandidate>): List<FileCandidate> {
        val tiersInOrder = listOf(
            FileCandidateTier.EXACT,
            FileCandidateTier.NORMALIZED,
            FileCandidateTier.FALLBACK
        )

        for (tier in tiersInOrder) {
            val tierCandidates = candidates.filter { it.tier == tier }
            if (tierCandidates.isEmpty()) continue

            return tierCandidates
                .groupBy { Pair(it.fileId, it.kind) }
                .map { (_, grouped) -> grouped.maxByOrNull { it.confidence }!! }
                .sortedByDescending { it.confidence }
        }

        return candidates.sortedByDescending { it.confidence }
    }

    private fun findExactPreferredFileIds(candidates: List<FileCandidate>): Set<String> {
        val exactGenericCandidates = candidates.filter {
            it.tier == FileCandidateTier.EXACT &&
                (it.kind == FileCandidateKind.GENERIC || it.kind == FileCandidateKind.TEXTURE)
        }

        val suffixedExactCandidates = exactGenericCandidates.filter { it.fileId.hasVariantSuffix() }
        if (suffixedExactCandidates.isEmpty()) return emptySet()

        return suffixedExactCandidates.mapTo(linkedSetOf()) { it.fileId }
    }

    private fun normalizeMatchingBaseName(baseName: String): String {
        val normalized = baseName.lowercase()

        if (normalized.startsWith("cutscene_char") || normalized.startsWith("char")) {
            return normalized
        }

        return normalized
    }

    private fun String.hasVariantSuffix(): Boolean {
        return when {
            startsWith("bg_idcard_bg_cutscene_") -> Regex("^bg_idcard_bg_cutscene_\\d+_.+$", RegexOption.IGNORE_CASE).matches(this)
            startsWith("cutscene_char") -> Regex("^cutscene_char\\d+_.+$", RegexOption.IGNORE_CASE).matches(this)
            startsWith("char") -> Regex("^char\\d+_.+$", RegexOption.IGNORE_CASE).matches(this)
            startsWith("specialillust") -> Regex("^specialillust.+(?:_|-).+$", RegexOption.IGNORE_CASE).matches(this)
            else -> Regex("(?:_|-)[a-z0-9]+$", RegexOption.IGNORE_CASE).containsMatchIn(this)
        }
    }
}
