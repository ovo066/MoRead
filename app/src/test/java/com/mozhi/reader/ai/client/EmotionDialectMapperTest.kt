package com.mozhi.reader.ai.client

import org.junit.Assert.assertEquals
import org.junit.Test

class EmotionDialectMapperTest {
    @Test
    fun `七种情绪映射到 MiniMax 枚举`() {
        val expected = mapOf(
            "开心" to "happy",
            "悲伤" to "sad",
            "愤怒" to "angry",
            "恐惧" to "fearful",
            "厌恶" to "disgusted",
            "惊讶" to "surprised",
            "中性" to "neutral"
        )
        expected.forEach { (emotion, value) ->
            assertEquals(value, EmotionDialectMapper.map(emotion, null, TtsEmotionDialect.MINIMAX)?.value)
        }
    }

    @Test
    fun `未知情绪安全降级`() {
        assertEquals("neutral", EmotionDialectMapper.map("复杂", null, TtsEmotionDialect.MINIMAX)?.value)
        assertEquals("auto", EmotionDialectMapper.map("复杂", null, TtsEmotionDialect.GMI)?.value)
        assertEquals("auto", EmotionDialectMapper.map(null, null, TtsEmotionDialect.GMI)?.value)
        assertEquals("general", EmotionDialectMapper.map("复杂", null, TtsEmotionDialect.AZURE)?.value)
    }

    @Test
    fun `自由指令优先用于 OpenAI 与 MiMo`() {
        assertEquals(
            "贴近耳边低声说",
            EmotionDialectMapper.map("低语", "贴近耳边低声说", TtsEmotionDialect.OPENAI)?.value
        )
        assertEquals(
            "贴近耳边低声说",
            EmotionDialectMapper.map("低语", "贴近耳边低声说", TtsEmotionDialect.MIMO)?.value
        )
    }
}
