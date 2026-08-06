package com.mozhi.reader.core.datastore

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 成对符号包裹内容的阅读美化规则；颜色使用 Android ARGB。 */
@Serializable
data class ReaderSyntaxRule(
    val id: Long,
    val name: String,
    val startDelimiter: String,
    val endDelimiter: String,
    val colorArgb: Int,
    val includeDelimiters: Boolean = true,
    val underline: Boolean = false,
    val enabled: Boolean = true
)

data class ReaderSyntaxStyleSpan(
    val start: Int,
    val endExclusive: Int,
    val colorArgb: Int,
    val underline: Boolean,
    val ruleId: Long
)

object ReaderSyntaxHighlighter {
    /** 同一位置有重叠规则时，列表靠前的规则优先。 */
    fun spans(text: String, rules: List<ReaderSyntaxRule>): List<ReaderSyntaxStyleSpan> {
        if (text.isEmpty()) return emptyList()
        val occupied = BooleanArray(text.length)
        val result = ArrayList<ReaderSyntaxStyleSpan>()
        rules.filter(ReaderSyntaxRule::enabled).forEach { rule ->
            val open = rule.startDelimiter
            val close = rule.endDelimiter
            if (open.isEmpty() || close.isEmpty()) return@forEach
            var from = 0
            while (from < text.length) {
                val openAt = text.indexOf(open, from)
                if (openAt < 0) break
                val contentStart = openAt + open.length
                val closeAt = text.indexOf(close, contentStart)
                if (closeAt < 0) break
                val matchEnd = closeAt + close.length
                val styleStart = if (rule.includeDelimiters) openAt else contentStart
                val styleEnd = if (rule.includeDelimiters) matchEnd else closeAt
                if (styleStart < styleEnd && (styleStart until styleEnd).none { occupied[it] }) {
                    result += ReaderSyntaxStyleSpan(
                        start = styleStart,
                        endExclusive = styleEnd,
                        colorArgb = rule.colorArgb,
                        underline = rule.underline,
                        ruleId = rule.id
                    )
                    for (index in styleStart until styleEnd) occupied[index] = true
                }
                from = matchEnd.coerceAtLeast(openAt + 1)
            }
        }
        return result.sortedBy(ReaderSyntaxStyleSpan::start)
    }

    val DEFAULT_RULES = listOf(
        ReaderSyntaxRule(1, "人物对白", "“", "”", 0xFFD06B42.toInt()),
        ReaderSyntaxRule(2, "直角引号", "「", "」", 0xFFB45F8A.toInt()),
        ReaderSyntaxRule(3, "书名与作品", "《", "》", 0xFF3D7FA6.toInt())
    )
}

object ReaderSyntaxRuleCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ReaderSyntaxRule.serializer())

    fun encode(rules: List<ReaderSyntaxRule>): String = json.encodeToString(serializer, rules)

    fun decode(raw: String?): List<ReaderSyntaxRule> {
        if (raw == null) return ReaderSyntaxHighlighter.DEFAULT_RULES
        return runCatching { json.decodeFromString(serializer, raw) }
            .getOrDefault(ReaderSyntaxHighlighter.DEFAULT_RULES)
    }
}
