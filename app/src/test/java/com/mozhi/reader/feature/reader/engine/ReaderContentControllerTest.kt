package com.mozhi.reader.feature.reader.engine

import kotlinx.coroutines.job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderContentControllerTest {

    private class RecordingListener : ReaderContentController.Listener {
        var contentChanges = 0
        var lastChapter = -1
        var lastOffset = -1
        var lastPageIndex = -1
        override fun onContentChanged(relativePosition: Int) {
            contentChanges++
        }

        override fun onPositionChanged(
            chapterIndex: Int,
            charOffset: Int,
            pageIndex: Int,
            pageCount: Int,
            bookProgress: Float
        ) {
            lastChapter = chapterIndex
            lastOffset = charOffset
            lastPageIndex = pageIndex
        }
    }

    private val paragraph = "春江潮水连海平海上明月共潮生滟滟随波千万里何处春江无月明"

    private fun spec() = TypesetSpec(
        visibleWidth = 100f,
        visibleHeight = 200f,
        contentLineStep = 25f,
        titleLineStep = 34f,
        paragraphSpacing = 9f,
        titleTopSpacing = 10f,
        titleBottomSpacing = 25f
    )

    private fun chapterBody(index: Int): String =
        List(6) { paragraph }.joinToString("\n")

    private fun metas(count: Int): List<ChapterMeta> = List(count) { index ->
        ChapterMeta(index, "第${index + 1}章", chapterBody(index).length)
    }

    @Test
    fun `opens at the stored position and derives the page`() = runTest {
        val listener = RecordingListener()
        val controller = ReaderContentController(this, { chapterBody(it) }, listener)
        controller.setChapters(metas(3))
        controller.updateEnvironment(spec(), FakeMeasure())
        controller.openPosition(1, 40)
        advanceUntilIdle()
        coroutineContext.job.children.forEach { it.join() }

        assertEquals(1, listener.lastChapter)
        assertTrue(controller.isReady)
        val page = controller.curPage()
        assertTrue(page is RenderPage.Laid)
        page as RenderPage.Laid
        assertTrue(page.page.chapterPosition <= 40)
    }

    @Test
    fun `turning past the last page crosses into the next chapter`() = runTest {
        val listener = RecordingListener()
        val controller = ReaderContentController(this, { chapterBody(it) }, listener)
        controller.setChapters(metas(3))
        controller.updateEnvironment(spec(), FakeMeasure())
        controller.openPosition(0, 0)
        advanceUntilIdle()
        coroutineContext.job.children.forEach { it.join() }

        var guard = 0
        while (controller.pageIndex < controller.pageCount - 1 && guard++ < 50) {
            assertTrue(controller.moveToNextPage())
        }
        assertTrue(controller.hasNextPage())
        assertTrue(controller.moveToNextPage())
        assertEquals(1, controller.chapterIndex)
        assertEquals(0, controller.charOffset)
    }

    @Test
    fun `moving back across the boundary lands on the previous chapter's last page`() = runTest {
        val listener = RecordingListener()
        val controller = ReaderContentController(this, { chapterBody(it) }, listener)
        controller.setChapters(metas(3))
        controller.updateEnvironment(spec(), FakeMeasure())
        controller.openPosition(1, 0)
        advanceUntilIdle()
        coroutineContext.job.children.forEach { it.join() }

        assertTrue(controller.hasPrevPage())
        assertTrue(controller.moveToPrevPage())
        assertEquals(0, controller.chapterIndex)
        assertEquals(controller.pageCount - 1, controller.pageIndex)
    }

    @Test
    fun `boundaries refuse at the very start and end`() = runTest {
        val listener = RecordingListener()
        val controller = ReaderContentController(this, { chapterBody(it) }, listener)
        controller.setChapters(metas(2))
        controller.updateEnvironment(spec(), FakeMeasure())
        controller.openPosition(0, 0)
        advanceUntilIdle()
        coroutineContext.job.children.forEach { it.join() }

        assertFalse(controller.hasPrevPage())
        assertFalse(controller.moveToPrevPage())

        controller.jumpToChapter(1)
        advanceUntilIdle()
        coroutineContext.job.children.forEach { it.join() }
        while (controller.pageIndex < controller.pageCount - 1) {
            assertTrue(controller.moveToNextPage())
        }
        assertFalse(controller.hasNextPage())
        assertFalse(controller.moveToNextPage())
    }

    @Test
    fun `refuses to advance chapters while nothing is laid out`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val listener = RecordingListener()
        val controller = ReaderContentController(
            this,
            { index ->
                gate.await()
                chapterBody(index)
            },
            listener
        )
        controller.setChapters(metas(3))
        controller.updateEnvironment(spec(), FakeMeasure())
        controller.openPosition(1, 40)

        // Nothing is laid out yet: taps on the placeholder must not skip chapters or move offsets.
        org.junit.Assert.assertFalse(controller.moveToNextPage())
        org.junit.Assert.assertFalse(controller.moveToPrevPage())
        assertEquals(1, controller.chapterIndex)
        assertEquals(40, controller.charOffset)

        gate.complete(Unit)
        advanceUntilIdle()
        coroutineContext.job.children.forEach { it.join() }
        assertTrue(controller.isReady)
    }

    @Test
    fun `progress jump resolves through cumulative char counts`() = runTest {
        val listener = RecordingListener()
        val controller = ReaderContentController(this, { chapterBody(it) }, listener)
        controller.setChapters(metas(4))
        controller.updateEnvironment(spec(), FakeMeasure())
        controller.openPosition(0, 0)
        advanceUntilIdle()
        coroutineContext.job.children.forEach { it.join() }

        controller.jumpToProgress(0.5f)
        advanceUntilIdle()
        coroutineContext.job.children.forEach { it.join() }
        assertEquals(2, controller.chapterIndex)
        assertTrue(kotlin.math.abs(controller.bookProgress() - 0.5f) < 0.05f)
    }
}
