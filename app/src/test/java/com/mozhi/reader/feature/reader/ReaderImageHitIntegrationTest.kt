package com.mozhi.reader.feature.reader

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.feature.importer.EpubLayoutDocumentParser
import com.mozhi.reader.feature.reader.engine.*
import com.mozhi.reader.feature.reader.render.ReaderPageStyle
import com.mozhi.reader.feature.reader.render.SpreadGeometry
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uses the real typesetter/controller and surface coordinate mapping, not prebuilt rectangles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReaderImageHitIntegrationTest {
    @Test fun paginatedImageUsesContentInsets() = runTest { verify(scroll = false, spread = false) }
    @Test fun spreadImagesUseTheCorrectLeafAndNeverHitTheGutter() = runTest { verify(scroll = false, spread = true) }
    @Test fun scrollingImageUsesItsMovingStripOrigin() = runTest { verify(scroll = true, spread = false) }

    private suspend fun TestScope.verify(scroll: Boolean, spread: Boolean) {
        val parsed = EpubLayoutDocumentParser().parseWithText("""<html><head><style>
            p{margin:0;text-indent:0}img{width:100%} .next{page-break-before:always}
            </style></head><body><p>第一处图片</p><p><img src="art.png"/></p>
            <div class="next"><p>第二处图片</p><p><img src="art.png"/></p></div></body></html>""".toByteArray(),
            0, "OPS/ch.xhtml", emptyMap())
        val content = ReaderChapterContent(parsed.text,
            EpubLayoutChapterBundle(parsed.document, mapOf("OPS/art.png" to "fixture.png"), emptyMap(), dom = parsed.dom),
            parsed.images.map { InlineImageSource(it.charOffset, "fixture.png", 300, 150, it.altText) })
        val controller = ReaderContentController(this, { content }, object : ReaderContentController.Listener {
            override fun onContentChanged(relativePosition: Int) = Unit
            override fun onPositionChanged(chapterIndex: Int, charOffset: Int, pageIndex: Int, pageCount: Int, bookProgress: Float) = Unit
        })
        val paged = ReaderPaneHolder(controller)
        val scrolling = ScrollPaneHolder(controller)
        val geometry = if (spread) SpreadGeometry(700f, 700f, 20f) else null
        val width = if (spread) 700 else 360
        val settings = ReaderSettings()
        val palette = readerPalette(settings.theme, false, Color.Blue)
        val key = readerRenderStyleKey(settings, palette, Density(1f), IntSize(width, 700), 20f, 12f, geometry)
        fun style() = ReaderPageStyle.resolve(settings, palette, Density(1f), geometry?.leafWidth?.toInt() ?: width,
            700, 20f, 12f, key.environment.columnWidth)
        try {
            if (scroll) scrolling.applyStyle(key, ::style, {}) else {
                paged.setViewport(width, 700)
                paged.applyStyle(key, ::style, geometry, false, {})
            }
            controller.setChapters(listOf(ChapterMeta(0, "测试", parsed.text.length)))
            controller.openPosition(0, 0)
            coroutineContext.job.children.toList().forEach { it.join() }
            if (scroll) scrolling.onContentRefreshed()
            val before = controller.charOffset
            if (scroll) {
                repeat(2) { pass ->
                    val visible = scrolling.visiblePages(0).first()
                    val line = visible.page.lines.first { it.inlineGlyphImages.isNotEmpty() || it.inlineImages.isNotEmpty() }
                    val image = (line.inlineImages + line.inlineGlyphImages).first()
                    val point = visible.origin + Offset(image.left + image.width / 2, line.lineTop + image.topOffset + image.height / 2)
                    assertEquals(parsed.images.first().charOffset, scrolling.imageAt(point)?.charOffset)
                    assertNull(scrolling.imageAt(Offset(0f, point.y)))
                    if (pass == 0) scrolling.applyScroll(12f)
                }
            } else {
                val pages = paged.visiblePages()
                assertEquals(if (spread) 2 else 1, pages.size)
                pages.forEachIndexed { index, (laid, origin) ->
                    val line = laid.page.lines.first { it.inlineGlyphImages.isNotEmpty() || it.inlineImages.isNotEmpty() }
                    val image = (line.inlineImages + line.inlineGlyphImages).first()
                    val point = origin + Offset(image.left + image.width / 2, line.lineTop + image.topOffset + image.height / 2)
                    assertEquals(parsed.images[index].charOffset, paged.imageAt(point)?.charOffset)
                    assertNull(paged.imageAt(Offset(point.x, 0f)))
                }
                geometry?.let { assertNull(paged.imageAt(Offset(it.spineX, 200f))) }
            }
            assertEquals(before, controller.charOffset)
        } finally { paged.release(); scrolling.release() }
    }
}
