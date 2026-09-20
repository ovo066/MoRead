package com.mozhi.reader.core.dictionary

import org.junit.Assert.*
import org.junit.Test

class DictionaryWordGlossTest {
    private val definition = "原形：tug\n词性：动词，第三人称单数\n音标：/tʌɡz/\n释义：用力拉；拽"

    @Test fun inflectionAndPartOfSpeechAreNotTheInlineMeaning() {
        assertEquals(WordGloss("用力拉；拽", "/tʌɡz/"), briefWordGloss(definition))
        assertEquals("用力拉；拽", briefWordGloss(definition.replace('\n', ' ')).meaning)
    }

    @Test fun numberedMarkdownSensesWorkWithoutAnExplicitMeaningField() {
        assertEquals("拉扯；拽", briefWordGloss("## tugs\n\n**原形**：tug\n**词性**：动词\n1. 拉扯；拽\n2. 拖船").meaning)
        assertEquals("拉扯", briefWordGloss("## tugs\n### 原形\ntug\n### 词性\n动词\n### 释义\n拉扯").meaning)
    }

    @Test fun dedicatedShortMeaningTakesPriorityOverLongDictionaryExplanation() {
        assertEquals("轻轻拽", briefWordGloss("基本释义：用力拉；拽\n**简短释义**：轻轻拽\n### 语境义\n拉一下衣袖").meaning)
        assertEquals("拉一下衣袖", briefWordGloss("基本释义：用力拉；拽\n### 语境义\n拉一下衣袖").meaning)
    }

    @Test fun htmlLayoutAndIpaSurviveWhileLabelsDoNot() {
        assertEquals(WordGloss("拉扯", "/tʌɡz/"), briefWordGloss("<h2>tugs</h2><p>原形：tug</p><p>音标：/tʌɡz/</p><p>释义：<b>拉扯</b></p>"))
        assertEquals("", briefWordGloss("原形：tug\n词性：动词\n音标：/tʌɡz/").meaning)
        assertEquals("原形", briefWordGloss("原形").meaning)
    }

    @Test fun savedLegacyLabelsAreRepairedWithoutChangingManualGlossesOrLegitimateMeanings() {
        val words = listOf(
            VocabularyWord("tugs", definition, gloss = "原形："),
            VocabularyWord("tug", definition, gloss = "轻拽"),
            VocabularyWord("archetype", "原形", gloss = "原形"),
            VocabularyWord("tugged", definition, gloss = "词性：动词，第三人称单数")
        )
        val loaded = VocabularyCodec.decode(VocabularyCodec.encode(words))
        assertEquals(listOf("用力拉；拽", "轻拽", "原形", "用力拉；拽"), loaded.map { it.gloss })
        assertEquals("/tʌɡz/", loaded.first().phonetic)
        assertEquals(definition, loaded.first().definition)
    }
}
