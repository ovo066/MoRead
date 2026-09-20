package com.mozhi.reader.feature.review

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import com.mozhi.reader.core.datastore.*
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
class ReviewTemplateRenderTest {
    @Test fun savedCssChangesExportAndSyntaxColorsReachOnlyMatchedText() {
        val template = ReviewShareTemplate("a", "薄荷", backgroundArgb = 0xFFF2F3E9.toInt(), textArgb = 0xFF2F443B.toInt(),
            accentArgb = 0xFF739A86.toInt(), fontChoice = "SERIF", css = "font-size: 1.15em; line-height: 1.8; border-width: 0.05em; border-radius: 0.8em;",
            syntaxEnabled = true, syntaxRules = listOf(ReaderSyntaxRule(1, "对白", "“", "”", 0xFFBF4269.toInt())))
        val entry = ReviewEntry(reviewTestBook(), "我", annotation = reviewTestAnnotation().copy(selectedText = "他轻声说：“旧物替人记事。”\n我合上书，继续向前。"))
        val options = reviewTemplateOptions(ReviewExportOptions(), template, ReaderSettings(), 1, false)
        val bitmap = renderReviewCard(entry, ReviewCardStyle.PAPER, options)
        assertEquals(template.backgroundArgb, bitmap.getPixel(50, 50))
        assertEquals(ReaderFont.SERIF, options.font.font)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue(pixels.count { it == template.syntaxRules.single().colorArgb } > 20)
        File("build/reports/reading-review/export-custom.png").apply { parentFile.mkdirs() }.outputStream()
            .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val plain = renderReviewCard(entry, ReviewCardStyle.PAPER, options.copy(template = template.copy(syntaxEnabled = false)))
        plain.getPixels(pixels, 0, plain.width, 0, 0, plain.width, plain.height)
        assertFalse(pixels.any { it == template.syntaxRules.single().colorArgb })
        plain.recycle()
    }

    @Test fun quoteLayoutPreservesCharactersAndSupportsGradientTextAndBackground() {
        val text = "雪线以上没有路，只有别人留下的脚印。".repeat(4)
        val template = ReviewShareTemplate("b", "渐变", css = "color: linear-gradient(to right, #bf4269, #396b8c); background: linear-gradient(to bottom, #f2eee7, #dbe9df); text-align: center;")
        val options = ReviewExportOptions(template = template)
        val block = reviewTextBlock(text, 400, 40f, template.textArgb, options, ReviewTemplateCss.parse(template.css), true)
        assertEquals(text, block.layout.text.toString())
        assertTrue(block.layout.lineCount > 1)
        assertNotNull(block.layout.paint.shader)
        val clipped = reviewTextBlock(text, 400, 40f, 0, options,
            ReviewTemplateCss.parse("color: transparent; background: linear-gradient(to right, #bf4269, #396b8c); background-clip: text;"), true)
        assertEquals(255, clipped.layout.paint.alpha)
        assertNotNull(clipped.layout.paint.shader)
        val bitmap = Bitmap.createBitmap(400, block.height, Bitmap.Config.ARGB_8888)
        block.draw(Canvas(bitmap))
        bitmap.recycle()
    }
}
