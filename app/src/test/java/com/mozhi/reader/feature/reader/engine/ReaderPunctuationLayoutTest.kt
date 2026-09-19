package com.mozhi.reader.feature.reader.engine

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.library.EpubStylesheetText
import com.mozhi.reader.feature.importer.EpubLayoutDocumentParser
import com.mozhi.reader.feature.reader.readerPalette
import com.mozhi.reader.feature.reader.render.PageBitmapRenderer
import com.mozhi.reader.feature.reader.render.ReaderPageStyle
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
class ReaderPunctuationLayoutTest {
    @Test fun punctuationStaysInsideTheTextAreaAcrossPlainLegacyAndDomLayouts() {
        val paragraphs = listOf(
            "她说：“河岸的灯火还亮着。”他回答：“是啊，已经很晚了！”然后合上书。",
            "天地玄黄宇宙洪荒……日月盈昃辰宿列张——寒来暑往，秋收冬藏。",
            "翻到《山谷（修订版）》的最后一页，他问：“真的结束了！？”她点了点头。",
            "窗外的雨停了，Compose keeps words together，中文与 English 也应自然换行。"
        )
        val css = "p { margin:0; text-indent:2em; }"
        val html = "<html><head><link rel='stylesheet' href='style.css'/></head><body>" +
            paragraphs.joinToString("") { "<p>$it</p>" } + "</body></html>"
        val parsed = EpubLayoutDocumentParser().parseWithText(html.toByteArray(), 0, "ch.xhtml", mapOf("style.css" to css))
        val legacy = EpubLayoutChapterBundle(parsed.document, emptyMap(), emptyMap())
        val dom = legacy.copy(dom = parsed.dom, stylesheets = listOf(EpubStylesheetText("style.css", css)))
        val settings = ReaderSettings()
        val palette = readerPalette(settings.theme, false, Color(0xff526d58))
        for (width in listOf(240, 320, 411)) for (spacing in listOf(0f, .12f)) {
            val style = ReaderPageStyle.resolve(settings, palette, Density(1f), width, 720, 0f, 0f)
            val measure = AndroidTextMeasure(style.contentSizePx, style.titleSizePx, style.typeface, spacing)
            for ((name, bundle) in listOf("plain" to null, "legacy" to legacy, "dom" to dom)) {
                val chapter = ChapterTypesetter(style.spec, measure).typeset(0, "河岸的灯火", parsed.text, epubLayout = bundle)
                val lines = chapter.pages.flatMap { it.lines }.filter { !it.isTitle && it.text.isNotBlank() }
                val context = "$name width=$width spacing=$spacing"
                for (line in lines) {
                    // GB-style line breaking permits a complete two-em dash/ellipsis at line start.
                    assertFalse("$context forbidden line start: ${line.text}", line.text.first() in "，。！？）》】”’,.;:!?")
                    assertTrue("$context punctuation-only line", line.text.any { it.isLetterOrDigit() })
                    assertFalse("$context forbidden line end: ${line.text}", line.text.last() in "（《【“‘")
                    assertTrue("$context right margin overflow: ${line.text}", line.columns.all { it.end <= style.contentWidth + .1f })
                    assertTrue("$context left margin overflow", line.columns.all { it.start >= -.1f })
                    line.columns.zipWithNext().forEach { (left, right) ->
                        if (left.charData == right.charData && left.charData in listOf("…", "—")) {
                            assertTrue("$context expanded paired mark", right.start <= left.end + .01f)
                        }
                    }
                }
                lines.zipWithNext().forEach { (left, right) ->
                    assertFalse("$context split a paired mark", left.text.last() == right.text.first() &&
                        left.text.last() in "…—")
                }
                assertEquals(context, parsed.text.filterNot { it.isWhitespace() },
                    lines.joinToString("") { it.text }.filterNot { it.isWhitespace() })
                if (width == 411 && spacing == 0f) {
                    val renderer = PageBitmapRenderer(style)
                    val bitmap = renderer.render(RenderPage.Laid(0, chapter.title, 0, chapter.pageCount, chapter.pages.first()),
                        into = null, bookProgress = 0f, timeText = "12:00", batteryPercent = 100)
                    try {
                        val file = File("build/reports/ui-qa/punctuation/$name.png")
                        file.parentFile?.mkdirs()
                        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { bitmap.recycle(); renderer.release() }
                }
            }
        }
    }
}
