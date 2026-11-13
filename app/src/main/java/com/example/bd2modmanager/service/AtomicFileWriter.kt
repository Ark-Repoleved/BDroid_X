package com.example.bd2modmanager.service

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.SyncFailedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Stages writes into a sibling temp file, verifies before commit, preserves
 * POSIX permissions, and atomically replaces [target] (ported from BD2ToolsBox).
 */
class AtomicFileWriter {

    fun write(
        target: File,
        verifyBeforeCommit: (File) -> Unit = {},
        write: (OutputStream) -> Unit
    ) {
        val dir = target.parentFile
            ?: throw IOException("Target has no parent directory: ${target.path}")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("Cannot create target parent directory: ${dir.path}")
        }

        val staged = try {
            File.createTempFile(STAGING_PREFIX, STAGING_SUFFIX, dir)
        } catch (createErr: IOException) {
            // Non-root Shizuku (shell UID 2000) cannot create files inside 2750 game dirs,
            // but an existing 0660 __data file is writable via ext_data_rw group.
            if (target.isFile && target.canWrite()) {
                writeViaSystemTempWhenDirNotWritable(target, verifyBeforeCommit, write)
                return
            }
            throw createErr
        }

        var committed = false
        try {
            FileOutputStream(staged).use { out ->
                write(out)
                out.flush()
                syncIfSupported(out)
            }
            verifyBeforeCommit(staged)
            preservePermissions(target, staged)
            moveIntoPlace(staged, target)
            committed = true
        } finally {
            if (!committed) {
                staged.delete()
            }
        }
    }

    private fun writeViaSystemTempWhenDirNotWritable(
        target: File,
        verifyBeforeCommit: (File) -> Unit,
        write: (OutputStream) -> Unit
    ) {
        val tempStaged = File.createTempFile(STAGING_PREFIX, STAGING_SUFFIX)
        try {
            FileOutputStream(tempStaged).use { out ->
                write(out)
                out.flush()
                syncIfSupported(out)
            }
            verifyBeforeCommit(tempStaged)
            FileOutputStream(target).use { out ->
                tempStaged.inputStream().use { input -> input.copyTo(out) }
                out.flush()
                syncIfSupported(out)
            }
            verifyBeforeCommit(target)
        } finally {
            tempStaged.delete()
        }
    }

    private fun syncIfSupported(out: FileOutputStream) {
        try {
            out.fd.sync()
        } catch (_: SyncFailedException) {
        } catch (_: IOException) {
        } catch (_: UnsupportedOperationException) {
        }
    }

    private fun preservePermissions(target: File, staged: File) {
        try {
            val permissions: Set<PosixFilePermission> =
                if (target.isFile) Files.getPosixFilePermissions(target.toPath())
                else newFilePermissions(target)
            Files.setPosixFilePermissions(staged.toPath(), permissions)
        } catch (_: UnsupportedOperationException) {
        } catch (_: IOException) {
        }
    }

    private fun newFilePermissions(target: File): Set<PosixFilePermission> {
        referenceFileForNewTarget(target.parentFile)?.let { reference ->
            return Files.getPosixFilePermissions(reference.toPath())
        }
        return parentDirectoryFilePermissions(target)
    }

    private fun referenceFileForNewTarget(dir: File?): File? {
        if (dir == null || !dir.isDirectory) return null
        return selectReferenceFile(dir.listFiles()?.toList() ?: emptyList())
    }

    private fun parentDirectoryFilePermissions(target: File): Set<PosixFilePermission> {
        val dir = target.parentFile
            ?: throw IOException("Target has no parent directory: ${target.path}")
        val dirPermissions = Files.getPosixFilePermissions(dir.toPath())
        return maskExecutableBits(dirPermissions)
    }

    private fun moveIntoPlace(staged: File, target: File) {
        try {
            Files.move(
                staged.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: AtomicMoveNotSupportedException) {
            throw IOException(
                "Filesystem does not support ATOMIC_MOVE; target left untouched: ${target.path}",
                e
            )
        } catch (e: IOException) {
            if (target.isFile && target.canWrite() && staged.isFile) {
                FileOutputStream(target).use { out ->
                    staged.inputStream().use { input -> input.copyTo(out) }
                    out.flush()
                    syncIfSupported(out)
                }
                staged.delete()
                return
            }
            throw e
        }
    }

    companion object {
        const val STAGING_PREFIX = ".bd2staged-"
        const val STAGING_SUFFIX = ".tmp"

        private val EXECUTE_BITS: Set<PosixFilePermission> = setOf(
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_EXECUTE
        )

        fun maskExecutableBits(permissions: Set<PosixFilePermission>): Set<PosixFilePermission> =
            permissions.filterNot { it in EXECUTE_BITS }.toSet()

        fun selectReferenceFile(candidates: List<File>): File? =
            candidates
                .filter { it.isFile && !isStagingName(it.name) }
                .minByOrNull { it.name }

        fun isStagingName(name: String): Boolean =
            name.startsWith(STAGING_PREFIX) && name.endsWith(STAGING_SUFFIX)
    }
}
