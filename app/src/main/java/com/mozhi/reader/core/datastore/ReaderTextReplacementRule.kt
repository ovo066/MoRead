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
    val ignoreCase: Boolean = false,
    val forListenOnly: Boolean = false
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

data class ListenTextSlice(
    val startCharOffset: Int,
    val endCharOffset: Int,
    val text: String
)

/** 仅改变送进 TTS 的文本；原文 UTF-16 坐标始终保持不变。 */
fun purifyForListening(
    body: String,
    startCharOffset: Int,
    endCharOffset: Int,
    rules: List<ReaderTextReplacementRule>
): ListenTextSlice {
    val safeStart = startCharOffset.coerceIn(0, body.length)
    val safeEnd = endCharOffset.coerceIn(safeStart, body.length)
    val purified = rules
        .asSequence()
        .filter { it.enabled && it.forListenOnly }
        .fold(body.substring(safeStart, safeEnd)) { text, rule ->
            runCatching { rule.compileRegex().replace(text, rule.replacement) }.getOrDefault(text)
        }
        .trim()
    return ListenTextSlice(safeStart, safeEnd, purified)
}

/** 有声书剧本版本：正文或任一听书专用净化规则变化都会失效。 */
fun audiobookRevision(body: String, rules: List<ReaderTextReplacementRule>): Int {
    val ruleFingerprint = rules.asSequence()
        .filter { it.enabled && it.forListenOnly }
        .joinToString("\u0000") { rule ->
            listOf(rule.id, rule.pattern, rule.replacement, rule.ignoreCase).joinToString("\u0001")
        }
    return 31 * body.hashCode() + ruleFingerprint.hashCode()
}
