package com.mozhi.reader.feature.reader

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompanionOwnershipTest {
    @Test fun preparationRunsOffOwnerAndReturnsBeforeOwnerMutation() = runBlocking {
        val ownerThread = Thread.currentThread()
        var mutated = false
        val prepared = prepareCompanionContext {
            assertNotSame(ownerThread, Thread.currentThread())
            assertFalse(mutated)
            "immutable prompt and tools"
        }
        assertSame(ownerThread, Thread.currentThread())
        mutated = true
        assertEquals("immutable prompt and tools", prepared)
        assertTrue(mutated)
    }

    @Test fun personaResetAndOldScreenCallbacksCannotLeakComposerText() {
        val original = CompanionComposerDraft(1).edit(1, "draft for one")
        assertEquals("draft for one", original.visibleTo(1)) // Side/full both read this same value.
        assertEquals("", original.visibleTo(2)) // New persona may project before async binding finishes.
        val reset = CompanionComposerDraft(2)
        assertEquals("", reset.visibleTo(2))
        assertEquals(reset, reset.edit(1, "late old-screen input"))
        val edited = reset.edit(2, "new draft")
        assertEquals("new draft", edited.visibleTo(2))
        assertEquals("draft for one", original.visibleTo(1))
    }
}
