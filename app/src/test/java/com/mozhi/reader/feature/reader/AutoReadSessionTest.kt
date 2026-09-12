package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.datastore.AutoReadSettings
import com.mozhi.reader.core.datastore.PageMode
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutoReadSessionTest {
    @Test fun waitsForChosenLayoutAndNeverAutomaticallyResumes() {
        val session = AutoReadSession()
        session.start(AutoReadSettings(mode = PageMode.SCROLL))
        session.onReady(PageMode.PAGINATED)
        assertFalse(session.running)
        session.onReady(PageMode.SCROLL)
        assertTrue(session.running)
        val token = session.generation
        session.pause(AutoReadPauseReason.BACKGROUND)
        session.onReady(PageMode.SCROLL)
        assertFalse(session.owns(token))
        assertEquals(AutoReadPhase.PAUSED, session.phase)
        session.start(session.settings)
        session.onReady(PageMode.SCROLL)
        assertTrue(session.running)
        assertFalse(session.owns(token))
    }

    @Test fun everyInterruptionInvalidatesPendingTurnsIncludingPreparation() {
        AutoReadPauseReason.entries.forEach { reason ->
            val session = AutoReadSession()
            session.start(AutoReadSettings())
            val token = session.generation
            session.pause(reason)
            session.onReady(PageMode.SCROLL)
            assertFalse(session.owns(token))
            assertEquals(reason, session.reason)
            session.stop()
            assertEquals(AutoReadPhase.OFF, session.phase)
        }
    }

    @Test fun speedAndClockAreBoundedWithNoCatchUpAfterStalls() {
        assertEquals(24f, AutoReadSettings(scrollDpPerSecond = Float.NaN).normalized().scrollDpPerSecond, 0f)
        assertEquals(120, AutoReadSettings(pageIntervalSeconds = Int.MAX_VALUE).normalized().pageIntervalSeconds)
        val clock = AutoReadFrameClock()
        assertEquals(0f, clock.distanceDp(0, 24f), 0f)
        assertEquals(1.2f, clock.distanceDp(50_000_000, 24f), 0.0001f)
        assertEquals(2.4f, clock.distanceDp(60_000_000_000, 24f), 0.0001f)
        assertEquals(0f, clock.distanceDp(10, 24f), 0f)
    }

    @Test fun timedReadingWaitsForContentAndTouchCancelsPendingWait() = runTest {
        val session = AutoReadSession().apply {
            start(AutoReadSettings(mode = PageMode.PAGINATED, pageIntervalSeconds = 3))
            onReady(PageMode.PAGINATED)
        }
        var ready = false
        var nextReady = false
        var turns = 0
        val job = launch { runAutoReadPaging(session, { ready }, { true }, { nextReady }) { turns++; ReaderTurnResult.COMMITTED } }
        advanceTimeBy(20_000)
        assertEquals(0, turns)
        ready = true
        advanceTimeBy(4_000)
        assertEquals(0, turns)
        nextReady = true
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, turns)
        session.pause(AutoReadPauseReason.TOUCH)
        advanceTimeBy(30_000)
        assertEquals(1, turns)
        job.join()
    }

    @Test fun endAndSupersedingNavigationStopWithoutAnotherTurn() = runTest {
        for (end in listOf(true, false)) {
            val session = AutoReadSession().apply {
                start(AutoReadSettings(mode = PageMode.PAGINATED, pageIntervalSeconds = 3))
                onReady(PageMode.PAGINATED)
            }
            var turns = 0
            val job = launch { runAutoReadPaging(session, { true }, { !end }, { true }) { turns++; ReaderTurnResult.CANCELLED } }
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(if (end) 0 else 1, turns)
            assertEquals(if (end) AutoReadPauseReason.END else AutoReadPauseReason.NAVIGATION, session.reason)
            job.join()
        }
    }

    @Test fun failedLoadingPausesInsteadOfPollingForever() = runTest {
        val session = AutoReadSession().apply {
            start(AutoReadSettings(mode = PageMode.PAGINATED, pageIntervalSeconds = 3))
            onReady(PageMode.PAGINATED)
        }
        val job = launch { runAutoReadPaging(session, { true }, { true }, { false }) { error("unloaded page cannot turn") } }
        advanceTimeBy(34_000)
        runCurrent()
        assertEquals(AutoReadPauseReason.ERROR, session.reason)
        job.join()
    }
}
