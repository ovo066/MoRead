package com.mozhi.reader.core.importer.convert

import com.mozhi.reader.core.importer.SourceFingerprints
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/** 清洗阶段没有 URL 读取能力；仅允许源文件内嵌的图片成为包资源。 */
internal class BookHtmlSanitizer(
    private val resolveImage: ((String) -> ConvertedResource?)? = null,
    private val dropFontFaces: Boolean = false
) {
    val resources = linkedMapOf<String, ConvertedResource>()
    private val resolvedImages = mutableMapOf<String, String?>()

    fun clean(document: Document): String {
        document.select("script,iframe,object,embed,form,input,button,select,textarea,option,fieldset," +
            "label,datalist,optgroup,output,meter,progress,link,base,meta,audio,video,source,track,canvas,template,noscript").remove()
        val css = document.select("style").joinToString("\n") { cleanCss(it.data()) }
        document.select("style").remove()
        (listOfNotNull(document.selectFirst("html")) + document.body().getAllElements()).forEach { element ->
            val tag = element.normalName()
            if (tag !in HTML_TAGS) {
                // 内联外来命名空间不进入 XHTML；保留其说明文字。
                if (tag == "svg" || tag == "math") element.replaceWith(TextNode(element.text()))
                else element.unwrap()
                return@forEach
            }
            if (tag == "img") {
                val image = embeddedImage(element.attr("src"))
                if (image == null) {
                    element.replaceWith(TextNode(element.attr("alt")))
                    return@forEach
                }
                element.attr("src", image)
            }
            element.attributes().asList().forEach { attr ->
                element.attr(attr.key, xmlCharacters(attr.value))
                val key = attr.key.lowercase(Locale.ROOT)
                when {
                    key.startsWith("on") -> element.removeAttr(attr.key)
                    key == "style" -> element.attr("style", cleanCss(attr.value))
                    key == "href" && tag == "a" -> {
                        val href = attr.value.trim()
                        if (!SAFE_LINK.containsMatchIn(href) || href.any { it.isISOControl() }) element.removeAttr(attr.key)
                        else element.attr("href", href)
                    }
                    key == "src" && tag == "img" -> Unit
                    key !in HTML_ATTRIBUTES -> element.removeAttr(attr.key)
                }
            }
        }
        return css
    }

    private fun normalizeCss(source: String): String {
        // 先展开转义并移除注释，防止 u\72l / @im/**/port 绕过资源过滤。
        return xmlCharacters(CSS_ESCAPE.replace(CSS_COMMENT.replace(source, "")) { match ->
            val hex = match.groupValues[1]
            if (hex.isNotEmpty()) {
                val value = hex.toInt(16)
                if (Character.isValidCodePoint(value) && value != 0) String(Character.toChars(value)) else ""
            } else match.groupValues[2]
        })
    }

    fun cleanCss(source: String): String {
        val normalized = normalizeCss(source)
        val noFonts = if (dropFontFaces) withoutFontFaces(normalized) else normalized
        val noImports = CSS_IMPORT.replace(noFonts, "")
        return CSS_URL.replace(noImports) { match ->
            val value = match.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("'")
            val image = embeddedImage(value)
            when {
                image != null -> "url(\"$image\")"
                value.matches(Regex("#[A-Za-z0-9_-]+")) -> "url(\"$value\")"
                else -> "none"
            }
        }.replace(Regex("(?i)(?:expression|javascript)\\s*[:(][^;{}]*"), "")
    }

    private fun embeddedImage(uri: String): String? {
        if (resolveImage != null) {
            if (uri in resources) return uri
            if (resolvedImages.containsKey(uri)) return resolvedImages[uri]
            if (uri.startsWith("kindle:", true) || uri.startsWith("mobi:resource:")) {
                val image = resolveImage(uri)?.let { registerImage(it.bytes, it.mediaType) }
                resolvedImages[uri] = image
                return image
            }
        }
        if (!uri.startsWith("data:", true)) return null
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        val header = uri.substring(5, comma).lowercase(Locale.ROOT).split(';')
        val mediaType = when (header.first()) {
            "image/jpg" -> "image/jpeg"
            else -> header.first()
        }
        if (!mediaType.matches(Regex("image/[a-z0-9.+-]+"))) return null
        val payload = uri.substring(comma + 1)
        val bytes = if ("base64" in header) {
            val compact = payload.filterNot(Char::isWhitespace)
            if (compact.length.toLong() > (BookDocumentInput.MAX_IMAGE_BYTES + 2L) / 3 * 4) imageTooLarge()
            try { Base64.getDecoder().decode(compact) } catch (_: IllegalArgumentException) { return null }
        } else {
            val output = ByteArrayOutputStream()
            var i = 0
            while (i < payload.length) {
                val char = payload[i]
                if (char == '%') {
                    val value = payload.substring(i + 1, minOf(i + 3, payload.length)).takeIf { it.length == 2 }
                        ?.toIntOrNull(16) ?: return null
                    output.write(value)
                    i += 3
                } else {
                    val codePoint = payload.codePointAt(i)
                    output.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
                    i += Character.charCount(codePoint)
                }
                if (output.size() > BookDocumentInput.MAX_IMAGE_BYTES) imageTooLarge()
            }
            output.toByteArray()
        }
        return registerImage(bytes, mediaType)
    }

    internal fun registerImage(source: ByteArray, mediaType: String): String? {
        var bytes = source
        val extension = IMAGE_TYPES[mediaType] ?: "img"
        if (bytes.size > BookDocumentInput.MAX_IMAGE_BYTES) imageTooLarge()
        if (bytes.isEmpty()) return null
        if (mediaType == "image/svg+xml") bytes = cleanSvg(bytes) ?: return null
        val href = "images/${SourceFingerprints.of(bytes).sha256}.$extension"
        resources.putIfAbsent(href, ConvertedResource(href, mediaType, bytes))
        return href
    }

    private fun cleanSvg(bytes: ByteArray): ByteArray? {
        val doc = Jsoup.parse(xmlCharacters(bytes.toString(Charsets.UTF_8)), "", Parser.xmlParser())
        val svg = doc.selectFirst("svg") ?: return null
        svg.getAllElements().toList().forEach { element ->
            if (element.normalName() !in SVG_TAGS) { element.remove(); return@forEach }
            element.attributes().asList().forEach { attr ->
                val key = attr.key.lowercase(Locale.ROOT)
                if (!key.matches(Regex("[a-z_][a-z0-9_.:-]*")) || key.startsWith("on") || key == "src" ||
                    (key.endsWith("href") && !attr.value.matches(Regex("#[A-Za-z0-9_-]+"))) ||
                    (':' in key && key !in setOf("xmlns:xlink", "xlink:href", "xml:space"))) {
                    element.removeAttr(attr.key)
                } else if (key == "style" || attr.value.contains("url", true) || '\\' in attr.value || "/*" in attr.value) {
                    // SVG 不递归抽取图片，避免 data URI 递归；所有 URL 都关闭。
                    element.attr(attr.key, CSS_URL.replace(CSS_IMPORT.replace(normalizeCss(attr.value), ""), "none"))
                } else element.attr(attr.key, xmlCharacters(attr.value))
            }
        }
        val nonContent = mutableListOf<org.jsoup.nodes.Node>()
        svg.traverse(object : org.jsoup.select.NodeVisitor {
            override fun head(node: org.jsoup.nodes.Node, depth: Int) {
                if (node is TextNode) node.text(xmlCharacters(node.wholeText))
                else if (node !is org.jsoup.nodes.Element) nonContent += node
            }
            override fun tail(node: org.jsoup.nodes.Node, depth: Int) = Unit
        })
        nonContent.forEach { it.remove() }
        svg.attr("xmlns", "http://www.w3.org/2000/svg")
        svg.attr("xmlns:xlink", "http://www.w3.org/1999/xlink")
        xmlOutput(doc)
        return svg.outerHtml().toByteArray(Charsets.UTF_8)
    }

    private fun imageTooLarge(): Nothing = throw BookConversionException(BookConversionException.Reason.IMAGE_TOO_LARGE)

    private fun withoutFontFaces(css: String): String {
        val start = Regex("@font-face\\b", RegexOption.IGNORE_CASE)
        val output = StringBuilder()
        var cursor = 0
        while (cursor < css.length) {
            val match = start.find(css, cursor) ?: break
            output.append(css, cursor, match.range.first)
            var p = match.range.last + 1
            var depth = 0
            var quote: Char? = null
            while (p < css.length) {
                val char = css[p++]
                if (quote != null) {
                    if (char == '\\' && p < css.length) p++ else if (char == quote) quote = null
                } else when (char) {
                    '\'', '"' -> quote = char
                    '{' -> depth++
                    '}' -> { depth--; if (depth <= 0) break }
                    ';' -> if (depth == 0) break
                }
            }
            cursor = p
        }
        return output.append(css, cursor, css.length).toString()
    }

    companion object {
        private val SAFE_LINK = Regex("^(https?://|mailto:)", RegexOption.IGNORE_CASE)
        private val HTML_TAGS = ("html body div section article main header footer nav aside p span a img br hr " +
            "h1 h2 h3 h4 h5 h6 strong b em i u s del ins mark small sub sup blockquote pre code kbd samp var " +
            "ul ol li dl dt dd table caption colgroup col thead tbody tfoot tr th td figure figcaption " +
            "details summary address abbr cite q time ruby rt rp bdi bdo wbr").split(' ').toSet()
        private val HTML_ATTRIBUTES = setOf("id", "class", "style", "title", "lang", "dir", "alt", "width", "height",
            "colspan", "rowspan", "scope", "headers", "start", "reversed", "type", "value", "span")
        private val SVG_TAGS = setOf("svg", "g", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon",
            "text", "tspan", "title", "desc", "defs", "clippath", "mask", "lineargradient", "radialgradient", "stop", "use")
        private val IMAGE_TYPES = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif", "image/svg+xml" to "svg",
            "image/webp" to "webp", "image/avif" to "avif", "image/bmp" to "bmp", "image/tiff" to "tiff")
        private val CSS_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        private val CSS_ESCAPE = Regex("\\\\([0-9a-fA-F]{1,6})\\s?|\\\\([^\\r\\n])")
        private val CSS_IMPORT = Regex("@import\\b[^;{}]*(?:;|$)", RegexOption.IGNORE_CASE)
        private val CSS_URL = Regex("url\\s*\\(\\s*(\"[^\"]*\"|'[^']*'|[^)]*)\\s*\\)", RegexOption.IGNORE_CASE)
    }
}

internal fun xmlOutput(document: Document) {
    document.outputSettings().syntax(Document.OutputSettings.Syntax.xml)
        .escapeMode(Entities.EscapeMode.xhtml).charset(Charsets.UTF_8).prettyPrint(false)
}

/** XML 1.0 不接受 NUL、孤立代理项等字符；在 DOM 构造前去掉，实体由 jsoup 序列化。 */
internal fun xmlCharacters(text: String): String = buildString(text.length) {
    var index = 0
    while (index < text.length) {
        val cp = text.codePointAt(index)
        if (cp == 9 || cp == 10 || cp == 13 || cp in 0x20..0xd7ff || cp in 0xe000..0xfffd || cp in 0x10000..0x10ffff) {
            appendCodePoint(cp)
        }
        index += Character.charCount(cp)
    }
}

internal fun documentTitle(value: String, limit: Int = 80): String {
    val text = xmlCharacters(value).replace(Regex("[\\s\\u00a0]+"), " ").trim()
    return text.substring(0, text.offsetByCodePoints(0, minOf(limit, text.codePointCount(0, text.length))))
}
