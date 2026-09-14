package com.mozhi.reader.core.library

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal data class ReadingTimeSlice(val epochDay: Long, val hour: Int, val durationMs: Long, val lastReadAt: Long)

/** Split at actual local hour boundaries, including midnight and daylight-saving transitions. */
internal fun readingTimeSlices(durationMs: Long, recordedAt: Long, zoneId: ZoneId): List<ReadingTimeSlice> = buildList {
    if (durationMs <= 0 || recordedAt <= 0) return@buildList
    var start = (recordedAt - durationMs).coerceAtLeast(0L)
    while (start < recordedAt) {
        val local = Instant.ofEpochMilli(start).atZone(zoneId)
        val boundary = local.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
        val end = minOf(recordedAt, boundary)
        add(ReadingTimeSlice(local.toLocalDate().toEpochDay(), local.hour, end - start, end))
        start = end
    }
}
