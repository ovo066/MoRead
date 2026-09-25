package com.mozhi.reader.feature.reader.engine.epub

import com.mozhi.reader.core.datastore.PublisherStyleMode
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.library.EpubStylesheetText
import com.mozhi.reader.feature.importer.EpubLayoutDocumentParser
import com.mozhi.reader.feature.reader.engine.BorderLineStyle
import com.mozhi.reader.feature.reader.engine.ChapterTypesetter
import com.mozhi.reader.feature.reader.engine.FakeMeasure
import com.mozhi.reader.feature.reader.engine.TextChapter
import com.mozhi.reader.feature.reader.engine.TypesetSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CSS 细节还原：边框样式、渐变、文字阴影、绝对/相对定位与 nowrap。 */
class EpubCssEffectsLayoutTest {
    private val spec = TypesetSpec(
        visibleWidth = 200f, visibleHeight = 400f, contentLineStep = 24f,
        titleLineStep = 32f, paragraphSpacing = 0f, blankLineSpacing = 0f,
        titleTopSpacing = 0f, titleBottomSpacing = 0f, contentFontSizePx = 16f,
        publisherStyleMode = PublisherStyleMode.RESPECT, justifyContent = false, bottomAlign = false
    )

    private fun typeset(css: String, body: String): Pair<TextChapter, String> {
        val html = "<html><head><link rel='stylesheet' href='OEBPS/Styles/main.css'/></head><body>$body</body></html>"
        val parsed = EpubLayoutDocumentParser().parseWithText(
            html.toByteArray(), 0, "OEBPS/Text/ch.xhtml", mapOf("OEBPS/Styles/main.css" to css)
        )
        val bundle = EpubLayoutChapterBundle(
            document = parsed.document, resourcePaths = emptyMap(), fontPaths = emptyMap(),
            dom = parsed.dom, stylesheets = listOf(EpubStylesheetText("OEBPS/Styles/main.css", css))
        )
        return ChapterTypesetter(spec, FakeMeasure()).typeset(0, "", parsed.text, epubLayout = bundle) to parsed.text
    }

    @Test
    fun `border styles, gradients and text shadows reach the page model`() {
        val (chapter, _) = typeset(
            "p { margin: 0; text-indent: 0; text-shadow: 1px 2px 3px #f00 } " +
                ".card { border-top: 2px dashed #000; border-bottom: 6px double #000; border-left: 2px dotted #000; " +
                "background: linear-gradient(to right, #fff, #000) }",
            "<div class='card'><p>卡片</p></div>"
        )
        val page = chapter.pages.first()
        val card = page.decorations.single()
        assertEquals(BorderLineStyle.DASHED, card.borderTopStyle)
        assertEquals(BorderLineStyle.DOUBLE, card.borderBottomStyle)
        assertEquals(BorderLineStyle.DOTTED, card.borderLeftStyle)
        val gradient = assertNotNull(card.backgroundGradient).let { requireNotNull(card.backgroundGradient) }
        assertEquals(90f, gradient.angleDeg)
        assertEquals(2, gradient.stops.size)
        val shadow = page.lines.first().columns.first().textShadows.single()
        assertEquals(1f * 16f / 16f, shadow.offsetX, .01f)
        assertEquals(2f, shadow.offsetY, .01f)
        assertEquals(3f, shadow.blurRadius, .01f)
    }

    @Test
    fun `absolute children sit in the corners of their positioned card without taking flow space`() {
        val (chapter, text) = typeset(
            "p, div { margin: 0; text-indent: 0 } " +
                ".card { position: relative; width: 100px; height: 160px; margin: 0 auto; border: 2px solid #555 } " +
                ".top { position: absolute; top: 6px; left: 8px } " +
                ".bottom { position: absolute; right: 8px; bottom: 6px } " +
                ".mid { position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); white-space: nowrap }",
            "<div class='card'><div class='top'>上</div><div class='mid'>居中文字</div><div class='bottom'>下</div></div><p>其后</p>"
        )
        val page = chapter.pages.first()
        val card = page.decorations.first()
        assertEquals(48f, card.left, .5f)
        assertEquals(152f, card.right, .5f)
        val lines = page.lines.filter { it.charLength > 0 }
        // 行序仍是文档序（上、居中、下、其后），text.mz 坐标单调。
        assertEquals(listOf("上", "居中文字", "下", "其后"), lines.map { it.text })
        assertTrue(lines.zipWithNext().all { (a, b) -> a.chapterPosition < b.chapterPosition })
        val top = lines[0]
        assertEquals(card.left + 2f + 8f, top.columns.first().start, .5f)
        assertEquals(card.top + 2f + 6f, top.lineTop, .5f)
        val bottom = lines[2]
        assertEquals(card.right - 2f - 8f, bottom.columns.last().end, .5f)
        // 盒底 = 行距推进（24），行框本身 20 高。
        assertEquals(card.bottom - 2f - 6f - 4f, bottom.lineBottom, 1f)
        // translate(-50%, -50%) 以自身盒子居中；nowrap 不折行。
        val middle = lines[1]
        assertEquals((card.left + card.right) / 2f, (middle.columns.first().start + middle.columns.last().end) / 2f, 1f)
        assertEquals((card.top + card.bottom) / 2f, (middle.lineTop + middle.lineBottom + 4f) / 2f, 1f)
        // 绝对定位不占位置：卡片后面的段落紧贴卡片底边。
        assertEquals(card.bottom, lines[3].lineTop, .5f)
        assertEquals(text.length, lines.last().let { it.chapterPosition + it.charLength })
    }

    @Test
    fun `relative offsets move the box without moving what follows`() {
        val (chapter, _) = typeset(
            "p { margin: 0; text-indent: 0 } .shift { position: relative; top: 10px; left: 20px }",
            "<p class='shift'>移动</p><p>原位</p>"
        )
        val lines = chapter.pages.first().lines
        assertEquals(20f, lines[0].columns.first().start, .5f)
        assertEquals(10f, lines[0].lineTop, .5f)
        assertEquals(0f, lines[1].columns.first().start, .5f)
        assertEquals(24f, lines[1].lineTop, .5f)
    }
}
