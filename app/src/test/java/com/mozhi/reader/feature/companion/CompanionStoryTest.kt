package com.mozhi.reader.feature.companion

import com.mozhi.reader.core.database.dao.CompletedCompanionRound
import com.mozhi.reader.core.database.entity.ReadingDailyEntity
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CompanionStoryTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 9, 11)
    private fun at(day: LocalDate, hour: Int, minute: Int = 0) = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun round(id: Long, time: Long, bookId: Long? = 1, persona: Long? = 3, type: String = "COMPANION", conversation: Long = 1, key: String? = "r$id") =
        CompletedCompanionRound(id, conversation, time, bookId, type, "[]", key, personaId = persona)
    private val books = mapOf(1L to "雨夜里的灯塔", 2L to "远行笔记")
    private val personas = mapOf(3L to CompanionPersonaInfo("知秋", null), 4L to CompanionPersonaInfo("拾光", null))
    private fun story(rounds: List<CompletedCompanionRound>, memories: List<CompanionMemoryNote> = emptyList(),
        selection: CompanionStatsSelection = CompanionStatsSelection(), reading: List<ReadingDailyEntity> = emptyList()) =
        buildCompanionStory(rounds, memories, selection, books, personas, reading, today, zone)

    @Test fun sittingsSplitOnLongPausesRolesAndBooksAndMarkTheFirstMeetingOnce() {
        val day = today.minusDays(1)
        val rows = listOf(
            round(1, at(day, 21, 0)), round(2, at(day, 21, 20)), round(3, at(day, 21, 45)), // One sitting, 45 minutes.
            round(4, at(day, 23, 0)),                                                     // Pause > 30 minutes.
            round(5, at(day, 23, 5), persona = 4),                                        // Another role.
            round(6, at(day, 23, 8), bookId = 2, persona = 4),                            // Another book.
            round(7, at(day, 23, 9), key = "r6")                                          // A branch copy is not counted twice.
        )
        val result = story(rows).days.single()
        val sittings = result.events.filterIsInstance<CompanionStoryEvent.Session>()
        assertEquals(listOf(3, 1, 1, 1), sittings.map { it.rounds })
        assertEquals(at(day, 21, 45), sittings.first().endAt)
        assertEquals(listOf(true, false, false, true), sittings.map { it.firstMeeting })
        assertEquals(6, result.rounds)
        assertEquals(23 * 60 + 8, result.lastMinute)
        assertTrue(result.events.first() is CompanionStoryEvent.FirstWords)
    }

    @Test fun firstsAndMilestonesUseAllHistoryButOnlyThePeriodIsShown() {
        val old = today.minusDays(20)
        val rows = (1L..12L).map { round(it, at(if (it <= 9) old else today, 20, it.toInt())) }
        val week = story(rows, selection = CompanionStatsSelection(period = CompanionStatsPeriod.WEEK))
        assertEquals(listOf(today), week.days.map { it.date })
        val events = week.days.single().events
        assertTrue(events.none { it is CompanionStoryEvent.FirstWords })
        assertEquals(10, events.filterIsInstance<CompanionStoryEvent.Milestone>().single().count)
        assertFalse(events.filterIsInstance<CompanionStoryEvent.Session>().single().firstMeeting)
        val all = story(rows)
        assertEquals(listOf(today, old), all.days.map { it.date }) // Newest day first.
    }

    @Test fun memoriesFollowTheScopeOfTheirConversationAndBlankOnesAreSkipped() {
        val rows = listOf(round(1, at(today, 9), conversation = 1),
            round(2, at(today, 10), bookId = null, type = "LIBRARY_COMPANION", conversation = 2).copy(sourceBookIdsJson = "[2]"))
        val memories = listOf(CompanionMemoryNote(1, 3, 1, 1, "喜欢在雨夜读灯塔", at(today, 9, 30)),
            CompanionMemoryNote(2, 4, 2, null, "在找一本关于远行的书", at(today, 10, 30)),
            CompanionMemoryNote(3, 4, 2, null, "  ", at(today, 11)))
        val all = story(rows, memories).days.single().events
        assertEquals(listOf("喜欢在雨夜读灯塔", "在找一本关于远行的书"), all.filterIsInstance<CompanionStoryEvent.Memory>().map { it.summary })
        val book = story(rows, memories, CompanionStatsSelection(scope = CompanionStatsScope.BOOK))
        assertEquals(listOf(3L), book.days.single().events.filterIsInstance<CompanionStoryEvent.Memory>().map { it.personaId })
        val library = story(rows, memories, CompanionStatsSelection(scope = CompanionStatsScope.LIBRARY)).days.single()
        val sitting = library.events.filterIsInstance<CompanionStoryEvent.Session>().single()
        assertTrue(sitting.library)
        assertEquals(listOf(2L), sitting.bookIds)
        assertEquals(setOf(2L), story(rows, memories, CompanionStatsSelection(scope = CompanionStatsScope.LIBRARY)).bookTitles.keys)
    }

    @Test fun readingTimeCountsOnlyBooksTalkedAboutThatDayAndDeletedBooksAreNotNamed() {
        val rows = listOf(round(1, at(today, 20)), round(2, at(today, 22), bookId = 9))
        val reading = listOf(ReadingDailyEntity(1, today.toEpochDay(), 1_800_000, 0), ReadingDailyEntity(2, today.toEpochDay(), 600_000, 0),
            ReadingDailyEntity(1, today.minusDays(1).toEpochDay(), 900_000, 0))
        val result = story(rows, reading = reading)
        assertEquals(1_800_000L, result.days.single().readingMs)
        assertEquals(emptyList<Long>(), result.days.single().events.filterIsInstance<CompanionStoryEvent.Session>().last().bookIds)
        assertEquals(setOf(1L), result.bookTitles.keys)
    }

    @Test fun clockHelpersMergeWrappingHoursAndPreferTheLaterPeak() {
        val hours = MutableList(24) { 0 }.apply { this[23] = 2; this[0] = 1; this[1] = 2; this[9] = 1; this[10] = 1 }
        assertEquals(listOf(9 to 2, 23 to 3), companionHourArcs(hours))
        assertEquals(listOf(0 to 24), companionHourArcs(List(24) { 1 }))
        assertEquals(emptyList<Pair<Int, Int>>(), companionHourArcs(List(24) { 0 }))
        assertEquals(23, peakCompanionHour(hours))
        assertNull(peakCompanionHour(List(24) { 0 }))
        assertEquals(CompanionDaypart.LATE_NIGHT, CompanionDaypart.of(0))
        assertEquals(CompanionDaypart.NIGHT, CompanionDaypart.of(23))
        assertEquals(CompanionDaypart.AFTERNOON, CompanionDaypart.of(12))
    }

    @Test fun hourlyDistributionFollowsTheSelectedPeriod() {
        val rows = listOf(round(1, at(today, 23)), round(2, at(today, 23, 30)), round(3, at(today.minusDays(9), 8)))
        val stats = buildCompanionStatistics(rows, emptyList(), CompanionStatsSelection(period = CompanionStatsPeriod.WEEK), today, zone)
        assertEquals(2, stats.roundsByHour[23])
        assertEquals(0, stats.roundsByHour[8])
    }
}
