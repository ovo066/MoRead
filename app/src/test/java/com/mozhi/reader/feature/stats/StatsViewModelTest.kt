package com.mozhi.reader.feature.stats

import com.mozhi.reader.core.database.entity.ReadingDailyEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsViewModelTest {
    @Test fun coverCalendarKeepsTheWholeAnchorMonthInEveryStatsDimension() {
        val today = LocalDate.of(2026, 9, 13)
        val book = com.mozhi.reader.core.database.entity.BookEntity(id = 1, title = "书", author = "", coverPath = null,
            epubPath = "", sourceType = com.mozhi.reader.core.database.entity.BookSourceType.TXT, importedAt = 1, totalChapters = 1)
        val days = listOf(readingDay(today.withDayOfMonth(1), 60_000), readingDay(today, 120_000),
            readingDay(today.withDayOfMonth(1).minusDays(1), 240_000), readingDay(today.minusYears(1), 480_000))
        StatsPeriod.entries.forEach { period ->
            val state = buildStatsState(days, listOf(book), 0, 0, StatsSelection(period, today), today)
            assertEquals(listOf(today.toEpochDay(), today.withDayOfMonth(1).toEpochDay()), state.monthTimeline.map { it.epochDay })
            assertEquals(180_000L, state.monthTimeline.sumOf { it.durationMs })
        }
        val empty = buildStatsState(days, listOf(book), 0, 0, StatsSelection(StatsPeriod.TOTAL, today.minusMonths(2)), today)
        assertTrue(empty.monthTimeline.isEmpty())
        assertEquals(900_000L, empty.periodDurationMs)
    }

    @Test fun weeklyTimelineConnectsOnlyActualReadingDaysAndKeepsBookRowsStable() {
        val monday = LocalDate.of(2025, 12, 29)
        val book = com.mozhi.reader.core.database.entity.BookEntity(id = 1, title = "第一本", author = "", coverPath = null,
            epubPath = "", sourceType = com.mozhi.reader.core.database.entity.BookSourceType.TXT, importedAt = 1, totalChapters = 1)
        val days = listOf(StatsTimelineDay(monday.toEpochDay(), listOf(PeriodBookStat(book, 60_000))),
            StatsTimelineDay(monday.plusDays(1).toEpochDay(), listOf(PeriodBookStat(book.copy(id = 2), 80_000), PeriodBookStat(book, 120_000))),
            StatsTimelineDay(monday.plusDays(3).toEpochDay(), listOf(PeriodBookStat(book, 180_000))),
            StatsTimelineDay(monday.plusDays(7).toEpochDay(), listOf(PeriodBookStat(book, 20_000))))
        val weeks = timelineWeeks(days)
        assertEquals(listOf(monday.plusDays(7).toEpochDay(), monday.toEpochDay()), weeks.map { it.startEpochDay })
        val readings = weeks.last().books
        assertEquals(listOf(1L, 2L), readings.map { it.book.id })
        assertEquals(listOf(60_000L, 120_000L, 0L, 180_000L, 0L, 0L, 0L), readings.first().dailyDurations)
        assertEquals(listOf(0..1, 3..3), readingSpans(readings.first().dailyDurations))
        assertEquals(listOf(1..1), readingSpans(readings.last().dailyDurations))
        assertTrue(readingSpans(List(7) { 0L }).isEmpty())
        assertEquals(listOf(0..6), readingSpans(List(7) { 1L }))
        assertEquals(days.sumOf { it.durationMs }, weeks.sumOf { week -> week.books.sumOf { it.dailyDurations.sum() } })
    }

    @Test fun totalIncludesAllYearsWithoutAComparisonOrFutureNavigation() {
        val date = LocalDate.of(2026, 9, 13)
        val days = listOf(readingDay(LocalDate.of(2024, 1, 1), 60_000), readingDay(LocalDate.of(2025, 2, 1), 120_000), readingDay(date, 180_000))
        val state = buildStatsState(days, emptyList(), 0, 0, StatsSelection(StatsPeriod.TOTAL, date), date)
        assertEquals(360_000L, state.periodDurationMs)
        assertEquals(3, state.periodReadingDays)
        assertEquals(0L, state.previousPeriodDurationMs)
        assertFalse(state.canGoNext)
        assertEquals("全部阅读记录", state.periodLabel)
        assertEquals(listOf("2024", "2025", "2026"), state.trend.map { it.label })
        assertEquals(state.periodDurationMs, state.trend.sumOf { it.durationMs })
    }
    @Test fun periodViewsUseActualHoursAndTagWeightsIncludeRetainedBooks() {
        val today = LocalDate.of(2026, 9, 13)
        val first = com.mozhi.reader.core.database.entity.BookEntity(id = 1, title = "第一本", author = "同一作者", coverPath = null,
            epubPath = "", sourceType = com.mozhi.reader.core.database.entity.BookSourceType.TXT, importedAt = 1, totalChapters = 1)
        val second = first.copy(id = 2, title = "保留的记录", removedAt = 1)
        val days = listOf(ReadingDailyEntity(1, today.toEpochDay(), 30 * 60_000L, 1),
            ReadingDailyEntity(2, today.minusDays(1).toEpochDay(), 60 * 60_000L, 1),
            ReadingDailyEntity(1, today.minusMonths(1).toEpochDay(), 120 * 60_000L, 1))
        val tag = com.mozhi.reader.core.database.entity.BookTagEntity(1, "文学", "blue", createdAt = 1)
        val refs = listOf(com.mozhi.reader.core.database.entity.BookTagRefEntity(1, 1), com.mozhi.reader.core.database.entity.BookTagRefEntity(2, 1))
        val hours = listOf(com.mozhi.reader.core.database.entity.ReadingHourlyEntity(1, today.toEpochDay(), 22, 30 * 60_000L))
        val state = buildStatsState(days, listOf(first, second), 0, 0, StatsSelection(StatsPeriod.MONTH, today), today, hours, listOf(tag), refs)
        assertEquals(90 * 60_000L, state.periodDurationMs)
        assertEquals(30 * 60_000L, state.hourlyDurations.sum())
        assertEquals(30 * 60_000L, state.timeBands.last().durationMs)
        assertEquals(2, state.tags.single().bookCount)
        assertEquals(90 * 60_000L, state.tags.single().durationMs)
        assertEquals(2, state.authors.single().bookCount)
        assertEquals(listOf(today.toEpochDay(), today.minusDays(1).toEpochDay()), state.timeline.map { it.epochDay })
        assertEquals(30, state.trend.size)
        assertEquals(90 * 60_000L, state.trend.sumOf { it.durationMs })
    }

    @Test fun oldDailyRecordsDoNotBecomeFabricatedHourlyReadings() {
        val date = LocalDate.of(2026, 9, 13)
        val state = buildStatsState(listOf(readingDay(date, 60_000)), emptyList(), 0, 0, StatsSelection(StatsPeriod.DAY, date), date)
        assertEquals(60_000L, state.periodDurationMs)
        assertEquals(0L, state.hourlyDurations.sum())
        assertEquals(24, state.trend.size)
        assertTrue(statsPeriodLabel(StatsPeriod.WEEK, LocalDate.of(2026, 1, 1)).contains("2025"))
        assertTrue(statsPeriodLabel(StatsPeriod.WEEK, LocalDate.of(2026, 1, 1)).contains("2026"))
    }
    @Test fun finishedCountUsesActualCompletionAndIncludesRetainedHistory() {
        val book = com.mozhi.reader.core.database.entity.BookEntity(id = 1, title = "书", author = "", coverPath = null,
            epubPath = "/book", sourceType = com.mozhi.reader.core.database.entity.BookSourceType.EPUB,
            importedAt = 1, totalChapters = 3, lastReadAt = 1, lastReadChapterIndex = 2)
        val books = listOf(book, book.copy(id = 2, reachedEnd = true, removedAt = 1),
            book.copy(id = 3, manualReadState = "FINISHED"), book.copy(id = 4, reachedEnd = true, manualReadState = "UNREAD"))
        val date = LocalDate.of(2026, 9, 11)
        val state = buildStatsState(listOf(ReadingDailyEntity(2, date.toEpochDay(), 60_000, 1)), books,
            0, 0, StatsSelection(StatsPeriod.DAY, date), date)
        assertEquals(2, state.finishedBooks)
        assertEquals(60_000L, state.periodDurationMs)
        assertEquals(2L, state.topBooks.single().book.id)
    }
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
