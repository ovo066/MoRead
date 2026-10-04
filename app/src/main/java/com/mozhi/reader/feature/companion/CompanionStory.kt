package com.mozhi.reader.feature.companion

import com.mozhi.reader.ai.companion.LibraryBookScopes
import com.mozhi.reader.core.database.dao.CompletedCompanionRound
import com.mozhi.reader.core.database.entity.ReadingDailyEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class CompanionPersonaInfo(val name: String, val avatarPath: String?)

/** A consolidated long-term memory, reduced to what the timeline shows. */
data class CompanionMemoryNote(
    val id: Long, val personaId: Long, val conversationId: Long, val bookId: Long?,
    val summary: String, val createdAt: Long
)

/** One line of the companion timeline. [at] is epoch millis. */
sealed interface CompanionStoryEvent {
    val at: Long

    /** The very first completed exchange in the selected scope. */
    data class FirstWords(override val at: Long, val personaId: Long?) : CompanionStoryEvent

    /** A sitting: consecutive exchanges with one role about the same book(s), at most [COMPANION_SESSION_GAP_MS] apart. */
    data class Session(
        override val at: Long, val endAt: Long, val rounds: Int, val personaId: Long?,
        val bookIds: List<Long>, val library: Boolean,
        /** At least one of [bookIds] is talked about here for the first time. */
        val firstMeeting: Boolean
    ) : CompanionStoryEvent

    data class Memory(override val at: Long, val personaId: Long, val summary: String) : CompanionStoryEvent

    data class Milestone(override val at: Long, val count: Int) : CompanionStoryEvent
}

data class CompanionStoryDay(
    val date: LocalDate,
    val rounds: Int,
    /** Reading time that day on the books talked about that day. */
    val readingMs: Long,
    val roundsByHour: List<Int>,
    /** Minute of the day (0 until 1440) of the latest event, where the node's clock hand points. */
    val lastMinute: Int,
    val events: List<CompanionStoryEvent>
)

data class CompanionStory(
    /** Newest day first; events inside a day are chronological. */
    val days: List<CompanionStoryDay> = emptyList(),
    val personas: Map<Long, CompanionPersonaInfo> = emptyMap(),
    val bookTitles: Map<Long, String> = emptyMap()
)

internal const val COMPANION_SESSION_GAP_MS = 30 * 60_000L
internal val COMPANION_MILESTONES = listOf(10, 50, 100, 200, 500, 1000, 2000, 5000)

/**
 * Turns completed rounds and consolidated memories into a narrative timeline. Firsts and milestones are
 * counted over all history in the selected scope, then only the events inside the period are shown.
 * [books] holds the titles of books whose records are retained; permanently deleted books are not named.
 */
internal fun buildCompanionStory(
    rounds: List<CompletedCompanionRound>,
    memories: List<CompanionMemoryNote>,
    selection: CompanionStatsSelection,
    books: Map<Long, String>,
    personas: Map<Long, CompanionPersonaInfo>,
    readingDays: List<ReadingDailyEntity> = emptyList(),
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault()
): CompanionStory {
    fun date(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    fun inScope(type: String) = when (selection.scope) {
        CompanionStatsScope.ALL -> type == "COMPANION" || type == LibraryBookScopes.CONVERSATION_TYPE
        CompanionStatsScope.BOOK -> type == "COMPANION"
        CompanionStatsScope.LIBRARY -> type == LibraryBookScopes.CONVERSATION_TYPE
    }
    fun inPeriod(day: LocalDate) = !day.isAfter(today) && (selection.period.days?.let { !day.isBefore(today.minusDays(it - 1)) } ?: true)

    // Same branch deduplication as the headline numbers, so the story never counts a copied round twice.
    val deduplicated = rounds.sortedWith(compareBy<CompletedCompanionRound> { it.createdAt }.thenBy { it.id })
        .distinctBy { it.replyRoundId?.takeIf(String::isNotBlank) ?: "legacy:${it.id}" }
    val conversationTypes = deduplicated.associate { it.conversationId to it.type }
    val ordered = deduplicated.filter { inScope(it.type) && !date(it.createdAt).isAfter(today) }

    val events = mutableListOf<CompanionStoryEvent>()
    ordered.firstOrNull()?.let { events += CompanionStoryEvent.FirstWords(it.createdAt, it.personaId) }
    COMPANION_MILESTONES.forEach { count -> ordered.getOrNull(count - 1)?.let { events += CompanionStoryEvent.Milestone(it.createdAt, count) } }

    val seenBooks = mutableSetOf<Long>()
    var session: CompanionStoryEvent.Session? = null
    for (row in ordered) {
        val rowBooks = companionRoundBooks(row).filter { it in books }.distinct()
        val library = row.type == LibraryBookScopes.CONVERSATION_TYPE
        val firstMeeting = rowBooks.any { it !in seenBooks }
        seenBooks += rowBooks
        val open = session
        session = if (open != null && date(open.at) == date(row.createdAt) && row.createdAt - open.endAt <= COMPANION_SESSION_GAP_MS &&
            open.personaId == row.personaId && open.library == library && (library || open.bookIds == rowBooks)) {
            open.copy(endAt = row.createdAt, rounds = open.rounds + 1, bookIds = (open.bookIds + rowBooks).distinct(),
                firstMeeting = open.firstMeeting || firstMeeting)
        } else {
            open?.let(events::add)
            CompanionStoryEvent.Session(row.createdAt, row.createdAt, 1, row.personaId, rowBooks, library, firstMeeting)
        }
    }
    session?.let(events::add)

    memories.filter { memory ->
        memory.summary.isNotBlank() && !date(memory.createdAt).isAfter(today) &&
            (selection.scope == CompanionStatsScope.ALL || conversationTypes[memory.conversationId]?.let(::inScope) == true)
    }.forEach { events += CompanionStoryEvent.Memory(it.createdAt, it.personaId, it.summary.trim()) }

    val order = listOf(CompanionStoryEvent.FirstWords::class, CompanionStoryEvent.Session::class,
        CompanionStoryEvent.Milestone::class, CompanionStoryEvent.Memory::class)
    val roundsByDay = ordered.groupBy { date(it.createdAt) }
    val days = events.filter { inPeriod(date(it.at)) }.groupBy { date(it.at) }.map { (day, dayEvents) ->
        val sorted = dayEvents.sortedWith(compareBy<CompanionStoryEvent> { it.at }.thenBy { order.indexOf(it::class) })
        val dayRounds = roundsByDay[day].orEmpty()
        val dayBooks = sorted.filterIsInstance<CompanionStoryEvent.Session>().flatMap { it.bookIds }.toSet()
        val latest = Instant.ofEpochMilli(sorted.maxOf { it.at }).atZone(zone)
        CompanionStoryDay(
            date = day, rounds = dayRounds.size,
            readingMs = readingDays.filter { it.epochDay == day.toEpochDay() && it.bookId in dayBooks }.sumOf { it.durationMs.coerceAtLeast(0L) },
            roundsByHour = IntArray(24).also { hours -> dayRounds.forEach { hours[Instant.ofEpochMilli(it.createdAt).atZone(zone).hour]++ } }.toList(),
            lastMinute = latest.hour * 60 + latest.minute,
            events = sorted
        )
    }.sortedByDescending { it.date }

    val usedPersonas = days.flatMap { it.events }.mapNotNull {
        when (it) {
            is CompanionStoryEvent.FirstWords -> it.personaId
            is CompanionStoryEvent.Session -> it.personaId
            is CompanionStoryEvent.Memory -> it.personaId
            is CompanionStoryEvent.Milestone -> null
        }
    }.toSet()
    val usedBooks = days.flatMap { it.events }.filterIsInstance<CompanionStoryEvent.Session>().flatMap { it.bookIds }.toSet()
    return CompanionStory(days, personas.filterKeys { it in usedPersonas }, books.filterKeys { it in usedBooks })
}

/** Time-of-day buckets for the companion clock; the boundaries follow common Chinese usage. */
enum class CompanionDaypart(val hours: IntRange) {
    LATE_NIGHT(0..4), EARLY_MORNING(5..7), MORNING(8..11), AFTERNOON(12..17), EVENING(18..20), NIGHT(21..23);

    companion object {
        fun of(hour: Int): CompanionDaypart = entries.first { Math.floorMod(hour, 24) in it.hours }
    }
}

/** The busiest hour; ties go to the later hour, which matches how readers describe "we chat at night". */
internal fun peakCompanionHour(hourly: List<Int>): Int? =
    hourly.withIndex().filter { it.value > 0 }.maxWithOrNull(compareBy<IndexedValue<Int>> { it.value }.thenBy { it.index })?.index

/**
 * Consecutive active hours merged into clock arcs (start hour, hour count). An arc may wrap past
 * midnight, so a 23:00–01:00 sitting is drawn as one stroke rather than two.
 */
internal fun companionHourArcs(hourly: List<Int>): List<Pair<Int, Int>> {
    val active = BooleanArray(24) { (hourly.getOrNull(it) ?: 0) > 0 }
    if (active.none { it }) return emptyList()
    if (active.all { it }) return listOf(0 to 24)
    val start = (0 until 24).first { !active[it] } // Begin scanning at a gap so wrapping arcs stay whole.
    val arcs = mutableListOf<Pair<Int, Int>>()
    var runStart = -1
    for (step in 1..24) {
        val hour = (start + step) % 24
        if (active[hour] && runStart < 0) runStart = hour
        if (!active[hour] && runStart >= 0) {
            arcs += runStart to Math.floorMod(hour - runStart, 24)
            runStart = -1
        }
    }
    return arcs.sortedBy { it.first }
}
