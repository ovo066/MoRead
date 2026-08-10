package com.mozhi.reader.feature.reader.engine

import com.mozhi.reader.core.datastore.ReaderSyntaxFont
import com.mozhi.reader.core.datastore.ReaderSyntaxRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterTypesetterTest {

    private val spec = TypesetSpec(
        visibleWidth = 100f,
        visibleHeight = 200f,
        contentLineStep = 25f,
        titleLineStep = 34f,
        paragraphSpacing = 9f,
        titleTopSpacing = 10f,
        titleBottomSpacing = 25f
    )

    private fun typeset(title: String, body: String): TextChapter =
        ChapterTypesetter(spec, FakeMeasure()).typeset(0, title, body)

    private val cjkParagraph = "春江潮水连海平海上明月共潮生滟滟随波千万里何处春江无月明"

    @Test
    fun `first line of a paragraph is indented two columns`() {
        val chapter = typeset("", cjkParagraph)
        val lines = chapter.pages.flatMap(TextPage::lines)
        assertEquals(20f, lines.first().startX, 0.01f)
        assertEquals(20f, lines.first().columns.first().start, 0.01f)
        assertTrue("continuation lines start at the margin", lines.drop(1).all { it.startX == 0f })
    }

    @Test
    fun `body offsets survive layout untouched`() {
        val body = cjkParagraph + "\n" + cjkParagraph
        val chapter = typeset("", body)
        val lines = chapter.pages.flatMap(TextPage::lines)
        for (line in lines) {
            val expected = body.substring(line.chapterPosition, line.chapterPosition + line.charLength)
            assertEquals(expected, line.text)
        }
        assertEquals(body.length, lines.sumOf(TextLine::charLength) + 1) // +1 for the '\n'
    }

    @Test
    fun `pages never overflow the visible height`() {
        val body = List(40) { cjkParagraph }.joinToString("\n")
        val chapter = typeset("", body)
        assertTrue(chapter.pageCount > 1)
        for (page in chapter.pages) {
            assertTrue(
                "page ${page.index} bottom ${page.lines.last().lineBottom}",
                page.lines.last().lineBottom <= spec.visibleHeight + 0.51f
            )
        }
    }

    @Test
    fun `char offset to page index round trips`() {
        val body = List(40) { cjkParagraph }.joinToString("\n")
        val chapter = typeset("", body)
        for (page in chapter.pages) {
            assertEquals(page.index, chapter.pageIndexAt(page.chapterPosition))
            assertEquals(page.chapterPosition, chapter.pageStartOffset(page.index))
        }
        // An offset in the middle of a page maps to that page.
        val middle = chapter.pages[1].chapterPosition + 3
        assertEquals(1, chapter.pageIndexAt(middle))
    }

    @Test
    fun `full lines are justified to the right margin`() {
        // 29 chars: 8 + 10 + 10 + 1, so the paragraph-final line is genuinely short.
        val chapter = typeset("", cjkParagraph + "也")
        val lines = chapter.pages.flatMap(TextPage::lines)
        val justified = lines.dropLast(1)
        assertTrue(justified.isNotEmpty())
        for (line in justified) {
            // Gaps absorb the residual, so the last cluster's natural end lands on the margin.
            assertEquals(spec.visibleWidth, line.columns.last().end, 1.5f)
        }
        val last = lines.last()
        assertTrue("paragraph-final line stays ragged", last.columns.last().end < spec.visibleWidth)
    }

    @Test
    fun `synthetic title carries no body offsets`() {
        val chapter = typeset("第一章 缘起", cjkParagraph)
        val titleLines = chapter.pages.first().lines.filter(TextLine::isTitle)
        assertTrue(titleLines.isNotEmpty())
        assertTrue(titleLines.all { it.charLength == 0 })
        val firstBodyLine = chapter.pages.first().lines.first { !it.isTitle }
        assertEquals(0, firstBodyLine.chapterPosition)
    }

    @Test
    fun `body-leading title is styled in place and keeps offsets`() {
        val title = "第一章 缘起"
        val body = title + "\n" + cjkParagraph
        val chapter = typeset(title, body)
        val titleLines = chapter.pages.first().lines.filter(TextLine::isTitle)
        assertTrue(titleLines.isNotEmpty())
        assertEquals(title.length, titleLines.sumOf(TextLine::charLength))
        val firstBodyLine = chapter.pages.first().lines.first { !it.isTitle }
        assertEquals(title.length + 1, firstBodyLine.chapterPosition)
    }

    @Test
    fun `mixed latin lines justify through spaces`() {
        val body = "word another word tail 汉字汉字汉字\n" + cjkParagraph
        val chapter = typeset("", body)
        val lines = chapter.pages.flatMap(TextPage::lines)
        for (line in lines) {
            assertTrue(line.columns.last().end <= spec.visibleWidth + 0.5f)
        }
    }

    @Test
    fun `empty body still yields one page`() {
        val chapter = typeset("", "")
        assertEquals(1, chapter.pageCount)
    }

    @Test
    fun `title page stays reachable when it shares its start offset with the body page`() {
        // A page so short the synthetic title fills page 0 alone; body pages start at offset 0 too.
        val tiny = spec.copy(visibleHeight = 40f, titleTopSpacing = 0f, titleBottomSpacing = 0f)
        val chapter = ChapterTypesetter(tiny, FakeMeasure())
            .typeset(0, "很长很长的章节标题一定要拆行", cjkParagraph)
        assertTrue(chapter.pageCount > 1)
        assertEquals(chapter.pages[0].chapterPosition, chapter.pages[1].chapterPosition)
        assertEquals("opening the chapter must land on the title page", 0, chapter.pageIndexAt(0))
    }

    @Test
    fun `inline image keeps one body character and is sized inside page`() {
        val body = "前文\n［图片］\n后文"
        val chapter = ChapterTypesetter(spec, FakeMeasure()).typeset(
            chapterIndex = 0,
            title = "",
            body = body,
            inlineImages = listOf(
                InlineImageSource(
                    charOffset = 3,
                    imagePath = "/private/image.png",
                    pixelWidth = 1200,
                    pixelHeight = 800,
                    altText = "测试插图"
                )
            )
        )

        val imageLine = chapter.pages.flatMap(TextPage::lines).single { it.inlineImage != null }
        assertEquals(3, imageLine.chapterPosition)
        assertEquals(4, imageLine.charLength)
        assertEquals(100f, imageLine.inlineImage!!.width, 0.01f)
        assertTrue(imageLine.inlineImage!!.height <= spec.visibleHeight * 0.72f)
        assertTrue(chapter.pages.all { page -> page.lines.all { it.lineBottom <= spec.visibleHeight + 0.51f } })
    }

    @Test
    fun `blank source line keeps a full line of height`() {
        val single = typeset("", cjkParagraph + "\n" + cjkParagraph)
        val separated = typeset("", cjkParagraph + "\n\n" + cjkParagraph)
        val singleGap = firstGapBetweenParagraphs(single)
        val separatedGap = firstGapBetweenParagraphs(separated)
        assertTrue(
            "blank line gap ($separatedGap) must exceed plain paragraph gap ($singleGap) by a line",
            separatedGap >= singleGap + 20f
        )
    }

    private fun firstGapBetweenParagraphs(chapter: TextChapter): Float {
        val lines = chapter.pages.flatMap(TextPage::lines).filter { it.columns.isNotEmpty() }
        val endOfFirst = lines.indexOfFirst(TextLine::isParagraphEnd)
        return lines[endOfFirst + 1].lineTop - lines[endOfFirst].lineBottom
    }

    @Test
    fun `relayout at another width finds the same sentence`() {
        val body = List(30) { cjkParagraph }.joinToString("\n")
        val wide = ChapterTypesetter(spec.copy(visibleWidth = 160f), FakeMeasure()).typeset(0, "", body)
        val narrow = ChapterTypesetter(spec, FakeMeasure()).typeset(0, "", body)
        val offset = wide.pages[2].chapterPosition
        val narrowPage = narrow.pages[narrow.pageIndexAt(offset)]
        assertTrue(narrowPage.chapterPosition <= offset)
        assertTrue(offset < narrowPage.chapterPosition + narrowPage.charLength + 1)
    }

    @Test
    fun `extended syntax style reaches laid out text columns`() {
        val rule = ReaderSyntaxRule(
            id = 21,
            name = "对白",
            startDelimiter = "“",
            endDelimiter = "”",
            colorArgb = 0xFF123456.toInt(),
            backgroundArgb = 0x33223344,
            font = ReaderSyntaxFont.SERIF,
            fontAssetId = "font-21",
            bold = true,
            italic = true,
            underline = true,
            strikethrough = true
        )
        val chapter = ChapterTypesetter(
            spec.copy(syntaxHighlightRules = listOf(rule)),
            FakeMeasure()
        ).typeset(0, "", "“你好”还有正文")
        val styled = chapter.pages.flatMap(TextPage::lines)
            .flatMap(TextLine::columns)
            .filter { it.syntaxColorArgb != null }

        assertEquals("“你好”", styled.joinToString("") { it.charData })
        assertTrue(styled.all { it.syntaxBackgroundArgb == rule.backgroundArgb })
        assertTrue(styled.all { it.syntaxFont == ReaderSyntaxFont.SERIF })
        assertTrue(styled.all { it.syntaxFontAssetId == "font-21" })
        assertTrue(styled.all { it.syntaxBold && it.syntaxItalic })
        assertTrue(styled.all { it.syntaxUnderline && it.syntaxStrikethrough })
    }

    @Test
    fun `fractional indent and left alignment are honored`() {
        val customSpec = spec.copy(indentCharCount = 1.5f, justifyContent = false)
        val chapter = ChapterTypesetter(customSpec, FakeMeasure()).typeset(0, "", cjkParagraph)
        val lines = chapter.pages.flatMap(TextPage::lines)

        assertEquals(15f, lines.first().startX, 0.01f)
        assertEquals(15f, lines.first().columns.first().start, 0.01f)
        assertTrue(lines.first().columns.last().end < customSpec.visibleWidth)
    }
}
