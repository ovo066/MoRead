package com.mozhi.reader.ai.client

enum class TtsEmotionDialect { MINIMAX, OPENAI, MIMO, AZURE }

data class EmotionDialectValue(
    val field: String,
    val value: String
)

object EmotionDialectMapper {
    private val miniMax = mapOf(
        "开心" to "happy",
        "悲伤" to "sad",
        "愤怒" to "angry",
        "恐惧" to "fearful",
        "厌恶" to "disgusted",
        "惊讶" to "surprised",
        "中性" to "neutral"
    )
    private val azure = mapOf(
        "开心" to "cheerful",
        "悲伤" to "sad",
        "愤怒" to "angry",
        "恐惧" to "fearful",
        "厌恶" to "disgruntled",
        "惊讶" to "excited",
        "中性" to "general"
    )

    fun map(
        emotion: String?,
        instruction: String?,
        dialect: TtsEmotionDialect
    ): EmotionDialectValue? {
        val normalized = emotion?.trim().orEmpty()
        val freeform = instruction?.trim().orEmpty()
        return when (dialect) {
            TtsEmotionDialect.MINIMAX -> EmotionDialectValue(
                "emotion",
                miniMax[normalized] ?: "neutral"
            )
            TtsEmotionDialect.OPENAI -> (freeform.ifBlank {
                normalized.takeIf(String::isNotBlank)?.let { "请用${it}的语气朗读。" }.orEmpty()
            }).takeIf(String::isNotBlank)?.let { EmotionDialectValue("instructions", it) }
            TtsEmotionDialect.MIMO -> (freeform.ifBlank { normalized })
                .takeIf(String::isNotBlank)?.let { EmotionDialectValue("voice_prompt", it) }
            TtsEmotionDialect.AZURE -> EmotionDialectValue(
                "style",
                azure[normalized] ?: "general"
            )
        }
    }
}
