package com.mozhi.reader.feature.reader.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import com.mozhi.reader.core.datastore.PublisherStyleMode
import com.mozhi.reader.core.epub.style.EpubDecorationStyle
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.feature.importer.EpubLayoutDocumentParser
import com.mozhi.reader.feature.reader.engine.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpubPublisherStyleRenderTest {
    @Test fun publishedLinkColorAndWaveSurviveRealPaintAndExplicitNoUnderlineIsRespected() {
        val html = """<html><head><style>p{margin:0;text-indent:0}a{color:red;text-decoration:none}.name{text-decoration:underline wavy blue}</style></head><body><p><a href='#note'>REDLINK</a></p><p><span class='name'>司马迁史记</span></p><p id='note'>这是注释。</p></body></html>"""
        val parsed = EpubLayoutDocumentParser().parseWithText(html.toByteArray(), 0, "OPS/ch.xhtml", emptyMap())
        val style = pageStyle()
        val chapter = ChapterTypesetter(style.spec, style.measure).typeset(0, "", parsed.text,
            epubLayout = EpubLayoutChapterBundle(parsed.document, emptyMap(), emptyMap(), dom = parsed.dom))
        val columns = chapter.pages.flatMap { it.lines }.flatMap { it.columns }
        assertTrue(columns.filter { it.linkHref != null }.all { it.linkUnderlineOverride == false && it.syntaxColorArgb == Color.RED })
        assertTrue(columns.any { it.decorationStyle == EpubDecorationStyle.WAVY && it.decorationColorArgb == Color.BLUE })
        val renderer = PageBitmapRenderer(style)
        val bitmap = renderer.render(RenderPage.Laid(0, "", 0, chapter.pageCount, chapter.pages.first()), null, 0f, "", 100)
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Publisher red must not be replaced by green reader accent", pixels.any { Color.red(it) > 180 && Color.green(it) < 80 && Color.blue(it) < 80 })
            assertTrue("Wave decoration must be painted in publisher blue", pixels.any { Color.blue(it) > 180 && Color.red(it) < 80 && Color.green(it) < 80 })
            val output = File("build/reports/ui-qa/epub-publisher-style.png").apply { parentFile!!.mkdirs() }
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle(); renderer.release() }
    }

    @Test fun wavePhaseJoinsAcrossClustersAndVariesVertically() {
        val bitmap = Bitmap.createBitmap(200, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val canvas = Canvas(bitmap)
        val painter = TextDecorationPainter()
        for (left in 0 until 200 step 20) painter.draw(canvas, left.toFloat(), (left + 20).toFloat(), 25f, 30f, Color.BLUE, 1f, EpubDecorationStyle.WAVY)
        val heights = (0 until 200).map { x -> (15..35).filter { bitmap.getPixel(x, it) != Color.WHITE }.average() }
        assertTrue(heights.all { !it.isNaN() })
        assertTrue(heights.max() - heights.min() > 3)
        bitmap.recycle()
    }

    @Test fun respectModeKeepsPanelAndBorderColorsTogetherWithPublisherTextInNightTheme() {
        val parsed = EpubLayoutDocumentParser().parseWithText(
            "<html><body><div style='background-color:white;color:black;border:1px solid red'>原书纸面</div></body></html>".toByteArray(),
            0, "OPS/ch.xhtml", emptyMap())
        val style = pageStyle()
        val chapter = ChapterTypesetter(style.spec.copy(darkTheme = true, themeBackgroundArgb = Color.BLACK), style.measure)
            .typeset(0, "", parsed.text, epubLayout = EpubLayoutChapterBundle(parsed.document, emptyMap(), emptyMap(), dom = parsed.dom))
        val panel = chapter.pages.flatMap { it.decorations }.first { it.backgroundColorArgb != null }
        assertEquals(Color.WHITE, panel.backgroundColorArgb)
        assertEquals(Color.RED, panel.borderTopColorArgb)
        assertTrue(chapter.pages.flatMap { it.lines }.flatMap { it.columns }.all { it.syntaxColorArgb == Color.BLACK })
    }

    @Test fun halfOpacityAppliesOnceToWaveAsItDoesToText() {
        val parsed = EpubLayoutDocumentParser().parseWithText(
            "<html><body><span style='opacity:.5;color:blue;text-decoration:underline wavy'>HALF</span></body></html>".toByteArray(),
            0, "OPS/ch.xhtml", emptyMap())
        val style = pageStyle()
        val chapter = ChapterTypesetter(style.spec, style.measure).typeset(0, "", parsed.text,
            epubLayout = EpubLayoutChapterBundle(parsed.document, emptyMap(), emptyMap(), dom = parsed.dom))
        val renderer = PageBitmapRenderer(style)
        val bitmap = renderer.render(RenderPage.Laid(0, "", 0, chapter.pageCount, chapter.pages.first()), null, 0f, "", 100)
        try {
            val bluePixels = (0 until bitmap.height).flatMap { y -> (0 until bitmap.width).mapNotNull { x ->
                val pixel = bitmap.getPixel(x, y)
                if (Color.blue(pixel) > 240 && Color.red(pixel) < 245 && Color.green(pixel) < 245) y to Color.green(pixel) else null
            } }
            val bottom = bluePixels.maxOf { it.first }
            val waveGreen = bluePixels.filter { it.first >= bottom - 4 }.minOf { it.second }
            assertTrue("Half-opacity blue over white must remain near 128, not be faded twice: $waveGreen", waveGreen in 120..160)
        } finally { bitmap.recycle(); renderer.release() }
    }

    private fun pageStyle() = ReaderPageStyle(
        viewWidth = 411, viewHeight = 600, paddingLeft = 24f, paddingRight = 24f,
        headerHeight = 0f, footerHeight = 0f, contentPaddingTop = 24f, contentPaddingBottom = 24f,
        immersiveContentTop = 0f, immersiveContentBottom = 600f, headerOffset = 0f, footerOffset = 0f,
        contentSizePx = 26f, titleSizePx = 30f, tipSizePx = 12f, lineStep = 40f,
        backgroundColor = Color.WHITE, textColor = Color.BLACK, mutedColor = Color.GRAY, accentColor = Color.GREEN,
        isDark = false, grain = false, typeface = Typeface.DEFAULT, customFontPath = null, customFontPaths = emptyMap(),
        showHeader = false, showFooter = false, backgroundImagePath = null, backgroundImageOpacity = 1f,
        preferReaderBackground = false, publisherStyleMode = PublisherStyleMode.RESPECT, syntaxHighlightRules = emptyList(),
        titleTopSpacingLines = 0f, titleBottomSpacingLines = 0f, paragraphSpacingEm = .5f, firstLineIndentEm = 0f,
        textJustification = false, letterSpacingEm = 0f)
}
