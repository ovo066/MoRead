package com.mozhi.reader.feature.bookshelf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
}
