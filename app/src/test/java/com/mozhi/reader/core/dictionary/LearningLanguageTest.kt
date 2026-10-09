package com.mozhi.reader.core.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningLanguageTest {

    @Test fun `alphabetic scripts are tokenised as words with accents and apostrophes`() {
        val words = ForeignWords.pattern.findAll("Ça va? Über straße l'été Привет мир 안녕하세요 친구 don’t 中文字").map { it.value }.toList()
        assertEquals(listOf("Ça", "va", "Über", "straße", "l'été", "Привет", "мир", "안녕하세요", "친구", "don’t"), words)
    }

    @Test fun `normalisation folds case and curly apostrophes`() {
        assertEquals("don't", ForeignWords.normalize("Don’t"))
        assertEquals("über", ForeignWords.normalize("Über"))
        assertEquals("привет", ForeignWords.normalize("Привет"))
        assertEquals(EnglishWords.normalize("Tugs"), ForeignWords.normalize("tugs"))
    }

    @Test fun `lookup at an offset picks the whole word in any alphabetic script`() {
        val body = "Он сказал: Привет! Then she left."
        assertEquals("Привет", ForeignWords.at(body, body.indexOf("рив"), 0)?.word)
        assertEquals("Then", ForeignWords.at(body, body.indexOf("hen"), 0)?.word)
        assertNull(ForeignWords.at("中文段落", 1, 0))
    }

    @Test fun `bionic reading skips hangul syllables`() {
        assertTrue(ForeignWords.supportsBionic("Hello"))
        assertTrue(ForeignWords.supportsBionic("Привет"))
        assertFalse(ForeignWords.supportsBionic("안녕"))
    }

    @Test fun `scripts and common latin languages are detected`() {
        assertEquals(LearningLanguage.JA, ScriptDetector.detect("吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。".repeat(3)))
        assertEquals(LearningLanguage.KO, ScriptDetector.detect("나는 학교에 갑니다 오늘은 날씨가 좋습니다 ".repeat(4)))
        assertEquals(LearningLanguage.RU, ScriptDetector.detect("Все счастливые семьи похожи друг на друга ".repeat(3)))
        assertEquals(LearningLanguage.DE, ScriptDetector.detect("Der Junge läuft über die Straße und grüßt die Mädchen schön ".repeat(3)))
        assertEquals(LearningLanguage.FR, ScriptDetector.detect("Le garçon était très ému à côté de sa sœur près de la fenêtre ".repeat(3)))
        assertEquals(LearningLanguage.EN, ScriptDetector.detect("It was the best of times, it was the worst of times ".repeat(3)))
        assertEquals(LearningLanguage.AUTO, ScriptDetector.detect("这是一段中文正文，没有外语。".repeat(5)))
    }

    @Test fun `foreign paragraphs include kana and exclude pure chinese`() {
        val body = "这是中文。\n吾輩は猫である。\nHello there.\n纯汉字段落"
        assertEquals(listOf("吾輩は猫である。", "Hello there."), foreignParagraphs(body).map { it.text })
    }

    @Test fun `gloss matcher finds words by token and unspaced entries by substring`() {
        val matcher = WordGlossMatcher(setOf("hello", "猫", "吾輩", "名前はまだ"))
        val text = "Hello! 吾輩は猫である。名前はまだ無い。"
        val hits = matcher.find(text).map { text.substring(it.first.first, it.first.last + 1) }
        assertEquals(listOf("Hello", "吾輩", "猫", "名前はまだ"), hits)
        assertTrue(WordGlossMatcher(emptySet()).find(text).isEmpty())
    }

    @Test fun `longer entries win when they overlap`() {
        val hits = WordGlossMatcher(setOf("見当", "見当がつかぬ")).find("とんと見当がつかぬ。").map { it.second }
        assertEquals(listOf("見当がつかぬ"), hits)
    }

    @Test fun `translation prompt names source and target`() {
        val prompt = TranslationPrompts.system(LearningLanguage.FR, TranslationTarget.ZH_HANT)
        assertTrue(prompt.contains("法语") && prompt.contains("繁体中文"))
        assertTrue(TranslationPrompts.system(LearningLanguage.AUTO, TranslationTarget.EN).contains("English"))
    }

    @Test fun `legacy vocabulary is treated as english and old caches as simplified chinese`() {
        assertEquals("en", VocabularyWord("tugs").languageCode)
        assertEquals("zh-Hans", ParagraphTranslation(0, 1, "k", "译").targetCode)
    }
}
