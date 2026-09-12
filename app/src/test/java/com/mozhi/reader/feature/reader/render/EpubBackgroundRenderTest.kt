package com.mozhi.reader.feature.reader.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import com.mozhi.reader.core.datastore.PublisherStyleMode
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.feature.importer.EpubLayoutDocumentParser
import com.mozhi.reader.feature.reader.engine.ChapterTypesetter
import com.mozhi.reader.feature.reader.engine.FakeMeasure
import com.mozhi.reader.feature.reader.engine.RenderPage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native pixels catch background images that have a valid box but are painted one pixel high. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpubBackgroundRenderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun automaticBackgroundDimensionsPreserveImageAspectRatio() {
        val file = temporary.newFile("art.png")
        val art = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        file.outputStream().use { art.compress(Bitmap.CompressFormat.PNG, 100, it) }
        art.recycle()

        val cases = listOf(
            "100% auto" to intArrayOf(0, 100, 240, 220),
            "100%" to intArrayOf(0, 100, 240, 220),
            "auto 100%" to intArrayOf(0, 0, 240, 320),
            "50% auto" to intArrayOf(60, 130, 180, 190),
            "100% 100%" to intArrayOf(0, 0, 240, 320),
            "0px auto" to intArrayOf(0, 0, 0, 0)
        )
        for (mode in PublisherStyleMode.entries) for ((size, bounds) in cases) {
            val parsed = EpubLayoutDocumentParser().parseWithText(
                """<html><head><style>body{height:100%}.art{height:100%;background-image:url('art.png');background-size:$size;background-position:center;background-repeat:no-repeat}</style></head><body><div class="art"></div></body></html>""".toByteArray(),
                0, "OPS/ch.xhtml", emptyMap()
            )
            val style = pageStyle()
            val chapter = ChapterTypesetter(style.spec.copy(publisherStyleMode = mode), FakeMeasure()).typeset(0, "", parsed.text,
                epubLayout = EpubLayoutChapterBundle(parsed.document, mapOf("OPS/art.png" to file.absolutePath),
                    emptyMap(), dom = parsed.dom))
            val renderer = PageBitmapRenderer(style)
            val bitmap = renderer.render(
                RenderPage.Laid(0, "", 0, chapter.pageCount, chapter.pages.single()), null, 0f, "", 100)
            try {
                for (y in 5 until 320 step 10) for (x in 5 until 240 step 10) {
                    val expected = if (x in bounds[0] until bounds[2] && y in bounds[1] until bounds[3]) Color.BLUE else Color.GREEN
                    assertEquals("$mode background-size:$size at ($x,$y)", expected, bitmap.getPixel(x, y))
                }
            } finally {
                bitmap.recycle()
                renderer.release()
            }
        }
    }

    private fun pageStyle() = ReaderPageStyle(
        viewWidth = 240, viewHeight = 320, paddingLeft = 0f, paddingRight = 0f,
        headerHeight = 0f, footerHeight = 0f, contentPaddingTop = 0f, contentPaddingBottom = 0f,
        immersiveContentTop = 0f, immersiveContentBottom = 320f, headerOffset = 0f, footerOffset = 0f,
        contentSizePx = 16f, titleSizePx = 20f, tipSizePx = 12f, lineStep = 24f,
        backgroundColor = Color.GREEN, textColor = Color.BLACK, mutedColor = Color.GRAY,
        accentColor = Color.BLACK, isDark = false, grain = false, typeface = Typeface.DEFAULT,
        customFontPath = null, customFontPaths = emptyMap(), showHeader = false, showFooter = false,
        backgroundImagePath = null, backgroundImageOpacity = 1f, preferReaderBackground = true,
        publisherStyleMode = PublisherStyleMode.SMART, syntaxHighlightRules = emptyList(),
        titleTopSpacingLines = 0f, titleBottomSpacingLines = 0f, paragraphSpacingEm = 0f,
        firstLineIndentEm = 0f, textJustification = false, letterSpacingEm = 0f
    )
}
