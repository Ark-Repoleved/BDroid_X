package com.example.bd2modmanager.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InstallSafetyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun connectionOccupancy_releasesOnlyOnceWhenLastTokenReleased() {
        var releasedCalls = 0
        val occupancy = ConnectionOccupancy { releasedCalls++ }

        val t1 = occupancy.acquire()
        val t2 = occupancy.acquire()
        assertEquals(2, occupancy.activeCount)

        occupancy.release(t1)
        assertEquals(0, releasedCalls)

        // Duplicate release of t1 is a no-op
        occupancy.release(t1)
        assertEquals(0, releasedCalls)

        occupancy.release(t2)
        assertEquals(1, releasedCalls)

        // Duplicate release of t2 does not fire callback again
        occupancy.release(t2)
        assertEquals(1, releasedCalls)
    }

    @Test
    fun journalStore_classifiesPendingAndInterruptedStages() {
        val journalFile = File(tempFolder.root, OperationJournalStore.JOURNAL_FILENAME)
        val store = OperationJournalStore(journalFile)

        store.append(JournalEntry("op-1", JournalStage.PLANNED.id, 100L, listOf("b1")))
        assertTrue(store.classify(downloadSharedHasFiles = false).first() is RecoveryNotice.InterruptedRepack)

        store.append(JournalEntry("op-1", JournalStage.STAGED.id, 200L, listOf("b1")))
        assertTrue(store.classify(downloadSharedHasFiles = true).first() is RecoveryNotice.PendingMove)

        store.append(JournalEntry("op-1", JournalStage.TARGET_REPLACING.id, 300L, listOf("b1")))
        val moveNotice = store.classify(downloadSharedHasFiles = true).first()
        assertTrue(moveNotice is RecoveryNotice.InterruptedMove && moveNotice.hasStagedFiles)

        store.append(JournalEntry("op-1", JournalStage.TARGET_REPLACED.id, 400L, listOf("b1")))
        assertTrue(store.classify(downloadSharedHasFiles = false).isEmpty())

        store.clear()
        assertTrue(store.classify(downloadSharedHasFiles = false).isEmpty())
    }
}
