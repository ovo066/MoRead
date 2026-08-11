package com.mozhi.reader.feature.reader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动条带的核心承诺：跨页缝隙与页内行距完全一致 —— 段中切缝空白恰为
 * (lineStep - textHeight)，段尾切缝再加 paragraphSpacing；bottomAlign 的页内拉伸
 * 不影响缝隙（extent 以末行 lineTop 为基）。
 */
class ChapterStripTest {

    private val lineStep = 24f
    private val paragraphSpacing = 10f
    private val blankLineSpacing = 16f
    private val textHeight = 20f // FakeMeasure 的正文行高

    private fun spec() = TypesetSpec(
        visibleWidth = 100f,
        visibleHeight = 100f,
        contentLineStep = lineStep,
        titleLineStep = 30f,
        paragraphSpacing = paragraphSpacing,
        blankLineSpacing = blankLineSpacing,
        titleTopSpacing = 0f,
        titleBottomSpacing = 0f
    )

    private fun typeset(body: String, title: String = ""): TextChapter =
        ChapterTypesetter(spec(), FakeMeasure()).typeset(0, title, body)

    /** 长短段交替（3 行段 / 2 行段），确保段中切缝与段尾切缝都出现。 */
    private fun multiPageBody(): String =
        List(10) { index -> "一".repeat(if (index % 2 == 0) 30 else 15) }.joinToString("\n")

    @Test
    fun `page seams reproduce continuous line spacing`() {
        val chapter = typeset(multiPageBody())
        assertTrue("需要多页样本，实际 ${chapter.pages.size} 页", chapter.pages.size >= 3)
        val strip = ChapterStrip(chapter, spec())

        var midParagraphSeams = 0
        var paragraphEndSeams = 0
        for (page in 0 until chapter.pages.size - 1) {
            val prevLast = chapter.pages[page].lines.last()
            val nextFirst = chapter.pages[page + 1].lines.first()
            val whiteGap = (strip.pageTops[page + 1] + nextFirst.lineTop) -
                (strip.pageTops[page] + prevLast.lineTop + textHeight)
            val expected = (lineStep - textHeight) +
                if (prevLast.isParagraphEnd) paragraphSpacing else 0f
            assertEquals(
                "第 $page 页切缝空白（段尾=${prevLast.isParagraphEnd}）",
                expected,
                whiteGap,
                0.01f
            )
            if (prevLast.isParagraphEnd) paragraphEndSeams++ else midParagraphSeams++
        }
        assertTrue("样本应包含段中切缝", midParagraphSeams > 0)
        assertTrue("样本应包含段尾切缝", paragraphEndSeams > 0)
    }

    @Test
    fun `strip offsets round trip through char offsets`() {
        val chapter = typeset(multiPageBody())
        val strip = ChapterStrip(chapter, spec())
        for (page in chapter.pages) {
            val line = page.lines.firstOrNull { it.charLength > 0 } ?: continue
            val y = strip.stripYOf(line.chapterPosition)
            assertEquals(line.chapterPosition, strip.charOffsetAt(y))
        }
    }

    @Test
    fun `a point inside the seam gap resolves to the next page's first character`() {
        val chapter = typeset(multiPageBody())
        val strip = ChapterStrip(chapter, spec())
        for (page in 0 until chapter.pages.size - 1) {
            val nextFirstChar = chapter.pages[page + 1].lines
                .firstOrNull { it.charLength > 0 }
                ?.chapterPosition ?: continue
            assertEquals(
                nextFirstChar,
                strip.charOffsetAt(strip.pageTops[page + 1] - 0.5f)
            )
        }
    }

    @Test
    fun `pageIndexAt honours exact page boundaries`() {
        val chapter = typeset(multiPageBody())
        val strip = ChapterStrip(chapter, spec())
        for (page in chapter.pages.indices) {
            assertEquals(page, strip.pageIndexAt(strip.pageTops[page]))
            if (page > 0) {
                assertEquals(page - 1, strip.pageIndexAt(strip.pageTops[page] - 0.1f))
            }
        }
    }

    @Test
    fun `page tops are strictly increasing even with a synthetic title`() {
        val chapter = typeset(multiPageBody(), title = "第一章 起风")
        val strip = ChapterStrip(chapter, spec())
        for (page in 1 until strip.pageTops.size) {
            assertTrue(strip.pageTops[page] > strip.pageTops[page - 1])
        }
        assertTrue(strip.totalHeight > strip.pageTops.last())
    }

    @Test
    fun `an empty chapter still occupies one viewport`() {
        val chapter = typeset("")
        val strip = ChapterStrip(chapter, spec())
        assertEquals(100f, strip.totalHeight, 0.01f)
        assertEquals(0, strip.charOffsetAt(0f))
        assertEquals(0f, strip.stripYOf(0), 0.01f)
    }

    /**
     * 空行分段的书，被切缝吞掉的是空行间距而不是段距——靠 isParagraphEnd 反推会短一截，
     * 所以条带必须读排版器记下的 trailingGap。
     */
    @Test
    fun `seams after a blank source line reproduce the blank line gap`() {
        val body = List(10) { "一".repeat(if (it % 2 == 0) 30 else 15) }.joinToString("\n\n")
        val chapter = ChapterTypesetter(spec(), FakeMeasure()).typeset(0, "", body)
        assertTrue("需要多页样本，实际 ${chapter.pages.size} 页", chapter.pages.size >= 3)
        val strip = ChapterStrip(chapter, spec())

        var blankSeams = 0
        for (page in 0 until chapter.pages.size - 1) {
            val prevLast = chapter.pages[page].lines.last()
            val nextFirst = chapter.pages[page + 1].lines.first()
            val whiteGap = (strip.pageTops[page + 1] + nextFirst.lineTop) -
                (strip.pageTops[page] + prevLast.lineTop + textHeight)
            val expected = (lineStep - textHeight) +
                if (prevLast.isParagraphEnd) blankLineSpacing else 0f
            assertEquals("第 $page 页切缝空白", expected, whiteGap, 0.01f)
            if (prevLast.isParagraphEnd) blankSeams++
        }
        assertTrue("样本应包含段尾切缝", blankSeams > 0)
    }
}
