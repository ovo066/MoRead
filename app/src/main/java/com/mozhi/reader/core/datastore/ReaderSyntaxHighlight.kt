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
    val strikethrough: Boolean = false,
    val css: String = "",
    /** Transient reader overlays; never added to the user's syntax-rule editor. */
    val englishPrefixes: Boolean = false,
    val englishWords: Set<String> = emptySet(),
    @kotlinx.serialization.Transient val paint: ReaderStylePaint = ReaderStylePaint()
)

data class ReaderSyntaxStyleSpan(
    val start: Int,
    val endExclusive: Int,
    /** null 表示不改颜色：「符号本身也着色」关闭时的包裹符号只跟随字形，不跟随颜色。 */
    val colorArgb: Int?,
    val backgroundArgb: Int?,
    val underline: Boolean,
    val font: ReaderSyntaxFont,
    val fontAssetId: String?,
    val bold: Boolean,
    val italic: Boolean,
    val strikethrough: Boolean,
    val ruleId: Long,
    val paintSpan: ReaderPaintSpan? = null,
    /** 只承载字体/字重/斜体的包裹符号片段，不计入命中数。 */
    val delimiterGlyphsOnly: Boolean = false
)

object ReaderSyntaxHighlighter {
    /** 同一位置有重叠规则时，列表靠前的规则优先。 */
    fun spans(text: String, rules: List<ReaderSyntaxRule>): List<ReaderSyntaxStyleSpan> {
        if (text.isEmpty()) return emptyList()
        val occupied = BooleanArray(text.length)
        val result = ArrayList<ReaderSyntaxStyleSpan>()
        rules.filter { it.enabled && !it.englishPrefixes && it.englishWords.isEmpty() }.map { rule ->
            val css = ReaderStyleCss.parse(rule.css)
            rule.copy(paint = css.paint, colorArgb = css.color ?: rule.colorArgb, backgroundArgb = if (css.clipText) null else css.background ?: rule.backgroundArgb,
                font = css.font ?: rule.font, fontAssetId = css.fontAssetId ?: rule.fontAssetId,
                bold = css.bold ?: rule.bold, italic = css.italic ?: rule.italic,
                underline = css.underline ?: rule.underline, strikethrough = css.strike ?: rule.strikethrough)
        }.forEach { rule ->
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
                        if (!rule.includeDelimiters && rule.changesGlyphs()) {
                            // 符号不着色，但字体必须与被包裹的内容一致，否则引号用正文字体、
                            // 内容换了字体，交界处字形不搭。
                            addSpan(result, occupied, rule, openAt, contentStart, delimiterGlyphsOnly = true)
                            addSpan(result, occupied, rule, closeAt, matchEnd, delimiterGlyphsOnly = true)
                        }
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
        val english = rules.firstOrNull { it.enabled && (it.englishPrefixes || it.englishWords.isNotEmpty()) }
            ?: return result.sortedBy(ReaderSyntaxStyleSpan::start)
        val styles = arrayOfNulls<ReaderSyntaxStyleSpan>(text.length)
        result.forEach { span -> for (i in span.start until span.endExclusive) styles[i] = span.copy(start = 0, endExclusive = 0) }
        val base = ReaderSyntaxStyleSpan(0, 0, english.colorArgb, null, false, ReaderSyntaxFont.INHERIT, null, false, false, false, english.id)
        val cache = mutableMapOf<Triple<ReaderSyntaxStyleSpan, Boolean, Boolean>, ReaderSyntaxStyleSpan>()
        com.mozhi.reader.core.dictionary.EnglishWords.pattern.findAll(text).forEach { match ->
            val marked = com.mozhi.reader.core.dictionary.EnglishWords.normalize(match.value) in english.englishWords
            val prefixEnd = match.range.first + if (english.englishPrefixes) (match.value.length + 1) / 2 else 0
            for (i in match.range) {
                val bold = i < prefixEnd
                if (!marked && !bold) continue
                val previous = styles[i] ?: base
                styles[i] = cache.getOrPut(Triple(previous, bold, marked)) {
                    previous.copy(bold = previous.bold || bold, underline = previous.underline || marked,
                        backgroundArgb = if (marked) english.backgroundArgb else previous.backgroundArgb)
                }
            }
        }
        val merged = mutableListOf<ReaderSyntaxStyleSpan>()
        var start = 0
        while (start < styles.size) {
            val style = styles[start]
            var end = start + 1
            while (end < styles.size && styles[end] == style) end++
            if (style != null) merged += style.copy(start = start, endExclusive = end)
            start = end
        }
        return merged
    }

    private fun ReaderSyntaxRule.changesGlyphs(): Boolean =
        font != ReaderSyntaxFont.INHERIT || bold || italic

    private fun addSpan(
        result: MutableList<ReaderSyntaxStyleSpan>,
        occupied: BooleanArray,
        rule: ReaderSyntaxRule,
        start: Int,
        endExclusive: Int,
        delimiterGlyphsOnly: Boolean = false
    ) {
        val safeStart = start.coerceIn(0, occupied.size)
        val safeEnd = endExclusive.coerceIn(safeStart, occupied.size)
        if (safeStart >= safeEnd || (safeStart until safeEnd).any { occupied[it] }) return
        result += if (delimiterGlyphsOnly) ReaderSyntaxStyleSpan(
            start = safeStart,
            endExclusive = safeEnd,
            colorArgb = null,
            backgroundArgb = null,
            underline = false,
            font = rule.font,
            fontAssetId = rule.fontAssetId,
            bold = rule.bold,
            italic = rule.italic,
            strikethrough = false,
            ruleId = rule.id,
            delimiterGlyphsOnly = true
        ) else ReaderSyntaxStyleSpan(
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
            ruleId = rule.id,
            paintSpan = rule.paint.takeIf { it.textGradient != null || it.backgroundGradient != null || it.backgroundImageId != null }
                ?.let { ReaderPaintSpan(it, rule.id, safeStart, safeEnd) }
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
