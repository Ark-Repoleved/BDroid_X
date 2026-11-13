package com.example.bd2modmanager.service

import com.google.gson.Gson
import java.io.File

enum class JournalStage(val id: String) {
    PLANNED("planned"),
    STAGED("staged"),
    TARGET_REPLACING("target_replacing"),
    TARGET_REPLACED("target_replaced")
}

data class JournalEntry(
    val operationId: String,
    val stage: String,
    val atMillis: Long,
    val bundleNames: List<String> = emptyList(),
    val note: String? = null
)

data class JournalDocument(
    val schemaVersion: Int = 1,
    val entries: List<JournalEntry> = emptyList()
)

sealed interface RecoveryNotice {
    data class InterruptedRepack(
        val operationId: String,
        val bundleNames: List<String>
    ) : RecoveryNotice

    data class PendingMove(
        val operationId: String,
        val bundleNames: List<String>
    ) : RecoveryNotice

    data class InterruptedMove(
        val operationId: String,
        val bundleNames: List<String>,
        val hasStagedFiles: Boolean
    ) : RecoveryNotice

    data class Unreadable(val message: String) : RecoveryNotice
}

/**
 * Lightweight crash-recovery journal for foreground repack and Shizuku file moves
 * (adapted from BD2ToolsBox OperationJournalStore + OperationRecovery).
 */
class OperationJournalStore(private val journalFile: File) {

    private val gson = Gson()
    private val lock = Any()

    fun append(entry: JournalEntry) {
        synchronized(lock) {
            val current = readEntriesLocked() ?: emptyList()
            val updated = JournalDocument(
                schemaVersion = 1,
                entries = current + entry
            )
            val json = gson.toJson(updated)
            AtomicFileWriter().write(target = journalFile) { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            if (journalFile.exists() && !journalFile.delete()) {
                val json = gson.toJson(JournalDocument())
                runCatching {
                    AtomicFileWriter().write(target = journalFile) { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
    }

    fun classify(downloadSharedHasFiles: Boolean): List<RecoveryNotice> {
        synchronized(lock) {
            if (!journalFile.exists()) return emptyList()
            val text = try {
                journalFile.readText()
            } catch (e: Exception) {
                return listOf(RecoveryNotice.Unreadable("Failed to read install journal: ${e.message}"))
            }
            if (text.isBlank()) {
                return listOf(RecoveryNotice.Unreadable("Install journal is empty."))
            }
            val doc = try {
                gson.fromJson(text, JournalDocument::class.java)
            } catch (e: Exception) {
                return listOf(RecoveryNotice.Unreadable("Corrupt install journal: ${e.message}"))
            } ?: return listOf(RecoveryNotice.Unreadable("Invalid install journal."))

            val entries = doc.entries
            if (entries.isEmpty()) return emptyList()

            val notices = mutableListOf<RecoveryNotice>()
            val byOp = entries.groupBy { it.operationId }
            for ((opId, opEntries) in byOp) {
                val last = opEntries.lastOrNull() ?: continue
                val bundles = opEntries.flatMap { it.bundleNames }.distinct()
                when (last.stage) {
                    JournalStage.TARGET_REPLACED.id -> Unit
                    JournalStage.TARGET_REPLACING.id ->
                        notices += RecoveryNotice.InterruptedMove(opId, bundles, downloadSharedHasFiles)
                    JournalStage.STAGED.id ->
                        if (downloadSharedHasFiles) {
                            notices += RecoveryNotice.PendingMove(opId, bundles)
                        }
                    JournalStage.PLANNED.id ->
                        notices += RecoveryNotice.InterruptedRepack(opId, bundles)
                    else ->
                        notices += RecoveryNotice.Unreadable("Unknown stage '${last.stage}' for $opId")
                }
            }
            return notices
        }
    }

    private fun readEntriesLocked(): List<JournalEntry>? {
        if (!journalFile.exists()) return emptyList()
        return try {
            val text = journalFile.readText()
            if (text.isBlank()) emptyList()
            else gson.fromJson(text, JournalDocument::class.java)?.entries ?: emptyList()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val JOURNAL_FILENAME = "install_journal.json"
    }
}
