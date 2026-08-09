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
