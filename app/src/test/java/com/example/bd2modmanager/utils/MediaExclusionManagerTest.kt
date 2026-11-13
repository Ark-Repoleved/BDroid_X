package com.example.bd2modmanager.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaExclusionManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun ensureMarker_createsNomediaInDedicatedFolderAndIsIdempotent() {
        val modDir = tempFolder.newFolder("BD2Mods")

        val first = MediaExclusionManager.ensureMarker(modDir)
        assertTrue(first is MediaExclusionResult.Created)
        assertTrue(File(modDir, ".nomedia").isFile)

        val second = MediaExclusionManager.ensureMarker(modDir)
        assertTrue(second is MediaExclusionResult.AlreadyPresent)
    }

    @Test
    fun ensureMarker_refusesMixedUseRootDirectories() {
        val downloadDir = tempFolder.newFolder("Download")
        val result = MediaExclusionManager.ensureMarker(downloadDir)

        assertTrue(result is MediaExclusionResult.Rejected)
        assertFalse(File(downloadDir, ".nomedia").exists())
    }

    @Test
    fun verifyProviderName_requiresExactNomediaName() {
        assertTrue(MediaExclusionManager.verifyProviderName(".nomedia"))
        assertFalse(MediaExclusionManager.verifyProviderName(".nomedia.txt"))
        assertFalse(MediaExclusionManager.verifyProviderName(null))
    }
}
