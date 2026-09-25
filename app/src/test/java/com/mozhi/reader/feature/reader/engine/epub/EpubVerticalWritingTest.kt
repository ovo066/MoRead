package com.mozhi.reader.feature.reader.engine.epub

import com.mozhi.reader.core.datastore.PublisherStyleMode
import com.mozhi.reader.core.epub.style.EpubStyle
import com.mozhi.reader.core.epub.style.EpubShadow
import com.mozhi.reader.core.epub.style.EpubWritingMode
import com.mozhi.reader.core.epub.style.ResolvedLength
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.library.EpubStylesheetText
import com.mozhi.reader.feature.importer.EpubLayoutDocumentParser
import com.mozhi.reader.feature.reader.engine.ChapterTypesetter
import com.mozhi.reader.feature.reader.engine.FakeMeasure
import com.mozhi.reader.feature.reader.engine.SelectionRect
import com.mozhi.reader.feature.reader.engine.TextChapter
import com.mozhi.reader.feature.reader.engine.TypesetSpec
import com.mozhi.reader.feature.reader.engine.VerticalOrientation
import com.mozhi.reader.feature.reader.engine.charOffsetOf
import com.mozhi.reader.feature.reader.engine.frameToPhysical
import com.mozhi.reader.feature.reader.engine.hitTextPos
import com.mozhi.reader.feature.reader.engine.physicalToFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** vertical-rl 在旋转坐标系里排：列序、字向、縦中横、盒模型映射与命中坐标。 */
class EpubVerticalWritingTest {
    private val spec = TypesetSpec(
        visibleWidth = 200f, visibleHeight = 300f, contentLineStep = 24f,
        titleLineStep = 32f, paragraphSpacing = 0f, blankLineSpacing = 0f,
        titleTopSpacing = 0f, titleBottomSpacing = 0f, contentFontSizePx = 20f,
        publisherStyleMode = PublisherStyleMode.RESPECT, justifyContent = false, bottomAlign = false
    )

    private fun typeset(css: String, body: String, htmlAttributes: String = ""): Pair<TextChapter, String> {
        val html = "<html$htmlAttributes><head><link rel='stylesheet' href='OEBPS/Styles/main.css'/></head>" +
            "<body>$body</body></html>"
        val parsed = EpubLayoutDocumentParser().parseWithText(
            html.toByteArray(), 0, "OEBPS/Text/ch.xhtml", mapOf("OEBPS/Styles/main.css" to css)
        )
        val bundle = EpubLayoutChapterBundle(
            document = parsed.document, resourcePaths = emptyMap(), fontPaths = emptyMap(),
            dom = parsed.dom, stylesheets = listOf(EpubStylesheetText("OEBPS/Styles/main.css", css))
        )
        return ChapterTypesetter(spec, FakeMeasure()).typeset(0, "", parsed.text, epubLayout = bundle) to parsed.text
    }

    private val prose = "天地玄黄宇宙洪荒日月盈昃辰宿列张寒来暑往秋收冬藏闰余成岁律吕调阳云腾致雨露结为霜" +
        "金生丽水玉出昆冈剑号巨阙珠称夜光果珍李柰菜重芥姜海咸河淡鳞潜羽翔龙师火帝鸟官人皇"

    @Test
    fun `writing mode on the html root lays the chapter out in columns`() {
        val (chapter, text) = typeset("html { -epub-writing-mode: vertical-rl } p { margin: 0; text-indent: 0 }", "<p>$prose</p>")
        assertEquals(EpubWritingMode.VERTICAL_RL, chapter.layoutCapability?.chapterWritingMode)
        assertTrue(chapter.layoutCapability?.supported == true)
        val page = chapter.pages.first()
        assertEquals(200f, page.verticalFrameWidth)
        // 帧里的一行是一列：列长是物理高 300，列宽 20px 的直立字，每列 15 字。
        val first = page.lines.first()
        assertEquals(15, first.columns.size)
        assertTrue(first.columns.last().end <= 300f + .01f)
        assertTrue(first.columns.all { it.verticalOrientation == VerticalOrientation.U })
        // 列从右往左排：物理 x 随列序递减，且没有越过内容框。
        val physicalX = page.lines.map { page.frameToPhysical(it.startX, it.lineTop).first }
        assertTrue(physicalX.zipWithNext().all { (a, b) -> b < a })
        assertTrue(physicalX.all { it in 0f..200f })
        // 正文坐标仍单调覆盖整段文字。
        assertEquals(text.length, chapter.pages.sumOf { it.charLength })
    }

    @Test
    fun `latin runs lie sideways while punctuation keeps vertical alternates`() {
        val (chapter, _) = typeset("body { writing-mode: vertical-rl } p { margin: 0 }", "<p>他说，Hi！</p>")
        val columns = chapter.pages.first().lines.first().columns.associate { it.charData to it.verticalOrientation }
        assertEquals(VerticalOrientation.U, columns["他"])
        assertEquals(VerticalOrientation.TU, columns["，"])
        assertEquals(VerticalOrientation.R, columns["H"])
        assertEquals(VerticalOrientation.TU, columns["！"])
    }

    @Test
    fun `text-combine-upright squeezes a digit run into one upright cell`() {
        val (chapter, text) = typeset(
            "html { writing-mode: vertical-rl } .tcy { -webkit-text-combine: horizontal } p { margin: 0 }",
            "<p>第<span class='tcy'>12</span>回</p>"
        )
        val columns = chapter.pages.first().lines.first().columns
        assertEquals(listOf("第", "12", "回"), columns.map { it.charData })
        val combined = columns[1]
        assertTrue(combined.combineUpright)
        assertEquals(2, combined.sourceLength)
        assertEquals(20f, combined.end - combined.start, .01f)
        assertEquals(text.indexOf('回'), chapter.pages.first().charOffsetOf(
            com.mozhi.reader.feature.reader.engine.TextPos(0, 2)
        ))
    }

    @Test
    fun `text-orientation upright stands latin letters up`() {
        val (chapter, _) = typeset(
            "html { writing-mode: vertical-rl } .up { text-orientation: upright } p { margin: 0 }",
            "<p><span class='up'>AB</span>C</p>"
        )
        val columns = chapter.pages.first().lines.first().columns
        assertEquals(VerticalOrientation.U, columns[0].verticalOrientation)
        assertEquals(VerticalOrientation.R, columns[2].verticalOrientation)
        // 直立的西文按字身推进，避免窄字宽在直立后互相压叠。
        assertEquals(20f, columns[0].end - columns[0].start, .01f)
        assertEquals(5f, columns[2].end - columns[2].start, .01f)
    }

    @Test
    fun `physical margins become the matching frame sides`() {
        val (chapter, _) = typeset(
            "html { writing-mode: vertical-rl } p { margin: 0; text-indent: 0 } div { margin-right: 40px; margin-top: 16px }",
            "<div><p>天地玄黄</p></div>"
        )
        val line = chapter.pages.first().lines.first()
        // CSS px 按 20/16 缩放：物理右边距 40px -> 帧顶 50；物理上边距 16px -> 帧左 20。
        assertEquals(50f, line.lineTop, .01f)
        assertEquals(20f, line.columns.first().start, .01f)
    }

    @Test
    fun `hits in physical coordinates land on the character drawn there`() {
        val (chapter, text) = typeset("html { writing-mode: vertical-rl } p { margin: 0; text-indent: 0 }", "<p>$prose</p>")
        val page = chapter.pages.first()
        val line = page.lines[1]
        val column = line.columns[3]
        // 屏幕上第二列第四个字的中心。
        val (x, y) = page.frameToPhysical((column.start + column.end) / 2f, (line.lineTop + line.lineBottom) / 2f)
        val (frameX, frameY) = page.physicalToFrame(x, y)
        val pos = requireNotNull(page.hitTextPos(frameX, frameY, exact = true))
        assertEquals(text[page.charOffsetOf(pos)], column.charData.single())
        val rect = page.frameToPhysical(SelectionRect(column.start, line.lineTop, column.end, line.lineBottom))
        assertTrue(x in rect.left..rect.right && y in rect.top..rect.bottom)
    }

    @Test
    fun `vertical-lr still falls back to horizontal flow`() {
        val (chapter, _) = typeset("html { writing-mode: vertical-lr }", "<p>$prose</p>")
        assertFalse(chapter.layoutCapability?.supported == true)
        assertNull(chapter.pages.first().verticalFrameWidth)
        assertNull(chapter.pages.first().lines.first().columns.first().verticalOrientation)
    }

    @Test
    fun `frame mapping rotates box sides, corners and shadows clockwise`() {
        val px = { value: Float -> ResolvedLength.Px(value) }
        val physical = EpubStyle(
            fontSizePx = 20f, colorArgb = 0,
            appliedProperties = setOf("margin-right", "width"),
            marginTop = px(1f), marginRight = px(2f), marginBottom = px(3f), marginLeft = px(4f),
            width = px(100f), height = ResolvedLength.Auto,
            borderWidths = listOf(1f, 2f, 3f, 4f),
            borderRadii = listOf(px(10f), px(20f), px(30f), px(40f)),
            boxShadows = listOf(EpubShadow(3f, 5f, 0f, 0f, 0, false))
        )
        val frame = EpubVerticalFrame.toFrame(physical)
        assertEquals(listOf(px(2f), px(3f), px(4f), px(1f)),
            listOf(frame.marginTop, frame.marginRight, frame.marginBottom, frame.marginLeft))
        assertEquals(listOf(2f, 3f, 4f, 1f), frame.borderWidths)
        assertEquals(listOf(px(20f), px(30f), px(40f), px(10f)), frame.borderRadii)
        assertEquals(ResolvedLength.Auto, frame.width)
        assertEquals(px(100f), frame.height)
        assertEquals(setOf("margin-top", "height"), frame.appliedProperties)
        assertEquals(5f, frame.boxShadows.single().offsetXPx)
        assertEquals(-3f, frame.boxShadows.single().offsetYPx)
    }
}
