package com.mozhi.reader.core.dictionary

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AiDictionaryEntryTest {
    private fun output(gloss: String, definition: String) = buildJsonObject {
        put("gloss", gloss); put("phonetic", "/tʌɡz/"); put("definition", definition)
    }.toString()

    @Test fun annotationUsesItsDedicatedFieldAndThePopupReceivesOnlyMarkdown() {
        val markdown = "## tugs\n\n原形：tug\n\n1. 用力拉；拽。\n\n### 例句\nHe tugs her sleeve."
        val result = parseAiDictionaryEntry(output("轻拽", markdown))
        assertEquals(markdown, result.definition)
        assertEquals(WordGloss("轻拽", "/tʌɡz/"), result.annotation)
        assertEquals(result, parseAiDictionaryEntry("```json\n${output("轻拽", markdown)}\n```"))
    }

    @Test fun explicitEmptyOrInvalidAnnotationDoesNotGuessFromTheExplanation() {
        assertEquals("", parseAiDictionaryEntry(output("", "暂时无法确定词义。")).annotation.meaning)
        assertEquals("", parseAiDictionaryEntry(output("原形：tug", "原形：tug\n释义：拉拽")).annotation.meaning)
        assertEquals("", parseAiDictionaryEntry(output("pull", "拉拽")).annotation.meaning)
    }

    @Test fun legacyMarkdownStillRendersAndFindsARealMeaning() {
        val markdown = "原形：tug\n词性：动词\n音标：/tʌɡz/\n释义：拉拽"
        assertEquals(AiDictionaryEntry(markdown, WordGloss("拉拽", "/tʌɡz/")), parseAiDictionaryEntry(markdown))
    }
}
