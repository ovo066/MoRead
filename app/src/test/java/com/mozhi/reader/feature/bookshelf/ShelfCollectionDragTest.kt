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

        assertEquals(
            ShelfDrop(book, ShelfDropPlacement.MERGE),
            findShelfDrop(Offset(150f, 40f), 1, regions, horizontal = true, allowMerge = true)
        )
        assertEquals(
            ShelfDrop(collection, ShelfDropPlacement.MERGE),
            findShelfDrop(Offset(260f, 40f), 1, regions, horizontal = true, allowMerge = true)
        )
        assertNull(
            findShelfDrop(Offset(40f, 40f), 1, regions, horizontal = true, allowMerge = true)
        )
    }

    @Test
    fun dividesGridAndListTargetsIntoExactDropZones() {
        val target = ShelfDropTarget("book:2", bookId = 2, collectionId = null)
        val region = ShelfDropRegion(target, Rect(100f, 100f, 200f, 300f))

        assertEquals(
            ShelfDrop(target, ShelfDropPlacement.BEFORE),
            findShelfDrop(
                Offset(110f, 150f),
                1,
                listOf(region),
                horizontal = true,
                allowMerge = true
            )
        )
        assertEquals(
            ShelfDrop(target, ShelfDropPlacement.MERGE),
            findShelfDrop(
                Offset(150f, 150f),
                1,
                listOf(region),
                horizontal = true,
                allowMerge = true
            )
        )
        assertEquals(
            ShelfDrop(target, ShelfDropPlacement.AFTER),
            findShelfDrop(
                Offset(190f, 150f),
                1,
                listOf(region),
                horizontal = true,
                allowMerge = true
            )
        )
        assertEquals(
            ShelfDrop(target, ShelfDropPlacement.BEFORE),
            findShelfDrop(
                Offset(150f, 120f),
                1,
                listOf(region),
                horizontal = false,
                allowMerge = true
            )
        )
        assertEquals(
            ShelfDrop(target, ShelfDropPlacement.AFTER),
            findShelfDrop(
                Offset(150f, 280f),
                1,
                listOf(region),
                horizontal = false,
                allowMerge = true
            )
        )
        assertEquals(
            ShelfDrop(target, ShelfDropPlacement.BEFORE),
            findShelfDrop(
                Offset(140f, 150f),
                1,
                listOf(region),
                horizontal = true,
                allowMerge = false
            )
        )
        assertEquals(
            ShelfDrop(target, ShelfDropPlacement.AFTER),
            findShelfDrop(
                Offset(160f, 150f),
                1,
                listOf(region),
                horizontal = true,
                allowMerge = false
            )
        )
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
        val owner = Any()
        state.register(target, Rect(100f, 0f, 200f, 100f), owner)

        state.begin(
            book = book,
            start = Offset(20f, 20f),
            coverBounds = Rect(10f, 10f, 110f, 160f),
            horizontal = true,
            allowMerge = true
        )
        assertNull(state.finish(minDistancePx = 8f))
        assertNull(state.sourceBook)

        state.begin(book, Offset(20f, 20f), Rect.Zero, horizontal = true, allowMerge = true)
        state.dragBy(Offset(85f, 0f))
        assertNull(state.finish(minDistancePx = 100f))

        state.begin(
            book = book,
            start = Offset(20f, 20f),
            coverBounds = Rect(10f, 10f, 110f, 160f),
            horizontal = true,
            allowMerge = true
        )
        state.dragBy(Offset(85f, 0f))
        assertEquals(Rect(95f, 10f, 195f, 160f), state.dragBounds)
        assertEquals(
            book to ShelfDrop(target, ShelfDropPlacement.BEFORE),
            state.finish(minDistancePx = 8f)
        )

        val replacementOwner = Any()
        state.register(target, Rect(200f, 0f, 300f, 100f), replacementOwner)
        state.unregister(target.entryKey, owner)
        state.begin(
            book = book,
            start = Offset(220f, 20f),
            coverBounds = Rect(10f, 10f, 110f, 160f),
            horizontal = true,
            allowMerge = true
        )
        state.dragBy(Offset.Zero)
        assertEquals(ShelfDrop(target, ShelfDropPlacement.BEFORE), state.activeDrop)
    }

    @Test
    fun previewRelayoutKeepsDropWhilePointerCoversTheSourcePlaceholder() {
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
        val source = ShelfDropTarget("book:1", bookId = 1, collectionId = null)
        val target = ShelfDropTarget("book:2", bookId = 2, collectionId = null)
        val sourceOwner = Any()
        val targetOwner = Any()
        state.register(source, Rect(0f, 0f, 100f, 100f), sourceOwner)
        state.register(target, Rect(100f, 0f, 200f, 100f), targetOwner)

        state.begin(
            book = book,
            start = Offset(20f, 50f),
            coverBounds = Rect(0f, 0f, 100f, 100f),
            horizontal = true,
            allowMerge = true
        )
        state.dragBy(Offset(170f, 0f))
        val expected = ShelfDrop(target, ShelfDropPlacement.AFTER)
        assertEquals(expected, state.activeDrop)

        state.register(source, Rect(100f, 0f, 200f, 100f), sourceOwner)
        state.register(target, Rect(0f, 0f, 100f, 100f), targetOwner)
        assertEquals(expected, state.activeDrop)

        state.register(source, Rect(200f, 0f, 300f, 100f), sourceOwner)
        assertNull(state.activeDrop)
    }

    @Test
    fun dragBoundsFollowTheExactCoverTranslation() {
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

        state.begin(
            book = book,
            start = Offset(20f, 20f),
            coverBounds = Rect(10f, 10f, 110f, 160f),
            horizontal = true,
            allowMerge = true
        )
        state.dragBy(Offset(30f, 40f))

        assertEquals(Rect(40f, 50f, 140f, 200f), state.dragBounds)
    }

    @Test
    fun edgeScrollUsesTheWholeViewportTenPercentThresholds() {
        val viewport = Rect(0f, 0f, 500f, 1000f)

        assertEquals(-1, shelfEdgeScrollDirection(Rect(0f, 99f, 50f, 199f), viewport))
        assertEquals(0, shelfEdgeScrollDirection(Rect(0f, 101f, 50f, 201f), viewport))
        assertEquals(0, shelfEdgeScrollDirection(Rect(0f, 799f, 50f, 899f), viewport))
        assertEquals(1, shelfEdgeScrollDirection(Rect(0f, 801f, 50f, 901f), viewport))
    }
}
