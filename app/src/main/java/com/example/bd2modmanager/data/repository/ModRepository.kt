package com.example.bd2modmanager.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.chaquo.python.Python
import com.example.bd2modmanager.data.model.MatchStrategy
import com.example.bd2modmanager.data.model.ModCacheInfo
import com.example.bd2modmanager.data.model.ModDetails
import com.example.bd2modmanager.data.model.ModInfo
import com.example.bd2modmanager.data.model.ResolutionState
import com.example.bd2modmanager.data.model.ResolvedTarget
import com.example.bd2modmanager.service.ModdingService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

class ModRepository(
    private val context: Context,
    private val characterRepository: CharacterRepository
) {

    companion object {
        private const val MOD_CACHE_FILENAME = "mod_cache.json"
    }

    private val gson = Gson()

    private data class ScannedModCandidate(
        val uriString: String,
        val lastModified: Long,
        val name: String,
        val uri: Uri,
        val isDirectory: Boolean,
        val modDetails: ModDetails
    )

    suspend fun scanMods(dirUri: Uri): List<ModInfo> {
        return withContext(Dispatchers.IO) {
            val existingCache = loadModCache()
            val newCache = mutableMapOf<String, ModCacheInfo>()
            val tempModsList = mutableListOf<ModInfo>()
            val candidates = mutableListOf<ScannedModCandidate>()
            val files = DocumentFile.fromTreeUri(context, dirUri)?.listFiles() ?: emptyArray()

            files.filter { it.isDirectory || it.name?.endsWith(".zip", ignoreCase = true) == true }
                .forEach { file ->
                    val uriString = file.uri.toString()
                    val lastModified = file.lastModified()
                    val cachedInfo = existingCache[uriString]

                    if (cachedInfo != null && cachedInfo.lastModified == lastModified) {
                        newCache[uriString] = cachedInfo
                        tempModsList.add(
                            ModInfo(
                                name = cachedInfo.name,
                                character = cachedInfo.character,
                                costume = cachedInfo.costume,
                                type = cachedInfo.type,
                                isEnabled = false,
                                uri = file.uri,
                                targetHashedName = cachedInfo.targetHashedName,
                                isDirectory = cachedInfo.isDirectory,
                                resolutionState = cachedInfo.resolutionState,
                                targetHash = cachedInfo.targetHash,
                                resolvedFamilyKey = cachedInfo.resolvedFamilyKey,
                                unresolvedFiles = cachedInfo.unresolvedFiles,
                                errorReason = cachedInfo.errorReason
                            )
                        )
                    } else {
                        val modName = file.name?.removeSuffix(".zip") ?: ""
                        val isDirectory = file.isDirectory
                        val modDetails = if (isDirectory) {
                            extractModDetailsFromDirectory(file.uri)
                        } else {
                            extractModDetailsFromUri(file.uri)
                        }
                        candidates.add(
                            ScannedModCandidate(
                                uriString = uriString,
                                lastModified = lastModified,
                                name = modName,
                                uri = file.uri,
                                isDirectory = isDirectory,
                                modDetails = modDetails
                            )
                        )
                    }
                }

            if (candidates.isNotEmpty()) {
                if (!Python.isStarted()) {
                    Python.start(com.chaquo.python.android.AndroidPlatform(context))
                }

                val batchPayload = JSONArray().apply {
                    candidates.forEachIndexed { index, candidate ->
                        put(JSONObject().apply {
                            put("id", index)
                            put("fileNames", JSONArray(candidate.modDetails.fileNames))
                        })
                    }
                }

                val (batchSuccess, batchResults) = ModdingService.resolveModBatch(
                    batchPayload.toString(),
                    context.cacheDir.absolutePath,
                    "HD"
                ) { }

                val resultsById = mutableMapOf<Int, JSONObject>()
                if (batchSuccess && batchResults != null) {
                    for (i in 0 until batchResults.length()) {
                        val item = batchResults.optJSONObject(i) ?: continue
                        val id = item.optInt("id", -1)
                        val result = item.optJSONObject("result") ?: continue
                        if (id >= 0) resultsById[id] = result
                    }
                }

                candidates.forEachIndexed { index, candidate ->
                    val resolvePayload = resultsById[index] ?: buildResolverFallback(candidate.modDetails.fileNames)
                    val resolutionState = parseResolutionState(resolvePayload)
                    val targetHash = resolvePayload.optString("targetHash").ifBlank { null }
                    val resolvedFamilyKey = resolvePayload.optString("resolvedFamilyKey").ifBlank { null }
                    val unresolvedFiles = jsonArrayToStringList(resolvePayload.optJSONArray("unresolvedFiles"))
                    val errorReason = resolvePayload.optString("errorReason").ifBlank { null }
                    val resolvedTargets = parseResolvedTargets(resolvePayload.optJSONArray("resolvedTargets"))
                    val bestMatch = characterRepository.findBestMatch(candidate.modDetails.fileId, candidate.modDetails.fileNames)

                    val displayCharacter: String
                    val displayCostume: String
                    val displayType: String
                    when {
                        resolutionState == ResolutionState.INVALID -> {
                            displayCharacter = "Invalid Mod"
                            displayCostume = "Split Required"
                            displayType = "invalid"
                        }
                        resolutionState == ResolutionState.UNKNOWN -> {
                            displayCharacter = "Unknown"
                            displayCostume = "Unknown"
                            displayType = "unknown"
                        }
                        bestMatch != null -> {
                            displayCharacter = bestMatch.character
                            displayCostume = bestMatch.costume
                            displayType = bestMatch.type
                        }
                        else -> {
                            displayCharacter = "Other"
                            displayCostume = "Other"
                            displayType = "misc"
                        }
                    }

                    val resolvedHash = targetHash
                    val newCacheInfo = ModCacheInfo(
                        uriString = candidate.uriString,
                        lastModified = candidate.lastModified,
                        name = candidate.name,
                        character = displayCharacter,
                        costume = displayCostume,
                        type = displayType,
                        targetHashedName = resolvedHash,
                        isDirectory = candidate.isDirectory,
                        resolutionState = resolutionState,
                        targetHash = resolvedHash,
                        resolvedFamilyKey = resolvedFamilyKey,
                        unresolvedFiles = unresolvedFiles,
                        errorReason = errorReason
                    )
                    newCache[candidate.uriString] = newCacheInfo

                    tempModsList.add(
                        ModInfo(
                            name = candidate.name,
                            character = displayCharacter,
                            costume = displayCostume,
                            type = displayType,
                            isEnabled = false,
                            uri = candidate.uri,
                            targetHashedName = resolvedHash,
                            isDirectory = candidate.isDirectory,
                            resolutionState = resolutionState,
                            targetHash = resolvedHash,
                            resolvedFamilyKey = resolvedFamilyKey,
                            resolvedTargets = resolvedTargets,
                            unresolvedFiles = unresolvedFiles,
                            errorReason = errorReason
                        )
                    )
                }
            }

            saveModCache(newCache)
            tempModsList.sortedBy { it.name }
        }
    }

    private fun loadModCache(): Map<String, ModCacheInfo> {
        val cacheFile = File(context.cacheDir, MOD_CACHE_FILENAME)
        if (!cacheFile.exists()) return emptyMap()

        return try {
            val json = cacheFile.readText()
            val type = object : TypeToken<Map<String, ModCacheInfo>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyMap()
        }
    }

    private fun saveModCache(cache: Map<String, ModCacheInfo>) {
        try {
            val cacheFile = File(context.cacheDir, MOD_CACHE_FILENAME)
            val json = gson.toJson(cache)
            cacheFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun extractModDetailsFromUri(zipUri: Uri): ModDetails {
        val fileNames = mutableListOf<String>()
        var fileId: String? = null
        try {
            context.contentResolver.openInputStream(zipUri)?.use {
                ZipInputStream(it).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        fileNames.add(entry.name)
                        if (fileId == null) fileId = characterRepository.extractFileId(entry.name)
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ModDetails(fileId, fileNames)
    }

    private fun extractModDetailsFromDirectory(dirUri: Uri): ModDetails {
        val fileNames = mutableListOf<String>()
        var fileId: String? = null
        try {
            DocumentFile.fromTreeUri(context, dirUri)?.listFiles()?.forEach { file ->
                val entryName = file.name ?: ""
                fileNames.add(entryName)
                if (file.isFile) {
                    if (fileId == null) fileId = characterRepository.extractFileId(entryName)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ModDetails(fileId, fileNames)
    }

    private fun buildResolverFallback(fileNames: List<String>): JSONObject {
        return JSONObject().apply {
            put("resolutionState", ResolutionState.UNKNOWN.name)
            put("errorReason", "Resolver failed")
            put("unresolvedFiles", JSONArray(fileNames))
            put("resolvedTargets", JSONArray())
        }
    }

    private fun parseResolutionState(payload: JSONObject): ResolutionState {
        return try {
            ResolutionState.valueOf(payload.optString("resolutionState", ResolutionState.UNKNOWN.name))
        } catch (_: Exception) {
            ResolutionState.UNKNOWN
        }
    }

    private fun jsonArrayToStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                add(array.optString(i))
            }
        }
    }

    private fun parseResolvedTargets(array: JSONArray?): List<ResolvedTarget> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val candidates = jsonArrayToStringList(obj.optJSONArray("normalizedCandidates"))
                val strategy = try {
                    MatchStrategy.valueOf(obj.optString("matchStrategy", MatchStrategy.NONE.name))
                } catch (_: Exception) {
                    MatchStrategy.NONE
                }
                add(
                    ResolvedTarget(
                        originalFileName = obj.optString("originalFileName"),
                        normalizedCandidates = candidates,
                        resolvedAssetKey = obj.optString("resolvedAssetKey").ifBlank { null },
                        resolvedBundleName = obj.optString("resolvedBundleName").ifBlank { null },
                        resolvedBundlePath = obj.optString("resolvedBundlePath").ifBlank { null },
                        assetType = obj.optString("assetType").ifBlank { null },
                        targetHash = obj.optString("targetHash").ifBlank { null },
                        familyKey = obj.optString("familyKey").ifBlank { null },
                        matchStrategy = strategy,
                        confidence = obj.optDouble("confidence", 0.0).toFloat()
                    )
                )
            }
        }
    }
}
