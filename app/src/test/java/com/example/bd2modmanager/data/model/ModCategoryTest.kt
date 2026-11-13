package com.example.bd2modmanager.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModCategoryTest {

    @Test
    fun categoryOf_mapsTypesAndDatingSim() {
        assertEquals(ModCategory.CUTSCENE, categoryOf("cutscene", "Base"))
        assertEquals(ModCategory.ILLUST, categoryOf("idle", "Base"))
        assertEquals(ModCategory.DATING, categoryOf("idle", "DATING SIM - Special"))
        assertEquals(ModCategory.OTHER, categoryOf("unknown", "Other"))
    }

    @Test
    fun isUnknownCharacter_matchesPlaceholders() {
        assertTrue(isUnknownCharacter(""))
        assertTrue(isUnknownCharacter("Unknown"))
        assertTrue(isUnknownCharacter("Unknown Character"))
        assertFalse(isUnknownCharacter("Justia"))
    }
}
