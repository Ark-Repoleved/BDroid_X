package com.example.bd2modmanager.data.repository

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Character metadata (gender, collab status, localized CN names, avatar/illust filenames)
 * loaded from bundled `assets/character_meta.json` (ported from BD2ToolsBox).
 */
class CharacterMetaRepository private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "CharacterMeta"
        private const val ASSET_NAME = "character_meta.json"
        private const val ILLUST_BASE =
            "https://raw.githubusercontent.com/myssal/Brown-Dust-2-Asset/" +
                "refs/heads/master/ui/illust/illust_inven_char/"

        @Volatile
        private var instance: CharacterMetaRepository? = null

        fun getInstance(context: Context): CharacterMetaRepository =
            instance ?: synchronized(this) {
                instance ?: CharacterMetaRepository(context.applicationContext).also { instance = it }
            }
    }

    data class Costume(
        val costumeId: String,
        val character: String,
        val characterCn: String,
        val costume: String,
        val costumeCn: String,
        val gender: String,
        val isCollab: Boolean,
        val datingId: String,
        val headImage: String,
        val illustImage: String,
        val element: String,
        val grade: Int,
        val skinType: String
    ) {
        val illustUrl: String? get() = illustImage.takeIf { it.isNotBlank() }?.let { ILLUST_BASE + it }
    }

    data class CharacterMeta(
        val name: String,
        val nameCn: String,
        val gender: String,
        val isCollab: Boolean,
        val element: String,
        val representative: Costume?
    ) {
        val isMale: Boolean get() = gender.equals("male", true)
        val isFemale: Boolean get() = gender.equals("female", true)
    }

    @Volatile
    private var byCostumeId: Map<String, Costume>? = null

    @Volatile
    private var byCharacter: Map<String, CharacterMeta>? = null

    private fun ensureLoaded() {
        if (byCostumeId != null) return
        synchronized(this) {
            if (byCostumeId != null) return
            val costumes = HashMap<String, Costume>()
            try {
                val text = appContext.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
                val root = JSONObject(text)
                val arr = root.optJSONArray("costumes")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val cid = o.optString("cid").trim()
                        if (cid.isBlank()) continue
                        costumes[cid] = Costume(
                            costumeId = cid,
                            character = o.optString("char").trim(),
                            characterCn = o.optString("charCn").trim(),
                            costume = o.optString("costume").trim(),
                            costumeCn = o.optString("costumeCn").trim(),
                            gender = o.optString("gender").trim(),
                            isCollab = o.optBoolean("collab"),
                            datingId = o.optString("dating").trim(),
                            headImage = o.optString("head").trim(),
                            illustImage = o.optString("illust").trim(),
                            element = o.optString("element").trim(),
                            grade = o.optInt("grade"),
                            skinType = o.optString("skin").trim()
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read $ASSET_NAME", e)
            }

            val chars = HashMap<String, CharacterMeta>()
            costumes.values.groupBy { it.character }.forEach { (name, list) ->
                if (name.isBlank()) return@forEach
                val rep = list.minByOrNull { it.costumeId }
                chars[name] = CharacterMeta(
                    name = name,
                    nameCn = list.firstOrNull { it.characterCn.isNotBlank() }?.characterCn ?: "",
                    gender = list.firstOrNull { it.gender.isNotBlank() }?.gender ?: "",
                    isCollab = list.any { it.isCollab },
                    element = list.firstOrNull { it.element.isNotBlank() }?.element ?: "",
                    representative = rep
                )
            }
            byCostumeId = costumes
            byCharacter = chars
        }
    }

    fun forCharacter(name: String): CharacterMeta? {
        ensureLoaded()
        return byCharacter?.get(name.trim())
    }

    fun peekCharacter(name: String): CharacterMeta? = byCharacter?.get(name.trim())

    fun allCharacterNames(): List<String> {
        ensureLoaded()
        return byCharacter?.keys?.sortedBy { it.lowercase() } ?: emptyList()
    }

    fun allRepresentatives(): List<Costume> {
        ensureLoaded()
        return byCharacter?.values
            ?.mapNotNull { it.representative }
            ?.sortedBy { it.character.lowercase() }
            ?: emptyList()
    }

    fun forCostumeId(costumeId: String): Costume? {
        ensureLoaded()
        return byCostumeId?.get(costumeId.trim())
    }

    fun costumeIdFromFileId(fileId: String?): String? {
        val s = fileId?.lowercase() ?: return null
        val m = Regex("char(\\d{6})").find(s) ?: return null
        return m.groupValues[1]
    }

    fun forFileId(fileId: String?): Costume? {
        val cid = costumeIdFromFileId(fileId) ?: return null
        return forCostumeId(cid)
    }

    fun forDatingId(fileId: String?): Costume? {
        val s = fileId?.lowercase() ?: return null
        val m = Regex("dating(\\d+)").find(s) ?: return null
        val id = m.groupValues[1]
        ensureLoaded()
        return byCostumeId?.values?.firstOrNull {
            it.datingId.isNotBlank() &&
                it.datingId.trimStart('0').ifEmpty { "0" } == id.trimStart('0').ifEmpty { "0" }
        }
    }
}
