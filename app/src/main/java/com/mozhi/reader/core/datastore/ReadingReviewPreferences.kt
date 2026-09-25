package com.mozhi.reader.core.datastore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ReadingReviewPreferences(
    val source: String = "MINE",
    val kind: String = "ALL",
    val bookIds: Set<Long> = emptySet(),
    val personaId: Long? = null,
    val oldestFirst: Boolean = false,
    val grid: Boolean = true
)

object ReadingReviewPreferencesCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(value: ReadingReviewPreferences): String = json.encodeToString(value)
    fun decode(raw: String?): ReadingReviewPreferences = runCatching {
        json.decodeFromString<ReadingReviewPreferences>(requireNotNull(raw))
    }.getOrDefault(ReadingReviewPreferences()).let {
        it.copy(source = it.source.takeIf { value -> value in setOf("MINE", "ALL", "AI") } ?: "MINE",
            kind = it.kind.takeIf { value -> value in setOf("ALL", "HIGHLIGHT", "NOTE") } ?: "ALL",
            bookIds = it.bookIds.filter { id -> id > 0 }.toSet(), personaId = it.personaId?.takeIf { id -> id > 0 })
    }
}
