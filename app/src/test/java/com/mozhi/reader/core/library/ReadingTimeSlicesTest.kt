package com.mozhi.reader.core.library

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class ReadingTimeSlicesTest {
    @Test fun midnightAndHourBoundariesPreserveEveryMillisecond() {
        val zone = ZoneId.of("Asia/Shanghai")
        val end = ZonedDateTime.of(2026, 9, 13, 1, 15, 0, 0, zone)
        val slices = readingTimeSlices(90 * 60_000L, end.toInstant().toEpochMilli(), zone)
        assertEquals(listOf(23, 0, 1), slices.map { it.hour })
        assertEquals(listOf(15L, 60L, 15L), slices.map { it.durationMs / 60_000 })
        assertEquals(90 * 60_000L, slices.sumOf { it.durationMs })
        assertEquals(end.toLocalDate().minusDays(1).toEpochDay(), slices.first().epochDay)
        assertEquals(end.toLocalDate().toEpochDay(), slices.last().epochDay)
    }

    @Test fun daylightSavingSkipsMissingHoursAndCountsRepeatedHours() {
        val zone = ZoneId.of("America/New_York")
        val spring = ZonedDateTime.of(2026, 3, 8, 4, 0, 0, 0, zone)
        assertEquals(listOf(1, 3), readingTimeSlices(120 * 60_000L, spring.toInstant().toEpochMilli(), zone).map { it.hour })
        val fall = ZonedDateTime.of(2026, 11, 1, 3, 0, 0, 0, zone)
        val slices = readingTimeSlices(180 * 60_000L, fall.toInstant().toEpochMilli(), zone)
        assertEquals(listOf(1, 1, 2), slices.map { it.hour })
        assertEquals(180 * 60_000L, slices.sumOf { it.durationMs })
    }
}
