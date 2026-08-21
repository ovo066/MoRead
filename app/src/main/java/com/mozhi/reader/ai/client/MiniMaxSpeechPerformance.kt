package com.mozhi.reader.ai.client

import java.util.Locale

internal data class MiniMaxSpeechPerformance(
    val speedMultiplier: Float = 1f,
    val volumeMultiplier: Float = 1f,
    val pitchOffset: Int = 0,
    val pauseSeconds: Float? = null
) {
    fun applyToText(text: String): String {
        val pause = pauseSeconds ?: return text
        if ("<#" in text) return text
        val marker = "<#${String.format(Locale.ROOT, "%.2f", pause)}#>"
        val lastContent = text.indexOfLast { !it.isWhitespace() }
        if (lastContent < 0) return text
        return if (text[lastContent] in TRAILING_QUOTES) {
            text.substring(0, lastContent) + marker + text.substring(lastContent)
        } else {
            text.substring(0, lastContent + 1) + marker + text.substring(lastContent + 1)
        }
    }

    private companion object {
        val TRAILING_QUOTES = setOf('”', '’', '」', '』', '"', '\'')
    }
}

internal object MiniMaxSpeechPerformanceMapper {
    fun map(emotion: String?, instruction: String?): MiniMaxSpeechPerformance {
        var speed = 1f
        var volume = 1f
        var pitch = 0

        when (emotion?.trim()) {
            "开心" -> { speed *= 1.04f; volume *= 1.03f; pitch += 1 }
            "悲伤" -> { speed *= 0.90f; volume *= 0.92f; pitch -= 1 }
            "愤怒" -> { speed *= 1.06f; volume *= 1.12f; pitch += 1 }
            "恐惧" -> { speed *= 0.96f; volume *= 0.94f; pitch += 1 }
            "厌恶" -> { speed *= 0.94f; volume *= 0.96f; pitch -= 1 }
            "惊讶" -> { speed *= 1.08f; volume *= 1.05f; pitch += 2 }
        }

        val cue = instruction.orEmpty()
        if (cue.containsAny("轻声", "低声", "耳语")) {
            speed *= 0.94f
            volume *= 0.82f
            pitch -= 1
        }
        if (cue.containsAny("高声", "大喊", "喊叫", "怒吼")) {
            volume *= 1.16f
            pitch += 1
        }
        if (cue.containsAny("急促", "快速", "语速加快")) speed *= 1.12f
        if (cue.containsAny("缓慢", "迟疑", "犹豫", "哽咽")) speed *= 0.90f
        if (cue.containsAny("颤抖", "发颤")) {
            speed *= 0.94f
            volume *= 0.94f
            pitch += 1
        }
        val pause = when {
            cue.containsAny("长停", "长时间停顿", "沉默片刻") -> 0.55f
            cue.containsAny("句末短停", "稍停", "短停", "停顿") -> 0.28f
            else -> null
        }

        return MiniMaxSpeechPerformance(
            speedMultiplier = speed.coerceIn(0.78f, 1.22f),
            volumeMultiplier = volume.coerceIn(0.72f, 1.28f),
            pitchOffset = pitch.coerceIn(-3, 3),
            pauseSeconds = pause
        )
    }
}

private fun String.containsAny(vararg values: String): Boolean = values.any(::contains)
