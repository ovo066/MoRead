package com.mozhi.reader.feature.reader

import androidx.compose.runtime.staticCompositionLocalOf
import com.mozhi.reader.core.database.entity.MessageEntity
import java.util.Locale

internal val LocalShowCompanionTokenUsage = staticCompositionLocalOf { false }

internal fun companionTokenUsageLabel(message: MessageEntity): String {
    val input = message.inputTokens?.takeIf { it >= 0 }
    val output = message.outputTokens?.takeIf { it >= 0 }
    if (input == null && output == null) return "暂无用量"
    val duration = message.generationTimeMs?.takeIf { it > 0 }
    val speed = if (output != null && duration != null) String.format(Locale.ROOT, "%.1f", output * 1000.0 / duration) else "—"
    return "↑ ${input ?: "—"} · ↓ ${output ?: "—"} · $speed tok/s"
}
