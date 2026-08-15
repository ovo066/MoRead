package com.mozhi.reader.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookQuoteLocatorTest {

    private val chapters = listOf(
        QuoteChapter(0, "第一章开头。他说他不会再回来。然后就走了。"),
        QuoteChapter(1, "第二章。风从山谷里穿过来，带着雪的味道。"),
        QuoteChapter(2, "第三章。他说他不会再回来。这次是真的。")
    )

    @Test
    fun findsEveryExactOccurrence() {
        val hits = BookQuoteLocator.locateAll(chapters, "他说他不会再回来")

        assertEquals(2, hits.size)
        assertEquals(listOf(0, 2), hits.map { it.chapterIndex })
        val first = hits.first()
        assertEquals(
            "他说他不会再回来",
            chapters[0].body.substring(first.startCharOffset, first.endCharOffset)
        )
    }

    @Test
    fun locateBestPrefersTheChapterTheModelNamed() {
        val hit = BookQuoteLocator.locateBest(chapters, "他说他不会再回来", preferredChapterIndex = 2)

        assertEquals(2, hit!!.chapterIndex)
    }

    @Test
    fun locateBestFallsBackToFirstOccurrenceWithoutAHint() {
        assertEquals(0, BookQuoteLocator.locateBest(chapters, "他说他不会再回来")!!.chapterIndex)
    }

    /** 模型复述时爱改标点/空格；为一个全角逗号让用户点不动说不过去。 */
    @Test
    fun toleratesPunctuationAndWhitespaceDrift() {
        val hit = BookQuoteLocator.locateBest(chapters, "风从山谷里穿过来 带着雪的味道")

        assertEquals(1, hit!!.chapterIndex)
        val located = chapters[1].body.substring(hit.startCharOffset, hit.endCharOffset)
        assertTrue("定位区间应覆盖原句：$located", located.startsWith("风从山谷里穿过来"))
        assertTrue(located.endsWith("带着雪的味道"))
    }

    @Test
    fun quotedFormOfTheSameSentenceStillResolves() {
        val hit = BookQuoteLocator.locateBest(chapters, "「他说他不会再回来」")

        assertEquals(0, hit!!.chapterIndex)
    }

    @Test
    fun tooShortQuotesAreRefused() {
        assertNull(BookQuoteLocator.locateBest(chapters, "他说"))
        assertNull(BookQuoteLocator.locateBest(chapters, "   "))
    }

    @Test
    fun quoteThatIsNotInTheBookReturnsNull() {
        assertNull(BookQuoteLocator.locateBest(chapters, "这句话书里根本没有出现过"))
    }

    @Test
    fun emptyInputsNeverThrow() {
        assertTrue(BookQuoteLocator.locateAll(emptyList(), "任何内容").isEmpty())
        assertTrue(BookQuoteLocator.locateAll(chapters, "").isEmpty())
    }

    /** 重叠命中不能把同一段算两遍，否则「共 N 处」会虚高。 */
    @Test
    fun overlappingMatchesAdvancePastEachHit() {
        val repeated = listOf(QuoteChapter(0, "啊啊啊啊啊啊啊啊"))

        val hits = BookQuoteLocator.locateAll(repeated, "啊啊啊啊")

        assertEquals(2, hits.size)
        assertEquals(0, hits[0].startCharOffset)
        assertEquals(4, hits[1].startCharOffset)
    }
}
