package com.example.bd2modmanager.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAndApkSelectorTest {

    @Test
    fun apkSelector_picksMatchingAbiAndRejectsWrongAbi() {
        val arm64 = UpdateRepository.ApkAsset("BDroidX-arm64-v8a.apk", "https://example/arm64.apk", 100L)
        val x64 = UpdateRepository.ApkAsset("BDroidX-x86_64.apk", "https://example/x64.apk", 100L)
        val x86 = UpdateRepository.ApkAsset("BDroidX-x86.apk", "https://example/x86.apk", 100L)

        assertEquals(arm64, ApkSelector.select(listOf(x64, arm64), listOf("arm64-v8a")))
        assertEquals(x64, ApkSelector.select(listOf(x86, x64, arm64), listOf("x86_64")))
        assertEquals(x86, ApkSelector.select(listOf(x64, x86), listOf("x86")))
        assertNull(ApkSelector.select(listOf(x64), listOf("arm64-v8a")))
    }

    @Test
    fun versionCompare_handlesSemverAndBetaSuffixes() {
        assertTrue(UpdateRepository.compare("2.0.0", "1.9.9") > 0)
        assertTrue(UpdateRepository.compare("2.0.0", "2.0.0-beta.1") > 0)
        assertTrue(UpdateRepository.compare("2.0.0-beta.2", "2.0.0-beta.1") > 0)
        assertTrue(UpdateRepository.compare("v2.0.10", "v2.0.9") > 0)
        assertEquals(0, UpdateRepository.compare("v2.0.0", "2.0.0"))
    }
}
