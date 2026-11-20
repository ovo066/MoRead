package com.mozhi.reader.core.importer.convert

import java.io.File
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

class MarkdownBookConverter {
    fun convert(source: File, displayName: String = source.name): ConvertedBook = convert(BookDocumentInput.read(source), displayName)

    fun convert(bytes: ByteArray, displayName: String): ConvertedBook {
        val (metadata, source) = frontMatter(BookDocumentInput.decode(bytes, html = false))
        // 危险链接由共用清洗器处理；渲染阶段不能先丢掉合法的 data 图片。
        val flavour = GFMFlavourDescriptor(useSafeLinks = false)
        val alts = mutableListOf<String>()
        val markdown = prepareForRendering(source, flavour, alts)
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
        // 整篇先渲染再切章，引用式链接和代码围栏不因章节边界失去上下文。
        val html = HtmlGenerator(markdown, tree, flavour).generateHtml()
        val document = Jsoup.parse(xmlCharacters(html))
        // 渲染器只在 alt 里留下 ASCII 字母数字，中文图注会被清空；渲染前换成的占位符在这里换回原文。
        document.select("img[alt]").forEach { image ->
            ALT_TOKEN.matchEntire(image.attr("alt"))?.let { match ->
                image.attr("alt", alts.getOrElse(match.groupValues[1].toInt()) { "" })
            }
        }
        // GFM 的任务项不能作为可操作表单进入书包，保留完成状态为普通文字。
        document.select("li input[type=checkbox]").forEach {
            it.replaceWith(TextNode(if (it.hasAttr("checked")) "[x] " else "[ ] "))
        }
        // 解析器把 ~~删除线~~ 输出成带类名的 span；换成语义标签，阅读器才知道要画线。
        document.select("span.user-del").forEach { it.tagName("del").removeClass("user-del") }
        // Markdown 的元信息只来自 front matter 和一级标题，内嵌 HTML 的 head 不覆盖它。
        document.select("title").remove()
        return HtmlBookConverter().convertDocument(document, displayName,
            title = metadata["title"], author = metadata["author"].orEmpty(), language = metadata["language"] ?: "und")
    }

    /**
     * 渲染前对源文做两处改写，改写位置都取自同一次解析：
     * - 图片的替代文字换成纯 ASCII 占位符，原文记进 [alts]（渲染器会把非 ASCII 的 alt 清空）；
     * - 解析器不认识的脚注定义前加转义，免得被当成普通链接定义静默吞掉。
     */
    private fun prepareForRendering(source: String, flavour: GFMFlavourDescriptor, alts: MutableList<String>): String {
        val mayHaveImages = "![" in source
        val mayHaveFootnotes = FOOTNOTE_DEFINITION.containsMatchIn(source)
        if (!mayHaveImages && !mayHaveFootnotes) return source
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(source)
        val pending = java.util.ArrayDeque<ASTNode>().apply { add(tree) }
        // (起点, 删除长度, 插入文字)
        val edits = mutableListOf<Triple<Int, Int, String>>()
        val images = mutableListOf<Pair<Int, Int>>()
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            when (node.type) {
                MarkdownElementTypes.IMAGE -> altRange(source, node.startOffset, node.endOffset)?.let(images::add)
                MarkdownElementTypes.LINK_DEFINITION -> {
                    val text = source.substring(node.startOffset, node.endOffset)
                    if (Regex("^ {0,3}\\[\\^[^\\r\\n]+\\]:").find(text) != null) {
                        edits += Triple(node.startOffset + text.indexOf('['), 0, "\\")
                    }
                }
                else -> pending.addAll(node.children)
            }
        }
        // 占位符按图片在文中的先后编号，和渲染结果里的顺序无关——换回时按编号取。
        images.sortedBy { it.first }.forEach { (start, end) ->
            edits += Triple(start, end - start, "$ALT_PREFIX${alts.size}")
            alts += source.substring(start, end).replace(Regex("\\\\(.)"), "$1").replace(Regex("[*_`]"), "").trim()
        }
        if (edits.isEmpty()) return source
        return StringBuilder(source).apply {
            edits.sortedByDescending { it.first }.forEach { (start, length, text) -> replace(start, start + length, text) }
        }.toString()
    }

    /** `![替代文字](…)` 里替代文字的区间；方括号可以嵌套，反斜杠转义的不算。 */
    private fun altRange(source: String, start: Int, end: Int): Pair<Int, Int>? {
        if (end - start < 4 || source[start] != '!' || source[start + 1] != '[') return null
        var depth = 1
        var index = start + 2
        while (index < end) {
            when (source[index]) {
                '\\' -> index++
                '[' -> depth++
                ']' -> if (--depth == 0) return if (index > start + 2) start + 2 to index else null
            }
            index++
        }
        return null
    }

    /** 只读取顶层文本元信息，不执行 YAML 标签、对象构造或外部引用。 */
    private fun frontMatter(text: String): Pair<Map<String, String>, String> {
        val firstLineEnd = text.indexOf('\n')
        if (firstLineEnd < 0 || text.substring(0, firstLineEnd).trim() != "---") return emptyMap<String, String>() to text
        val closing = Regex("(?m)^ *---[ \\t]*(?:\\n|$)").find(text, firstLineEnd + 1)
            ?: return emptyMap<String, String>() to text
        // 只拆元信息区的行；大文件正文不再为读取三个字段复制成整本行列表。
        val lines = text.substring(firstLineEnd + 1, closing.range.first).lines()
        val metadata = mutableMapOf<String, String>()
        var index = 0
        while (index < lines.size) {
            val match = Regex("^(title|author|language):\\s*(.*)$").matchEntire(lines[index++]) ?: continue
            val key = match.groupValues[1]
            var value = match.groupValues[2].trim()
            if (value in setOf("|", ">", "|-", ">-")) {
                val parts = mutableListOf<String>()
                while (index < lines.size && (lines[index].startsWith(' ') || lines[index].isBlank())) parts += lines[index++].trim()
                value = parts.joinToString(" ")
            } else {
                value = scalar(value)
            }
            if (value.isNotBlank()) metadata[key] = xmlCharacters(value)
        }
        return metadata to text.substring(closing.range.last + 1)
    }

    private companion object {
        const val ALT_PREFIX = "mzalt"
        val ALT_TOKEN = Regex("mzalt(\\d+)")
        val FOOTNOTE_DEFINITION = Regex("(?m)^ {0,3}\\[\\^")
    }

    private fun scalar(source: String): String {
        val quote = source.firstOrNull()
        if (quote != '\'' && quote != '"') {
            return source.replace(Regex("\\s+#.*$"), "").trim().takeUnless { it == "null" || it == "~" }.orEmpty()
        }
        val result = StringBuilder()
        var index = 1
        while (index < source.length) {
            val char = source[index++]
            if (char == quote) {
                if (quote == '\'' && source.getOrNull(index) == '\'') { result.append('\''); index++; continue }
                return result.toString()
            }
            if (quote == '"' && char == '\\' && index < source.length) {
                val escaped = source[index++]
                result.append(when (escaped) { 'n', 'r', 't' -> ' '; else -> escaped })
            } else result.append(char)
        }
        return source
    }
}
