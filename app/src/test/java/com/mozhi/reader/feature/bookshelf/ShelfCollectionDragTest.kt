package com.mozhi.reader.feature.bookshelf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfCollectionDragTest {
    private fun testBook(id: Long = 1) = BookEntity(
        id = id,
        title = "书",
        author = "",
        coverPath = null,
        epubPath = "/book.epub",
        sourceType = BookSourceType.EPUB,
        importedAt = 1,
        totalChapters = 1
    )

    @Test
    fun fiveAndSixColumnGridsUseMeasuredBoundsIncludingRailAndPageOffsets() {
        listOf(5, 6).forEach { columns ->
            val origin = Offset(180f, 32f)
            val regions = (0 until columns * 2).map { index ->
                val left = origin.x + (index % columns) * 114f
                val top = origin.y + (index / columns) * 170f
                ShelfDropRegion(
                    ShelfDropTarget("book:${index + 1}", bookId = index + 1L, collectionId = null),
                    Rect(left, top, left + 100f, top + 150f)
                )
            }
            val lastInRow = regions[columns - 1]
            val nextRow = regions[columns]
            assertEquals(
                ShelfDrop(lastInRow.target, ShelfDropPlacement.MERGE),
                findShelfDrop(lastInRow.bounds.center, 1, regions, horizontal = true, allowMerge = true)
            )
            assertEquals(
                ShelfDrop(nextRow.target, ShelfDropPlacement.BEFORE),
                findShelfDrop(
                    nextRow.bounds.topLeft + Offset(5f, 50f), 1, regions,
                    horizontal = true, allowMerge = true
                )
            )
            assertEquals(
                ShelfDrop(lastInRow.target, ShelfDropPlacement.AFTER),
                findShelfDrop(
                    Offset(lastInRow.bounds.center.x, lastInRow.bounds.bottom + 5f),
                    1, regions, horizontal = true, allowMerge = true
                )
            )
            assertEquals(
                Rect(0f, 170f, 100f, 320f),
                shelfBoundsInContainer(nextRow.bounds, origin)
            )
            assertNull(findShelfDrop(Offset(50f, 80f), 1, regions, true, true))
        }
    }

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
    fun collectionEdgesAcceptBooksWithoutDodgingInGridAndList() {
        val collection = ShelfDropTarget("collection:7", bookId = null, collectionId = 7)
        val region = ShelfDropRegion(collection, Rect(100f, 100f, 200f, 300f))
        listOf(true, false).forEach { horizontal ->
            listOf(Offset(101f, 101f), Offset(199f, 299f), region.bounds.center).forEach { pointer ->
                assertEquals(
                    ShelfDrop(collection, ShelfDropPlacement.MERGE),
                    findShelfDrop(pointer, 1, listOf(region), horizontal, allowMerge = true)
                )
            }
        }
        assertEquals(
            ShelfDrop(collection, ShelfDropPlacement.BEFORE),
            findShelfDrop(Offset(101f, 101f), 1, listOf(region), true, allowMerge = false)
        )
    }

    @Test
    fun gapBesideCollectionStillAllowsReorderingOnRelease() {
        val book = ShelfDropTarget("book:2", bookId = 2, collectionId = null)
        val collection = ShelfDropTarget("collection:7", bookId = null, collectionId = 7)
        val regions = listOf(
            ShelfDropRegion(book, Rect(0f, 0f, 100f, 100f)),
            ShelfDropRegion(collection, Rect(120f, 0f, 220f, 100f))
        )
        assertEquals(
            ShelfDrop(collection, ShelfDropPlacement.BEFORE),
            findShelfDrop(Offset(119f, 50f), 1, regions, true, allowMerge = true)
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
    fun gridGapUsesTheNearestCoverEdge() {
        val second = ShelfDropTarget("book:2", bookId = 2, collectionId = null)
        val third = ShelfDropTarget("book:3", bookId = 3, collectionId = null)
        val regions = listOf(
            ShelfDropRegion(second, Rect(110f, 0f, 210f, 100f)),
            ShelfDropRegion(third, Rect(220f, 0f, 320f, 100f))
        )

        assertEquals(
            ShelfDrop(second, ShelfDropPlacement.AFTER),
            findShelfDrop(Offset(213f, 50f), 1, regions, horizontal = true, allowMerge = true)
        )
        assertEquals(
            ShelfDrop(third, ShelfDropPlacement.BEFORE),
            findShelfDrop(Offset(217f, 50f), 1, regions, horizontal = true, allowMerge = true)
        )
    }

    @Test
    fun listTitlesAndVerticalGapsAreDropTargets() {
        val first = ShelfDropTarget("book:2", bookId = 2, collectionId = null)
        val second = ShelfDropTarget("book:3", bookId = 3, collectionId = null)
        val regions = listOf(
            ShelfDropRegion(first, Rect(0f, 0f, 320f, 122f)),
            ShelfDropRegion(second, Rect(0f, 136f, 320f, 258f))
        )
        assertEquals(
            ShelfDrop(second, ShelfDropPlacement.BEFORE),
            findShelfDrop(Offset(280f, 145f), 1, regions, horizontal = false, allowMerge = true)
        )
        assertEquals(
            ShelfDrop(first, ShelfDropPlacement.AFTER),
            findShelfDrop(Offset(280f, 125f), 1, regions, horizontal = false, allowMerge = true)
        )
        assertEquals(
            ShelfDrop(second, ShelfDropPlacement.BEFORE),
            findShelfDrop(Offset(280f, 133f), 1, regions, horizontal = false, allowMerge = true)
        )
    }

    @Test
    fun gridRowGapKeepsTheNearestInsertionTarget() {
        val first = ShelfDropTarget("book:2", bookId = 2, collectionId = null)
        val second = ShelfDropTarget("book:3", bookId = 3, collectionId = null)
        val regions = listOf(
            ShelfDropRegion(first, Rect(0f, 0f, 100f, 180f)),
            ShelfDropRegion(second, Rect(0f, 200f, 100f, 380f))
        )
        assertEquals(
            ShelfDrop(first, ShelfDropPlacement.AFTER),
            findShelfDrop(Offset(50f, 185f), 1, regions, horizontal = true, allowMerge = true)
        )
        assertEquals(
            ShelfDrop(second, ShelfDropPlacement.BEFORE),
            findShelfDrop(Offset(50f, 195f), 1, regions, horizontal = true, allowMerge = true)
        )
    }

    @Test
    fun cancellingADragClearsDropAndScrollBeforeTheNextGesture() {
        val state = ShelfCollectionDragState()
        val target = ShelfDropTarget("book:2", bookId = 2, collectionId = null)
        val cover = Rect(0f, 0f, 100f, 150f)
        state.register(target, Rect(0f, 800f, 320f, 1000f), Any())
        state.setViewport(Rect(0f, 0f, 320f, 1000f))
        state.begin(testBook(), Offset(50f, 50f), cover, horizontal = false, allowMerge = true)
        state.dragBy(Offset(0f, 900f), minDistancePx = 8f)
        assertEquals(1, state.autoScrollDirection)
        assertTrue(state.activeDrop != null)

        state.cancel()
        assertNull(state.sourceBook)
        assertNull(state.activeDrop)
        assertNull(state.finish())
        assertEquals(0, state.autoScrollDirection)
        state.begin(testBook(), Offset(50f, 50f), cover, horizontal = false, allowMerge = true)
        val next = requireNotNull(state.finish())
        assertNull(next.drop)
        assertTrue(next.showLongPressMenu)
    }

    @Test
    fun scrollSpeedMatchesAtSixtyAndOneHundredTwentyFramesPerSecond() {
        fun distance(frames: Int): Float = (1..frames).sumOf { frame ->
            val start = (frame - 1) * 1_000_000_000L / frames
            val end = frame * 1_000_000_000L / frames
            shelfScrollDistance(end - start, 720f).toDouble()
        }.toFloat()
        assertEquals(720f, distance(60), 0.001f)
        assertEquals(distance(60), distance(120), 0.001f)
    }

    @Test
    fun accessibilityReorderActionsStayWithinThePinnedSegment() {
        val pinned = testBook(1).copy(pinnedAt = 10)
        val unpinned = testBook(2)
        val last = testBook(3)
        val entries = listOf(pinned, unpinned, last).map(ShelfEntry::Book)
        var result: ShelfDrop? = null

        assertTrue(shelfReorderActions(entries, pinned) { _, drop -> result = drop }.isEmpty())
        val actions = shelfReorderActions(entries, unpinned) { _, drop -> result = drop }
        assertEquals(listOf("向后移动"), actions.map { it.label })
        assertTrue(actions.single().action())
        assertEquals(ShelfDrop(ShelfEntry.Book(last).dropTarget(), ShelfDropPlacement.AFTER), result)
    }

    @Test
    fun noMoveFallsBackAndOnlyThresholdedDragDrops() {
        val state = ShelfCollectionDragState()
        val book = testBook()
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
        assertEquals(ShelfDragResult(book, drop = null, showLongPressMenu = true), state.finish())
        assertNull(state.sourceBook)

        state.begin(book, Offset(20f, 20f), Rect.Zero, horizontal = true, allowMerge = true)
        state.dragBy(Offset(85f, 0f), minDistancePx = 100f)
        assertEquals(ShelfDragResult(book, drop = null, showLongPressMenu = false), state.finish())

        state.begin(
            book = book,
            start = Offset(20f, 20f),
            coverBounds = Rect(10f, 10f, 110f, 160f),
            horizontal = true,
            allowMerge = true
        )
        state.dragBy(Offset(85f, 0f), minDistancePx = 8f)
        assertEquals(Rect(95f, 10f, 195f, 160f), state.dragBounds)
        assertEquals(
            ShelfDragResult(
                book,
                ShelfDrop(target, ShelfDropPlacement.BEFORE),
                showLongPressMenu = false
            ),
            state.finish()
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
        state.dragBy(Offset.Zero, minDistancePx = 8f)
        assertEquals(ShelfDrop(target, ShelfDropPlacement.BEFORE), state.activeDrop)
    }

    @Test
    fun previewRelayoutKeepsDropWhilePointerIsNearestTheSourcePlaceholder() {
        val state = ShelfCollectionDragState()
        val book = testBook()
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
        state.dragBy(Offset(170f, 0f), minDistancePx = 8f)
        val expected = ShelfDrop(target, ShelfDropPlacement.AFTER)
        assertEquals(expected, state.activeDrop)

        state.register(target, Rect(0f, 0f, 100f, 100f), targetOwner)
        assertEquals(expected, state.activeDrop)

        state.register(source, Rect(100f, 0f, 200f, 100f), sourceOwner)
        assertEquals(expected, state.activeDrop)

        state.register(source, Rect(200f, 0f, 300f, 100f), sourceOwner)
        assertEquals(expected, state.activeDrop)
    }

    @Test
    fun gridPreviewKeepsBeforeDropWhileLaterBookMovesIntoTheTargetSlot() {
        val state = ShelfCollectionDragState()
        val books = (1L..6L).map(::testBook)
        val targets = books.associate { book ->
            book.id to ShelfDropTarget("book:${book.id}", bookId = book.id, collectionId = null)
        }
        val owners = books.associate { it.id to Any() }
        val initialBounds = listOf(
            Rect(0f, 0f, 100f, 100f),
            Rect(110f, 0f, 210f, 100f),
            Rect(220f, 0f, 320f, 100f),
            Rect(0f, 120f, 100f, 220f),
            Rect(110f, 120f, 210f, 220f),
            Rect(220f, 120f, 320f, 220f)
        )
        books.forEachIndexed { index, book ->
            state.register(targets.getValue(book.id), initialBounds[index], owners.getValue(book.id))
        }

        state.begin(
            book = books.last(),
            start = Offset(270f, 170f),
            coverBounds = initialBounds.last(),
            horizontal = true,
            allowMerge = false
        )
        state.dragBy(Offset(-53f, -120f), minDistancePx = 8f)
        val expectedDrop = ShelfDrop(targets.getValue(3), ShelfDropPlacement.BEFORE)
        assertEquals(expectedDrop, state.activeDrop)

        listOf(
            3L to Rect(0f, 120f, 100f, 220f),
            4L to Rect(110f, 120f, 210f, 220f),
            5L to Rect(220f, 120f, 320f, 220f),
            6L to Rect(220f, 0f, 320f, 100f)
        ).forEach { (bookId, bounds) ->
            state.register(targets.getValue(bookId), bounds, owners.getValue(bookId))
            assertEquals(expectedDrop, state.activeDrop)
        }

        state.dragBy(Offset(1f, 0f), minDistancePx = 8f)
        assertEquals(expectedDrop, state.activeDrop)
        val result = requireNotNull(state.finish())
        val drop = requireNotNull(result.drop)
        assertEquals(expectedDrop, drop)
        assertEquals(
            listOf(1L, 2L, 6L, 3L, 4L, 5L),
            reorderCollectionMembers(
                books,
                sourceBookId = result.source.id,
                targetBookId = requireNotNull(drop.target.bookId),
                after = drop.placement == ShelfDropPlacement.AFTER
            ).map(BookEntity::id)
        )
    }

    @Test
    fun dragBoundsFollowTheExactCoverTranslation() {
        val state = ShelfCollectionDragState()
        val book = testBook()

        state.begin(
            book = book,
            start = Offset(20f, 20f),
            coverBounds = Rect(10f, 10f, 110f, 160f),
            horizontal = true,
            allowMerge = true
        )
        state.dragBy(Offset(30f, 40f), minDistancePx = 8f)

        assertEquals(Rect(40f, 50f, 140f, 200f), state.dragBounds)
    }

    @Test
    fun edgeScrollUsesThePointerAndWholeViewportTenPercentThresholds() {
        val viewport = Rect(0f, 0f, 500f, 1000f)

        assertEquals(-1, shelfEdgeScrollDirection(99f, viewport))
        assertEquals(0, shelfEdgeScrollDirection(101f, viewport))
        assertEquals(0, shelfEdgeScrollDirection(899f, viewport))
        assertEquals(1, shelfEdgeScrollDirection(901f, viewport))
    }

    @Test
    fun edgeScrollWaitsForRealDragInsteadOfUsingTheCoverEdge() {
        val state = ShelfCollectionDragState()
        state.setViewport(Rect(0f, 0f, 500f, 1000f))
        state.begin(
            book = testBook(),
            start = Offset(50f, 850f),
            coverBounds = Rect(0f, 800f, 100f, 950f),
            horizontal = true,
            allowMerge = true
        )

        assertEquals(0, state.autoScrollDirection)
        state.dragBy(Offset(7f, 40f), minDistancePx = 50f)
        assertEquals(0, state.autoScrollDirection)
        state.dragBy(Offset(2f, 11f), minDistancePx = 50f)
        assertEquals(1, state.autoScrollDirection)
    }

    @Test
    fun oneCoverDistanceSuppressesTheMenuEvenAfterReturningToStart() {
        val state = ShelfCollectionDragState()
        val cover = Rect(0f, 0f, 100f, 150f)

        state.begin(testBook(), Offset(20f, 20f), cover, horizontal = true, allowMerge = true)
        state.dragBy(Offset(12f, 0f), minDistancePx = 8f)
        assertTrue(requireNotNull(state.finish()).showLongPressMenu)

        state.begin(testBook(), Offset(20f, 20f), cover, horizontal = true, allowMerge = true)
        state.dragBy(Offset(100f, 0f), minDistancePx = 8f)
        state.dragBy(Offset(-100f, 0f), minDistancePx = 8f)
        val result = requireNotNull(state.finish())
        assertNull(result.drop)
        assertFalse(result.showLongPressMenu)
    }
}
