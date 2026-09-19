package com.mozhi.reader.core.datastore

import kotlinx.serialization.Serializable

@Serializable
enum class AnnotationContextMode(val label: String, val chars: Int) {
    ECONOMY("省流", 8_000), BALANCED("均衡", 16_000), FULL("充分", 32_000), CUSTOM("自定义", 0)
}

/** 阅读资料预算含本章正文、目标段落、前文片段和梗概；角色与写作提示词另计。 */
@Serializable
data class ProactiveAnnotationContextSettings(
    val mode: AnnotationContextMode = AnnotationContextMode.BALANCED,
    val customChars: Int = 24_000
) {
    val budgetChars: Int get() = if (mode == AnnotationContextMode.CUSTOM) {
        customChars.coerceIn(MIN_CHARS, MAX_CHARS)
    } else mode.chars

    fun normalized() = copy(customChars = customChars.coerceIn(MIN_CHARS, MAX_CHARS))

    companion object {
        const val MIN_CHARS = 4_000
        const val MAX_CHARS = 64_000
    }
}
