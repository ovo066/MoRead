package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.database.entity.MessageEntity
import org.junit.Assert.*
import org.junit.Test

class CompanionMessageReconcilerTest {
    private val row = MessageEntity(id = 2, conversationId = 1, role = "assistant",
        content = "reply", createdAt = 1, clientRoundId = "round")

    @Test fun eventFirstSurvivesOldRoomSnapshotThenYieldsToDatabaseEditsAndDeletion() {
        val owner = CompanionMessageReconciler(1)
        owner.committed(row)
        assertEquals(listOf(row), owner.observe(emptyList()))
        assertEquals(listOf(row), owner.observe(listOf(row)))
        val edited = row.copy(content = "edited")
        assertEquals(listOf(edited), owner.observe(listOf(edited)))
        assertTrue(owner.observe(emptyList()).isEmpty())
    }

    @Test fun roomFirstCommitDoesNotResurrectDeletedMessage() {
        val owner = CompanionMessageReconciler(1)
        owner.observe(listOf(row))
        owner.committed(row)
        assertTrue(owner.observe(emptyList()).isEmpty())
    }

    @Test fun deletingNewestEventBeforeRoomObservesItCannotResurrectIt() {
        val owner = CompanionMessageReconciler(1)
        val previous = row.copy(id = 41)
        val newest = row.copy(id = 42)
        owner.observe(listOf(previous))
        owner.committed(newest)
        owner.forget(setOf(42))
        assertEquals(listOf(previous), owner.observe(listOf(previous)))
        assertFalse(owner.committed(newest))
        assertEquals(listOf(previous), owner.observe(listOf(previous, newest)))
    }

    @Test fun rerollTombstonesAllRemovedRowsAndRestoresOnFailedMutation() {
        val owner = CompanionMessageReconciler(1)
        owner.committed(row)
        val next = row.copy(id = 3, role = "tool")
        owner.committed(next)
        owner.forget(setOf(2, 3))
        assertTrue(owner.observe(emptyList()).isEmpty())
        assertFalse(owner.committed(row))
        owner.restore(listOf(row, next))
        assertEquals(listOf(row, next), owner.observe(emptyList()))
    }

    @Test fun lateCommitAndSnapshotFromPreviousConversationAreRejected() {
        val owner = CompanionMessageReconciler(1)
        owner.clear(2)
        assertFalse(owner.committed(row))
        assertTrue(owner.observe(listOf(row)).isEmpty())
        val current = row.copy(conversationId = 2)
        assertTrue(owner.committed(current))
        assertEquals(listOf(current), owner.observe(emptyList()))
    }

    @Test fun switchingConversationDropsUnobservedRows() {
        val owner = CompanionMessageReconciler(1)
        owner.committed(row)
        owner.clear()
        assertTrue(owner.observe(emptyList()).isEmpty())
    }
}
