package com.mozhi.reader.core.importer.convert

import java.io.File
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor

class HtmlBookConverter {
    fun convert(source: File, displayName: String = source.name): ConvertedBook = convert(BookDocumentInput.read(source), displayName)

    fun convert(bytes: ByteArray, displayName: String): ConvertedBook =
        convertDocument(Jsoup.parse(xmlCharacters(BookDocumentInput.decode(bytes, html = true))), displayName)

    internal fun convertDocument(
        document: Document,
        displayName: String,
        title: String? = null,
        author: String? = null,
        language: String? = null,
        sanitizer: BookHtmlSanitizer = BookHtmlSanitizer()
    ): ConvertedBook {
        val htmlTitle = document.title()
        val htmlAuthor = document.selectFirst("meta[name=author]")?.attr("content").orEmpty()
        val root = document.selectFirst("html")
        val htmlLanguage = root?.attr("lang").orEmpty().ifBlank { root?.attr("xml:lang").orEmpty() }
        val css = sanitizer.clean(document)
        val body = document.body()
        val h1 = body.select("h1").filter { it.parents().none { parent -> parent.normalName() in NON_CHAPTER_CONTAINERS } }
        val bookTitle = documentTitle(title?.takeIf(String::isNotBlank)
            ?: htmlTitle.takeIf(String::isNotBlank)
            ?: h1.singleOrNull()?.text()?.takeIf(String::isNotBlank)
            ?: displayName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.'), 200)
            .ifBlank { "Untitled" }
        val boundaries = when {
            h1.size > 1 -> h1
            else -> body.select("h2").filter { it.parents().none { parent -> parent.normalName() in NON_CHAPTER_CONTAINERS } }
        }.toSet()
        // 唯一一级标题是书名，不额外制造一个只有书名的前言章节。
        h1.singleOrNull()?.remove()
        if (!hasReadableText(body)) throw BookConversionException(BookConversionException.Reason.EMPTY_DOCUMENT)
        val chapters = split(body, boundaries, bookTitle)
        val resources = sanitizer.resources.values.toMutableList()
        if (css.isNotBlank()) resources += ConvertedResource("document.css", "text/css", css.toByteArray(Charsets.UTF_8))
        return ConvertedBook(
            title = bookTitle,
            author = documentTitle(author ?: htmlAuthor, 120),
            language = (language ?: htmlLanguage).trim().replace('_', '-')
                .takeIf { it.matches(Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")) } ?: "und",
            chapters = chapters,
            resources = resources,
            htmlAttributes = document.selectFirst("html")?.attributes()?.asList()?.associate { it.key to it.value }.orEmpty(),
            bodyAttributes = body.attributes().asList().associate { it.key to it.value }
        )
    }

    private fun split(body: Element, boundaries: Set<Element>, bookTitle: String): List<ConvertedChapter> {
        val chapters = mutableListOf<ConvertedChapter>()
        var chapterTitle = bookTitle
        var output = Document.createShell("").also(::xmlOutput)
        // 保存源祖先链；切章时按需重新创建浅副本，跨章 div/section 不会成为悬空标签。
        val ancestors = mutableListOf<Element>()
        val copies = mutableListOf<Element>()
        fun container(): Element {
            while (copies.size < ancestors.size) {
                val original = ancestors[copies.size]
                val copy = Element(original.tag(), "", original.attributes().clone())
                (copies.lastOrNull() ?: output.body()).appendChild(copy)
                copies.add(copy)
            }
            return copies.lastOrNull() ?: output.body()
        }
        fun finish() {
            if (hasReadableText(output.body()) || output.body().select("img").isNotEmpty()) {
                chapters += ConvertedChapter(documentTitle(chapterTitle).ifBlank { documentTitle(bookTitle) }, output.body().html())
            }
        }
        body.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node === body) return
                if (node is Element) {
                    if (node in boundaries) {
                        finish()
                        output = Document.createShell("").also(::xmlOutput)
                        copies.clear()
                        chapterTitle = documentTitle(node.text()).ifBlank { bookTitle }
                    }
                    container()
                    ancestors.add(node)
                    container()
                } else if (node is TextNode) {
                    container().appendChild(TextNode(xmlCharacters(node.wholeText)))
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node !== body) {
                    ancestors.removeAt(ancestors.lastIndex)
                    if (copies.size > ancestors.size) copies.removeAt(copies.lastIndex)
                }
            }
        })
        finish()
        return chapters
    }

    private fun hasReadableText(body: Element): Boolean = body.text().any { !it.isWhitespace() && !it.isISOControl() }

    private companion object {
        val NON_CHAPTER_CONTAINERS = setOf("pre", "code", "blockquote", "li", "table")
    }
}
