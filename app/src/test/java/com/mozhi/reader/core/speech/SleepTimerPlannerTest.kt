package com.mozhi.reader.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerPlannerTest {
    @Test
    fun `暂停时倒计时冻结`() {
        val state = SleepTimerPlanner.start(SleepTimerPlan.Minutes(15))
        assertEquals(state, SleepTimerPlanner.tick(state, 5_000, playing = false))
    }

    @Test
    fun `时间到期`() {
        val state = SleepTimerPlanner.tick(
            SleepTimerPlanner.start(SleepTimerPlan.Minutes(1)),
            60_000,
            playing = true
        )
        assertTrue(SleepTimerPlanner.isExpired(state))
    }

    @Test
    fun `跨章递减`() {
        val first = SleepTimerPlanner.start(SleepTimerPlan.Chapters(2))
        val second = SleepTimerPlanner.onChapterCompleted(first)
        assertFalse(SleepTimerPlanner.isExpired(second))
        assertTrue(SleepTimerPlanner.isExpired(SleepTimerPlanner.onChapterCompleted(second)))
    }
}
