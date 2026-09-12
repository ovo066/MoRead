package com.mozhi.reader.feature.companion

import com.mozhi.reader.ai.companion.LibraryBookScope
import com.mozhi.reader.ai.companion.LibraryBookScopes
import com.mozhi.reader.core.database.dao.CompanionUsageRow
import com.mozhi.reader.core.database.dao.CompletedCompanionRound
import com.mozhi.reader.core.database.dao.CompanionWordsRow
import com.mozhi.reader.core.database.entity.ReadingDailyEntity
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CompanionStatisticsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 9, 11)
    private fun time(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun round(id: Long, day: LocalDate = today, type: String = "COMPANION", key: String? = "r$id") = CompletedCompanionRound(id, id, time(day), 1, type, "[]", key)

    @Test fun periodIncludesLocalMidnightAndDeduplicatesBranchesBeforeDateFiltering() {
        val rows = listOf(round(1, today.minusDays(6)), round(2, today.minusDays(7)), round(3), round(4, today.plusDays(1)), round(5, key = "r2"))
        val stats = buildCompanionStatistics(rows, emptyList(), CompanionStatsSelection(period = CompanionStatsPeriod.WEEK), today, zone)
        assertEquals(2, stats.rounds)
        assertEquals(2, stats.activeDays)
        assertEquals(1, stats.books)
        assertNull(stats.knownTokens)
    }

    @Test fun bookAndLibraryConversationsHaveSeparateCountsAndUnknownUsageIsNotZero() {
        val scopes = LibraryBookScopes.encode(listOf(LibraryBookScope(1, "甲", "a".repeat(64), 0, 10), LibraryBookScope(2, "乙", "b".repeat(64), 2, 5)))
        val rows = listOf(round(1), round(2, type = "LIBRARY_COMPANION").copy(bookId = null, bookScopesJson = scopes), round(3, type = "SELECTION"))
        val usage = listOf(CompanionUsageRow(1, "r1", time(today), "COMPANION", null), CompanionUsageRow(2, "r2", time(today), "LIBRARY_COMPANION", 300), CompanionUsageRow(3, "r2", time(today), "LIBRARY_COMPANION", 300))
        val stats = buildCompanionStatistics(rows, usage, CompanionStatsSelection(), today, zone)
        assertEquals(2, stats.rounds)
        assertEquals(2, stats.books)
        assertEquals(300L, stats.knownTokens)
        assertEquals(1, stats.unknownUsageReplies)
        val book = buildCompanionStatistics(rows, usage, CompanionStatsSelection(scope = CompanionStatsScope.BOOK), today, zone)
        assertEquals(1, book.rounds)
        assertNull(book.knownTokens)
        val library = buildCompanionStatistics(rows, usage, CompanionStatsSelection(scope = CompanionStatsScope.LIBRARY), today, zone)
        assertEquals(1, library.rounds)
        assertEquals(2, library.books)
    }

    @Test fun legacyRowsRemainExplicitAndMalformedBookScopesDoNotInventAssociations() {
        val rows = listOf(round(1, key = null), round(2, type = "LIBRARY_COMPANION", key = null).copy(bookId = null, bookScopesJson = "broken"))
        val stats = buildCompanionStatistics(rows, emptyList(), CompanionStatsSelection(), today, zone)
        assertEquals(2, stats.rounds)
        assertEquals(2, stats.legacyRounds)
        assertEquals(1, stats.books)
        assertNull(stats.knownTokens)
    }

    @Test fun knownEmptyTurnDoesNotInheritBooksDiscoveredByALaterMessage() {
        val scopes = LibraryBookScopes.encode(listOf(LibraryBookScope(2, "乙", "b".repeat(64), 2, 5)))
        val chat = round(1, type = "LIBRARY_COMPANION").copy(bookId = null, bookScopesJson = scopes)
        fun count(source: String?) = buildCompanionStatistics(listOf(chat.copy(sourceBookIdsJson = source)), emptyList(), CompanionStatsSelection(), today, zone).books
        assertEquals(1, count(null)) // Only legacy unknown associations fall back to conversation metadata.
        assertEquals(0, count("[]"))
        assertEquals(1, count("[2]"))
        assertEquals(0, count("broken"))
        assertEquals(0, count("[-2]"))
        assertEquals(0, count("[1,2,3,4,5,6,7,8,9]"))
    }

    @Test fun readingTimeUsesOnlyAssociatedBooksAndSelectedLocalDates() {
        val rows = listOf(round(1, today.minusDays(3)), round(2, today.minusDays(15)).copy(bookId = 2))
        fun reading(bookId: Long, day: LocalDate, ms: Long) = ReadingDailyEntity(bookId, day.toEpochDay(), ms, time(day))
        val reading = listOf(reading(1, today, 3_600_000), reading(1, today.minusDays(6), 600_000),
            reading(1, today.minusDays(7), 1_000), reading(1, today.plusDays(1), 4_000),
            reading(2, today, 7_000), reading(3, today, 9_000), reading(1, today.minusDays(1), -5))
        val stats = buildCompanionStatistics(rows, emptyList(), CompanionStatsSelection(period = CompanionStatsPeriod.WEEK), today, zone, readingDays = reading)
        assertEquals(1, stats.books)
        assertEquals(4_200_000L, stats.readingDurationMs)
        assertEquals(today.minusDays(15), stats.firstChatDate) // First companionship date is not limited to this week.
        assertEquals(0L, buildCompanionStatistics(emptyList(), emptyList(), CompanionStatsSelection(), today, zone, readingDays = reading).readingDurationMs)
    }

    @Test fun chatCharactersDeduplicateBranchesBeforeTheDateFilterWithoutMergingUserAndAssistant() {
        val words = listOf(
            CompanionWordsRow(1, "legacy-reply", time(today.minusDays(6)), "COMPANION", 8, "user"),
            CompanionWordsRow(2, "legacy-reply", time(today.minusDays(6)), "COMPANION", 20, "assistant"),
            CompanionWordsRow(3, "legacy-reply", time(today), "COMPANION", 8, "user"),
            CompanionWordsRow(4, "legacy-reply", time(today), "COMPANION", 20, "assistant"),
            CompanionWordsRow(5, "old", time(today.minusDays(7)), "COMPANION", 30),
            CompanionWordsRow(6, "old", time(today), "COMPANION", 30),
            CompanionWordsRow(7, "s", time(today), "SELECTION", 999),
            CompanionWordsRow(8, "library", time(today), "LIBRARY_COMPANION", 12),
            CompanionWordsRow(9, "future", time(today.plusDays(1)), "COMPANION", 99),
            CompanionWordsRow(10, null, time(today), "COMPANION", -3)
        )
        val selection = CompanionStatsSelection(period = CompanionStatsPeriod.WEEK)
        val all = buildCompanionStatistics(emptyList(), emptyList(), selection, today, zone, words = words)
        val book = buildCompanionStatistics(emptyList(), emptyList(), selection.copy(scope = CompanionStatsScope.BOOK), today, zone, words = words)
        assertEquals(40L, all.chatCharacters)
        assertEquals(28L, book.chatCharacters)
    }

    @Test fun permanentBookDeletionRemovesItsCountWithoutErasingACrossBookConversation() {
        val chat = round(1, type = "LIBRARY_COMPANION").copy(bookId = null, sourceBookIdsJson = "[1,2]")
        val retained = buildCompanionStatistics(listOf(chat), emptyList(), CompanionStatsSelection(), today, zone, retainedBookIds = setOf(1, 2))
        val deleted = buildCompanionStatistics(listOf(chat), emptyList(), CompanionStatsSelection(), today, zone, retainedBookIds = setOf(2))
        assertEquals(2, retained.books)
        assertEquals(1, deleted.books)
        assertEquals(1, deleted.rounds)
        assertEquals(1, deleted.conversations)
    }

    @Test fun readingDurationUsesCompactChineseUnitsWithoutInventingTime() {
        assertEquals("0分钟", formatCompanionReadingDuration(-1))
        assertEquals("9分钟", formatCompanionReadingDuration(9 * 60_000L))
        assertEquals("7时36分", formatCompanionReadingDuration(27_360_000))
    }
}
