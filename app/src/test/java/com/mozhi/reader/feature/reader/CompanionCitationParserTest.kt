package com.mozhi.reader.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionCitationParserTest {

    @Test
    fun parsesMarkedCitationWithChapter() {
        val parsed = CompanionCitationParser.parse(
            "他在这里就埋了伏笔——〔原文 第3章〕「叶文洁按下了那个按钮」——后面才收。"
        )

        assertEquals(1, parsed.citations.size)
        assertEquals(3, parsed.citations.first().chapterNumber)
        assertEquals("叶文洁按下了那个按钮", parsed.citations.first().quote)
        // 标记是给程序看的，读者眼里应该只剩通顺的引用句。
        assertTrue(parsed.displayText.contains("——「叶文洁按下了那个按钮」——"))
        assertTrue(!parsed.displayText.contains("〔原文"))
    }

    @Test
    fun parsesMarkedCitationWithoutChapter() {
        val parsed = CompanionCitationParser.parse("""〔原文〕"这一段写得极好，值得反复读"""")

        assertEquals(1, parsed.citations.size)
        assertNull(parsed.citations.first().chapterNumber)
    }

    @Test
    fun toleratesSpacingInsideTheMarker() {
        val parsed = CompanionCitationParser.parse("〔 原文 第 12 章 〕 「他终于回过头来看了一眼」")

        assertEquals(12, parsed.citations.first().chapterNumber)
        assertEquals("他终于回过头来看了一眼", parsed.citations.first().quote)
    }

    @Test
    fun collectsMultipleCitationsInOrder() {
        val parsed = CompanionCitationParser.parse(
            "前后呼应：〔原文 第1章〕「他说他不会再回来」，到了〔原文 第9章〕「他还是回来了」。"
        )

        assertEquals(listOf(1, 9), parsed.citations.map { it.chapterNumber })
    }

    /** 模型忘记加标记是常态；裸引号兜底，否则「跳到原文」会时有时无。 */
    @Test
    fun fallsBackToBareQuotesWhenNoMarkerPresent() {
        val parsed = CompanionCitationParser.parse("原文里写的是「他终于回过头来看了一眼」。")

        assertEquals(1, parsed.citations.size)
        assertNull(parsed.citations.first().chapterNumber)
        assertEquals("他终于回过头来看了一眼", parsed.citations.first().quote)
        // 兜底不改写正文。
        assertTrue(parsed.displayText.contains("「他终于回过头来看了一眼」"))
    }

    /** 有显式标记时不再扫裸引号，避免把对话里的普通引号也当成引文。 */
    @Test
    fun markerPresentSuppressesBareQuoteScanning() {
        val parsed = CompanionCitationParser.parse(
            "〔原文 第2章〕「风从山谷里穿过来」；顺带一提，「我个人挺喜欢这段的」。"
        )

        assertEquals(1, parsed.citations.size)
        assertEquals("风从山谷里穿过来", parsed.citations.first().quote)
    }

    @Test
    fun ignoresQuotesTooShortToLocate() {
        val parsed = CompanionCitationParser.parse("他只说了「好」。")

        assertTrue(parsed.citations.isEmpty())
    }

    @Test
    fun deduplicatesRepeatedQuotes() {
        val parsed = CompanionCitationParser.parse(
            "〔原文 第1章〕「他说他不会再回来」……〔原文 第1章〕「他说他不会再回来」"
        )

        assertEquals(1, parsed.citations.size)
    }

    @Test
    fun capsCitationCount() {
        val raw = (1..20).joinToString("") { "〔原文 第${it}章〕「这是第${it}段足够长的引文内容」" }

        assertEquals(CompanionCitationParser.MAX_CITATIONS, CompanionCitationParser.parse(raw).citations.size)
    }

    @Test
    fun plainMessagesYieldNothingAndAreLeftUntouched() {
        val raw = "这一段我觉得写得挺克制的，没有渲染情绪。"

        val parsed = CompanionCitationParser.parse(raw)

        assertTrue(parsed.citations.isEmpty())
        assertEquals(raw, parsed.displayText)
    }

    @Test
    fun labelShowsChapterAndTruncatesLongQuotes() {
        val long = CompanionCitation(3, "这是一段非常长的引文内容需要被截断显示才不会撑破胶囊")

        val label = CompanionCitationParser.label(long)

        assertTrue(label.startsWith("第3章 · "))
        assertTrue(label.endsWith("…"))

        assertEquals("短一些的引文内容", CompanionCitationParser.label(CompanionCitation(null, "短一些的引文内容")))
    }
}
