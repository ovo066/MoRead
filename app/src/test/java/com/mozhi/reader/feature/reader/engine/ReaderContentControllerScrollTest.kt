package com.mozhi.reader.feature.reader.engine

import kotlinx.coroutines.job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/** 滚动模式的位置推进：滑窗随 scrollTo 平移、邻章保温、同章推进不惊动内容回调。 */
class ReaderContentControllerScrollTest {

    private class RecordingListener : ReaderContentController.Listener {
        var contentChanges = 0
        var lastChapter = -1
        var lastOffset = -1
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
        }
    }

    private val paragraph = "春江潮水连海平海上明月共潮生滟滟随波千万里何处春江无月明"

    private fun spec() = TypesetSpec(
        visibleWidth = 100f,
        visibleHeight = 200f,
        contentLineStep = 25f,
        titleLineStep = 34f,
        paragraphSpacing = 9f,
        blankLineSpacing = 9f,
        titleTopSpacing = 10f,
        titleBottomSpacing = 25f
    )

    private fun chapterBody(): String = List(6) { paragraph }.joinToString("\n")

    private fun metas(count: Int): List<ChapterMeta> = List(count) { index ->
        ChapterMeta(index, "第${index + 1}章", chapterBody().length)
    }

    @Test
    fun `scrolling into the next chapter slides the window and preloads ahead`() = runTest {
        val listener = RecordingListener()
        val controller = ReaderContentController(
            this,
            { ReaderChapterContent(chapterBody()) },
            listener
        )
        controller.setChapters(metas(4))
        controller.updateEnvironment(spec(), FakeMeasure())
        controller.openPosition(0, 0)
        advanceUntilIdle()
        coroutineContext.job.children.forEach { it.join() }

        val nextBefore = controller.laidChapter(1)
        assertNotNull(nextBefore)

        controller.scrollTo(1, 5)
        assertEquals(1, controller.chapterIndex)
        assertEquals(5, controller.charOffset)
        assertEquals(1, listener.lastChapter)
        assertEquals(5, listener.lastOffset)
        // 原来的下一章原样变成当前章，不重排。
        assertSame(nextBefore, controller.laidChapter(0))

        advanceUntilIdle()
        coroutineContext.job.children.forEach { it.join() }
        assertNotNull("跨章后应预排新的下一章", controller.laidChapter(1))
    }

    @Test
    fun `scrolling backwards reuses the previous chapter's layout`() = runTest {
        val listener = RecordingListener()
        val controller = ReaderContentController(
            this,
            { ReaderChapterContent(chapterBody()) },
            listener
        )
        controller.setChapters(metas(4))
        controller.updateEnvironment(spec(), FakeMeasure())
        controller.openPosition(1, 0)
        advanceUntilIdle()
        coroutineContext.job.children.forEach { it.join() }

        val prevBefore = controller.laidChapter(-1)
        assertNotNull(prevBefore)

        controller.scrollTo(0, 12)
        assertEquals(0, controller.chapterIndex)
        assertEquals(12, controller.charOffset)
        assertSame(prevBefore, controller.laidChapter(0))
    }

    @Test
    fun `scrolling within the chapter publishes position without content churn`() = runTest {
        val listener = RecordingListener()
        val controller = ReaderContentController(
            this,
            { ReaderChapterContent(chapterBody()) },
            listener
        )
        controller.setChapters(metas(2))
        controller.updateEnvironment(spec(), FakeMeasure())
        controller.openPosition(0, 0)
        advanceUntilIdle()
        coroutineContext.job.children.forEach { it.join() }

        val changesBefore = listener.contentChanges
        controller.scrollTo(0, 30)
        assertEquals(30, controller.charOffset)
        assertEquals(30, listener.lastOffset)
        assertEquals("同章滚动不应触发内容重绘回调", changesBefore, listener.contentChanges)
    }

    @Test
    fun `a far scroll rebinds the whole window`() = runTest {
        val listener = RecordingListener()
        val controller = ReaderContentController(
            this,
            { ReaderChapterContent(chapterBody()) },
            listener
        )
        controller.setChapters(metas(6))
        controller.updateEnvironment(spec(), FakeMeasure())
        controller.openPosition(0, 0)
        advanceUntilIdle()
        coroutineContext.job.children.forEach { it.join() }

        controller.scrollTo(4, 0)
        assertEquals(4, controller.chapterIndex)
        advanceUntilIdle()
        coroutineContext.job.children.forEach { it.join() }
        assertNotNull(controller.laidChapter(0))
        assertNotNull(controller.laidChapter(-1))
        assertNotNull(controller.laidChapter(1))
    }
}
