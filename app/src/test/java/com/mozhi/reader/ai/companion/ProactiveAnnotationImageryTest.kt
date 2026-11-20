package com.mozhi.reader.ai.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveAnnotationImageryTest {
    private val scenery = "月光洒落在湖面，她站在桥上回头，白色长裙与发丝在微风里飞舞，远处灯火倒映在水面，夜色笼罩着山峰。"
    private val dialogue = "“你怎么现在才来？”他压低声音问。“路上耽搁了，”她说，“街道上到处都是人，我只好绕到小巷里走。”"
    private val reasoning = "他其实明白，如果现在就走，所以前面的安排都白费了；但他觉得自己应该再等等，毕竟谁也不知道明天会怎样。"
    private val panel = "姓名：林默　等级：12　力量：38　敏捷：41　体质：29　精神：77　剩余属性点：5　金币：10240"

    private fun score(text: String) = ProactiveAnnotationImagery.score(text)

    @Test fun sceneryAndPosesScoreAboveTheThreshold() {
        assertTrue(score(scenery) >= ProactiveAnnotationImagery.MIN_SCORE)
        assertTrue(score("大雪落在长街的屋檐上，他站在门口抬头仰望夜空，黑色斗篷的衣角被狂风掀起。") >= ProactiveAnnotationImagery.MIN_SCORE)
    }

    @Test fun dialogueReasoningAndStatPanelsAreNotPictures() {
        assertTrue(score(dialogue) < ProactiveAnnotationImagery.MIN_SCORE)
        assertTrue(score(reasoning) < ProactiveAnnotationImagery.MIN_SCORE)
        assertTrue(score(panel) < ProactiveAnnotationImagery.MIN_SCORE)
        assertEquals(0, score("月光、湖面、灯火。"))
    }

    @Test fun sceneryInsideSpeechCountsForLessThanTheSameWordsInNarration() {
        val spoken = "“$scenery”"
        assertTrue(score(spoken) < score(scenery))
    }

    @Test fun latinScriptSceneryIsRecognised() {
        val english = "Moonlight fell across the lake. She stood on the bridge and turned, her pale cloak " +
            "drifting in the mist while lantern light flickered on the river below."
        assertTrue(score(english) >= ProactiveAnnotationImagery.MIN_SCORE)
        assertTrue(score("He thought about what she had said and decided that it would be better to wait until morning.") <
            ProactiveAnnotationImagery.MIN_SCORE)
    }

    @Test fun targetsTakeTheStrongestSlotsUpToTheLimitAndPreferEarlierOnTies() {
        val body = listOf(dialogue, scenery, reasoning, scenery, dialogue).joinToString("\n")
        val slots = ProactiveAnnotationParagraphs.split(body).map { listOf(it) }
        val scenic = slots.map { it.single() }.filter { body.substring(it.start, it.end) == scenery }
        assertEquals(2, scenic.size)
        assertEquals(emptySet<Int>(), ProactiveAnnotationImagery.targets(body, slots, 0))
        assertEquals(setOf(scenic.first().end), ProactiveAnnotationImagery.targets(body, slots, 1))
        assertEquals(scenic.map { it.end }.toSet(), ProactiveAnnotationImagery.targets(body, slots, 5))
    }

    @Test fun eachSlotOffersItsMostVisualParagraphAndPlainChaptersGetNothing() {
        val body = "$dialogue\n$scenery"
        val paragraphs = ProactiveAnnotationParagraphs.split(body)
        // 一格里对白排在前面（正选），配图仍然落在同格更有画面的那一段。
        assertEquals(setOf(paragraphs.last().end), ProactiveAnnotationImagery.targets(body, listOf(paragraphs), 1))
        val plain = "$dialogue\n$reasoning"
        assertEquals(emptySet<Int>(),
            ProactiveAnnotationImagery.targets(plain, listOf(ProactiveAnnotationParagraphs.split(plain)), 3))
    }
}
