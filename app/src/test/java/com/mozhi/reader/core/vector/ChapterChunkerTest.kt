package com.mozhi.reader.core.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterChunkerTest {

    @Test
    fun shortChapterYieldsSingleChunk() {
        val text = "第一段。\n\n第二段。"
        val chunks = ChapterChunker.chunk(text)
        assertEquals(listOf("第一段。\n第二段。"), chunks)
    }

    @Test
    fun blankTextYieldsNothing() {
        assertTrue(ChapterChunker.chunk("").isEmpty())
        assertTrue(ChapterChunker.chunk(" \n\t\n  \r\n").isEmpty())
    }

    @Test
    fun paragraphsPackGreedilyWithoutSplittingAny() {
        val paragraphs = List(7) { i -> "段${i}".padEnd(200, '字') }
        val chunks = ChapterChunker.chunk(paragraphs.joinToString("\n"))

        // 200+1+200=401 还能装，再加一段超 480 → 每块两段。
        chunks.dropLast(1).forEach { chunk ->
            assertEquals(2, chunk.split('\n').size)
            assertTrue(chunk.length <= ChapterChunker.TARGET_CHARS)
        }
        // 段落永不跨块截断，拼回去等于原文段落序列。
        assertEquals(paragraphs, chunks.flatMap { it.split('\n') })
    }

    @Test
    fun oversizedParagraphSplitsAtSentenceBoundaries() {
        val sentence = "他望着远处的灯火想了很久".padEnd(149, '想') + "。"
        val paragraph = sentence.repeat(5) // 750 字，超过 MAX_CHARS
        val chunks = ChapterChunker.chunk(paragraph)

        assertTrue(chunks.size >= 2)
        chunks.forEach { chunk ->
            assertTrue("块长 ${chunk.length} 超上限", chunk.length <= ChapterChunker.MAX_CHARS)
            assertTrue("块未以句号结尾：${chunk.takeLast(4)}", chunk.endsWith("。"))
        }
        assertEquals(paragraph, chunks.joinToString(""))
    }

    @Test
    fun terminatorKeepsTrailingClosersWithSentence() {
        val exclaimed = "“" + "这不可能".padEnd(340, '话') + "！”"
        val reply = "她沉默了".padEnd(340, '默') + "。"
        val paragraph = exclaimed + reply // 超过 MAX_CHARS，触发段内切句

        val chunks = ChapterChunker.chunk(paragraph)

        // 收尾引号跟着感叹号归前句，不会漂到下一块的开头。
        assertEquals(listOf(exclaimed, reply), chunks)
    }

    @Test
    fun sentenceWithoutTerminatorsHardSplitsAtMax() {
        val monster = "长".repeat(1500)
        val chunks = ChapterChunker.chunk(monster)
        assertEquals(listOf(640, 640, 220), chunks.map { it.length })
        assertEquals(monster, chunks.joinToString(""))
    }

    @Test
    fun singleParagraphBetweenTargetAndMaxStaysWhole() {
        val paragraph = "整".repeat(600)
        val chunks = ChapterChunker.chunk(paragraph)
        assertEquals(listOf(paragraph), chunks)
    }

    @Test
    fun windowsLineEndingsAreHandled() {
        val text = "第一段。\r\n第二段。\r\n"
        assertEquals(listOf("第一段。\n第二段。"), ChapterChunker.chunk(text))
    }
    @Test
    fun offsetsUseOriginalUtf16CoordinatesIncludingEmojiAndWhitespace() {
        val text = "  开头😀结尾  \r\n\r\n第二段。"
        val chunks = ChapterChunker.chunkWithOffsets(text)

        assertEquals(1, chunks.size)
        assertEquals("开头😀结尾\n第二段。", chunks.single().text)
        assertEquals(text.indexOf("开头"), chunks.single().startCharOffset)
        assertEquals(text.indexOf("第二段。") + "第二段。".length, chunks.single().endCharOffset)
    }

    @Test
    fun hardSplitOffsetsRemainContiguousUtf16Ranges() {
        val text = "😀" + "长".repeat(1_400)
        val chunks = ChapterChunker.chunkWithOffsets(text)

        assertEquals(listOf(640, 640, 122), chunks.map { it.text.length })
        assertEquals(0, chunks.first().startCharOffset)
        assertEquals(text.length, chunks.last().endCharOffset)
        assertTrue(chunks.zipWithNext().all { (left, right) -> left.endCharOffset == right.startCharOffset })
        chunks.forEach { chunk ->
            assertEquals(chunk.text, text.substring(chunk.startCharOffset, chunk.endCharOffset))
        }
    }

}
