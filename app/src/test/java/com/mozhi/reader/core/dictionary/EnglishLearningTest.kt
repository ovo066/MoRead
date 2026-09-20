package com.mozhi.reader.core.dictionary

import com.mozhi.reader.core.datastore.*
import org.junit.Assert.*
import org.junit.Test

class EnglishLearningTest {
    @Test fun pointLookupKeepsChapterOffsetsAndEnglishContractions() {
        val body = "他说：Don't re-enter the wonderful world."
        val hit = EnglishWords.at(body, body.indexOf("enter") + 2, 3)!!
        assertEquals("re-enter", hit.word)
        assertEquals(body.indexOf("re-enter"), hit.offset)
        assertEquals(3, hit.chapterIndex)
        assertEquals("Don't", EnglishWords.at(body, body.indexOf("Don't"), 3)!!.word)
        assertNull(EnglishWords.at(body, 0, 3))
        assertNull(EnglishWords.at(body, body.indexOf(' '), 3))
    }
    @Test fun bionicAndVocabularyMergeWithoutMovingTextOffsetsOrStylingChinese() {
        val text = "Hello book 中文 books"
        val rules = listOf(ReaderSyntaxRule(-1, "English", "", "", 0xff000000.toInt(),
            backgroundArgb = 0x220000ff, englishPrefixes = true, englishWords = setOf("book")))
        val spans = ReaderSyntaxHighlighter.spans(text, rules)
        assertTrue(spans.any { it.start == 0 && it.endExclusive == 3 && it.bold })
        assertTrue(spans.filter { it.start in 6..9 }.all { it.underline })
        assertTrue(spans.any { it.start == 6 && it.bold && it.underline })
        assertFalse(spans.any { it.start <= 11 && it.endExclusive > 11 })
        assertFalse(spans.filter { it.start >= 14 }.any { it.underline })
        assertEquals("Hello book 中文 books", text)
    }
}
