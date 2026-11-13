package com.example.bd2modmanager.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.attribute.PosixFilePermission

class AtomicFileWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun write_commitsNewAndReplacesExistingWithoutLeavingStagingFiles() {
        val dir = tempFolder.newFolder("bundle")
        val target = File(dir, "__data")
        target.writeText("original")

        AtomicFileWriter().write(
            target = target,
            verifyBeforeCommit = { staged ->
                assertEquals("updated-payload", staged.readText())
            }
        ) { out ->
            out.write("updated-payload".toByteArray())
        }

        assertEquals("updated-payload", target.readText())
        assertTrue(dir.listFiles()?.none { AtomicFileWriter.isStagingName(it.name) } == true)
    }

    @Test
    fun write_preservesTargetWhenVerificationFails() {
        val dir = tempFolder.newFolder("bundle")
        val target = File(dir, "__data")
        target.writeText("intact-original")

        try {
            AtomicFileWriter().write(
                target = target,
                verifyBeforeCommit = { throw IOException("verification failed") }
            ) { out ->
                out.write("corrupt".toByteArray())
            }
            org.junit.Assert.fail("Expected IOException")
        } catch (_: IOException) {
        }

        assertEquals("intact-original", target.readText())
        assertTrue(dir.listFiles()?.none { AtomicFileWriter.isStagingName(it.name) } == true)
    }

    @Test
    fun maskExecutableBits_stripsOnlyExecutePermissions() {
        val dirPerms = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE
        )
        val masked = AtomicFileWriter.maskExecutableBits(dirPerms)
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ
            ),
            masked
        )
    }

    @Test
    fun selectReferenceFile_skipsStagingAndPicksDeterministically() {
        val dir = tempFolder.newFolder("ref")
        val staged = File(dir, "${AtomicFileWriter.STAGING_PREFIX}123${AtomicFileWriter.STAGING_SUFFIX}").apply { writeText("s") }
        val b = File(dir, "__info").apply { writeText("i") }
        val a = File(dir, "__data").apply { writeText("d") }

        val selected = AtomicFileWriter.selectReferenceFile(listOf(staged, b, a))
        assertEquals("__data", selected?.name)
        assertFalse(AtomicFileWriter.isStagingName(selected!!.name))
        assertNull(AtomicFileWriter.selectReferenceFile(listOf(staged)))
    }
}
