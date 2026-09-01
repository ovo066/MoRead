package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.datastore.ChineseConversionMode
import com.mozhi.reader.core.library.QuoteChapter
import com.mozhi.reader.core.library.ReaderTextAnchorCodec
import com.mozhi.reader.core.library.ReaderTextAnchors
import com.mozhi.reader.core.text.ChineseTextConverter
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
        val parsed = CompanionCitationParser.parse("〔原文〕「这一段写得极好，值得反复读」")

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

    @Test
    fun ignoresBareQuotesWithoutExplicitMarker() {
        val parsed = CompanionCitationParser.parse("原文里写的是「他终于回过头来看了一眼」。")

        assertTrue(parsed.citations.isEmpty())
        assertTrue(parsed.displayText.contains("「他终于回过头来看了一眼」"))
    }

    @Test
    fun ignoresMarkerWithNonCanonicalQuotes() {
        val raw = "〔原文 第2章〕\"他终于回过头来看了一眼\""

        val parsed = CompanionCitationParser.parse(raw)

        assertTrue(parsed.citations.isEmpty())
        assertEquals(raw, parsed.displayText)
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

    @Test
    fun verifierKeepsOnlyQuotesThatExistVerbatim() {
        val citations = listOf(
            CompanionCitation(2, "风从山谷里穿过来"),
            CompanionCitation(1, "这句话并不存在于书中")
        )
        val chapters = listOf(
            QuoteChapter(0, "第一章没有相关内容。"),
            QuoteChapter(1, "清晨，风从山谷里穿过来，带着雪的味道。")
        )

        val located = CompanionCitationVerifier.locate(citations, chapters)

        assertEquals(1, located.size)
        assertEquals("风从山谷里穿过来", located.single().citation.quote)
        assertEquals(2, located.single().citation.chapterNumber)
        assertEquals(1, located.single().chapterIndex)

        val regionalBody = "前面的程式碼很长。滑鼠裡面的程式碼。后文。"
        val regional = CompanionCitationVerifier.locate(
            listOf(CompanionCitation(1, "滑鼠裡面的程式碼")),
            listOf(QuoteChapter(0, regionalBody))
        ).single()
        val shown = ChineseTextConverter().convert(regionalBody, ChineseConversionMode.TW2SP)
        val resolved = ReaderTextAnchors.resolve(
            shown,
            ReaderTextAnchorCodec.decode(regional.sourceAnchorJson)!!,
            ChineseConversionMode.TW2SP,
            ChineseTextConverter()
        )!!
        assertEquals("鼠标里面的代码", shown.substring(resolved.start, resolved.end))
    }

    @Test
    fun verifierRejectsParaphrasesAndPunctuationChanges() {
        val chapters = listOf(QuoteChapter(0, "风从山谷里穿过来，带着雪的味道。"))

        assertTrue(
            CompanionCitationVerifier.locate(
                listOf(CompanionCitation(1, "风从山谷里穿过来 带着雪的味道")),
                chapters
            ).isEmpty()
        )
    }
}
