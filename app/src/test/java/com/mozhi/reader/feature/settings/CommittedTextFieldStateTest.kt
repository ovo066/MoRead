package com.mozhi.reader.feature.settings

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.mozhi.reader.ui.components.CommittedTextFieldState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CommittedTextFieldStateTest {
    @Test fun rapidTypingAndMiddleInsertionKeepSelectionAndComposition() {
        val state = CommittedTextFieldState("https://host/v1")
        state.focusChanged(true)
        val edit = TextFieldValue("https://new.host/v1 ", TextRange(11), TextRange(8, 11))
        state.edit(edit)
        state.receivePersisted("https://h")
        state.receivePersisted("https://host/v1")
        assertEquals(edit, state.value)
        assertTrue(state.dirty)
        assertEquals(TextRange(8, 11), state.value.composition)
    }

    @Test fun initialDelayedLoadOnlyReplacesUnfocusedCleanDraft() {
        val state = CommittedTextFieldState("")
        state.receivePersisted("https://loaded")
        assertEquals("https://loaded", state.value.text)
        state.focusChanged(true)
        state.receivePersisted("https://external")
        assertEquals("https://loaded", state.value.text)
        state.focusChanged(false)
        assertEquals("https://external", state.value.text)
    }

    @Test fun normalizationOccursOnlyAtCommitAndOldEchoCannotUndoDraft() {
        val writes = mutableListOf<String>()
        val state = CommittedTextFieldState("old").apply { onCommit = writes::add }
        state.focusChanged(true)
        val edit = TextFieldValue("  https://new/v1  ", TextRange(10), TextRange(2, 10))
        state.edit(edit)
        assertTrue(writes.isEmpty())
        state.commit()
        assertEquals(listOf("https://new/v1"), writes)
        assertEquals(edit, state.value)
        state.receivePersisted("old")
        assertEquals(edit, state.value)
        state.receivePersisted("https://new/v1")
        state.completeWrite("https://new/v1", true)
        assertEquals(edit, state.value) // No active-IME rewrite, even on acknowledgment.
        state.focusChanged(false)
        assertEquals("https://new/v1", state.value.text)
    }

    @Test fun acknowledgmentOfOlderEditDoesNotOverwriteNewerEdit() {
        val state = CommittedTextFieldState("")
        state.edit(TextFieldValue("first"))
        state.commit()
        state.edit(TextFieldValue("second", TextRange(3)))
        state.completeWrite("first", true)
        state.receivePersisted("first")
        assertEquals(TextFieldValue("second", TextRange(3)), state.value)
        assertTrue(state.dirty)
    }

    @Test fun revertingWhileWriteIsPendingMustPersistTheRevert() {
        val writes = mutableListOf<String>()
        val state = CommittedTextFieldState("old").apply { onCommit = { writes += it } }
        state.edit(TextFieldValue("new"))
        state.commit()
        state.edit(TextFieldValue("old"))
        assertTrue(state.dirty)
        state.commit()
        assertEquals(listOf("new", "old"), writes)
        state.receivePersisted("new")
        assertEquals("old", state.value.text)
        state.completeWrite("old", true)
        state.receivePersisted("old")
        assertFalse(state.dirty)
    }

    @Test fun promptWhitespaceIsPreservedAtCommit() {
        var saved = ""
        val state = CommittedTextFieldState("").apply {
            normalize = { it }
            onCommit = { saved = it }
        }
        state.edit(TextFieldValue("  tags\n\n"))
        state.commit()
        assertEquals("  tags\n\n", saved)
    }

    @Test fun flushAwaitsPersistenceBeforeTestOrNavigation() = runTest {
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val writes = SettingsWriteQueue(this) { error("unexpected persistence failure") }
        val state = CommittedTextFieldState("").apply {
            onCommit = { text -> writes.enqueue { gate.await(); order += text } }
        }
        state.edit(TextFieldValue(" https://new "))
        state.commit()
        launch {
            assertTrue(writes.flush())
            state.confirmFlushed()
            order += "test"
        }
        runCurrent()
        assertTrue(order.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("https://new", "test"), order)
        assertFalse(state.dirty)
    }

    @Test fun presetAfterFlushIsNotBlockedByUnobservedStorageEcho() {
        val state = CommittedTextFieldState("old")
        state.edit(TextFieldValue("custom"))
        state.commit()
        state.confirmFlushed()
        state.acceptExternalChange() // Deliberate provider selection, not a storage echo.
        state.receivePersisted("provider-default")
        assertEquals("provider-default", state.value.text)
    }

    @Test fun configurationRestoreKeepsDraftSelectionAndCompositionAndRetriesPendingWrite() {
        val original = CommittedTextFieldState("old")
        val value = TextFieldValue("new text", TextRange(3), TextRange(0, 3))
        original.edit(value)
        original.commit()
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(CommittedTextFieldState.Saver) { scope.save(original) }!!
        val restored = CommittedTextFieldState.Saver.restore(saved)!!
        assertEquals(value, restored.value)
        assertTrue(restored.dirty)
        var retried: String? = null
        restored.onCommit = { retried = it }
        restored.commit()
        assertEquals("new text", retried)
    }

    @Test fun duplicatePendingCommitIsSuppressedButFailedWriteCanBeRetried() {
        var count = 0
        val state = CommittedTextFieldState("old").apply { onCommit = { count++ } }
        state.edit(TextFieldValue("new"))
        repeat(3) { state.commit() }
        assertEquals(1, count)
        state.receivePersisted("new") // Matching text alone is not a write-result acknowledgment.
        state.receivePersisted("old")
        state.commit()
        assertEquals(1, count)
        state.completeWrite("new", false)
        assertTrue(state.dirty)
        state.commit()
        assertEquals(2, count)
        state.completeWrite("new", true)
        state.receivePersisted("old")
        assertEquals("new", state.value.text)
        state.receivePersisted("new")
        assertFalse(state.dirty)
    }

    @Test fun oldSameTextWriteResultCannotAcknowledgeLatestFailedAttempt() {
        val state = CommittedTextFieldState("old")
        state.edit(TextFieldValue("A"))
        state.commit()
        val first = state.writeVersion
        state.edit(TextFieldValue("B"))
        state.commit()
        state.edit(TextFieldValue("A"))
        state.commit()
        val latest = state.writeVersion
        state.completeWrite("A", true, first)
        assertTrue(state.dirty)
        state.completeWrite("A", false, latest)
        assertTrue(state.dirty)
        state.commit()
        assertTrue(state.writeVersion > latest)
    }

    @Test fun explicitDiscardPreventsDisposeFromResubmittingFailedDraft() {
        var count = 0
        val state = CommittedTextFieldState("old").apply { onCommit = { count++ } }
        state.edit(TextFieldValue("new"))
        state.commit()
        state.completeWrite("new", false)
        state.discard()
        state.commit()
        assertEquals(1, count)
        assertEquals("old", state.value.text)
    }

    @Test fun failedPersistencePreventsActionAndCanBeRetried() = runTest {
        val queue = SettingsWriteQueue(this) { }
        queue.enqueue { throw IllegalStateException("disk unavailable") }
        assertFalse(queue.flush())
        queue.enqueue { }
        assertTrue(queue.flush())
    }
}
