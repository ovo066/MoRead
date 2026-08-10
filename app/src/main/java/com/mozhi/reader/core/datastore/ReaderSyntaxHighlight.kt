package com.mozhi.reader.core.datastore

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
enum class ReaderSyntaxMatchMode {
    DELIMITED,
    REGEX
}

@Serializable
enum class ReaderSyntaxFont {
    INHERIT,
    SYSTEM,
    SERIF,
    SANS_SERIF,
    MONOSPACE,
    CUSTOM
}

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
    val enabled: Boolean = true,
    /** 正则模式匹配完整命中；成对符号模式继续使用 start/endDelimiter。 */
    val matchMode: ReaderSyntaxMatchMode = ReaderSyntaxMatchMode.DELIMITED,
    val pattern: String = "",
    val ignoreCase: Boolean = false,
    val backgroundArgb: Int? = null,
    val font: ReaderSyntaxFont = ReaderSyntaxFont.INHERIT,
    /** [ReaderFontAsset.id]；仅 [ReaderSyntaxFont.CUSTOM] 时使用。 */
    val fontAssetId: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false
)

data class ReaderSyntaxStyleSpan(
    val start: Int,
    val endExclusive: Int,
    val colorArgb: Int,
    val backgroundArgb: Int?,
    val underline: Boolean,
    val font: ReaderSyntaxFont,
    val fontAssetId: String?,
    val bold: Boolean,
    val italic: Boolean,
    val strikethrough: Boolean,
    val ruleId: Long
)

object ReaderSyntaxHighlighter {
    /** 同一位置有重叠规则时，列表靠前的规则优先。 */
    fun spans(text: String, rules: List<ReaderSyntaxRule>): List<ReaderSyntaxStyleSpan> {
        if (text.isEmpty()) return emptyList()
        val occupied = BooleanArray(text.length)
        val result = ArrayList<ReaderSyntaxStyleSpan>()
        rules.filter(ReaderSyntaxRule::enabled).forEach { rule ->
            when (rule.matchMode) {
                ReaderSyntaxMatchMode.DELIMITED -> {
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
                        addSpan(result, occupied, rule, styleStart, styleEnd)
                        from = matchEnd.coerceAtLeast(openAt + 1)
                    }
                }
                ReaderSyntaxMatchMode.REGEX -> {
                    val pattern = rule.pattern.takeIf { it.isNotBlank() && it.length <= MAX_REGEX_LENGTH }
                        ?: return@forEach
                    val options = buildSet {
                        add(RegexOption.MULTILINE)
                        if (rule.ignoreCase) add(RegexOption.IGNORE_CASE)
                    }
                    runCatching { Regex(pattern, options) }.getOrNull()
                        ?.findAll(text)
                        ?.forEach { match ->
                            addSpan(
                                result,
                                occupied,
                                rule,
                                match.range.first,
                                match.range.last + 1
                            )
                        }
                }
            }
        }
        return result.sortedBy(ReaderSyntaxStyleSpan::start)
    }

    private fun addSpan(
        result: MutableList<ReaderSyntaxStyleSpan>,
        occupied: BooleanArray,
        rule: ReaderSyntaxRule,
        start: Int,
        endExclusive: Int
    ) {
        val safeStart = start.coerceIn(0, occupied.size)
        val safeEnd = endExclusive.coerceIn(safeStart, occupied.size)
        if (safeStart >= safeEnd || (safeStart until safeEnd).any { occupied[it] }) return
        result += ReaderSyntaxStyleSpan(
            start = safeStart,
            endExclusive = safeEnd,
            colorArgb = rule.colorArgb,
            backgroundArgb = rule.backgroundArgb,
            underline = rule.underline,
            font = rule.font,
            fontAssetId = rule.fontAssetId,
            bold = rule.bold,
            italic = rule.italic,
            strikethrough = rule.strikethrough,
            ruleId = rule.id
        )
        for (index in safeStart until safeEnd) occupied[index] = true
    }

    val DEFAULT_RULES = listOf(
        ReaderSyntaxRule(1, "人物对白", "“", "”", 0xFFD06B42.toInt()),
        ReaderSyntaxRule(2, "直角引号", "「", "」", 0xFFB45F8A.toInt()),
        ReaderSyntaxRule(3, "书名与作品", "《", "》", 0xFF3D7FA6.toInt())
    )

    private const val MAX_REGEX_LENGTH = 256
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
