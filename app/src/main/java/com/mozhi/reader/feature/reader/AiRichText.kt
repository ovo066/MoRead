package com.mozhi.reader.feature.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
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

/** HTML → 安全 Markdown 的纯函数，便于 JVM 回归测试。 */
internal object AiRichTextNormalizer {
    private val htmlTag = Regex("<[/]?[A-Za-z][^>]*>")
    private val excessiveBlankLines = Regex("\\n[ \\t]*\\n(?:[ \\t]*\\n)+")

    fun toMarkdown(source: String): String {
        if (!htmlTag.containsMatchIn(source)) return source
        val document = Jsoup.parseBodyFragment(source)
        document.select("script,style,iframe,object,embed,form,input,button,svg,canvas").remove()
        return buildString {
            document.body().childNodes().forEach { appendNode(it, this) }
        }
            .replace("\u00A0", " ")
            .replace(excessiveBlankLines, "\n\n")
            .trim()
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
