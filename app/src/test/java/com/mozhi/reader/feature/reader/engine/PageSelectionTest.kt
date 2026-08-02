package com.mozhi.reader.feature.reader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSelectionTest {

    private val spec = TypesetSpec(
        visibleWidth = 100f,
        visibleHeight = 200f,
        contentLineStep = 25f,
        titleLineStep = 34f,
        paragraphSpacing = 9f,
        titleTopSpacing = 0f,
        titleBottomSpacing = 0f
    )

    private val paragraph = "春江潮水连海平海上明月共潮生滟滟随波千万里何处春江无月明"

    private fun page(): TextPage =
        ChapterTypesetter(spec, FakeMeasure()).typeset(0, "", paragraph + "\n" + paragraph)
            .pages
            .first()

    @Test
    fun `hit test resolves the cluster under the point`() {
        val page = page()
        val line = page.lines.first()
        // Third cluster of the first line: x within its bounds, y at line middle.
        val column = line.columns[2]
        val pos = page.hitTextPos(
            x = (column.start + column.end) / 2f,
            y = (line.lineTop + line.lineBottom) / 2f,
            exact = true
        )
        assertEquals(TextPos(0, 2), pos)
    }

    @Test
    fun `exact hit misses outside the text area`() {
        val page = page()
        assertNull(page.hitTextPos(x = 5f, y = -50f, exact = true))
        assertNotNull(page.hitTextPos(x = 5f, y = -50f, exact = false))
    }

    @Test
    fun `selection rects cover the ordered range and swap reversed ends`() {
        val page = page()
        val start = TextPos(0, 2)
        val end = TextPos(1, 3)
        val forward = page.selectionRects(start, end)
        val backward = page.selectionRects(end, start)
        assertEquals(forward, backward)
        assertEquals(2, forward.size)
        // The first line's rect runs from the hit cluster to the line end.
        assertEquals(page.lines[0].columns[2].start, forward[0].left, 0.01f)
        assertEquals(page.lines[0].columns.last().end, forward[0].right, 0.01f)
        // The second line's rect starts at the line head.
        assertEquals(page.lines[1].columns.first().start, forward[1].left, 0.01f)
    }

    @Test
    fun `selected text matches the covered clusters`() {
        val page = page()
        val text = page.selectedText(TextPos(0, 0), TextPos(0, 4))
        assertEquals(page.lines[0].text.substring(0, 5), text)
    }

    @Test
    fun `word selection returns a non-empty range around the hit`() {
        val page = page()
        val (start, end) = page.wordSelectionAt(TextPos(0, 3))
        assertTrue(start <= TextPos(0, 3))
        assertTrue(end >= start)
        assertTrue(page.selectedText(start, end).isNotEmpty())
    }

    @Test
    fun `dragging a handle moves its edge and keeps the other fixed`() {
        val start = TextPos(0, 2)
        val end = TextPos(1, 3)
        val grown = dragSelectionHandle(start, end, hit = TextPos(0, 0), draggingStart = true)
        assertEquals(HandleDrag(TextPos(0, 0), end, draggingStart = true), grown)
        val shrunk = dragSelectionHandle(start, end, hit = TextPos(1, 0), draggingStart = false)
        assertEquals(HandleDrag(start, TextPos(1, 0), draggingStart = false), shrunk)
    }

    @Test
    fun `dragging a handle past the fixed edge swaps the handles`() {
        val start = TextPos(0, 2)
        val end = TextPos(1, 3)
        // Start handle dragged below the end: the selection flips and the drag owns the end.
        val flippedStart = dragSelectionHandle(start, end, hit = TextPos(1, 5), draggingStart = true)
        assertEquals(HandleDrag(end, TextPos(1, 5), draggingStart = false), flippedStart)
        // End handle dragged above the start: mirrored flip.
        val flippedEnd = dragSelectionHandle(start, end, hit = TextPos(0, 0), draggingStart = false)
        assertEquals(HandleDrag(TextPos(0, 0), start, draggingStart = true), flippedEnd)
    }

    @Test
    fun `dragging a handle onto the fixed edge collapses to one cluster without flipping`() {
        val start = TextPos(0, 2)
        val end = TextPos(1, 3)
        val collapsed = dragSelectionHandle(start, end, hit = end, draggingStart = true)
        assertEquals(HandleDrag(end, end, draggingStart = true), collapsed)
    }
}
