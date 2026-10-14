package com.example.bd2modmanager.service

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

object RootFileManager {
    suspend fun hasRootPermission(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            val (success, output) = runRootCommand(context, "id -u", 30)
            success && output.lineSequence().any { it.trim() == "0" }
        }

    suspend fun moveDownloadToGame(context: Context): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            // Use fixed paths and merge files without removing unrelated game resources.
            val script = """
                set -e
                [ "${'$'}(id -u)" = "0" ] || {
                    echo "Root access was not granted."
                    exit 1
                }
                src='/storage/emulated/0/Download/Shared'
                dst='/storage/emulated/0/Android/data/com.neowizgames.game.browndust2/files/UnityCache/Shared'
                [ -d "${'$'}src" ] || {
                    echo "Download/Shared directory not found."
                    exit 1
                }
                [ -d "${'$'}dst" ] || {
                    echo "Game Shared directory not found. Launch the game and download its resources first."
                    exit 1
                }
                [ -n "${'$'}(ls -A "${'$'}src")" ] || {
                    echo "Download/Shared is empty."
                    exit 1
                }
                # Remove source files only after the entire copy succeeds.
                cp -R "${'$'}src/." "${'$'}dst/"
                rm -r "${'$'}src"
            """.trimIndent()

            val (success, output) = runRootCommand(context, script, 180)
            if (success) {
                true to "Files moved to game directory successfully using Root."
            } else {
                false to "Root move failed: ${output.ifBlank { "Check root authorization and directory access." }}"
            }
        }

    private fun runRootCommand(
        context: Context,
        command: String,
        timeoutSeconds: Long
    ): Pair<Boolean, String> {
        var logFile: File? = null
        var process: Process? = null
        try {
            // Redirect output to a file to avoid blocking on a full output pipe.
            val outputFile = File.createTempFile("root-operation-", ".log", context.cacheDir)
            logFile = outputFile
            val running = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .redirectOutput(outputFile)
                .start()
            process = running
            if (!running.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                running.destroyForcibly()
                return false to "Root operation timed out. Check authorization and inspect the source and destination before retrying."
            }
            return (running.exitValue() == 0) to outputFile.readText().trim().takeLast(4000)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false to "Unable to run root command: ${e.message ?: "Unknown error."}"
        } finally {
            process?.let { if (it.isAlive) it.destroyForcibly() }
            logFile?.delete()
        }
    }
}
