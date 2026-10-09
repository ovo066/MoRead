package com.mozhi.reader.ai.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterResearchPartsTest {

    @Test fun `candidates favour frequent surname names and dialogue speakers`() {
        val chapters = listOf(
            0 to "林冲道：“兄弟。”鲁智深笑道：“好。”林冲回到营中，林冲又想起往事。高兴得很。",
            1 to "林冲独自出门。只听鲁智深道：“走罢。”宝玉道：“妹妹。”宝玉说：“好。”",
            2 to "林冲拔刀。王熙凤笑道：“来了。”王熙凤走开。王熙凤坐下。"
        )
        val names = CharacterCandidates.rank(chapters).map { it.name }
        assertEquals("林冲", names.first())
        assertTrue("鲁智深" in names)
        assertTrue("王熙凤" in names)
        assertTrue("宝玉" in names)
        assertFalse("王熙" in names)
        assertFalse("高兴" in names)
    }

    @Test fun `latin books use capitalised words`() {
        val text = "Then Elizabeth smiled at Darcy. Mr Darcy looked at Elizabeth. Soon Elizabeth and Darcy walked, and Darcy spoke to Elizabeth."
        val names = CharacterCandidates.rank(listOf(0 to text)).map { it.name }
        assertEquals(listOf("Darcy", "Elizabeth"), names.sorted())
    }

    @Test fun `passages cover first and later appearances and stay bounded`() {
        val filler = "风吹过原野。".repeat(200)
        val chapters = (0 until 12).map { it to "${filler}林冲在第${it}章出现了。$filler" }
        val passages = CharacterPassages.collect(chapters, listOf("林冲"))
        assertTrue(passages.size in 2..8)
        assertEquals(0, passages.first().chapterIndex)
        assertEquals(11, passages.last().chapterIndex)
        assertTrue(passages.sumOf { it.text.length } <= 9_000)
        passages.forEach { passage ->
            assertEquals(chapters[passage.chapterIndex].second.substring(passage.start, passage.start + passage.text.length), passage.text)
        }
    }

    @Test fun `quotes are mapped back to their own chapter coordinates`() {
        val passages = listOf(SourcePassage(3, 100, "林冲在灯塔等候多时。"), SourcePassage(7, 40, "林冲与鲁智深结为兄弟。"))
        val raw = """{"characters":[{"name":"林冲","facts":[{"text":"等候","quote":"林冲在灯塔等候多时。"},{"text":"结义","quote":"林冲与鲁智深结为兄弟。"}]}]}"""
        val result = ChapterKnowledgeCodec.parseCharactersAcross(raw, passages)
        assertEquals(listOf(3, 7), result.map { it.first })
        assertEquals(100, result[0].second.facts.single().start)
        assertEquals(40, result[1].second.facts.single().start)
        val crossing = """{"characters":[{"name":"林冲","facts":[{"text":"拼接","quote":"等候多时。\n\n〔……〕\n\n林冲与"}]}]}"""
        assertTrue(runCatching { ChapterKnowledgeCodec.parseCharactersAcross(crossing, passages) }.isFailure)
    }
}
