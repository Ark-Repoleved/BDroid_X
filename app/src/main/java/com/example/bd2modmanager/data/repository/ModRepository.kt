package com.example.bd2modmanager.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.bd2modmanager.data.model.ModCacheInfo
import com.example.bd2modmanager.data.model.ModDetails
import com.example.bd2modmanager.data.model.ModInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    suspend fun scanMods(dirUri: Uri): List<ModInfo> {
        return withContext(Dispatchers.IO) {
            val existingCache = loadModCache()
            val newCache = mutableMapOf<String, ModCacheInfo>()
            val tempModsList = mutableListOf<ModInfo>()
            val files = DocumentFile.fromTreeUri(context, dirUri)?.listFiles() ?: emptyArray()

            files.filter { it.isDirectory || it.name?.endsWith(".zip", ignoreCase = true) == true }
                .forEach { file ->
                    val uriString = file.uri.toString()
                    val lastModified = file.lastModified()
                    val cachedInfo = existingCache[uriString]

                    val modInfo = if (cachedInfo != null && cachedInfo.lastModified == lastModified) {
                        newCache[uriString] = cachedInfo
                        ModInfo(
                            name = cachedInfo.name,
                            character = cachedInfo.character,
                            costume = cachedInfo.costume,
                            type = cachedInfo.type,
                            isEnabled = false,
                            uri = file.uri,
                            targetHashedName = cachedInfo.targetHashedName,
                            isDirectory = cachedInfo.isDirectory
                        )
                    } else {
                        val modName = file.name?.removeSuffix(".zip") ?: ""
                        val isDirectory = file.isDirectory
                        val modDetails = if (isDirectory) {
                            extractModDetailsFromDirectory(file.uri)
                        } else {
                            extractModDetailsFromUri(file.uri)
                        }
                        val bestMatch = characterRepository.findBestMatch(modDetails.fileId, modDetails.fileNames)

                        val newCacheInfo = ModCacheInfo(
                            uriString = uriString,
                            lastModified = lastModified,
                            name = modName,
                            character = bestMatch?.character ?: "Unknown",
                            costume = bestMatch?.costume ?: "Unknown",
                            type = bestMatch?.type ?: "idle",
                            targetHashedName = bestMatch?.hashedName,
                            isDirectory = isDirectory
                        )
                        newCache[uriString] = newCacheInfo

                        ModInfo(
                            name = modName,
                            character = bestMatch?.character ?: "Unknown",
                            costume = bestMatch?.costume ?: "Unknown",
                            type = bestMatch?.type ?: "idle",
                            isEnabled = false,
                            uri = file.uri,
                            targetHashedName = bestMatch?.hashedName,
                            isDirectory = isDirectory
                        )
                    }
                    tempModsList.add(modInfo)
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
}
