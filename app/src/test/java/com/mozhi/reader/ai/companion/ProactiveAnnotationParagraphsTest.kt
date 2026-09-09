package com.mozhi.reader.ai.companion

import org.junit.Assert.*
import org.junit.Test

class ProactiveAnnotationParagraphsTest {
    @Test fun splitKeepsOriginalOffsetsAndSkipsShortAndTitleLines() {
        val line = "这是正文。".repeat(12)
        val body = "第一章\n\n  $line  \r\n短段\n$line"
        val rows = ProactiveAnnotationParagraphs.split(body)
        assertEquals(2, rows.size)
        rows.forEach { assertEquals(line, body.substring(it.start, it.end)) }
        assertEquals(body.indexOf(line), rows.first().start)
        assertEquals(body.length, rows.last().end)
    }
    @Test fun commonChineseAndEnglishHeadingsAreNeverCandidates() {
        listOf("序章", "楔子", "Chapter 12", "CHAPTER IV").forEach { title ->
            assertTrue(ProactiveAnnotationParagraphs.isHeading(title + " " + "标题".repeat(30)))
        }
        assertFalse(ProactiveAnnotationParagraphs.isHeading("正文讲述了第十二章的故事。"))
    }
    @Test fun deterministicCandidatesAreBoundedAndOrdered() {
        val body = (0..40).joinToString("\n") { "$it" + "句子。".repeat(30) }
        val rows = ProactiveAnnotationParagraphs.candidates(body, Int.MAX_VALUE)
        assertEquals(10, rows.size)
        assertEquals(rows, ProactiveAnnotationParagraphs.candidates(body, Int.MAX_VALUE))
        assertEquals(rows.sortedBy { it.end }, rows)
    }
    @Test fun prefixEndsAtTargetAndNeverContainsSuffix() {
        val body = "前".repeat(40_000) + "\n" + "目标".repeat(30) + "\n禁止后文"
        val target = ProactiveAnnotationParagraphs.split(body)[1]
        val prefix = ProactiveAnnotationParagraphs.prefix(body, target)
        assertTrue(prefix.endsWith("目标".repeat(30)))
        assertFalse(prefix.contains("禁止后文"))
        assertEquals(ProactiveAnnotationParagraphs.MAX_PREFIX_CHARS, prefix.length)
    }
    @Test fun longTargetRemainsWholeAndSourceRevisionChangesWithBody() {
        val body = "字".repeat(40_000)
        assertEquals(body, ProactiveAnnotationParagraphs.prefix(body, ProactiveAnnotationParagraphs.split(body).single()))
        assertNotEquals(ProactiveAnnotationParagraphs.revision(body), ProactiveAnnotationParagraphs.revision(body + "新"))
    }
}
