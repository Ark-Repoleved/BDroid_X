package com.example.bd2modmanager.data.repository

import android.content.Context
import com.chaquo.python.Python
import com.example.bd2modmanager.data.model.CharacterInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

class CharacterRepository(private val context: Context) {

    companion object {
        private const val CHARACTERS_JSON_FILENAME = "characters.json"
        private const val MOD_CACHE_FILENAME = "mod_cache.json"
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
                        // When a new characters.json is generated, the mod cache becomes invalid.
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
                    // Try parsing new format first
                    val rootObject = org.json.JSONObject(jsonString)
                    val jsonArray = rootObject.getJSONArray("characters")
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val fileId = obj.getString("file_id").lowercase()
                        val charInfo = CharacterInfo(obj.getString("character"), obj.getString("costume"), obj.getString("type"), obj.getString("hashed_name"))
                        lut.getOrPut(fileId) { mutableListOf() }.add(charInfo)
                    }
                } catch (e: org.json.JSONException) {
                    // Fallback to old format
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

    fun findBestMatch(fileId: String?, fileNames: List<String>): CharacterInfo? {
        if (fileId == null) return null

        val candidates = characterLut[fileId] ?: return null
        if (candidates.size == 1) return candidates.first()
        if (candidates.isEmpty()) return null

        val hasCutsceneKeyword = fileNames.any { it.contains("cutscene", ignoreCase = true) }
        if (hasCutsceneKeyword) {
            candidates.find { it.type == "cutscene" }?.let { return it }
        }

        val validHashCandidates = candidates.filter { !it.hashedName.isNullOrBlank() }
        if (validHashCandidates.size == 1) {
            return validHashCandidates.first()
        }

        candidates.find { it.type == "idle" }?.let { return it }

        return candidates.first()
    }

    fun extractFileId(entryName: String): String? {
        // First, get the base filename without extension
        val baseName = entryName.substringBeforeLast(".").lowercase()
        
        // 1. Check if the entire basename matches known patterns (for known character assets)
        // This ensures we don't partially match e.g., "char067004" from "char067004_back2"
        val knownPattern = "^(char\\d{6}|illust_dating\\d+|illust_special\\d+|illust_talk\\d+|npc\\d+|specialillust\\w+|storypack\\w+|rhythmhitanim)$"
            .toRegex(RegexOption.IGNORE_CASE)
        
        if (knownPattern.matches(baseName)) {
            return baseName
        }
        
        // 2. Fallback: use the full basename as file_id (for misc assets)
        // Remove _digits suffix only for texture variants (e.g., texture_2 -> texture)
        val withoutTextureSuffix = baseName.replace(Regex("_\\d+$"), "")
        return if (withoutTextureSuffix.isNotEmpty() && withoutTextureSuffix.length >= 2) {
            withoutTextureSuffix
        } else {
            null
        }
    }
}
