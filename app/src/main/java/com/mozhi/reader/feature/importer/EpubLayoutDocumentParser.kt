package com.mozhi.reader.feature.importer

import com.mozhi.reader.core.epub.dom.EpubDomChapter
import com.mozhi.reader.core.epub.dom.EpubDomNode
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

data class ParsedEpubLayoutChapter(
    val text: String,
    val images: List<EpubImageReference>,
    val document: EpubLayoutChapter,
    val dom: EpubDomChapter
)

/** Converts one XHTML spine item into a compact native-layout document anchored to `text.mz`. */
@Singleton
class EpubLayoutDocumentParser @Inject constructor() {
    private val stylesheetCache = object : LinkedHashMap<List<StylesheetSignature>, EpubLegacyStyleBridge>(
        STYLESHEET_CACHE_SIZE,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<List<StylesheetSignature>, EpubLegacyStyleBridge>
        ): Boolean = size > STYLESHEET_CACHE_SIZE
    }

    fun parse(
        bytes: ByteArray,
        chapterIndex: Int,
        href: String,
        expectedText: String,
        stylesheets: Map<String, String>
    ): EpubLayoutChapter {
        val parsed = parseWithText(bytes, chapterIndex, href, stylesheets)
        check(parsed.text == expectedText) {
            "EPUB 布局文本与 text.mz 坐标不一致：$href"
        }
        return parsed.document
    }

    /** Parses XHTML, semantic text, image anchors and native layout in one DOM traversal. */
    fun parseWithText(
        bytes: ByteArray,
        chapterIndex: Int,
        href: String,
        stylesheets: Map<String, String>
    ): ParsedEpubLayoutChapter {
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
        val css = stylesheetSet(stylesheetSources)
        val rubyText = IdentityHashMap<Element, String>()
        document.select("ruby").forEach { ruby ->
            rubyText[ruby] = ruby.select("rt").text().collapseSpaces()
        }
        document.select(DROPPED_SELECTOR).remove()

        val body = document.body()
        val output = StringBuilder(bytes.size.coerceAtMost(64 * 1024))
        val nodeAnchors = IdentityHashMap<Node, MutableAnchor>()
        val blocks = ArrayList<EpubLayoutBlock>()
        val images = ArrayList<EpubImageReference>()
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
            normalized.anchors.forEach { anchor ->
                val target = nodeAnchors.getOrPut(anchor.node) { MutableAnchor(start + anchor.start, start + anchor.end) }
                target.start = minOf(target.start, start + anchor.start)
                target.end = maxOf(target.end, start + anchor.end)
            }
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
            // <picture> is a formatting container, while its fallback <img> is the replaced
            // element. Prefer that <img> even when preceding <source> nodes appear first, so
            // selectors such as picture > img keep controlling size, margins and display.
            val pictureImage = element.takeIf { it.normalName() == "picture" }
                ?.selectFirst("img[src], img[data-src], img[srcset]")
            val pictureSource = element.takeIf { it.normalName() == "picture" && pictureImage == null }
                ?.selectFirst("source[srcset]")
            val sourceElement = pictureImage ?: pictureSource ?: element
            val source = sourceElement.attr("src")
                .ifBlank { sourceElement.attr("data-src") }
                .ifBlank { sourceElement.attr("data") }
                .ifBlank { sourceElement.attr("href") }
                .ifBlank { sourceElement.attr("xlink:href") }
                .ifBlank { sourceElement.attr("srcset").substringBefore(',').trim().substringBefore(' ') }
            val resourceHref = when {
                source.startsWith("data:", true) -> source
                else -> EpubResourcePath.normalize(source, href)
            }
            if (output.isNotEmpty()) output.append('\n')
            val start = output.length
            output.append(EpubTextExtractor.IMAGE_PLACEHOLDER)
            val imageAnchor = MutableAnchor(start, output.length)
            nodeAnchors[element] = imageAnchor
            // The wrapper keeps an aggregate anchor for legacy metadata, while the actual img
            // receives the same range for the V2 DOM/box tree. For malformed source-only picture
            // markup we retain import metadata, but only a real img participates in V2 layout.
            if (pictureImage != null) nodeAnchors[pictureImage] = imageAnchor
            val altText = sourceElement.attr("alt").ifBlank { element.attr("aria-label") }.collapseSpaces()
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
                altText = altText
            )
            if (resourceHref != null) images += EpubImageReference(start, resourceHref, altText)
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
                    val rawText = node.wholeText
                    // Pretty-printed XHTML puts indentation whitespace directly inside a div
                    // before its first <p>. Letting that whitespace claim pendingOwner made the
                    // paragraph inherit the div as its block node, duplicating the card's border,
                    // background and shadow around every paragraph (exactly the nested boxes seen
                    // in the real sample). Whitespace still participates once actual inline text
                    // has started, where it can be semantically significant.
                    if (pendingOwner != null || rawText.isNotBlank()) {
                        if (pendingOwner == null) {
                            pendingOwner = node.nearestBlock(body) { element ->
                                css.styleFor(element).blockDisplay
                            }
                        }
                        val parent = node.parent() as? Element
                        val ruby = parent?.parents()?.firstOrNull { it.normalName() == "ruby" }
                            ?: parent?.takeIf { it.normalName() == "ruby" }
                        val link = parent?.parents()?.firstOrNull { it.normalName() == "a" }
                            ?: parent?.takeIf { it.normalName() == "a" }
                        pendingPieces += StyledPiece(
                            text = rawText,
                            sourceNode = node,
                            style = parent?.let(css::styleFor) ?: css.styleFor(body),
                            elements = parent?.inlineElements(pendingOwner) ?: emptyList(),
                            linkHref = link?.attr("href")?.takeIf(String::isNotBlank),
                            rubyText = ruby?.let(rubyText::get)?.takeIf(String::isNotBlank)
                        )
                    }
                }
                is Element -> when {
                    node !== body && node.normalName() in IMAGE_TAGS -> appendImage(node)
                    node.normalName() == "br" -> flush()
                    node.normalName() == "hr" -> appendSeparator(node)
                    else -> {
                        val firstChildBlock = blocks.size
                        node.childNodes().forEach(visit)
                        if (node.normalName() in TEXT_BLOCK_TAGS || css.styleFor(node).blockDisplay) flush()
                        if (node !== body && node.normalName() in CONTAINER_TAGS) {
                            val children = blocks.subList(firstChildBlock, blocks.size)
                                .filter { it.kind != EpubLayoutBlockKind.CONTAINER }
                            val style = css.styleFor(node)
                            val elementRef = node.toElementRef()
                            // A div containing only inline/direct text is already represented by
                            // its text block. Adding a same-element container would apply width,
                            // padding, background and border twice. Nested block children still
                            // need the separate container to wrap the whole card.
                            val representedByOwnTextBlock = children.size == 1 &&
                                children.single().element == elementRef
                            if (children.isNotEmpty() && !representedByOwnTextBlock) {
                                blocks += EpubLayoutBlock(
                                    orderIndex = order++,
                                    kind = EpubLayoutBlockKind.CONTAINER,
                                    textStart = children.minOf(EpubLayoutBlock::textStart),
                                    textEnd = children.maxOf(EpubLayoutBlock::textEnd),
                                    element = elementRef,
                                    ancestors = node.layoutAncestors(),
                                    style = style
                                )
                            } else if (children.isEmpty() && style.hasRuleDecoration()) {
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

        val extractedText = output.toString()
        val semanticType = sequenceOf(
            body,
            document.getAllElements().firstOrNull { it.hasAttr("epub:type") }
        ).filterNotNull()
            .map { it.attr("epub:type").lowercase() }
            .joinToString(" ")
        val extensionFullscreen = document.getAllElements().any { element ->
            element.classNames().any { it.contains("duokan-page-fullscreen", true) } ||
                element.attributes().asList().any { attribute ->
                    attribute.key.contains("duokan-page-fullscreen", true)
                }
        }
        val visibleBlocks = blocks.filter { it.kind != EpubLayoutBlockKind.CONTAINER }
        val bodyStyle = css.styleFor(body)
        val singleArtworkPage = visibleBlocks.count { it.kind == EpubLayoutBlockKind.IMAGE } == 1 &&
            visibleBlocks.count { it.kind != EpubLayoutBlockKind.IMAGE } <= 1
        // 精排卷首页常把整页插画放在 body.background-image，而 XHTML 里只有卷号和标题。
        val publisherArtworkPage = bodyStyle.backgroundImageHref != null && visibleBlocks.size <= 3
        val immersivePage = semanticType.contains("cover") || semanticType.contains("titlepage") ||
            extensionFullscreen || singleArtworkPage || publisherArtworkPage
        val diagnostics = css.unsupportedProperties.sorted().map { property ->
            EpubLayoutDiagnostic(
                severity = EpubLayoutDiagnosticSeverity.INFO,
                code = "unsupported-css-property",
                message = property,
                href = href
            )
        }
        val chapterDiagnostics = diagnostics
        return ParsedEpubLayoutChapter(
            text = extractedText,
            images = images,
            document = EpubLayoutChapter(
                chapterIndex = chapterIndex,
                href = href,
                documentTitle = document.title().trim().takeIf(String::isNotEmpty),
                immersivePage = immersivePage,
                bodyStyle = bodyStyle,
                stylesheetHrefs = linkedStylesheets.distinct(),
                blocks = blocks.sortedWith(compareBy(EpubLayoutBlock::textStart, EpubLayoutBlock::orderIndex)),
                textLength = extractedText.length,
                diagnostics = chapterDiagnostics
            ),
            dom = EpubDomChapter(
                chapterIndex = chapterIndex,
                href = href,
                documentTitle = document.title().trim().takeIf(String::isNotEmpty),
                bodyNode = body.toDomNode(rubyText, nodeAnchors, 0, 0),
                textLength = extractedText.length,
                diagnostics = chapterDiagnostics
            )
        )
    }

    private fun stylesheetSet(sources: List<EpubStylesheetSource>): EpubLegacyStyleBridge {
        val signature = sources.map { source ->
            StylesheetSignature(source.href, source.css.length, source.css.hashCode())
        }
        val template = synchronized(stylesheetCache) {
            stylesheetCache[signature] ?: EpubLegacyStyleBridge.parse(sources).also { parsed ->
                stylesheetCache[signature] = parsed
            }
        }
        // Computed styles are keyed by DOM identity and must never retain a finished chapter.
        return template.newDocumentScope()
    }

    private fun normalizePieces(pieces: List<StyledPiece>): NormalizedText {
        if (pieces.isEmpty()) return NormalizedText("", emptyList())
        val text = StringBuilder()
        val spans = ArrayList<EpubLayoutSpan>()
        val anchors = IdentityHashMap<TextNode, MutableAnchor>()
        var pendingWhitespace: StyledPiece? = null

        fun append(value: Char, piece: StyledPiece) {
            val start = text.length
            text.append(value)
            val anchor = anchors.getOrPut(piece.sourceNode) { MutableAnchor(start, text.length) }
            anchor.start = minOf(anchor.start, start)
            anchor.end = text.length
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
        return NormalizedText(
            text.toString(),
            spans,
            anchors.entries.map { (node, anchor) -> NodeAnchor(node, anchor.start, anchor.end) }
        )
    }

    private fun Element.toDomNode(
        rubyText: IdentityHashMap<Element, String>,
        anchors: IdentityHashMap<Node, MutableAnchor>,
        childIndex: Int,
        childIndexOfType: Int
    ): EpubDomNode {
        val allowedAttributes = buildMap {
            DOM_ATTRIBUTE_WHITELIST.forEach { name ->
                attr(name).takeIf(String::isNotBlank)?.let { put(name, it) }
            }
            rubyText[this@toDomNode]?.takeIf(String::isNotBlank)?.let { put("data-ruby", it) }
        }
        val typeCounts = HashMap<String, Int>()
        var elementIndex = 0
        val domChildren = childNodes().mapNotNull { child ->
            when (child) {
                is TextNode -> anchors[child]?.let { anchor ->
                    EpubDomNode(tag = "#text", textStart = anchor.start, textEnd = anchor.end)
                }
                is Element -> {
                    val tag = child.normalName().lowercase()
                    val typeIndex = typeCounts.getOrDefault(tag, 0).also { typeCounts[tag] = it + 1 }
                    child.toDomNode(rubyText, anchors, elementIndex++, typeIndex)
                }
                else -> null
            }
        }
        val ownAnchor = anchors[this]
        return EpubDomNode(
            tag = normalName().lowercase(),
            id = id().takeIf(String::isNotEmpty),
            classes = classNames().sorted(),
            attributes = allowedAttributes,
            childIndex = childIndex,
            childIndexOfType = childIndexOfType,
            textStart = ownAnchor?.start ?: -1,
            textEnd = ownAnchor?.end ?: -1,
            children = domChildren
        )
    }

    private fun Node.nearestBlock(
        body: Element,
        isCssBlock: (Element) -> Boolean
    ): Element {
        var current = parent() as? Element
        while (current != null && current !== body) {
            if (current.normalName() in TEXT_BLOCK_TAGS || isCssBlock(current)) return current
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

    private data class StylesheetSignature(
        val href: String,
        val length: Int,
        val contentHash: Int
    )

    private data class StyledPiece(
        val text: String,
        val sourceNode: TextNode,
        val style: EpubComputedStyle,
        val elements: List<EpubElementRef>,
        val linkHref: String?,
        val rubyText: String?
    )

    private data class NormalizedText(
        val text: String,
        val spans: List<EpubLayoutSpan>,
        val anchors: List<NodeAnchor> = emptyList()
    )

    private data class NodeAnchor(val node: TextNode, val start: Int, val end: Int)
    private data class MutableAnchor(var start: Int, var end: Int)

    private companion object {
        const val DROPPED_SELECTOR = "script, style, title, rt, rp, [style*=display:none]"
        const val STYLESHEET_CACHE_SIZE = 12
        val IMAGE_TAGS = setOf("img", "image", "picture", "object")
        val TEXT_BLOCK_TAGS = setOf(
            "p", "div", "section", "article", "blockquote", "li", "tr", "td", "th",
            "h1", "h2", "h3", "h4", "h5", "h6", "pre", "figcaption", "dd", "dt"
        )
        val CONTAINER_TAGS = setOf("div", "section", "article", "aside", "blockquote", "figure")
        val DOM_ATTRIBUTE_WHITELIST = setOf(
            "style", "epub:type", "alt", "src", "href", "colspan", "rowspan", "width", "height",
            "xlink:href", "srcset", "rel", "aria-label"
        )
    }
}
