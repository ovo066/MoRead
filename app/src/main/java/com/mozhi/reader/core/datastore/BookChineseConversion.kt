package com.mozhi.reader.core.datastore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ChineseConversionMode {
    OFF,
    TW2SP,
    S2TWP
}

object BookChineseConversionCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(values: Map<Long, ChineseConversionMode>): String =
        json.encodeToString(values.filterValues { it != ChineseConversionMode.OFF })

    fun decode(raw: String?): Map<Long, ChineseConversionMode> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<Long, ChineseConversionMode>>(raw) }
            .getOrDefault(emptyMap())
    }
}

fun ReaderSettings.chineseConversionModeFor(bookId: Long): ChineseConversionMode =
    bookChineseConversions[bookId] ?: ChineseConversionMode.OFF
