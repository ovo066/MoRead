package com.mozhi.reader.feature.reader.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReaderSpreadControllerTest {
    private class Listener : ReaderContentController.Listener {
        var onChange: () -> Unit = {}
        var positions = 0
        var ends = 0
        var errors = 0
        var sourceRevisions = 0
        override fun onSourceRevisionChanged() { sourceRevisions++ }
        override fun onContentError(chapterIndex: Int, error: Throwable) { errors++ }
        override fun onContentChanged(relativePosition: Int) = onChange()
        override fun onPositionChanged(chapterIndex: Int, charOffset: Int, pageIndex: Int, pageCount: Int, bookProgress: Float) {
            positions++
        }
        override fun onReachedBookEnd() { ends++ }
    }

    private val body = "春江潮水连海平海上明月共潮生".repeat(30)
    private val spec = TypesetSpec(
        visibleWidth = 100f, visibleHeight = 100f,
        contentLineStep = 25f, titleLineStep = 34f,
        paragraphSpacing = 0f, blankLineSpacing = 0f,
        titleTopSpacing = 0f, titleBottomSpacing = 0f
    )

    private fun TestScope.controller(
        listener: Listener = Listener(),
        count: Int = 105,
        loader: suspend (Int) -> ReaderChapterContent? = { ReaderChapterContent(body) }
    ): ReaderContentController = ReaderContentController(this, loader, listener).also {
        it.setChapters(List(count) { index -> ChapterMeta(index, "", body.length) })
    }

    private suspend fun TestScope.finishLayouts() {
        advanceUntilIdle()
        coroutineContext.job.children.toList().forEach { it.join() }
    }

    @Test
    fun `intra chapter frozen turn commits once when an unrelated neighbor finishes loading`() = runTest {
        val neighborGate = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()
        val listener = Listener()
        val controller = controller(listener, count = 2) { index ->
            if (index == 1) neighborGate.await()
            ReaderChapterContent(body)
        }
        listener.onChange = { if (controller.isReady) ready.complete(Unit) }
        controller.updateEnvironment(spec, FakeMeasure(), spread = true)
        ready.await()
        val turn = controller.captureTurn(forward = true)
        val target = (turn.targetPages.first() as RenderPage.Laid).page
        val originalGeneration = controller.layoutGeneration
        neighborGate.complete(Unit)
        finishLayouts()
        assertTrue(controller.layoutGeneration > originalGeneration)
        assertTrue(controller.canCommitTurn(turn))
        val before = listener.positions
        assertTrue(controller.commitTurn(turn)) // The exact API called by ReaderPane.fillPage.
        assertEquals(before + 1, listener.positions)
        assertEquals(2, controller.pageIndex)
        assertSame(target, (controller.curSpread().first as RenderPage.Laid).page)
        assertFalse(controller.commitTurn(turn)) // An old frozen frame cannot be committed twice.
    }

    @Test
    fun `forward and backward intra chapter turns survive neighbor annotation publication`() = runTest {
        val controller = controller(count = 2)
        controller.updateEnvironment(spec, FakeMeasure(), spread = true)
        finishLayouts()
        for (forward in listOf(true, false)) {
            val turn = controller.captureTurn(forward)
            val markers = if (forward) listOf(InlineMarkerReservation(3, InlineMarkerKind.ANNOTATION)) else emptyList()
            controller.setInlineMarkers(mapOf(1 to markers))
            finishLayouts()
            assertTrue(controller.canCommitTurn(turn))
            assertTrue(controller.commitTurn(turn))
            assertEquals(if (forward) 2 else 0, controller.pageIndex)
        }
    }

    @Test
    fun `cross chapter frozen turn rejects a repaginated destination even with unchanged source ink`() = runTest {
        val controller = controller(count = 2) { index -> ReaderChapterContent(if (index == 0) "正文" else body) }
        controller.updateEnvironment(spec, FakeMeasure(), spread = true)
        finishLayouts()
        val turn = controller.captureTurn(forward = true)
        assertEquals(1, turn.targetPages.first().chapterIndex)
        controller.setInlineMarkers(mapOf(1 to listOf(InlineMarkerReservation(3, InlineMarkerKind.ANNOTATION))))
        finishLayouts()
        assertEquals(turn.sourcePages, controller.turnPages(0))
        assertNotEquals(turn.targetPages, controller.turnPages(1))
        assertFalse(controller.commitTurn(turn))
        assertEquals(0, controller.chapterIndex)
        assertEquals(0, controller.charOffset)
    }

    @Test
    fun `frozen turn never rebases through viewport change explicit navigation or source replacement`() = runTest {
        val controller = controller(count = 2)
        controller.updateEnvironment(spec, FakeMeasure(), spread = true)
        finishLayouts()
        val resize = controller.captureTurn(true)
        controller.updateEnvironment(spec.copy(visibleWidth = 140f), FakeMeasure(), spread = true)
        finishLayouts()
        assertFalse(controller.commitTurn(resize))
        val jump = controller.captureTurn(true)
        controller.jumpToChapter(0, controller.charOffset) // Even returning to same ink is a new owner.
        assertFalse(controller.commitTurn(jump))
        val source = controller.captureTurn(true)
        controller.reloadFromSource()
        finishLayouts()
        assertFalse(controller.commitTurn(source))
    }

    @Test
    fun `failed pending scroll is dropped and a later preload cannot apply the obsolete offset`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()
        var fail = true
        val listener = Listener()
        val controller = controller(listener, count = 2) { index ->
            if (index == 1) {
                gate.await()
                if (fail) error("temporary failure")
            }
            ReaderChapterContent("正文".repeat(20))
        }
        listener.onChange = { if (controller.isReady) ready.complete(Unit) }
        controller.updateEnvironment(spec.copy(visibleHeight = 1000f), FakeMeasure())
        ready.await()
        controller.scrollWithinWindow(1, 5)
        gate.complete(Unit)
        finishLayouts()
        assertEquals(0, controller.chapterIndex)
        fail = false
        assertFalse(controller.hasNextSpread()) // Retry prefetch only, not another scroll request.
        finishLayouts()
        assertEquals(0, controller.chapterIndex)
        controller.scrollWithinWindow(1, 12)
        assertEquals(1, controller.chapterIndex)
        assertEquals(12, controller.charOffset)
    }

    @Test
    fun `completion latch resets on genuine source revision or open but not backturn or presentation`() = runTest {
        val listener = Listener()
        val controller = controller(listener, count = 1) { ReaderChapterContent("正文") }
        controller.updateEnvironment(spec, FakeMeasure(), spread = true)
        finishLayouts()
        assertEquals(1, listener.ends)
        controller.jumpToChapter(0)
        assertEquals(1, listener.ends)
        controller.reloadFromSource(resetBookEnd = false)
        finishLayouts()
        assertEquals(1, listener.ends)
        assertEquals(0, listener.sourceRevisions)
        controller.reloadFromSource()
        finishLayouts()
        assertEquals(2, listener.ends)
        assertEquals(1, listener.sourceRevisions)
        controller.openPosition(0, 0)
        assertEquals(3, listener.ends)
    }

    @Test
    fun `null source fails closed rather than manufacturing an empty readable chapter`() = runTest {
        val listener = Listener()
        val controller = controller(listener, count = 1) { null }
        controller.updateEnvironment(spec, FakeMeasure(), spread = true)
        finishLayouts()
        assertFalse(controller.isReady)
        assertTrue(controller.curSpread().first is RenderPage.Placeholder)
        assertFalse(controller.moveToNextSpread())
        assertFalse(controller.moveToNextPage())
        assertEquals(0, controller.charOffset)
        assertEquals(0, listener.ends)
        assertEquals(1, listener.errors)
        assertNull(controller.captureVisibleRead(controller.curSpread().toList()))
    }

    @Test
    fun `failed next source is not consumed and a subsequent gesture can retry`() = runTest {
        var available = false
        val controller = controller(count = 2) { index ->
            if (index == 1 && !available) null else ReaderChapterContent("正文")
        }
        controller.updateEnvironment(spec, FakeMeasure(), spread = true)
        finishLayouts()
        assertFalse(controller.moveToNextSpread())
        assertEquals(0, controller.chapterIndex)
        available = true
        assertFalse(controller.hasNextSpread()) // Requests retry, does not spend the position.
        finishLayouts()
        assertTrue(controller.hasNextSpread())
        assertTrue(controller.moveToNextSpread())
        assertEquals(1, controller.chapterIndex)
    }

    @Test
    fun `drawn snapshot rejects prefetched pages and frozen pages after marker reflow`() = runTest {
        val controller = controller(count = 2)
        controller.updateEnvironment(spec, FakeMeasure(), spread = true)
        finishLayouts()
        val frozen = controller.captureVisibleRead(controller.curSpread().toList())!!
        val prefetched = controller.captureVisibleRead(controller.nextSpread().toList())!!
        assertTrue(controller.isCurrentVisibleRead(frozen))
        assertFalse(controller.isCurrentVisibleRead(frozen.copy(displayEnd = frozen.displayEnd + 100)))
        assertNull(controller.rebaseDrawnSnapshot(prefetched))
        val right = controller.curSpread().second as RenderPage.Laid
        assertEquals(right.page.chapterPosition + right.page.charLength, frozen.displayEnd)
        controller.focus(right.page.chapterPosition + 1)
        assertTrue(controller.isCurrentVisibleRead(frozen)) // Focus is not the visible endpoint.
        controller.setInlineMarkers(mapOf(0 to listOf(InlineMarkerReservation(3, InlineMarkerKind.ANNOTATION))))
        finishLayouts()
        assertFalse(controller.isCurrentVisibleRead(frozen))
        assertNull(controller.rebaseDrawnSnapshot(frozen)) // Old bitmap cannot claim the new spread.
        val newlyDrawn = controller.captureVisibleRead(controller.curSpread().toList())!!
        assertTrue(controller.isCurrentVisibleRead(newlyDrawn))
    }

    @Test
    fun `unchanged drawn current ink can rebase after a neighbor-only layout`() = runTest {
        val controller = controller(count = 2)
        controller.updateEnvironment(spec, FakeMeasure(), spread = true)
        finishLayouts()
        val drawn = controller.captureVisibleRead(controller.curSpread().toList())!!
        controller.setInlineMarkers(mapOf(1 to listOf(InlineMarkerReservation(3, InlineMarkerKind.ANNOTATION))))
        finishLayouts()
        assertFalse(controller.isCurrentVisibleRead(drawn))
        val rebased = controller.rebaseDrawnSnapshot(drawn)!!
        assertSame(drawn.pages, rebased.pages)
        assertEquals(drawn.displayEnd, rebased.displayEnd)
        assertTrue(controller.isCurrentVisibleRead(rebased))
        controller.jumpToChapter(1)
        assertFalse(controller.isCurrentVisibleRead(rebased))
        assertNull(controller.rebaseDrawnSnapshot(rebased))
    }

    @Test
    fun `source replacement invalidates drawn provenance and automatic follow ownership`() = runTest {
        val controller = controller(count = 1)
        controller.updateEnvironment(spec, FakeMeasure(), spread = true)
        finishLayouts()
        val drawn = controller.captureVisibleRead(controller.curSpread().toList())!!
        val oldNavigation = controller.navigationGeneration
        val oldSource = controller.sourceGeneration
        controller.reloadFromSource()
        assertFalse(controller.isCurrentVisibleRead(drawn))
        assertTrue(controller.navigationGeneration > oldNavigation)
        assertTrue(controller.sourceGeneration > oldSource)
        finishLayouts()
        assertNull(controller.rebaseDrawnSnapshot(drawn))
    }

    @Test
    fun `102 to loading 103 cannot skip to 104 when 104 finishes first`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val ready102 = CompletableDeferred<Unit>()
        val ready104 = CompletableDeferred<Unit>()
        val listener = Listener()
        val controller = controller(listener) { index ->
            if (index == 103) gate.await()
            ReaderChapterContent("正文")
        }
        listener.onChange = {
            if (controller.chapterIndex == 102 && controller.isReady) ready102.complete(Unit)
            if (controller.chapterIndex == 103 && controller.laidChapter(1) != null) ready104.complete(Unit)
        }
        controller.updateEnvironment(spec, FakeMeasure())
        controller.openPosition(102, 0)
        ready102.await()
        assertTrue(controller.moveToNextPage())
        assertEquals(103, controller.chapterIndex)
        ready104.await()
        assertFalse(controller.isReady)
        assertTrue(controller.laidChapter(1) != null)
        assertFalse(controller.hasNextPage())
        assertFalse(controller.moveToNextPage())
        assertFalse(controller.hasPrevPage())
        assertFalse(controller.moveToPrevPage())
        assertEquals(103, controller.chapterIndex)
        assertEquals(0, controller.charOffset)
        gate.complete(Unit)
        finishLayouts()
        assertTrue(controller.isReady)
    }

    @Test
    fun `spread refuses unloaded target without partial progress then advances once`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val ready102 = CompletableDeferred<Unit>()
        val listener = Listener()
        val controller = controller(listener) { index ->
            if (index == 103) gate.await()
            ReaderChapterContent("正文")
        }
        listener.onChange = {
            if (controller.chapterIndex == 102 && controller.isReady) ready102.complete(Unit)
        }
        controller.updateEnvironment(spec, FakeMeasure(), spread = true)
        controller.openPosition(102, 0)
        ready102.await()
        assertTrue(controller.curSpread().second is RenderPage.Blank)
        assertFalse(controller.hasNextSpread())
        val before = listener.positions
        repeat(4) { assertFalse(controller.moveToNextSpread()) }
        assertEquals(before, listener.positions)
        assertEquals(102, controller.chapterIndex)
        assertTrue(controller.nextSpread().first is RenderPage.Placeholder)
        gate.complete(Unit)
        finishLayouts()
        val readyBefore = listener.positions
        assertTrue(controller.moveToNextSpread())
        assertEquals(readyBefore + 1, listener.positions)
        assertEquals(103, controller.chapterIndex)
        assertEquals(0, controller.curSpread().first.pageIndex)
    }

    @Test
    fun `spread back from 103 lands on odd last spread of 102 with blank right`() = runTest {
        val controller = controller(loader = { ReaderChapterContent("正文") })
        controller.updateEnvironment(spec, FakeMeasure(), spread = true)
        controller.openPosition(103, 0)
        finishLayouts()
        assertTrue(controller.hasPrevSpread())
        assertTrue(controller.moveToPrevSpread())
        assertEquals(102, controller.chapterIndex)
        assertEquals(0, controller.pageIndex)
        assertTrue(controller.curSpread().second is RenderPage.Blank)
        assertEquals(102, controller.curSpread().second.chapterIndex)
    }

    @Test
    fun `spread back refuses when previous chapter is not laid`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()
        val listener = Listener()
        val controller = controller(listener) { index ->
            if (index == 102) gate.await()
            ReaderChapterContent(body)
        }
        listener.onChange = { if (controller.chapterIndex == 103 && controller.isReady) ready.complete(Unit) }
        controller.updateEnvironment(spec, FakeMeasure(), spread = true)
        controller.openPosition(103, 0)
        ready.await()
        assertFalse(controller.hasPrevSpread())
        assertFalse(controller.moveToPrevSpread())
        assertEquals(103, controller.chapterIndex)
        gate.complete(Unit)
        finishLayouts()
    }

    @Test
    fun `spread moves two real pages with one publication and right leaf TTS stays visible`() = runTest {
        val listener = Listener()
        val controller = controller(listener, count = 1)
        controller.updateEnvironment(spec, FakeMeasure(), spread = true)
        finishLayouts()
        val first = controller.curSpread()
        val right = first.second as RenderPage.Laid
        val rightOffset = right.page.chapterPosition + 1
        assertTrue(controller.isDisplaying(0, rightOffset))
        controller.focus(rightOffset)
        assertEquals(rightOffset, controller.charOffset)
        assertEquals(0, controller.pageIndex)
        assertEquals(0, controller.curSpread().first.pageIndex)
        val next = controller.nextSpread().first as RenderPage.Laid
        assertFalse(controller.isDisplaying(0, next.page.chapterPosition + 1))
        val before = listener.positions
        assertTrue(controller.moveToNextSpread())
        assertEquals(before + 1, listener.positions)
        assertEquals(2, controller.pageIndex)
        assertEquals(next.page.chapterPosition, controller.charOffset)
        assertTrue(controller.moveToPrevSpread())
        assertEquals(0, controller.pageIndex)
    }

    @Test
    fun `resize and marker repagination preserve a right leaf focus anchor`() = runTest {
        val controller = controller(count = 1)
        controller.updateEnvironment(spec, FakeMeasure(), spread = true)
        finishLayouts()
        val right = controller.curSpread().second as RenderPage.Laid
        val anchor = right.page.chapterPosition + 1
        controller.focus(anchor)
        controller.setInlineMarkers(mapOf(0 to listOf(InlineMarkerReservation(5, InlineMarkerKind.ANNOTATION))))
        finishLayouts()
        assertEquals(anchor, controller.charOffset)
        assertTrue(controller.isDisplaying(0, anchor))
        controller.updateEnvironment(spec.copy(visibleWidth = 180f), FakeMeasure(), spread = false)
        finishLayouts()
        assertEquals(anchor, controller.charOffset)
        assertTrue(controller.isDisplaying(0, anchor))
        controller.updateEnvironment(spec, FakeMeasure(), spread = true)
        finishLayouts()
        assertEquals(anchor, controller.charOffset)
        assertTrue(controller.isDisplaying(0, anchor))
    }

    @Test
    fun `far chapter marker changes preserve visible layout identity and generation`() = runTest {
        val controller = controller()
        controller.updateEnvironment(spec, FakeMeasure(), spread = true)
        finishLayouts()
        val current = controller.laidChapter(0)
        val next = controller.laidChapter(1)
        val generation = controller.layoutGeneration
        controller.setInlineMarkers(mapOf(102 to listOf(InlineMarkerReservation(5, InlineMarkerKind.ANNOTATION))))
        finishLayouts()
        assertSame(current, controller.laidChapter(0))
        assertSame(next, controller.laidChapter(1))
        assertEquals(generation, controller.layoutGeneration)
    }

    @Test
    fun `marker relayout during turn retains real pages then invalidates old generation`() = runTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var block = false
        val measure = object : TextMeasure by FakeMeasure() {
            override fun breakLines(text: String, isTitle: Boolean, availableWidth: Float, firstLineIndent: Float): IntArray {
                if (block) {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                return FakeMeasure().breakLines(text, isTitle, availableWidth, firstLineIndent)
            }
        }
        val controller = controller(count = 2)
        controller.updateEnvironment(spec, measure, spread = true)
        finishLayouts()
        controller.moveToNextSpread()
        val anchor = controller.charOffset
        val old = controller.laidChapter(0)
        val neighbor = controller.laidChapter(1)
        val frozenTurn = controller.captureTurn(forward = true)
        block = true
        controller.setInlineMarkers(mapOf(0 to listOf(InlineMarkerReservation(3, InlineMarkerKind.ANNOTATION))))
        runCurrent()
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertSame(old, controller.laidChapter(0))
            assertSame(neighbor, controller.laidChapter(1))
            assertTrue(controller.curSpread().first is RenderPage.Laid)
            assertTrue(controller.curSpread().second is RenderPage.Laid)
        } finally {
            release.countDown()
        }
        finishLayouts()
        assertFalse(controller.commitTurn(frozenTurn))
        assertEquals(anchor, controller.charOffset)
        assertTrue(controller.isDisplaying(0, anchor))
        assertSame(neighbor, controller.laidChapter(1))
    }

    @Test
    fun `odd final chapter notifies book end once and blank adds no progress`() = runTest {
        val listener = Listener()
        val controller = controller(listener, count = 1) { ReaderChapterContent("正文") }
        controller.updateEnvironment(spec, FakeMeasure(), spread = true)
        finishLayouts()
        assertTrue(controller.isLastPageReady())
        assertTrue(controller.curSpread().second is RenderPage.Blank)
        repeat(5) { assertFalse(controller.moveToNextSpread()) }
        assertEquals(1, listener.ends)
        assertEquals(0, controller.charOffset)
    }

    @Test
    fun `viewport relayout and explicit jumps invalidate a frozen turn`() = runTest {
        val controller = controller(count = 2)
        controller.updateEnvironment(spec, FakeMeasure(), spread = true)
        finishLayouts()
        val frozenTurn = controller.captureTurn(forward = true)
        controller.updateEnvironment(spec.copy(visibleWidth = 140f), FakeMeasure(), spread = true)
        assertFalse(controller.commitTurn(frozenTurn))
        assertFalse(controller.hasNextSpread())
        assertFalse(controller.moveToNextSpread())
        finishLayouts()
        val nextTurn = controller.captureTurn(forward = true)
        controller.jumpToChapter(1)
        assertFalse(controller.commitTurn(nextTurn))
        assertFalse(controller.positionChangeIsSequential)
    }

    @Test
    fun `scroll cannot consume a placeholder chapter before layout arrives`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()
        val listener = Listener()
        val controller = controller(listener, count = 3) { index ->
            if (index == 1) gate.await()
            ReaderChapterContent(body)
        }
        listener.onChange = { if (controller.isReady) ready.complete(Unit) }
        controller.updateEnvironment(spec, FakeMeasure())
        ready.await()
        controller.scrollWithinWindow(1, 20)
        assertEquals(0, controller.chapterIndex)
        assertEquals(0, controller.charOffset)
        gate.complete(Unit)
        finishLayouts()
        assertEquals(1, controller.chapterIndex)
        assertEquals(20, controller.charOffset)
    }
}
