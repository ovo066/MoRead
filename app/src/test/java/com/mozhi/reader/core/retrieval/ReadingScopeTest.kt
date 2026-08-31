package com.mozhi.reader.core.retrieval

import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingScopeTest {
    @Test
    fun rereadingOlderChapterKeepsMonotonicHighWaterVisible() {
        val book = BookEntity(
            id = 1,
            title = "测试书",
            author = "",
            coverPath = null,
            epubPath = "book.txt",
            sourceType = BookSourceType.TXT,
            importedAt = 0,
            totalChapters = 100,
            lastReadChapterIndex = 2,
            lastReadCharOffset = 30,
            maxReachedChapterIndex = 40,
            maxReachedCharOffset = 900
        )

        val scope = ReadingScope.uptoProgress(book)

        assertTrue(scope.allowsPosition(40, 900))
        assertFalse(scope.allowsPosition(40, 901))
        assertFalse(scope.allowsChapter(41))
    }

    @Test
    fun wholeBookAllowsAnyNormalPositionAndChunk() {
        val scope = ReadingScope.WholeBook

        assertTrue(scope.allowsPosition(9_999, -1))
        assertTrue(scope.allowsChunk(9_999, -1, -1))
    }

    @Test
    fun boundaryChapterRejectsUnknownOrPartiallyUnreadChunk() {
        val scope = ReadingScope.upto(3, 100)

        assertTrue(scope.allowsChunk(2, -1, -1))
        assertTrue(scope.allowsChunk(3, 10, 100))
        assertFalse(scope.allowsChunk(3, -1, -1))
        assertFalse(scope.allowsChunk(3, 90, 101))
        assertFalse(scope.allowsPosition(3, -1))
    }
}
