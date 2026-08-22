package com.mozhi.reader.feature.stats

import com.mozhi.reader.core.database.entity.ReadingDailyEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsViewModelTest {
    @Test
    fun `ranges use exact day week month and year boundaries`() {
        val leapDay = LocalDate.of(2024, 2, 29)

        val day = statsPeriodRange(StatsPeriod.DAY, leapDay)
        val week = statsPeriodRange(StatsPeriod.WEEK, leapDay)
        val month = statsPeriodRange(StatsPeriod.MONTH, leapDay)
        val year = statsPeriodRange(StatsPeriod.YEAR, leapDay)

        assertEquals(LocalDate.of(2024, 2, 29).toEpochDay(), day.startEpochDay)
        assertEquals(LocalDate.of(2024, 3, 1).toEpochDay(), day.endEpochDayExclusive)
        assertEquals(LocalDate.of(2024, 2, 26).toEpochDay(), week.startEpochDay)
        assertEquals(LocalDate.of(2024, 3, 4).toEpochDay(), week.endEpochDayExclusive)
        assertEquals(LocalDate.of(2024, 2, 1).toEpochDay(), month.startEpochDay)
        assertEquals(LocalDate.of(2024, 3, 1).toEpochDay(), month.endEpochDayExclusive)
        assertEquals(LocalDate.of(2024, 1, 1).toEpochDay(), year.startEpochDay)
        assertEquals(LocalDate.of(2025, 1, 1).toEpochDay(), year.endEpochDayExclusive)
    }

    @Test
    fun `aggregation changes precision and compares the previous period`() {
        val today = LocalDate.of(2026, 8, 10)
        val days = listOf(
            readingDay(LocalDate.of(2025, 8, 10), 1_000),
            readingDay(LocalDate.of(2026, 7, 10), 2_000),
            readingDay(LocalDate.of(2026, 8, 9), 3_000),
            readingDay(LocalDate.of(2026, 8, 10), 4_000),
            readingDay(LocalDate.of(2026, 8, 10), 500)
        )

        val dayState = buildStatsState(
            days, emptyList(), 0, 0, StatsSelection(StatsPeriod.DAY, today), today
        )
        val monthState = buildStatsState(
            days, emptyList(), 0, 0, StatsSelection(StatsPeriod.MONTH, today), today
        )
        val yearState = buildStatsState(
            days, emptyList(), 0, 0, StatsSelection(StatsPeriod.YEAR, today), today
        )

        assertEquals(4_500, dayState.periodDurationMs)
        assertEquals(3_000, dayState.previousPeriodDurationMs)
        assertEquals(7_500, monthState.periodDurationMs)
        assertEquals(2_000, monthState.previousPeriodDurationMs)
        assertEquals(9_500, yearState.periodDurationMs)
        assertEquals(1_000, yearState.previousPeriodDurationMs)
        assertEquals(2, monthState.periodReadingDays)
        assertFalse(monthState.canGoNext)

        val oldMonth = buildStatsState(
            days,
            emptyList(),
            0,
            0,
            StatsSelection(StatsPeriod.MONTH, LocalDate.of(2026, 7, 10)),
            today
        )
        assertTrue(oldMonth.canGoNext)
    }

    private fun readingDay(date: LocalDate, durationMs: Long) = ReadingDailyEntity(
        bookId = 1,
        epochDay = date.toEpochDay(),
        durationMs = durationMs,
        lastReadAt = 0
    )
}
