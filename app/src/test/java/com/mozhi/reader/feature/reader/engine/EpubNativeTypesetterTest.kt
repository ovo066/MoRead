package com.mozhi.reader.feature.reader.engine

import com.mozhi.reader.core.library.EpubComputedStyle
import com.mozhi.reader.core.library.EpubElementRef
import com.mozhi.reader.core.library.EpubLayoutBlock
import com.mozhi.reader.core.library.EpubLayoutBlockKind
import com.mozhi.reader.core.library.EpubLayoutChapter
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.library.EpubLayoutSpan
import com.mozhi.reader.core.library.EpubResolvedFontFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubNativeTypesetterTest {

    private val spec = TypesetSpec(
        visibleWidth = 100f,
        visibleHeight = 100f,
        contentLineStep = 25f,
        titleLineStep = 34f,
        paragraphSpacing = 0f,
        blankLineSpacing = 0f,
        titleTopSpacing = 0f,
        titleBottomSpacing = 0f,
        indentCharCount = 0f,
        justifyContent = false,
        bottomAlign = false
    )

    @Test
    fun `avoid container moves to next page when it fits there`() {
        val intro = "天地玄黄宇宙洪荒日月盈昃辰宿列张寒来暑往"
        val first = "甲乙丙丁戊"
        val second = "己庚辛壬癸"
        val body = "$intro\n$first\n$second"
        val firstStart = intro.length + 1
        val secondStart = firstStart + first.length + 1
        val containerStyle = EpubComputedStyle(
            avoidBreakInside = true,
            paddingTopEm = 0.2f,
            paddingBottomEm = 0.2f,
            borderWidthEm = 0.04f,
            backgroundColorArgb = 0xFFF7F7F7.toInt()
        )
        val blocks = listOf(
            block(0, 0, intro.length),
            block(1, firstStart, firstStart + first.length),
            block(2, secondStart, body.length),
            block(
                order = 3,
                start = firstStart,
                end = body.length,
                kind = EpubLayoutBlockKind.CONTAINER,
                style = containerStyle
            )
        )

        val chapter = typeset(body, blocks)

        assertEquals(2, chapter.pageCount)
        assertTrue(chapter.pages[0].lines.all { it.chapterPosition < firstStart })
        assertEquals(firstStart, chapter.pages[1].lines.first().chapterPosition)
        assertTrue(chapter.pages[1].decorations.single().drawTopEdge)
        assertTrue(chapter.pages[1].decorations.single().drawBottomEdge)
    }

    @Test
    fun `avoid inline span wraps as one badge`() {
        val body = "天地玄黄宇宙洪荒甲乙丙"
        val block = block(
            order = 0,
            start = 0,
            end = body.length,
            spans = listOf(
                EpubLayoutSpan(0, 8, style = EpubComputedStyle()),
                EpubLayoutSpan(8, body.length, style = EpubComputedStyle(avoidBreakInside = true))
            )
        )

        val lines = typeset(body, listOf(block)).pages.flatMap(TextPage::lines)

        assertEquals(8, lines[0].charLength)
        assertEquals(3, lines[1].charLength)
    }

    @Test
    fun `avoid decorated inline span stays one rounded capsule`() {
        val body = "前标签后"
        val capsuleStyle = EpubComputedStyle(
            marginTopEm = 0.08f,
            marginRightEm = 0.1f,
            marginBottomEm = 0.08f,
            marginLeftEm = 0.1f,
            paddingTopEm = 0.1f,
            paddingRightEm = 0.2f,
            paddingBottomEm = 0.1f,
            paddingLeftEm = 0.2f,
            borderWidthEm = 0.04f,
            borderColorArgb = 0xFFAA5500.toInt(),
            borderRadiusEm = 0.4f,
            backgroundColorArgb = 0xFFFFE0B2.toInt(),
            avoidBreakInside = true
        )
        val block = block(
            order = 0,
            start = 0,
            end = body.length,
            spans = listOf(
                EpubLayoutSpan(
                    textStart = 1,
                    textEnd = 3,
                    elements = listOf(EpubElementRef("span", classes = listOf("danmaku"))),
                    style = capsuleStyle
                )
            )
        )

        val lines = typeset(body, listOf(block), spec.copy(visibleWidth = 38f))
            .pages
            .flatMap(TextPage::lines)

        assertEquals(listOf(1, 2, 1), lines.map(TextLine::charLength))
        val capsuleLine = lines[1]
        val decoration = capsuleLine.inlineDecorations.single()
        assertEquals(2.5f, decoration.left, 0.01f)
        assertEquals(34.5f, decoration.right, 0.01f)
        assertEquals(10f, decoration.borderRadius, 0.01f)
        assertTrue(decoration.drawLeftEdge)
        assertTrue(decoration.drawRightEdge)
        assertEquals(8.5f, capsuleLine.columns.first().start, 0.01f)
        assertTrue(capsuleLine.lineBottom - capsuleLine.lineTop > 20f)
    }

    @Test
    fun `inline padding and border affect wrapping and glyph placement`() {
        val body = "甲乙丙"
        val block = block(
            order = 0,
            start = 0,
            end = body.length,
            spans = listOf(
                EpubLayoutSpan(
                    textStart = 0,
                    textEnd = body.length,
                    elements = listOf(EpubElementRef("span", classes = listOf("op-label"))),
                    style = EpubComputedStyle(
                        paddingRightEm = 0.2f,
                        paddingLeftEm = 0.2f,
                        borderWidthEm = 0.04f,
                        borderRadiusEm = 0.3f,
                        backgroundColorArgb = 0xFFE8F5E9.toInt()
                    )
                )
            )
        )

        val lines = typeset(body, listOf(block), spec.copy(visibleWidth = 35f))
            .pages
            .flatMap(TextPage::lines)

        assertEquals(listOf(2, 1), lines.map(TextLine::charLength))
        assertEquals(6f, lines[0].columns.first().start, 0.01f)
        assertTrue(lines[0].inlineDecorations.single().drawLeftEdge)
        assertFalse(lines[0].inlineDecorations.single().drawRightEdge)
        assertFalse(lines[1].inlineDecorations.single().drawLeftEdge)
        assertTrue(lines[1].inlineDecorations.single().drawRightEdge)
    }

    @Test
    fun `embedded font face matches weight and italic`() {
        val body = "粗斜"
        val block = block(
            order = 0,
            start = 0,
            end = body.length,
            spans = listOf(
                EpubLayoutSpan(
                    textStart = 0,
                    textEnd = body.length,
                    elements = listOf(EpubElementRef("strong")),
                    style = EpubComputedStyle(fontFamily = "demo", fontWeight = 700, italic = true)
                )
            )
        )

        val line = typeset(
            body = body,
            blocks = listOf(block),
            fontFaces = listOf(
                EpubResolvedFontFace("demo", "regular.ttf", weight = 400),
                EpubResolvedFontFace("demo", "bold.ttf", weight = 700),
                EpubResolvedFontFace("demo", "bold-italic.ttf", weight = 700, italic = true)
            )
        ).pages.single().lines.single()

        assertEquals("bold-italic.ttf", line.columns.first().fontFilePath)
    }

    @Test
    fun `ruby reserves upper line band and centers base text`() {
        val body = "漢字后"
        val block = block(
            order = 0,
            start = 0,
            end = body.length,
            spans = listOf(
                EpubLayoutSpan(
                    textStart = 0,
                    textEnd = 2,
                    elements = listOf(EpubElementRef("ruby")),
                    rubyText = "abcdefghi"
                )
            )
        )

        val lines = typeset(body, listOf(block), spec.copy(visibleWidth = 30f))
            .pages
            .flatMap(TextPage::lines)

        assertEquals(listOf(2, 1), lines.map(TextLine::charLength))
        val rubyLine = lines.first()
        val ruby = rubyLine.rubyPlacements.single()
        assertEquals("abcdefghi", ruby.text)
        assertEquals(0f, ruby.left, 0.01f)
        assertEquals(22.5f, ruby.right, 0.01f)
        assertEquals(1.25f, rubyLine.columns.first().start, 0.01f)
        assertTrue(ruby.baseline < rubyLine.lineBase)
        assertTrue(rubyLine.lineBottom - rubyLine.lineTop > 20f)
    }

    @Test
    fun `decoration suppresses repeated caps across pages`() {
        val body = "天地玄黄宇宙洪荒日月盈昃辰宿列张寒来暑往秋收冬藏闰余成岁律吕调阳"
        val style = EpubComputedStyle(
            paddingTopEm = 0.2f,
            paddingBottomEm = 0.2f,
            borderWidthEm = 0.04f,
            borderRadiusEm = 0.4f,
            backgroundColorArgb = 0xFFFFFFFF.toInt()
        )
        val blocks = listOf(
            block(0, 0, body.length),
            block(1, 0, body.length, EpubLayoutBlockKind.CONTAINER, style)
        )

        val chapter = typeset(body, blocks, spec.copy(visibleHeight = 60f))

        assertTrue(chapter.pageCount > 1)
        assertTrue(chapter.pages.first().decorations.single().drawTopEdge)
        assertFalse(chapter.pages.first().decorations.single().drawBottomEdge)
        assertFalse(chapter.pages.last().decorations.single().drawTopEdge)
        assertTrue(chapter.pages.last().decorations.single().drawBottomEdge)
    }

    private fun typeset(
        body: String,
        blocks: List<EpubLayoutBlock>,
        typesetSpec: TypesetSpec = spec,
        fontFaces: List<EpubResolvedFontFace> = emptyList()
    ): TextChapter {
        val document = EpubLayoutChapter(
            chapterIndex = 0,
            href = "OEBPS/Text/chapter.xhtml",
            blocks = blocks,
            textLength = body.length
        )
        return ChapterTypesetter(typesetSpec, FakeMeasure()).typeset(
            chapterIndex = 0,
            title = "",
            body = body,
            epubLayout = EpubLayoutChapterBundle(document, emptyMap(), emptyMap(), fontFaces)
        )
    }

    private fun block(
        order: Int,
        start: Int,
        end: Int,
        kind: EpubLayoutBlockKind = EpubLayoutBlockKind.PARAGRAPH,
        style: EpubComputedStyle = EpubComputedStyle(textIndentEm = 0f),
        spans: List<EpubLayoutSpan> = emptyList()
    ) = EpubLayoutBlock(
        orderIndex = order,
        kind = kind,
        textStart = start,
        textEnd = end,
        element = EpubElementRef(if (kind == EpubLayoutBlockKind.CONTAINER) "div" else "p"),
        style = style,
        spans = spans
    )
}
