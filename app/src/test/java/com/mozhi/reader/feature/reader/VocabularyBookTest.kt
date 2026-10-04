package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.dictionary.VocabularyWord
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VocabularyBookTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private val now = at("2026-09-27T09:00:00")

    @Test fun groupsNewestFirstByDayThenWeekThenMonth() {
        val words = listOf(
            VocabularyWord("august", createdAt = at("2026-08-30T12:00:00")),
            VocabularyWord("late", createdAt = at("2026-09-26T23:59:00")),
            VocabularyWord("early", createdAt = at("2026-09-27T00:01:00")),
            VocabularyWord("week", createdAt = at("2026-09-21T10:00:00")),
            VocabularyWord("september", createdAt = at("2026-09-20T08:00:00")),
            VocabularyWord("lastyear", createdAt = at("2025-12-31T20:00:00")),
            VocabularyWord("skewed", createdAt = at("2026-09-28T08:00:00"))
        )
        val groups = vocabularyGroups(words, "", VocabularyFilter.ALL, now, zone)
        assertEquals(listOf(VocabularyPeriod.Today, VocabularyPeriod.Yesterday, VocabularyPeriod.PastWeek,
            VocabularyPeriod.Month(2026, 9), VocabularyPeriod.Month(2026, 8), VocabularyPeriod.Month(2025, 12)),
            groups.map { it.period })
        // 时钟回拨导致的「未来」时间戳并入今天，且仍按时间倒序排在最前。
        assertEquals(listOf("skewed", "early"), groups[0].words.map { it.word })
        assertEquals(listOf("late"), groups[1].words.map { it.word })
        assertEquals(listOf("week"), groups[2].words.map { it.word })
    }

    @Test fun filterAndQueryCombine() {
        val words = listOf(
            VocabularyWord("serendipity", definition = "意外发现珍贵事物", gloss = "意外之喜", createdAt = 3),
            VocabularyWord("lighthouse", definition = "灯塔", learned = true, createdAt = 2),
            VocabularyWord("故", definition = "旧的", createdAt = 1)
        )
        fun find(query: String, filter: VocabularyFilter) =
            vocabularyGroups(words, query, filter, now, zone).flatMap { it.words }.map { it.word }
        assertEquals(listOf("serendipity", "故"), find("", VocabularyFilter.LEARNING))
        assertEquals(listOf("lighthouse"), find("", VocabularyFilter.LEARNED))
        assertEquals(listOf("serendipity"), find(" 之喜 ", VocabularyFilter.ALL))
        assertEquals(listOf("lighthouse"), find("LIGHT", VocabularyFilter.ALL))
        assertEquals(emptyList<String>(), find("灯塔", VocabularyFilter.LEARNING))
        val stats = vocabularyStats(words)
        assertEquals(VocabularyStats(3, 2, 1), stats)
        assertEquals(1f / 3, stats.masteredFraction, 1e-6f)
        assertEquals(0f, vocabularyStats(emptyList()).masteredFraction)
    }

    @Test fun previewStripsMarkdownRepeatedHeadwordAndDuplicateGloss() {
        val markdown = "## Serendipity\n\n**n.** 不期而遇的美好\n- 意外发现珍贵事物的机缘\n> `a happy accident`"
        assertEquals("n. 不期而遇的美好 意外发现珍贵事物的机缘 a happy accident",
            vocabularyPreview("serendipity", markdown, "意外之喜"))
        assertEquals("", vocabularyPreview("故", "旧的", "旧的"))
        assertEquals(10, vocabularyPreview("x", "a".repeat(50), "", maxChars = 10).length)
    }

    @Test fun contextHighlightFindsFirstCaseInsensitiveMatch() {
        assertEquals(2..12, vocabularyContextHighlight("A Serendipity by serendipity", "serendipity"))
        assertEquals(1..1, vocabularyContextHighlight("温故而知新", "故"))
        assertNull(vocabularyContextHighlight("nothing here", "word"))
        assertNull(vocabularyContextHighlight("anything", " "))
    }
}
