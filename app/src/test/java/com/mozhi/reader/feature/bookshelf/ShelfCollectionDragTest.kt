package com.mozhi.reader.feature.bookshelf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShelfCollectionDragTest {
    @Test
    fun findsTargetUnderPointerAndSkipsTheSourceBook() {
        val source = ShelfDropTarget("book:1", bookId = 1, collectionId = null)
        val book = ShelfDropTarget("book:2", bookId = 2, collectionId = null)
        val collection = ShelfDropTarget("collection:7", bookId = null, collectionId = 7)
        val regions = listOf(
            ShelfDropRegion(source, Rect(0f, 0f, 100f, 100f)),
            ShelfDropRegion(book, Rect(110f, 0f, 210f, 100f)),
            ShelfDropRegion(collection, Rect(220f, 0f, 320f, 100f))
        )

        assertEquals(book, findShelfDropTarget(Offset(150f, 40f), 1, regions))
        assertEquals(collection, findShelfDropTarget(Offset(260f, 40f), 1, regions))
        assertNull(findShelfDropTarget(Offset(40f, 40f), 1, regions))
    }

    @Test
    fun noMoveFallsBackAndOnlyThresholdedDragDrops() {
        val state = ShelfCollectionDragState()
        val book = BookEntity(
            id = 1,
            title = "书",
            author = "",
            coverPath = null,
            epubPath = "/book.epub",
            sourceType = BookSourceType.EPUB,
            importedAt = 1,
            totalChapters = 1
        )
        val target = ShelfDropTarget("book:2", bookId = 2, collectionId = null)
        state.register(target, Rect(100f, 0f, 200f, 100f))

        state.begin(book, Offset(20f, 20f))
        assertNull(state.finish(minDistancePx = 8f))
        assertNull(state.sourceBook)

        state.begin(book, Offset(20f, 20f))
        state.dragBy(Offset(85f, 0f))
        assertNull(state.finish(minDistancePx = 100f))

        state.begin(book, Offset(20f, 20f))
        state.dragBy(Offset(85f, 0f))
        assertEquals(book to target, state.finish(minDistancePx = 8f))
    }
}
