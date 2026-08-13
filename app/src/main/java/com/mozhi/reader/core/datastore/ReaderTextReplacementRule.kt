package com.mozhi.reader.core.datastore

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 用户定义的正文清洗规则，按列表顺序应用到每一章。 */
@Serializable
data class ReaderTextReplacementRule(
    val id: Long,
    val name: String,
    val pattern: String,
    val replacement: String = "",
    val enabled: Boolean = true,
    val ignoreCase: Boolean = false
)

object ReaderTextReplacementRuleCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ReaderTextReplacementRule.serializer())

    fun encode(rules: List<ReaderTextReplacementRule>): String = json.encodeToString(serializer, rules)

    fun decode(raw: String?): List<ReaderTextReplacementRule> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }
}

fun ReaderTextReplacementRule.compileRegex(): Regex = Regex(
    pattern = pattern,
    options = buildSet {
        add(RegexOption.MULTILINE)
        if (ignoreCase) add(RegexOption.IGNORE_CASE)
    }
)

fun ReaderTextReplacementRule.validationError(): String? = when {
    name.isBlank() -> "规则名称不能为空"
    pattern.isBlank() -> "匹配表达式不能为空"
    pattern.length > MAX_TEXT_REPLACEMENT_PATTERN_LENGTH -> "匹配表达式不能超过 $MAX_TEXT_REPLACEMENT_PATTERN_LENGTH 个字符"
    else -> runCatching { compileRegex() }.exceptionOrNull()?.message ?: null
}

private const val MAX_TEXT_REPLACEMENT_PATTERN_LENGTH = 1_000
