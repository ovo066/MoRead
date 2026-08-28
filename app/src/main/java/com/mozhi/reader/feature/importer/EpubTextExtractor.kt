package com.mozhi.reader.feature.importer

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor

data class EpubImageReference(
    val charOffset: Int,
    val href: String,
    val altText: String
)

data class ExtractedEpubText(
    val text: String,
    val images: List<EpubImageReference>
)

/**
 * 把 EPUB spine XHTML 归一化为自绘引擎正文。图片在正文中占一行「［图片］」，并另外返回
 * 资源 href 与该行起点的 UTF-16 偏移。保留可读 token 让 RAG/导出有语义，也兼容旧正文坐标。
 */
@Singleton
class EpubTextExtractor @Inject constructor() {

    fun extract(bytes: ByteArray, baseUri: String = ""): String =
        extractWithImages(bytes, baseUri).text

    fun extractWithImages(bytes: ByteArray, baseUri: String = ""): ExtractedEpubText {
        val document = Jsoup.parse(bytes.inputStream(), null, baseUri)
        document.select(DROPPED_SELECTOR).remove()
        val body = document.body()
        val blocks = ArrayList<Block>()
        val current = StringBuilder()

        fun flush() {
            val text = current.toString().collapseSpaces()
            if (text.isNotEmpty()) blocks += Block.Text(text)
            current.setLength(0)
        }

        body.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when {
                    node is TextNode -> current.append(node.wholeText)
                    node is Element && node.normalName() == "br" -> flush()
                    node is Element && node.normalName() in IMAGE_TAGS -> {
                        flush()
                        val source = node.attr("src")
                            .ifBlank { node.attr("data-src") }
                            .ifBlank { node.attr("data") }
                            .ifBlank { node.attr("href") }
                            .ifBlank { node.attr("xlink:href") }
                        val href = resolveResourceHref(baseUri, source)
                        if (href == null) {
                            blocks += Block.Text(IMAGE_PLACEHOLDER)
                        } else {
                            blocks += Block.Image(
                                href = href,
                                altText = node.attr("alt").collapseSpaces()
                            )
                        }
                    }
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.isTextBlock()) flush()
            }
        })
        flush()

        val output = StringBuilder()
        val images = ArrayList<EpubImageReference>()
        blocks.forEach { block ->
            if (output.isNotEmpty()) output.append('\n')
            when (block) {
                is Block.Text -> output.append(block.value)
                is Block.Image -> {
                    val offset = output.length
                    output.append(IMAGE_PLACEHOLDER)
                    images += EpubImageReference(offset, block.href, block.altText)
                }
            }
        }
        return ExtractedEpubText(output.toString(), images)
    }

    private fun resolveResourceHref(baseUri: String, source: String): String? {
        var value = source.trim()
        if (value.isEmpty()) return null
        if (value.startsWith("data:", ignoreCase = true)) return value
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return null
        value = value.substringBefore('#').substringBefore('?').replace('\\', '/')
        if (value.isEmpty()) return null

        val combined = if (value.startsWith('/')) {
            value.removePrefix("/")
        } else {
            val base = baseUri.substringBefore('#').substringBefore('?').replace('\\', '/')
            val directory = base.substringBeforeLast('/', "")
            if (directory.isEmpty()) value else "$directory/$value"
        }
        val normalized = ArrayDeque<String>()
        combined.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (normalized.isNotEmpty()) normalized.removeLast()
                else -> normalized.addLast(segment)
            }
        }
        if (normalized.isEmpty()) return null
        return runCatching {
            URLDecoder.decode(
                normalized.joinToString("/").replace("+", "%2B"),
                StandardCharsets.UTF_8.name()
            )
        }.getOrElse { normalized.joinToString("/") }
    }

    private fun Element.isTextBlock(): Boolean = normalName() in BLOCK_TAGS ||
        attr("style").replace(" ", "").contains("display:block", true)

    private fun String.collapseSpaces(): String {
        val builder = StringBuilder(length)
        var pendingSpace = false
        forEach { char ->
            if (char.isWhitespace()) {
                if (builder.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) {
                    builder.append(' ')
                    pendingSpace = false
                }
                builder.append(char)
            }
        }
        return builder.toString()
    }

    private sealed interface Block {
        data class Text(val value: String) : Block
        data class Image(val href: String, val altText: String) : Block
    }

    companion object {
        const val INLINE_IMAGE_CHAR: Char = '\uFFFC'
        const val IMAGE_PLACEHOLDER = "［图片］"
        private const val DROPPED_SELECTOR = "script, style, title, rt, rp, [style*=display:none]"
        private val IMAGE_TAGS = setOf("img", "image", "object")
        private val BLOCK_TAGS = setOf(
            "p", "div", "section", "article", "blockquote", "li", "tr", "td", "th",
            "h1", "h2", "h3", "h4", "h5", "h6", "pre", "figcaption", "dd", "dt"
        )
    }
}
