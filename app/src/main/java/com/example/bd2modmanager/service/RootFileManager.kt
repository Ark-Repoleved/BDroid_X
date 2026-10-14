package com.example.bd2modmanager.service

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

object RootFileManager {

    suspend fun copyDownloadToGame(
        context: Context
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        var logFile: File? = null
        var process: Process? = null

        try {
            val outputFile = File.createTempFile(
                "root-copy-",
                ".log",
                context.cacheDir
            )
            logFile = outputFile

            // Keep paths fixed. Never insert untrusted input into this script.
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

                # Merge source contents into the existing game directory.
                # Keep the source files for retrying or manual transfer.
                cp -R "${'$'}src/." "${'$'}dst/"
            """.trimIndent()

            // Redirect output to a file to avoid blocking on a full output pipe.
            val running = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .redirectOutput(outputFile)
                .start()
            process = running

            // Allow time for root authorization and file copying.
            if (!running.waitFor(180, TimeUnit.SECONDS)) {
                running.destroyForcibly()
                return@withContext false to
                    "Root copy timed out. Some files may have been copied. Please try again."
            }

            val details = outputFile.readText().trim().takeLast(4000)

            if (running.exitValue() == 0) {
                true to
                    "Files copied successfully. Source files were kept in Download/Shared."
            } else {
                false to "Failed to copy files using root: ${
                    details.ifBlank {
                        "Check root authorization and directory access."
                    }
                }"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false to "Unable to run root copy: ${e.message ?: "Unknown error."}"
        } finally {
            process?.let { running ->
                if (running.isAlive) {
                    running.destroyForcibly()
                }
            }
            logFile?.delete()
        }
    }
}
