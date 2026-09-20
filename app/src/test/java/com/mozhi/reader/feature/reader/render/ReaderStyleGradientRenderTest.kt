package com.mozhi.reader.feature.reader.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as PixelColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.library.EpubStylesheetText
import com.mozhi.reader.feature.importer.EpubLayoutDocumentParser
import com.mozhi.reader.feature.reader.readerPalette
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
class ReaderStyleGradientRenderTest {
    private val gradient = "linear-gradient(90deg, #ff0000, #0000ff)"
    private fun settings(css: String) = ReaderSettings(theme = ReaderTheme.LIGHT,
        syntaxHighlightEnabled = true, syntaxHighlightRules = listOf(ReaderSyntaxRule(1, "test", "[", "]", 0xff222222.toInt(), css = css)),
        showHeader = false, showFooter = false, firstLineIndentEm = 0f, textJustification = false,
        titleTopSpacing = 0f, titleBottomSpacing = 1f)
    private fun style(settings: ReaderSettings) = ReaderPageStyle.resolve(settings,
        readerPalette(settings.theme, false, Color.Black), Density(1f), 420, 720, 0f, 0f)

    @Test fun backgroundGradientIsSharedAcrossGlyphsAndWrappedLines() {
        val style = style(settings("background: $gradient;"))
        val chapter = ChapterTypesetter(style.spec, style.measure).typeset(0, "", "[" + "ABCDEFGHIJ ".repeat(9) + "]\nplain text")
        val page = chapter.pages.first()
        val lines = page.lines.filter { it.columns.any { c -> c.syntaxPaintSpan != null } }
        assertTrue(lines.size > 1)
        assertEquals(1, lines.flatMap { it.columns }.mapNotNull { it.syntaxPaintSpan }.distinct().size)
        val bitmap = render(style, chapter, "css-gradient-wrapped.png")
        try {
            val row = lines.first()
            val left = pixel(bitmap, style, row.columns.first().start + 2, row.lineTop + 1)
            val right = pixel(bitmap, style, row.columns.last().end - 2, row.lineTop + 1)
            assertTrue("left should be red", PixelColor.red(left) > PixelColor.blue(left) + 100)
            assertTrue("right should be blue", PixelColor.blue(right) > PixelColor.red(right) + 100)
            val wrapped = lines[1]
            val sameX = pixel(bitmap, style, wrapped.columns.first().start + 2, wrapped.lineTop + 1)
            assertEquals(PixelColor.red(left).toFloat(), PixelColor.red(sameX).toFloat(), 3f)
        } finally { bitmap.recycle() }
    }

    @Test fun transparentTextFallbackDoesNotHideGradientOrLeakToPlainText() {
        val style = style(settings("background: $gradient; -webkit-background-clip: text; color: transparent;"))
        val chapter = ChapterTypesetter(style.spec, style.measure).typeset(0, "", "[GRADIENT TEXT]\nordinary text")
        val bitmap = render(style, chapter, "css-gradient-text.png")
        try {
            val styled = chapter.pages.first().lines.first()
            val ordinary = chapter.pages.first().lines.last()
            assertTrue(countPixels(bitmap, style, styled) { PixelColor.red(it) > PixelColor.blue(it) + 50 } > 20)
            assertTrue(countPixels(bitmap, style, styled) { PixelColor.blue(it) > PixelColor.red(it) + 50 } > 20)
            assertEquals(0, countPixels(bitmap, style, ordinary) { kotlin.math.abs(PixelColor.blue(it) - PixelColor.red(it)) > 30 })
            assertNull(style.measure.contentPaint.shader)
            assertEquals(255, style.measure.contentPaint.alpha)
        } finally { bitmap.recycle() }
    }

    @Test fun titleGradientAndManagedImageBackgroundRenderAndPreservePagination() {
        val image = File("build/reports/ui-qa/css-test-background.png").apply { parentFile!!.mkdirs() }
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(0xffd8efda.toInt())
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val settings = settings("background-image: url(\"asset:paper\"); color: $gradient;").copy(
            imageLibrary = listOf(ReaderImageAsset("paper", "paper", image.absolutePath)),
            titleStyle = ReaderTitleStyle(css = "color: $gradient; background: linear-gradient(90deg,#fbe4df,#dce8f5); padding: .4em;"))
        val style = style(settings)
        val chapter = ChapterTypesetter(style.spec, style.measure).typeset(0, "GRADIENT TITLE", "[IMAGE BACKGROUND]\nplain text")
        val bitmap = render(style, chapter, "css-gradient-title-image.png")
        try {
            val row = chapter.pages.first().lines.first { it.columns.any { c -> c.syntaxPaintSpan != null } }
            assertEquals(0xffd8efda.toInt(), pixel(bitmap, style, row.columns.first().start + 2, row.lineTop + 1))
            val title = chapter.pages.first().lines.first { it.isReaderTitle }
            assertTrue(countPixels(bitmap, style, title) { PixelColor.red(it) > PixelColor.blue(it) + 80 } > 20)
            assertTrue(countPixels(bitmap, style, title) { PixelColor.blue(it) > PixelColor.red(it) + 80 } > 20)
            val solid = style(settings.copy(syntaxHighlightRules = settings.syntaxHighlightRules.map { it.copy(css = "color:#222;") }))
            val original = ChapterTypesetter(solid.spec, solid.measure).typeset(0, "GRADIENT TITLE", "[IMAGE BACKGROUND]\nplain text")
            assertEquals(original.pages.map { it.lines.map { l -> l.chapterPosition to l.lineBase } },
                chapter.pages.map { it.lines.map { l -> l.chapterPosition to l.lineBase } })
        } finally { bitmap.recycle() }
    }

    @Test fun bothEpubBackendsCarryPaintsAcrossPaginationWithoutOverridingPublisherColors() {
        val parsed = EpubLayoutDocumentParser().parseWithText(("<html><body><p>[" + "Wrapped text ".repeat(100) + "]</p></body></html>").toByteArray(), 0, "OPS/ch.xhtml", emptyMap())
        val settings = settings("color: $gradient; background: $gradient;")
        val style = style(settings)
        for (native in listOf(false, true)) for (publisherColor in listOf(false, true)) {
            val bundle = EpubLayoutChapterBundle(parsed.document, emptyMap(), emptyMap(), dom = parsed.dom.takeIf { native },
                stylesheets = if (native) listOf(EpubStylesheetText("OPS/main.css",
                    if (publisherColor) "p{color:#246;background-color:#fff}" else "p{margin:0}")) else emptyList())
            // Legacy style comes from the parsed document; use the DOM path for stylesheet precedence.
            if (!native && publisherColor) continue
            val chapter = ChapterTypesetter(style.spec, style.measure).typeset(0, "", parsed.text, epubLayout = bundle)
            assertTrue(chapter.pages.size > 1)
            val spans = chapter.pages.flatMap { it.lines }.flatMap { it.columns }.mapNotNull { it.syntaxPaintSpan }
            assertTrue(spans.isNotEmpty())
            if (publisherColor) assertTrue(spans.all { it.paint.textGradient == null && it.paint.backgroundGradient == null })
            else assertTrue(spans.all { it.paint.textGradient != null && it.paint.backgroundGradient != null })
        }
    }

    @Test fun corruptImageFallsBackToSolidColorWithoutCrashingThePreview() {
        val file = File("build/reports/ui-qa/css-corrupt-image.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf()) }
        val style = style(settings("background-color: #d8efda; background-image: url(asset:broken);").copy(
            imageLibrary = listOf(ReaderImageAsset("broken", "broken", file.absolutePath))))
        val chapter = ChapterTypesetter(style.spec, style.measure).typeset(0, "", "[FALLBACK]")
        val bitmap = render(style, chapter, "css-corrupt-image-fallback.png")
        try {
            val row = chapter.pages.first().lines.first()
            assertEquals(0xffd8efda.toInt(), pixel(bitmap, style, row.columns.first().start + 2, row.lineTop + 1))
        } finally { bitmap.recycle() }
    }

    private fun render(style: ReaderPageStyle, chapter: TextChapter, name: String): Bitmap {
        val renderer = PageBitmapRenderer(style)
        try {
            return renderer.render(RenderPage.Laid(0, chapter.title, 0, chapter.pageCount, chapter.pages.first()), null, 0f, "", 100).also { bitmap ->
                File("build/reports/ui-qa/$name").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally { renderer.release() }
    }
    private fun pixel(bitmap: Bitmap, style: ReaderPageStyle, x: Float, y: Float) =
        bitmap.getPixel((style.paddingLeft + x).toInt(), (style.contentTop + y).toInt())
    private fun countPixels(bitmap: Bitmap, style: ReaderPageStyle, line: TextLine, predicate: (Int) -> Boolean): Int {
        var count = 0
        for (y in line.lineTop.toInt() until line.lineBottom.toInt())
            for (x in line.columns.first().start.toInt() until line.columns.last().end.toInt())
                if (predicate(pixel(bitmap, style, x.toFloat(), y.toFloat()))) count++
        return count
    }
}
