package com.mozhi.reader.ai.knowledge

import com.mozhi.reader.core.database.entity.ChapterKnowledgeEntity
import com.mozhi.reader.core.retrieval.ReadingScope
import org.junit.Assert.*
import org.junit.Test

class ChapterKnowledgeTest {
    private val source = "林舟在灯塔等候。小满带来一封信。两人约定天亮后出发。"
    private val raw = """{"outline":"林舟在灯塔等候时，小满带来了一封信。两人约定等到天亮便出发。","summary":[{"text":"小满给林舟带信，两人约定出发。","quote":"小满带来一封信。"}],"characters":[{"name":"林舟","facts":[{"text":"在灯塔等候。","quote":"林舟在灯塔等候。"}]}]}"""

    @Test fun verifiedFactsCarryAbsoluteUtf16OffsetsForCitationNavigation() {
        val result = ChapterKnowledgeCodec.parse(raw, KnowledgePart(18, source))
        val fact = result.summary.single()
        assertEquals(18 + source.indexOf(fact.quote), fact.start)
        assertEquals(fact.start + fact.quote.length, fact.end)
        assertEquals("林舟", result.characters.single().name)
        assertTrue(result.readableOutline.startsWith("林舟在灯塔等候时"))
    }

    @Test fun inventedNamesQuotesAndAmbiguousQuotesCannotBecomeKnowledge() {
        assertTrue(runCatching { ChapterKnowledgeCodec.parse(raw.replace("小满带来一封信。\"", "小满其实是凶手。\""), KnowledgePart(0, source)) }.isFailure)
        assertTrue(runCatching { ChapterKnowledgeCodec.parse(raw.replace("\"name\":\"林舟\"", "\"name\":\"幕后反派\""), KnowledgePart(0, source)) }.isFailure)
        assertTrue(runCatching { ChapterKnowledgeCodec.parse(raw, KnowledgePart(0, source + source)) }.isFailure)
    }

    @Test fun partsCoverTheFullReadableSourceWithoutSplittingSurrogates() {
        val text = "章".repeat(9999) + "😀" + "段落。\n".repeat(6000)
        val parts = ChapterKnowledgeCodec.parts(text)
        assertEquals(text, parts.joinToString("") { it.text })
        assertEquals(0, parts.first().start)
        parts.zipWithNext().forEach { (left, right) -> assertEquals(left.start + left.text.length, right.start) }
        assertTrue(parts.all { it.text.length <= 10_000 && !it.text.last().isHighSurrogate() && !it.text.first().isLowSurrogate() })
        assertTrue(runCatching { ChapterKnowledgeCodec.parts("字".repeat(60_001)) }.isFailure)
    }

    @Test fun cachedFactsStayAvailableOnRereadAndDisappearAfterScopeResetOrRevisionChange() {
        val value = ChapterKnowledgeCodec.parse(raw, KnowledgePart(0, source))
        val row = ChapterKnowledgeEntity(1, 2, "revision", source.length, ChapterKnowledgeCodec.hash(source), "model-key", "model", 1, ChapterKnowledgeCodec.encode(value), 1)
        assertNotNull(ChapterKnowledgeCodec.visible(row, ReadingScope.upto(8, 5), "revision"))
        assertNull(ChapterKnowledgeCodec.visible(row, ReadingScope.upto(2, source.length - 1), "revision"))
        assertNull(ChapterKnowledgeCodec.visible(row, ReadingScope.upto(8, 5), "changed"))
        assertNull(ChapterKnowledgeCodec.visible(row.copy(promptVersion = 999), ReadingScope.WholeBook, "revision"))
    }

    @Test fun outlinesRequireConnectedProseAndOldSavedEvidenceRemainsReadable() {
        assertTrue(runCatching { ChapterKnowledgeCodec.validateOutline("- 人物出现\n- 情节发展") }.isFailure)
        assertTrue(runCatching { ChapterKnowledgeCodec.validateOutline("1. 人物出现\n2. 情节发展") }.isFailure)
        assertTrue(runCatching { ChapterKnowledgeCodec.parse(raw.replace("\"outline\":", "\"missing\":"), KnowledgePart(0, source)) }.isFailure)
        val fact = KnowledgeFact("旧版保存的概括", "林舟在灯塔等候。", 0, 8)
        assertEquals(fact.text, ChapterKnowledge(listOf(fact)).readableOutline)
    }

    @Test fun wholeBookPartsHaveNoSixtyThousandCharacterCutoff() {
        val source = "很长的一章。\n".repeat(12_000)
        val parts = ChapterKnowledgeCodec.parts(source, enforceChapterLimit = false)
        assertEquals(source, parts.joinToString("") { it.text })
        assertTrue(parts.size > 6)
    }
}
