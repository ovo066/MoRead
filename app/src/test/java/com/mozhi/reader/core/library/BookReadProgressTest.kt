package com.mozhi.reader.core.library

import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class BookReadProgressTest {

    private fun book(
        chapterIndex: Int = 0,
        charOffset: Int = 0,
        totalChapters: Int = 50,
        lastReadAt: Long = 1_000L
    ) = BookEntity(
        id = 1,
        title = "书",
        author = "作者",
        coverPath = null,
        epubPath = "",
        sourceType = BookSourceType.TXT,
        importedAt = 0,
        totalChapters = totalChapters,
        lastReadChapterIndex = chapterIndex,
        lastReadCharOffset = charOffset,
        lastReadAt = lastReadAt
    )

    @Test
    fun unreadBookIsZero() {
        assertEquals(0f, readFraction(book(lastReadAt = 0), BookReadSpan(5_000, 10_000)), 0f)
    }

    @Test
    fun fallsBackToChapterFormulaWhenCharCountsMissing() {
        // 老书没落 charCount：总字符为 0，只能按章估，沿用旧口径避免显示 0%。
        assertEquals(0.2f, readFraction(book(chapterIndex = 9), BookReadSpan(0, 0)), 1e-4f)
        assertEquals(0.2f, readFraction(book(chapterIndex = 9), null), 1e-4f)
    }

    @Test
    fun chapterStartDoesNotCountCurrentChapterAsFinished() {
        // 这正是修掉的 2% 偏差：旧口径给 20%，真实位置是 18%。
        val span = BookReadSpan(charsBeforeChapter = 9_000, totalChars = 50_000)
        assertEquals(0.18f, readFraction(book(chapterIndex = 9), span), 1e-4f)
    }

    @Test
    fun offsetWithinChapterAdvancesProgress() {
        val span = BookReadSpan(charsBeforeChapter = 9_000, totalChars = 50_000)
        assertEquals(0.19f, readFraction(book(chapterIndex = 9, charOffset = 500), span), 1e-4f)
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        val span = BookReadSpan(charsBeforeChapter = 49_000, totalChars = 50_000)
        assertEquals(1f, readFraction(book(chapterIndex = 49, charOffset = 9_999), span), 0f)
        val negative = BookReadSpan(charsBeforeChapter = 0, totalChars = 50_000)
        assertEquals(0f, readFraction(book(charOffset = -20), negative), 0f)
    }

    @Test
    fun percentRoundsAndNeverFakesCompletion() {
        assertEquals(19, readPercent(0.189f))
        assertEquals(18, readPercent(0.184f))
        assertEquals(99, readPercent(0.9987f))
        assertEquals(100, readPercent(1f))
        assertEquals(0, readPercent(0f))
        assertEquals(1, readPercent(0.0001f))
    }
}
