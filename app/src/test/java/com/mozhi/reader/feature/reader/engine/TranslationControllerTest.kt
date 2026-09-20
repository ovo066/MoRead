package com.mozhi.reader.feature.reader.engine

import com.mozhi.reader.core.dictionary.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class TranslationControllerTest {
    @Test fun changingSavedWordGlossReplacesVisibleAnnotationWithoutMovingTheReadingAnchor() = runTest {
        val body = List(8) { "A reader opens a book by the window." }.joinToString("\n")
        val controller = ReaderContentController(this, { ReaderChapterContent(body) }, object : ReaderContentController.Listener {
            override fun onContentChanged(relativePosition: Int) = Unit
            override fun onPositionChanged(chapterIndex: Int, charOffset: Int, pageIndex: Int, pageCount: Int, bookProgress: Float) = Unit
        })
        controller.setChapters(listOf(ChapterMeta(0, "", body.length)))
        val oldStyle = TypesetSpec(240f, 220f, 25f, 32f, 8f, 8f, 0f, 0f,
            wordGlosses = mapOf("book" to WordGloss("书本", "/bʊk/")))
        controller.updateEnvironment(oldStyle, FakeMeasure())
        val anchor = englishParagraphs(body)[3].start
        controller.openPosition(0, anchor)
        suspend fun awaitLayout() {
            advanceUntilIdle()
            coroutineContext.job.children.forEach { it.join() }
        }
        awaitLayout()
        fun annotations() = (controller.curPage() as RenderPage.Laid).page.lines.flatMap { it.rubyPlacements }.map { it.text }
        assertTrue(annotations().contains("书本"))
        controller.updateEnvironment(oldStyle.copy(wordGlosses = mapOf("book" to WordGloss("预订"))), FakeMeasure())
        awaitLayout()
        assertTrue(annotations().contains("预订"))
        assertFalse(annotations().contains("书本"))
        assertFalse(annotations().contains("/bʊk/"))
        assertEquals(anchor, controller.charOffset)
    }

    @Test fun togglingAndReceivingTranslationsRetainsOriginalPositionAndExistingPageDuringReflow() = runTest {
        val body = List(8) { "A reader opens a book by the window." }.joinToString("\n")
        val paragraphs = englishParagraphs(body)
        val cache = paragraphs.map { ParagraphTranslation(it.start, it.end, it.key, "读者在窗边翻开书。".repeat(5)) }
        val controller = ReaderContentController(this, { ReaderChapterContent(body, translations = cache) }, object : ReaderContentController.Listener {
            override fun onContentChanged(relativePosition: Int) = Unit
            override fun onPositionChanged(chapterIndex: Int, charOffset: Int, pageIndex: Int, pageCount: Int, bookProgress: Float) = Unit
        })
        controller.setChapters(listOf(ChapterMeta(0, "", body.length)))
        controller.updateEnvironment(TypesetSpec(200f, 160f, 25f, 32f, 8f, 8f, 0f, 0f), FakeMeasure())
        val anchor = paragraphs[4].start
        controller.openPosition(0, anchor)
        suspend fun awaitLayout(predicate: () -> Boolean) {
            advanceUntilIdle()
            coroutineContext.job.children.forEach { it.join() }
            assertTrue(predicate())
        }
        awaitLayout { controller.curPage() is RenderPage.Laid }
        val original = controller.curPage() as RenderPage.Laid
        controller.setTranslationsVisible(true)
        assertSame(original.page, (controller.curPage() as RenderPage.Laid).page)
        awaitLayout { (controller.curPage() as? RenderPage.Laid)?.pageCount != original.pageCount }
        assertEquals(anchor, controller.charOffset)
        val expanded = controller.curPage() as RenderPage.Laid
        controller.setTranslationsVisible(false)
        assertSame(expanded.page, (controller.curPage() as RenderPage.Laid).page)
        awaitLayout { (controller.curPage() as? RenderPage.Laid)?.pageCount == original.pageCount }
        assertEquals(anchor, controller.charOffset)
        assertEquals(original.pageIndex, controller.curPage().pageIndex)
    }
}
