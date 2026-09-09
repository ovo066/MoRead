package com.mozhi.reader.feature.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DraftSaveGateTest {
    @Test fun failedNavigationShowsDecisionAndRetryMustActuallySaveBeforeLeaving() = runTest {
        var attempts = 0
        var left = false
        val writes = SettingsWriteQueue(this) { }
        writes.enqueue("url") { if (++attempts == 1) error("disk full") }
        val gate = DraftSaveGate(this, {}, { retry ->
            if (retry) writes.retryFailed()
            writes.flush()
        }, {}, writes::discardFailures)
        gate.run(navigation = true) { left = true }
        runCurrent()
        assertTrue(gate.failed)
        assertTrue(gate.canDiscard)
        assertFalse(left)
        assertFalse(writes.flush()) // Merely reading the failure cannot erase it.
        gate.retry()
        runCurrent()
        assertEquals(2, attempts)
        assertFalse(gate.failed)
        assertTrue(left)
    }

    @Test fun cancelKeepsDraftAndDiscardRequiresExplicitNavigationDecision() = runTest {
        var discarded = false
        var invoked = false
        val gate = DraftSaveGate(this, {}, { false }, {}, { discarded = true })
        gate.run(navigation = true) { invoked = true }
        runCurrent()
        gate.cancel()
        assertFalse(discarded)
        assertFalse(invoked)
        gate.run(navigation = false) { invoked = true }
        runCurrent()
        gate.discard() // A failed configuration/test action cannot run with unsaved settings.
        assertFalse(discarded)
        assertFalse(invoked)
        gate.run(navigation = true) { invoked = true }
        runCurrent()
        gate.discard()
        assertTrue(discarded)
        assertTrue(invoked)
    }

    @Test fun savingAffordanceBlocksRepeatedActionsUntilBarrierCompletes() = runTest {
        val barrier = CompletableDeferred<Boolean>()
        var actions = 0
        val gate = DraftSaveGate(this, {}, { barrier.await() }, {}, {})
        gate.run { actions++ }
        assertTrue(gate.saving)
        gate.run { actions += 100 }
        runCurrent()
        assertEquals(0, actions)
        barrier.complete(true)
        runCurrent()
        assertFalse(gate.saving)
        assertEquals(1, actions)
    }

    @Test fun successOnAnotherFieldNeverHidesEarlierSaveFailure() = runTest {
        val queue = SettingsWriteQueue(this) { }
        queue.enqueue("url") { error("cannot save URL") }
        queue.enqueue("model") { }
        assertFalse(queue.flush())
        queue.enqueue("url") { }
        assertTrue(queue.flush())
    }
}
