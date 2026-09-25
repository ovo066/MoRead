package com.mozhi.reader.feature.reader.engine

import com.mozhi.reader.core.datastore.PublisherStyleMode
import com.mozhi.reader.core.datastore.ReaderSyntaxFont
import com.mozhi.reader.core.datastore.ReaderSyntaxRule
import com.mozhi.reader.core.library.EpubComputedStyle
import com.mozhi.reader.core.library.EpubElementRef
import com.mozhi.reader.core.library.EpubLayoutBlock
import com.mozhi.reader.core.library.EpubLayoutBlockKind
import com.mozhi.reader.core.library.EpubLayoutChapter
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.feature.importer.EpubLayoutDocumentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高亮规则换了字体时，排版必须按渲染用的那套字体测宽。以前按正文字体测、按规则字体画，
 * 英文逐字母错位：窄字母后留空、宽字母互相压叠（CJK 各字体都是全角，所以只有英文露馅）。
 */
class SyntaxFontMeasurementTest {

    private val spec = TypesetSpec(
        visibleWidth = 200f,
        visibleHeight = 200f,
        contentLineStep = 24f,
        titleLineStep = 32f,
        paragraphSpacing = 0f,
        blankLineSpacing = 0f,
        titleTopSpacing = 0f,
        titleBottomSpacing = 0f,
        contentFontSizePx = 20f,
        indentCharCount = 0f,
        justifyContent = false,
        bottomAlign = false,
        publisherStyleMode = PublisherStyleMode.TAKE_OVER
    )

    private val rule = ReaderSyntaxRule(
        id = 31,
        name = "对白",
        startDelimiter = "“",
        endDelimiter = "”",
        colorArgb = 0xFFF04B0D.toInt(),
        font = ReaderSyntaxFont.SANS_SERIF,
        bold = true
    )

    private val body = "他说“Amset”好"

    @Test
    fun `plain text measures and breaks highlighted runs in the rule font`() {
        val measure = WideSansMeasure()
        val chapter = ChapterTypesetter(spec.copy(syntaxHighlightRules = listOf(rule)), measure)
            .typeset(0, "", body)

        assertLatinFollowsRuleFont(chapter)
        val run = measure.styledBreakRuns.single()
        assertEquals("“Amset”", body.substring(run.start, run.end))
        assertEquals(ReaderSyntaxFont.SANS_SERIF, run.style.syntaxFont)
    }

    @Test
    fun `legacy epub backend measures highlighted runs in the rule font`() {
        val block = EpubLayoutBlock(
            orderIndex = 0,
            kind = EpubLayoutBlockKind.PARAGRAPH,
            textStart = 0,
            textEnd = body.length,
            element = EpubElementRef("p"),
            style = EpubComputedStyle(textIndentEm = 0f)
        )
        val document = EpubLayoutChapter(
            chapterIndex = 0,
            href = "OEBPS/Text/chapter.xhtml",
            bodyStyle = EpubComputedStyle(),
            blocks = listOf(block),
            textLength = body.length
        )
        val chapter = ChapterTypesetter(spec.copy(syntaxHighlightRules = listOf(rule)), WideSansMeasure())
            .typeset(0, "", body, epubLayout = EpubLayoutChapterBundle(document, emptyMap(), emptyMap(), emptyList()))

        assertLatinFollowsRuleFont(chapter)
    }

    @Test
    fun `epub takeover measures highlighted runs in the rule font`() {
        val parsed = EpubLayoutDocumentParser().parseWithText(
            "<html><head><style>p{margin:0;font-family:serif}</style></head><body><p>$body</p></body></html>"
                .toByteArray(),
            0,
            "OPS/ch.xhtml",
            emptyMap()
        )
        val chapter = ChapterTypesetter(spec.copy(syntaxHighlightRules = listOf(rule)), WideSansMeasure())
            .typeset(0, "", parsed.text, epubLayout = EpubLayoutChapterBundle(
                parsed.document, emptyMap(), emptyMap(), dom = parsed.dom
            ))

        assertLatinFollowsRuleFont(chapter)
    }

    private fun assertLatinFollowsRuleFont(chapter: TextChapter) {
        val columns = chapter.pages.flatMap(TextPage::lines).flatMap(TextLine::columns)
            .filter { it.inlineMarkerKind == null }
        val latin = columns.filter { it.charData.single().code < 128 }
        assertEquals("Amset", latin.joinToString("") { it.charData })
        assertTrue(latin.all { it.syntaxFont == ReaderSyntaxFont.SANS_SERIF })
        latin.forEach { column -> assertEquals(WIDE_LATIN, column.end - column.start, 0.01f) }
        // 相邻字形首尾相接：不留缝也不压叠。
        columns.zipWithNext().forEach { (previous, next) ->
            assertEquals("${previous.charData}→${next.charData}", previous.end, next.start, 0.01f)
        }
    }

    /** FakeMeasure 的 ASCII 是 5px；「黑体」里改成 8px，模拟规则字体与正文字体宽度不同。 */
    private class WideSansMeasure(private val base: FakeMeasure = FakeMeasure()) : TextMeasure by base {
        val styledBreakRuns = ArrayList<StyledTextRun>()

        override fun charWidths(text: String, style: MeasuredTextStyle): FloatArray =
            base.charWidths(text, style).also { widths ->
                if (style.syntaxFont == ReaderSyntaxFont.SANS_SERIF) {
                    for (index in widths.indices) if (text[index].code < 128) widths[index] = WIDE_LATIN
                }
            }

        override fun breakLines(
            text: String,
            isTitle: Boolean,
            availableWidth: Float,
            firstLineIndent: Float,
            styledRuns: List<StyledTextRun>
        ): IntArray {
            styledBreakRuns += styledRuns
            return base.breakLines(text, isTitle, availableWidth, firstLineIndent)
        }
    }

    private companion object {
        const val WIDE_LATIN = 8f
    }
}
