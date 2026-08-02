package com.mozhi.reader.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookTextSearchTest {

    @Test
    fun hitOffsetsAreUtf16AndMatchBody() {
        val body = "夜色沉沉。张小敬拔刀而起，长安的夜色从未如此凶险。"
        val hits = searchChapterText(2, "上元", body, "夜色")

        assertEquals(2, hits.size)
        hits.forEach { hit ->
            assertEquals("夜色", body.substring(hit.charOffset, hit.charOffset + 2))
            assertEquals(2, hit.chapterIndex)
        }
    }

    @Test
    fun snippetWindowClampsAtChapterBoundaries(): Unit {
        val body = "开头就命中" + "后".repeat(100)
        val head = searchChapterText(0, "", body, "开头").single()
        assertEquals(0, head.matchStartInSnippet)
        assertTrue(head.snippet.length <= 2 + SNIPPET_RADIUS * 2)

        val tailBody = "前".repeat(100) + "结尾命中"
        val tail = searchChapterText(0, "", tailBody, "命中").single()
        assertEquals(
            "命中",
            tail.snippet.substring(
                tail.matchStartInSnippet,
                tail.matchStartInSnippet + tail.matchLength
            )
        )
    }

    @Test
    fun searchIgnoresCaseAndCapsPerChapter() {
        val body = "Sherlock 说：sherlock 只是名字。" + "sherlock。".repeat(100)
        val hits = searchChapterText(0, "", body, "Sherlock")
        assertEquals(50, hits.size)

        val highlighted = hits.first()
        assertEquals(
            "Sherlock",
            highlighted.snippet.substring(
                highlighted.matchStartInSnippet,
                highlighted.matchStartInSnippet + highlighted.matchLength
            )
        )
    }

    @Test
    fun blankQueryReturnsNothing() {
        assertTrue(searchChapterText(0, "", "正文", "  ").isEmpty())
    }
}
