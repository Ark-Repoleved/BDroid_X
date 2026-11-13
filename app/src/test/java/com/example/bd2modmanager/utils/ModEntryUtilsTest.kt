package com.example.bd2modmanager.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModEntryUtilsTest {

    @Test
    fun shouldIgnoreModEntry_ignoresNomediaAndModfileAndPreviewImages() {
        assertTrue(ModEntryUtils.shouldIgnoreModEntry(".nomedia"))
        assertTrue(ModEntryUtils.shouldIgnoreModEntry("folder/.nomedia"))
        assertTrue(ModEntryUtils.shouldIgnoreModEntry("pack1003_title1.modfile"))
        assertTrue(ModEntryUtils.shouldIgnoreModEntry("preview.png"))
        assertFalse(ModEntryUtils.shouldIgnoreModEntry("char000104.png"))
        assertFalse(ModEntryUtils.shouldIgnoreModEntry("char000104.atlas"))
        assertFalse(ModEntryUtils.shouldIgnoreModEntry("char000104.skel"))
    }
}
