package com.mozhi.reader.feature.reader.engine

import com.mozhi.reader.core.library.EpubComputedStyle
import com.mozhi.reader.core.library.EpubElementRef
import com.mozhi.reader.core.library.EpubLayoutBlock
import com.mozhi.reader.core.library.EpubLayoutBlockKind
import com.mozhi.reader.core.library.EpubLayoutChapter
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.library.EpubTextAlign
import kotlinx.coroutines.CompletableDeferred
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
        var contentErrors = 0
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

        override fun onContentError(chapterIndex: Int, error: Throwable) {
            contentErrors++
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

    private fun chapterBody(index: Int): String =
        List(6) { paragraph }.joinToString("\n")

    private fun metas(count: Int): List<ChapterMeta> = List(count) { index ->
        ChapterMeta(index, "第${index + 1}章", chapterBody(index).length)
    }

    @Test
    fun `opens at the stored position and derives the page`() = runTest {
        val listener = RecordingListener()
        val controller = ReaderContentController(
            this,
            { ReaderChapterContent(chapterBody(it)) },
            listener
        )
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
        val controller = ReaderContentController(
            this,
            { ReaderChapterContent(chapterBody(it)) },
            listener
        )
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
        val controller = ReaderContentController(
            this,
            { ReaderChapterContent(chapterBody(it)) },
            listener
        )
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
        val controller = ReaderContentController(
            this,
            { ReaderChapterContent(chapterBody(it)) },
            listener
        )
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
                ReaderChapterContent(chapterBody(index))
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
        val controller = ReaderContentController(
            this,
            { ReaderChapterContent(chapterBody(it)) },
            listener
        )
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

    @Test
    fun `invalidating epub layouts reloads styles after an initial plain text fallback`() = runTest {
        val body = "居中标题"
        var availableLayout: EpubLayoutChapterBundle? = null
        var layoutLoads = 0
        val listener = RecordingListener()
        val controller = ReaderContentController(
            scope = this,
            chapterLoader = {
                layoutLoads++
                ReaderChapterContent(body, availableLayout)
            },
            listener = listener
        )
        controller.setChapters(listOf(ChapterMeta(0, "", body.length)))
        controller.updateEnvironment(spec(), FakeMeasure())
        controller.openPosition(0, 0)
        advanceUntilIdle()
        coroutineContext.job.children.forEach { it.join() }

        val plainPage = controller.curPage() as RenderPage.Laid
        val plainStart = plainPage.page.lines.single().startX
        assertTrue(plainPage.page.decorations.isEmpty())

        availableLayout = EpubLayoutChapterBundle(
            document = EpubLayoutChapter(
                chapterIndex = 0,
                href = "chapter.xhtml",
                blocks = listOf(
                    EpubLayoutBlock(
                        orderIndex = 0,
                        kind = EpubLayoutBlockKind.HEADING,
                        textStart = 0,
                        textEnd = body.length,
                        element = EpubElementRef("h1"),
                        style = EpubComputedStyle(
                            textAlign = EpubTextAlign.CENTER,
                            fontWeight = 700,
                            backgroundColorArgb = 0xFFEEDDCC.toInt()
                        )
                    )
                ),
                textLength = body.length
            ),
            resourcePaths = emptyMap(),
            fontPaths = emptyMap()
        )
        controller.reloadFromSource()
        advanceUntilIdle()
        coroutineContext.job.children.forEach { it.join() }

        val styledPage = controller.curPage() as RenderPage.Laid
        assertTrue(layoutLoads >= 2)
        assertTrue(styledPage.page.lines.single().startX > plainStart)
        assertTrue(styledPage.page.decorations.isNotEmpty())
        assertTrue(styledPage.page.lines.single().columns.all { it.syntaxBold })
    }

    @Test
    fun `broken native layout falls back to plain text instead of spinning forever`() = runTest {
        val body = "可正常阅读"
        val listener = RecordingListener()
        val throwingMeasure = object : TextMeasure by FakeMeasure() {
            override fun charWidths(text: String, style: MeasuredTextStyle): FloatArray {
                if (style.fontFamily == "broken-font") error("broken embedded font")
                return FakeMeasure().charWidths(text, style)
            }
        }
        val bundle = EpubLayoutChapterBundle(
            document = EpubLayoutChapter(
                chapterIndex = 0,
                href = "chapter.xhtml",
                blocks = listOf(
                    EpubLayoutBlock(
                        orderIndex = 0,
                        kind = EpubLayoutBlockKind.PARAGRAPH,
                        textStart = 0,
                        textEnd = body.length,
                        element = EpubElementRef("p"),
                        style = EpubComputedStyle(fontFamily = "broken-font")
                    )
                ),
                textLength = body.length
            ),
            resourcePaths = emptyMap(),
            fontPaths = emptyMap()
        )
        val controller = ReaderContentController(
            scope = this,
            chapterLoader = { ReaderChapterContent(body, bundle) },
            listener = listener
        )
        controller.setChapters(listOf(ChapterMeta(0, "", body.length)))
        controller.updateEnvironment(spec(), throwingMeasure)
        controller.openPosition(0, 0)
        advanceUntilIdle()
        coroutineContext.job.children.forEach { it.join() }

        assertTrue(controller.isReady)
        assertTrue(controller.curPage() is RenderPage.Laid)
        assertEquals(0, listener.contentErrors)
    }

    @Test
    fun `reload ignores content returned by the previous source version`() = runTest {
        var source = "old"
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val controller = ReaderContentController(
            scope = this,
            chapterLoader = {
                val captured = source
                if (captured == "old") {
                    oldStarted.complete(Unit)
                    releaseOld.await()
                }
                ReaderChapterContent(captured)
            },
            listener = RecordingListener()
        )
        controller.setChapters(listOf(ChapterMeta(0, "", 3)))
        controller.updateEnvironment(spec(), FakeMeasure())
        controller.openPosition(0, 0)
        oldStarted.await()

        source = "new"
        controller.reloadFromSource()
        releaseOld.complete(Unit)
        advanceUntilIdle()
        coroutineContext.job.children.forEach { it.join() }

        assertEquals("new", controller.chapterBody(0))
    }
}
