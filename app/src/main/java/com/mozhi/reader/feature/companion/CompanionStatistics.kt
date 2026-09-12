package com.mozhi.reader.feature.companion

import com.mozhi.reader.ai.companion.LibraryBookScopes
import com.mozhi.reader.core.database.dao.CompanionUsageRow
import com.mozhi.reader.core.database.dao.CompletedCompanionRound
import com.mozhi.reader.core.database.dao.CompanionWordsRow
import com.mozhi.reader.core.database.entity.ReadingDailyEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class CompanionStatsScope(val label: String) { ALL("全部伴读"), BOOK("书内伴读"), LIBRARY("书库伴读") }
enum class CompanionStatsPeriod(val label: String, val days: Long?) { WEEK("近 7 天", 7), MONTH("近 30 天", 30), ALL("全部", null) }
data class CompanionStatsSelection(val scope: CompanionStatsScope = CompanionStatsScope.ALL, val period: CompanionStatsPeriod = CompanionStatsPeriod.ALL)
data class CompanionStatistics(
    val rounds: Int = 0,
    val activeDays: Int = 0,
    val books: Int = 0,
    val conversations: Int = 0,
    val knownTokens: Long? = null,
    val unknownUsageReplies: Int = 0,
    val roundsByDay: Map<LocalDate, Int> = emptyMap(),
    val legacyRounds: Int = 0,
    val firstChatDate: LocalDate? = null,
    val chatCharacters: Long = 0L,
    val readingDurationMs: Long = 0L
)

internal fun buildCompanionStatistics(
    rounds: List<CompletedCompanionRound>,
    usage: List<CompanionUsageRow>,
    selection: CompanionStatsSelection,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    words: List<CompanionWordsRow> = emptyList(),
    readingDays: List<ReadingDailyEntity> = emptyList(),
    retainedBookIds: Set<Long>? = null
): CompanionStatistics {
    fun date(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    fun includes(type: String, millis: Long): Boolean {
        if (type !in setOf("COMPANION", LibraryBookScopes.CONVERSATION_TYPE)) return false
        val rightType = when (selection.scope) {
            CompanionStatsScope.ALL -> true
            CompanionStatsScope.BOOK -> type == "COMPANION"
            CompanionStatsScope.LIBRARY -> type == LibraryBookScopes.CONVERSATION_TYPE
        }
        val day = date(millis)
        return rightType && !day.isAfter(today) && (selection.period.days?.let { !day.isBefore(today.minusDays(it - 1)) } ?: true)
    }
    // Branch copies retain assistant round identities. Deduplicate before applying the date filter.
    val deduplicated = rounds.sortedWith(compareBy<CompletedCompanionRound> { it.createdAt }.thenBy { it.id })
        .distinctBy { it.replyRoundId?.takeIf(String::isNotBlank) ?: "legacy:${it.id}" }
    val unique = deduplicated.filter { includes(it.type, it.createdAt) }
    val tokens = usage.sortedWith(compareBy<CompanionUsageRow> { it.createdAt }.thenBy { it.id })
        .distinctBy { it.roundId?.takeIf(String::isNotBlank) ?: "legacy:${it.id}" }
        .filter { includes(it.type, it.createdAt) }
    val bookIds = unique.flatMap { row ->
        row.bookId?.let(::listOf) ?: runCatching {
            row.sourceBookIdsJson?.let { json ->
                LibraryBookScopes.decodeTurnBooks(json)
            } ?: LibraryBookScopes.decode(row.bookScopesJson).map { it.bookId }
        }.getOrDefault(emptyList())
    }.toSet().let { ids -> retainedBookIds?.let(ids::intersect) ?: ids }
    val byDay = unique.groupingBy { date(it.createdAt) }.eachCount().toSortedMap()
    val known = tokens.mapNotNull { it.tokens?.takeIf { count -> count >= 0 } }
    return CompanionStatistics(
        rounds = unique.size, activeDays = byDay.size, books = bookIds.size,
        conversations = unique.map { it.conversationId }.distinct().size,
        knownTokens = known.takeIf { it.isNotEmpty() }?.sumOf { it.toLong() },
        unknownUsageReplies = tokens.count { it.tokens == null || it.tokens < 0 },
        roundsByDay = byDay,
        legacyRounds = unique.count { it.replyRoundId.isNullOrBlank() },
        firstChatDate = deduplicated.filter {
            when (selection.scope) {
                CompanionStatsScope.ALL -> it.type in setOf("COMPANION", LibraryBookScopes.CONVERSATION_TYPE)
                CompanionStatsScope.BOOK -> it.type == "COMPANION"
                CompanionStatsScope.LIBRARY -> it.type == LibraryBookScopes.CONVERSATION_TYPE
            }
        }.minOfOrNull { date(it.createdAt) }?.takeUnless { it.isAfter(today) },
        chatCharacters = words.sortedWith(compareBy<CompanionWordsRow> { it.createdAt }.thenBy { it.id })
            .distinctBy { it.role + ":" + (it.roundId?.takeIf(String::isNotBlank) ?: "word:${it.id}") }
            .filter { includes(it.type, it.createdAt) }.sumOf { it.characters.coerceAtLeast(0L) },
        readingDurationMs = readingDays.filter { day ->
            val date = LocalDate.ofEpochDay(day.epochDay)
            day.bookId in bookIds && !date.isAfter(today) &&
                (selection.period.days?.let { !date.isBefore(today.minusDays(it - 1)) } ?: true)
        }.sumOf { it.durationMs.coerceAtLeast(0L) }
    )
}

internal fun formatCompanionReadingDuration(durationMs: Long): String {
    val minutes = durationMs.coerceAtLeast(0) / 60_000
    return if (minutes >= 60) "${minutes / 60}时${minutes % 60}分" else "${minutes}分钟"
}
