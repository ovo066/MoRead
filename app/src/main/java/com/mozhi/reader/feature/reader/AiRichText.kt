package com.mozhi.reader.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * AI 气泡统一富文本：Markdown 原样渲染；检测到 HTML 时先做白名单语义转换再交给
 * Compose Markdown，绝不把模型输出交给可执行脚本的 WebView。
 */
@Composable
internal fun AiRichText(
    content: String,
    palette: ReaderPalette,
    modifier: Modifier = Modifier
) {
    val safeMarkdown = remember(content) { AiRichTextNormalizer.toMarkdown(content) }
    Markdown(
        content = safeMarkdown,
        colors = markdownColor(
            text = palette.onBackground,
            codeBackground = palette.onBackground.copy(alpha = 0.08f),
            dividerColor = palette.glassBorder,
            tableBackground = palette.onBackground.copy(alpha = 0.04f)
        ),
        typography = markdownTypography(
            h1 = MaterialTheme.typography.titleMedium,
            h2 = MaterialTheme.typography.titleMedium,
            h3 = MaterialTheme.typography.titleSmall,
            h4 = MaterialTheme.typography.titleSmall,
            h5 = MaterialTheme.typography.titleSmall,
            h6 = MaterialTheme.typography.titleSmall,
            text = MaterialTheme.typography.bodyMedium,
            paragraph = MaterialTheme.typography.bodyMedium,
            ordered = MaterialTheme.typography.bodyMedium,
            bullet = MaterialTheme.typography.bodyMedium,
            list = MaterialTheme.typography.bodyMedium,
            quote = MaterialTheme.typography.bodyMedium,
            textLink = TextLinkStyles(
                style = MaterialTheme.typography.bodyMedium
                    .copy(color = palette.accent, textDecoration = TextDecoration.Underline)
                    .toSpanStyle()
            )
        ),
        modifier = modifier
    )
}

/**
 * 流式气泡专用富文本：按块级边界分块渲染，稳定块整体跳过重组。
 * 含 HTML 时退回整段渲染（表格等跨段结构不能从中间切开）；
 * 块间距对齐 Markdown 渲染器的默认 block 间距，完成落库换整段渲染时无跳动。
 */
@Composable
internal fun StreamingAiRichText(
    content: String,
    palette: ReaderPalette,
    modifier: Modifier = Modifier
) {
    val chunks = remember(content) {
        if (AiRichTextNormalizer.containsHtml(content)) {
            listOf(content)
        } else {
            AiRichTextNormalizer.splitStreamingBlocks(content)
        }
    }
    if (chunks.size <= 1) {
        AiRichText(content = content, palette = palette, modifier = modifier)
    } else {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            chunks.forEachIndexed { index, chunk ->
                key(index) {
                    AiRichText(content = chunk, palette = palette)
                }
            }
        }
    }
}

/** HTML → 安全 Markdown 的纯函数，便于 JVM 回归测试。 */
internal object AiRichTextNormalizer {
    private val htmlTag = Regex("<[/]?[A-Za-z][^>]*>")
    private val excessiveBlankLines = Regex("\\n[ \\t]*\\n(?:[ \\t]*\\n)+")

    fun containsHtml(source: String): Boolean = htmlTag.containsMatchIn(source)

    /**
     * 把流式 Markdown 切成块级片段（空行边界、代码围栏内不切）。
     * 流式渲染按片段建立子组合：已完成的片段字符串不再变化，其解析与文本
     * 布局全部缓存，每个新 token 只重排最后一个片段——重排成本从 O(全文)
     * 降到 O(当前段落)，长回复也不会在滚动中掉帧。
     */
    fun splitStreamingBlocks(source: String): List<String> {
        if (source.isBlank()) return listOf(source)
        val blocks = mutableListOf<String>()
        val current = StringBuilder()
        var inFence = false
        fun commit() {
            if (current.isNotBlank()) blocks.add(current.toString())
            current.setLength(0)
        }
        source.lineSequence().forEach { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence
                if (current.isNotEmpty()) current.append('\n')
                current.append(line)
                return@forEach
            }
            if (!inFence && line.isBlank()) {
                commit()
            } else {
                if (current.isNotEmpty()) current.append('\n')
                current.append(line)
            }
        }
        commit()
        return blocks.ifEmpty { listOf(source) }
    }

    fun toMarkdown(source: String): String {
        if (!htmlTag.containsMatchIn(source)) return unwrapMath(source)
        val document = Jsoup.parseBodyFragment(source)
        document.select("script,style,iframe,object,embed,form,input,button,svg,canvas").remove()
        return unwrapMath(
            buildString {
                document.body().childNodes().forEach { appendNode(it, this) }
            }
                .replace("\u00A0", " ")
                .replace(excessiveBlankLines, "\n\n")
                .trim()
        )
    }

    /**
     * GFM 会把 `$…$` / `$$…$$` 解析成 INLINE_MATH / BLOCK_MATH 节点，渲染器 0.44 及更早
     * 直接丢弃整段（「第 $\omega$ 阶」变成「第 阶」）。0.45 起改为按原文回填，但屏幕上仍是
     * 裸 LaTeX，所以这里先把公式拆成可读文本：能映射的记号换成 Unicode，映射不到的只脱定界符。
     *
     * 围栏代码块与行内代码跨段保留原样（代码里的 `$` 不是公式）；不像 LaTeX 的一律不动，
     * 「$100 and $50」这类货币写法不会被改写。
     */
    fun unwrapMath(source: String): String {
        if ('$' !in source) return source
        val result = StringBuilder(source.length)
        val plain = StringBuilder()
        var inFence = false
        fun flush() {
            if (plain.isNotEmpty()) {
                result.append(rewriteOutsideCodeSpans(plain.toString()))
                plain.setLength(0)
            }
        }
        source.split("\n").forEachIndexed { index, line ->
            val prefix = if (index == 0) "" else "\n"
            val trimmed = line.trimStart()
            val fenceLine = trimmed.startsWith("```") || trimmed.startsWith("~~~")
            if (fenceLine || inFence) {
                flush()
                if (fenceLine) inFence = !inFence
                result.append(prefix).append(line)
            } else {
                plain.append(prefix).append(line)
            }
        }
        flush()
        return result.toString()
    }

    private val codeSpan = Regex("`+[^`]*`+")
    // 行内公式不跨行；块级 $$…$$ 允许跨行。
    private val mathSpan = Regex("\\$\\$([\\s\\S]+?)\\$\\$|\\$([^$\\n]+?)\\$")

    private fun rewriteOutsideCodeSpans(text: String): String {
        if ('$' !in text) return text
        val out = StringBuilder(text.length)
        var last = 0
        codeSpan.findAll(text).forEach { match ->
            out.append(rewriteMathSpans(text.substring(last, match.range.first)))
            out.append(match.value)
            last = match.range.last + 1
        }
        out.append(rewriteMathSpans(text.substring(last)))
        return out.toString()
    }

    private fun rewriteMathSpans(text: String): String {
        if ('$' !in text) return text
        return mathSpan.replace(text) { match ->
            val inner = match.groupValues[1].ifEmpty { match.groupValues[2] }
            // 只有带 LaTeX 记号的才是公式；纯数字/货币保持原样。
            if (inner.none { it == '\\' || it == '^' || it == '_' || it == '{' }) match.value
            else latexToPlainText(inner).ifBlank { match.value }
        }
    }

    private val latexSpacing = Regex("\\\\(?:quad|qquad|left|right|displaystyle|limits)\\b|\\\\[,;:!> ]")
    private val latexWrapper =
        Regex("\\\\(?:text|textrm|textbf|mathrm|mathbf|mathit|mathsf|mathcal|mathbb|operatorname)\\s*\\{([^\\{\\}]*)\\}")
    private val latexFraction = Regex("\\\\(?:d|t)?frac\\s*\\{([^\\{\\}]*)\\}\\s*\\{([^\\{\\}]*)\\}")
    private val latexRoot = Regex("\\\\sqrt\\s*\\{([^\\{\\}]*)\\}")
    private val latexEscape = Regex("\\\\([%&#\\\$_\\{\\}])")
    // 命令名后的空格是 LaTeX 终止符，这里保留成普通空格：「a \leq b」读作「a ≤ b」比
    // 严格的「a ≤b」自然，代价只是「3\times 4」会留成「3× 4」。
    private val latexCommand = Regex("\\\\([A-Za-z]+)")
    private val latexScript = Regex("([\\^_])(?:\\{([^\\{\\}]*)\\}|(\\S))")
    private val collapsibleSpaces = Regex("[ \\t]+")

    private const val SUPERSCRIPT_KEYS = "0123456789+-=()ni"
    private const val SUPERSCRIPT_VALUES = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱ"
    private const val SUBSCRIPT_KEYS = "0123456789+-=()aeijkmnoprstuvx"
    private const val SUBSCRIPT_VALUES = "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎ₐₑᵢⱼₖₘₙₒₚᵣₛₜᵤᵥₓ"

    private val latexSymbols: Map<String, String> = buildMap {
        val greekLower = listOf(
            "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
            "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ",
            "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
            "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ", "sigma" to "σ",
            "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "varphi" to "φ",
            "chi" to "χ", "psi" to "ψ", "omega" to "ω"
        )
        val greekUpper = listOf(
            "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
            "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω"
        )
        val operators = listOf(
            "infty" to "∞", "times" to "×", "div" to "÷", "cdot" to "·", "pm" to "±", "mp" to "∓",
            "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "ne" to "≠", "neq" to "≠",
            "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝", "to" to "→",
            "rightarrow" to "→", "Rightarrow" to "⇒", "leftarrow" to "←", "Leftarrow" to "⇐",
            "leftrightarrow" to "↔", "Leftrightarrow" to "⇔", "mapsto" to "↦", "sum" to "∑",
            "prod" to "∏", "int" to "∫", "oint" to "∮", "partial" to "∂", "nabla" to "∇",
            "in" to "∈", "notin" to "∉", "ni" to "∋", "subset" to "⊂", "subseteq" to "⊆",
            "supset" to "⊃", "supseteq" to "⊇", "cup" to "∪", "cap" to "∩", "setminus" to "∖",
            "forall" to "∀", "exists" to "∃", "nexists" to "∄", "neg" to "¬", "land" to "∧",
            "wedge" to "∧", "lor" to "∨", "vee" to "∨", "emptyset" to "∅", "varnothing" to "∅",
            "aleph" to "ℵ", "ldots" to "…", "dots" to "…", "cdots" to "⋯", "vdots" to "⋮",
            "prime" to "′", "circ" to "∘", "bullet" to "∙", "star" to "⋆", "dagger" to "†",
            "deg" to "°", "angle" to "∠", "perp" to "⊥", "parallel" to "∥", "therefore" to "∴",
            "because" to "∵", "sqrt" to "√", "hbar" to "ℏ", "ell" to "ℓ"
        )
        putAll(greekLower)
        putAll(greekUpper)
        putAll(operators)
    }

    /** LaTeX → 可读纯文本；映射不到的记号原样保留，绝不吞字。 */
    fun latexToPlainText(source: String): String {
        var text = source
        text = latexSpacing.replace(text, " ")
        text = latexFraction.replace(text) { "${it.groupValues[1]}/${it.groupValues[2]}" }
        text = latexRoot.replace(text) { "√${it.groupValues[1]}" }
        text = latexWrapper.replace(text) { it.groupValues[1] }
        text = latexCommand.replace(text) { match ->
            latexSymbols[match.groupValues[1]] ?: match.value
        }
        text = latexScript.replace(text) { match ->
            val marker = match.groupValues[1]
            val body = match.groupValues[2].ifEmpty { match.groupValues[3] }
            val keys = if (marker == "^") SUPERSCRIPT_KEYS else SUBSCRIPT_KEYS
            val values = if (marker == "^") SUPERSCRIPT_VALUES else SUBSCRIPT_VALUES
            if (body.isNotEmpty() && body.all { it in keys }) {
                body.map { values[keys.indexOf(it)] }.joinToString("")
            } else {
                marker + body
            }
        }
        text = latexEscape.replace(text) { it.groupValues[1] }
        text = text.filterNot { it == '{' || it == '}' }
        return collapsibleSpaces.replace(text, " ").trim()
    }

    private fun appendNode(node: Node, out: StringBuilder) {
        when (node) {
            is TextNode -> out.append(node.wholeText)
            !is Element -> node.childNodes().forEach { appendNode(it, out) }
            else -> when (node.normalName()) {
                "br" -> out.append('\n')
                "p", "div", "section", "article", "header", "footer", "main", "aside" -> {
                    appendChildren(node, out)
                    out.ensureBlockBreak()
                }
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    val level = node.normalName().last().digitToInt().coerceIn(1, 6)
                    out.ensureLineStart()
                    out.append("#".repeat(level)).append(' ')
                    appendChildren(node, out)
                    out.ensureBlockBreak()
                }
                "strong", "b" -> wrapped(out, "**") { appendChildren(node, out) }
                "em", "i" -> wrapped(out, "*") { appendChildren(node, out) }
                "s", "strike", "del" -> wrapped(out, "~~") { appendChildren(node, out) }
                "code" -> {
                    if (node.parent()?.normalName() == "pre") {
                        out.append(node.wholeText())
                    } else {
                        val text = node.text()
                        val fence = if ('`' in text) "``" else "`"
                        out.append(fence).append(text).append(fence)
                    }
                }
                "pre" -> {
                    out.ensureLineStart()
                    out.append("```\n").append(node.wholeText().trimEnd()).append("\n```")
                    out.ensureBlockBreak()
                }
                "blockquote" -> {
                    val quote = renderChildren(node).trim()
                    out.ensureLineStart()
                    quote.lineSequence().forEach { line -> out.append("> ").append(line).append('\n') }
                    out.ensureBlockBreak()
                }
                "ul" -> appendList(node, out, ordered = false)
                "ol" -> appendList(node, out, ordered = true)
                "li" -> appendChildren(node, out)
                "a" -> {
                    val label = renderChildren(node).trim().ifBlank { node.attr("href") }
                    val href = safeUrl(node.attr("href"))
                    if (href == null) out.append(label) else out.append('[').append(label).append("](")
                        .append(href).append(')')
                }
                "img" -> {
                    val src = safeUrl(node.attr("src"))
                    val alt = node.attr("alt").ifBlank { "图片" }.replace(']', '）')
                    if (src != null) out.append("![$alt]($src)") else out.append("［$alt］")
                }
                "hr" -> {
                    out.ensureLineStart()
                    out.append("---")
                    out.ensureBlockBreak()
                }
                "table" -> appendTable(node, out)
                "details" -> {
                    appendChildren(node, out)
                    out.ensureBlockBreak()
                }
                "summary" -> {
                    wrapped(out, "**") { appendChildren(node, out) }
                    out.append('\n')
                }
                else -> appendChildren(node, out)
            }
        }
    }

    private fun appendChildren(element: Element, out: StringBuilder) {
        element.childNodes().forEach { appendNode(it, out) }
    }

    private fun renderChildren(element: Element): String = buildString {
        appendChildren(element, this)
    }

    private fun appendList(element: Element, out: StringBuilder, ordered: Boolean) {
        out.ensureLineStart()
        val items = element.children().filter { it.normalName() == "li" }
        items.forEachIndexed { index, item ->
            val marker = if (ordered) "${index + 1}. " else "- "
            val text = renderChildren(item).trim().replace("\n", "\n  ")
            out.append(marker).append(text).append('\n')
        }
        out.ensureBlockBreak()
    }

    private fun appendTable(table: Element, out: StringBuilder) {
        val rows = table.select("tr").map { row ->
            row.children().filter { it.normalName() == "th" || it.normalName() == "td" }
                .map { it.text().replace('|', '｜').trim() }
        }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return
        val width = rows.maxOf(List<String>::size)
        fun row(values: List<String>): String =
            (values + List(width - values.size) { "" }).joinToString(" | ", "| ", " |")
        out.ensureLineStart()
        out.append(row(rows.first())).append('\n')
        out.append(row(List(width) { "---" })).append('\n')
        rows.drop(1).forEach { out.append(row(it)).append('\n') }
        out.ensureBlockBreak()
    }

    private inline fun wrapped(out: StringBuilder, marker: String, content: () -> Unit) {
        out.append(marker)
        content()
        out.append(marker)
    }

    private fun safeUrl(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        return value.takeIf {
            it.startsWith("https://", ignoreCase = true) ||
                it.startsWith("http://", ignoreCase = true) ||
                it.startsWith("mailto:", ignoreCase = true)
        }
    }

    private fun StringBuilder.ensureLineStart() {
        if (isNotEmpty() && last() != '\n') append('\n')
    }

    private fun StringBuilder.ensureBlockBreak() {
        when {
            isEmpty() -> Unit
            endsWith("\n\n") -> Unit
            last() == '\n' -> append('\n')
            else -> append("\n\n")
        }
    }
}
