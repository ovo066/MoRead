package com.mozhi.reader.feature.importer

import com.mozhi.reader.core.library.EpubComputedStyle
import com.mozhi.reader.core.library.EpubElementRef
import com.mozhi.reader.core.library.EpubLayoutBlock
import com.mozhi.reader.core.library.EpubLayoutBlockKind
import com.mozhi.reader.core.library.EpubLayoutChapter
import com.mozhi.reader.core.library.EpubLayoutDiagnostic
import com.mozhi.reader.core.library.EpubLayoutDiagnosticSeverity
import com.mozhi.reader.core.library.EpubLayoutSpan
import com.mozhi.reader.core.library.EpubResourcePath
import java.util.IdentityHashMap
import javax.inject.Inject
import javax.inject.Singleton
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Converts one XHTML spine item into a compact native-layout document anchored to `text.mz`. */
@Singleton
class EpubLayoutDocumentParser @Inject constructor() {

    fun parse(
        bytes: ByteArray,
        chapterIndex: Int,
        href: String,
        expectedText: String,
        stylesheets: Map<String, String>
    ): EpubLayoutChapter {
        val document = Jsoup.parse(bytes.inputStream(), null, href)
        val linkedStylesheets = ArrayList<String>()
        val stylesheetSources = ArrayList<EpubStylesheetSource>()
        val stylesheetsByPath = stylesheets.entries.associate { it.key.lowercase() to it.value }
        document.head()?.children()?.forEach { element ->
            when (element.normalName()) {
                "link" -> if (element.attr("rel").contains("stylesheet", true)) {
                    val resolved = EpubResourcePath.normalize(element.attr("href"), href)
                    if (resolved != null) {
                        linkedStylesheets += resolved
                        stylesheetsByPath[resolved.lowercase()]?.let { css ->
                            stylesheetSources += EpubStylesheetSource(resolved, css)
                        }
                    }
                }
                "style" -> stylesheetSources += EpubStylesheetSource(href, element.data())
            }
        }
        val css = EpubCssStylesheetSet.parse(stylesheetSources)
        val rubyText = IdentityHashMap<Element, String>()
        document.select("ruby").forEach { ruby ->
            rubyText[ruby] = ruby.select("rt").text().collapseSpaces()
        }
        document.select(DROPPED_SELECTOR).remove()

        val body = document.body()
        val output = StringBuilder(expectedText.length)
        val blocks = ArrayList<EpubLayoutBlock>()
        val pendingPieces = ArrayList<StyledPiece>()
        var pendingOwner: Element? = null
        var order = 0

        fun appendBlockText(owner: Element) {
            val normalized = normalizePieces(pendingPieces)
            pendingPieces.clear()
            pendingOwner = null
            if (normalized.text.isEmpty()) return
            if (output.isNotEmpty()) output.append('\n')
            val start = output.length
            output.append(normalized.text)
            val end = output.length
            blocks += EpubLayoutBlock(
                orderIndex = order++,
                kind = owner.toBlockKind(),
                textStart = start,
                textEnd = end,
                element = owner.toElementRef(),
                ancestors = owner.layoutAncestors(),
                style = css.styleFor(owner),
                spans = normalized.spans.map { span ->
                    span.copy(textStart = start + span.textStart, textEnd = start + span.textEnd)
                }
            )
        }

        fun flush() {
            val owner = pendingOwner ?: return pendingPieces.clear()
            appendBlockText(owner)
        }

        fun appendImage(element: Element) {
            flush()
            val source = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("href") }
                .ifBlank { element.attr("xlink:href") }
            val resourceHref = when {
                source.startsWith("data:", true) -> source
                else -> EpubResourcePath.normalize(source, href)
            }
            if (output.isNotEmpty()) output.append('\n')
            val start = output.length
            output.append(EpubTextExtractor.IMAGE_PLACEHOLDER)
            blocks += EpubLayoutBlock(
                orderIndex = order++,
                kind = if (element.hasClass("separator-img")) {
                    EpubLayoutBlockKind.SEPARATOR
                } else {
                    EpubLayoutBlockKind.IMAGE
                },
                textStart = start,
                textEnd = output.length,
                element = element.toElementRef(),
                ancestors = element.layoutAncestors(),
                style = css.styleFor(element),
                resourceHref = resourceHref,
                altText = element.attr("alt").collapseSpaces()
            )
        }

        fun appendSeparator(element: Element) {
            flush()
            blocks += EpubLayoutBlock(
                orderIndex = order++,
                kind = EpubLayoutBlockKind.SEPARATOR,
                textStart = output.length,
                textEnd = output.length,
                element = element.toElementRef(),
                ancestors = element.layoutAncestors(),
                style = css.styleFor(element)
            )
        }

        lateinit var visit: (Node) -> Unit
        visit = { node ->
            when (node) {
                is TextNode -> {
                    if (pendingOwner == null) pendingOwner = node.nearestBlock(body)
                    val parent = node.parent() as? Element
                    val ruby = parent?.parents()?.firstOrNull { it.normalName() == "ruby" }
                        ?: parent?.takeIf { it.normalName() == "ruby" }
                    val link = parent?.parents()?.firstOrNull { it.normalName() == "a" }
                        ?: parent?.takeIf { it.normalName() == "a" }
                    pendingPieces += StyledPiece(
                        text = node.wholeText,
                        style = parent?.let(css::styleFor) ?: css.styleFor(body),
                        elements = parent?.inlineElements(pendingOwner) ?: emptyList(),
                        linkHref = link?.attr("href")?.takeIf(String::isNotBlank),
                        rubyText = ruby?.let(rubyText::get)?.takeIf(String::isNotBlank)
                    )
                }
                is Element -> when {
                    node !== body && node.normalName() in IMAGE_TAGS -> appendImage(node)
                    node.normalName() == "br" -> flush()
                    node.normalName() == "hr" -> appendSeparator(node)
                    else -> {
                        val firstChildBlock = blocks.size
                        node.childNodes().forEach(visit)
                        if (node.normalName() in TEXT_BLOCK_TAGS) flush()
                        if (node !== body && node.normalName() in CONTAINER_TAGS) {
                            val children = blocks.subList(firstChildBlock, blocks.size)
                                .filter { it.kind != EpubLayoutBlockKind.CONTAINER }
                            val style = css.styleFor(node)
                            if (children.isNotEmpty()) {
                                blocks += EpubLayoutBlock(
                                    orderIndex = order++,
                                    kind = EpubLayoutBlockKind.CONTAINER,
                                    textStart = children.minOf(EpubLayoutBlock::textStart),
                                    textEnd = children.maxOf(EpubLayoutBlock::textEnd),
                                    element = node.toElementRef(),
                                    ancestors = node.layoutAncestors(),
                                    style = style
                                )
                            } else if (style.hasRuleDecoration()) {
                                appendSeparator(node)
                            }
                        }
                    }
                }
                else -> node.childNodes().forEach(visit)
            }
        }
        visit(body)
        flush()

        check(output.toString() == expectedText) {
            "EPUB 布局文本与 text.mz 坐标不一致：$href"
        }
        val diagnostics = css.unsupportedProperties.sorted().map { property ->
            EpubLayoutDiagnostic(
                severity = EpubLayoutDiagnosticSeverity.INFO,
                code = "unsupported-css-property",
                message = property,
                href = href
            )
        }
        return EpubLayoutChapter(
            chapterIndex = chapterIndex,
            href = href,
            documentTitle = document.title().trim().takeIf(String::isNotEmpty),
            bodyStyle = css.styleFor(body),
            stylesheetHrefs = linkedStylesheets.distinct(),
            blocks = blocks.sortedWith(compareBy(EpubLayoutBlock::textStart, EpubLayoutBlock::orderIndex)),
            textLength = expectedText.length,
            diagnostics = diagnostics
        )
    }

    private fun normalizePieces(pieces: List<StyledPiece>): NormalizedText {
        if (pieces.isEmpty()) return NormalizedText("", emptyList())
        val text = StringBuilder()
        val spans = ArrayList<EpubLayoutSpan>()
        var pendingWhitespace: StyledPiece? = null

        fun append(value: Char, piece: StyledPiece) {
            val start = text.length
            text.append(value)
            val previous = spans.lastOrNull()
            if (previous != null && previous.textEnd == start && previous.style == piece.style &&
                previous.elements == piece.elements && previous.linkHref == piece.linkHref &&
                previous.rubyText == piece.rubyText
            ) {
                spans[spans.lastIndex] = previous.copy(textEnd = text.length)
            } else {
                spans += EpubLayoutSpan(
                    textStart = start,
                    textEnd = text.length,
                    elements = piece.elements,
                    style = piece.style,
                    linkHref = piece.linkHref,
                    rubyText = piece.rubyText
                )
            }
        }

        pieces.forEach { piece ->
            piece.text.forEach { char ->
                if (char.isWhitespace()) {
                    if (text.isNotEmpty() && pendingWhitespace == null) pendingWhitespace = piece
                } else {
                    pendingWhitespace?.let { append(' ', it) }
                    pendingWhitespace = null
                    append(char, piece)
                }
            }
        }
        return NormalizedText(text.toString(), spans)
    }

    private fun Node.nearestBlock(body: Element): Element {
        var current = parent() as? Element
        while (current != null && current !== body) {
            if (current.normalName() in TEXT_BLOCK_TAGS) return current
            current = current.parent()
        }
        return body
    }

    private fun Element.toBlockKind(): EpubLayoutBlockKind = when (normalName()) {
        "h1", "h2", "h3", "h4", "h5", "h6" -> EpubLayoutBlockKind.HEADING
        "blockquote" -> EpubLayoutBlockKind.QUOTE
        "li" -> EpubLayoutBlockKind.LIST_ITEM
        else -> EpubLayoutBlockKind.PARAGRAPH
    }

    private fun Element.toElementRef() = EpubElementRef(
        tag = normalName(),
        id = id().takeIf(String::isNotEmpty),
        classes = classNames().sorted(),
        inlineStyle = attr("style").takeIf(String::isNotBlank)
    )

    private fun Element.layoutAncestors(): List<EpubElementRef> = parents()
        .asReversed()
        .filter { it.normalName() != "html" }
        .map { it.toElementRef() }

    private fun Element.inlineElements(block: Element?): List<EpubElementRef> {
        val result = ArrayList<EpubElementRef>()
        var current: Element? = this
        while (current != null && current !== block) {
            result += current.toElementRef()
            current = current.parent()
        }
        return result.asReversed()
    }

    private fun EpubComputedStyle.hasRuleDecoration(): Boolean =
        backgroundColorArgb != null || backgroundImageHref != null ||
            boxShadows.isNotEmpty() || borderWidthEm != null || borderTopWidthEm != null ||
            borderRightWidthEm != null || borderBottomWidthEm != null || borderLeftWidthEm != null ||
            widthEm != null || widthFraction != null ||
            heightEm != null || heightViewportFraction != null

    private fun String.collapseSpaces(): String {
        val builder = StringBuilder(length)
        var pendingSpace = false
        for (char in this) {
            if (char.isWhitespace()) {
                if (builder.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) builder.append(' ')
                pendingSpace = false
                builder.append(char)
            }
        }
        return builder.toString()
    }

    private data class StyledPiece(
        val text: String,
        val style: EpubComputedStyle,
        val elements: List<EpubElementRef>,
        val linkHref: String?,
        val rubyText: String?
    )

    private data class NormalizedText(
        val text: String,
        val spans: List<EpubLayoutSpan>
    )

    private companion object {
        const val DROPPED_SELECTOR = "script, style, title, rt, rp, [style*=display:none]"
        val IMAGE_TAGS = setOf("img", "image")
        val TEXT_BLOCK_TAGS = setOf(
            "p", "div", "section", "article", "blockquote", "li", "tr", "td", "th",
            "h1", "h2", "h3", "h4", "h5", "h6", "pre", "figcaption", "dd", "dt"
        )
        val CONTAINER_TAGS = setOf("div", "section", "article", "aside", "blockquote", "figure")
    }
}
