package com.mozhi.reader.feature.reader.engine.epub

import com.mozhi.reader.core.epub.dom.EpubDomNode
import com.mozhi.reader.core.epub.style.EpubDisplay
import com.mozhi.reader.core.epub.style.EpubFloatValue
import com.mozhi.reader.core.epub.style.EpubStyle
import com.mozhi.reader.core.epub.style.ResolvedLength
import com.mozhi.reader.core.epub.style.StyledDomNode

/**
 * Box tree for the v2 EPUB layout engine: the styled DOM is normalised into block-level boxes,
 * tables and inline flows (anonymous blocks). Every text-bearing leaf keeps its text.mz anchor so
 * the produced lines stay on the shared character coordinate system.
 */
internal sealed interface EpubBox

internal class EpubBlockBox(
    val node: StyledDomNode?,
    val style: EpubStyle,
    val children: List<EpubBox>,
    val tag: String
) : EpubBox

/** A replaced image that participates in block or float layout instead of a text line. */
internal class EpubImageBox(
    val node: StyledDomNode,
    val style: EpubStyle,
    val textStart: Int,
    val textEnd: Int,
    val altText: String,
    val linkHref: String? = null,
    val attrWidth: String? = null,
    val attrHeight: String? = null
) : EpubBox

internal class EpubInlineFlowBox(
    /** Style context of the containing block: alignment, indent, line height. */
    val blockStyle: EpubStyle,
    val items: List<EpubInlineItem>,
    val isHeading: Boolean,
    /** Tag of the element owning this flow; anonymous paragraphs live in div/body/section. */
    val ownerTag: String
) : EpubBox

internal class EpubTableBox(
    val node: StyledDomNode,
    val style: EpubStyle,
    val rows: List<EpubTableRow>
) : EpubBox

internal class EpubTableRow(val style: EpubStyle, val cells: List<EpubTableCell>)

internal class EpubTableCell(val node: StyledDomNode, val style: EpubStyle, val children: List<EpubBox>)

internal sealed interface EpubInlineItem

internal class InlineTextItem(
    val textStart: Int,
    val textEnd: Int,
    val style: EpubStyle,
    /** Innermost decorated inline ancestor, keyed by identity for fragment merging. */
    val decoratedBox: StyledDomNode?,
    val linkHref: String?,
    val rubyGroup: Int?,
    val rubyText: String?
) : EpubInlineItem

internal class InlineImageItem(
    val style: EpubStyle,
    val textStart: Int,
    val textEnd: Int,
    val altText: String,
    val linkHref: String? = null,
    /** HTML width/height attributes, the pre-CSS sizing路径 many converters still use. */
    val attrWidth: String? = null,
    val attrHeight: String? = null
) : EpubInlineItem

internal data object InlineBreakItem : EpubInlineItem

/** A float encountered in content; the block layer places it and following text wraps around. */
internal class InlineFloatItem(val box: EpubBox) : EpubInlineItem

internal object EpubBoxTreeBuilder {

    fun build(body: StyledDomNode): EpubBlockBox =
        EpubBlockBox(body, body.style, partitionChildren(body), body.node.tag)

    /**
     * Single DOM-order walk over an element's children: consecutive inline-level content is
     * grouped into anonymous [EpubInlineFlowBox]es, block-level children stand alone, floats stay
     * inside the inline flow so text keeps wrapping around them.
     */
    private fun partitionChildren(element: StyledDomNode): List<EpubBox> {
        val builder = FlowBuilder(element)
        walkChildren(element, builder, InlineContext(element.style, null, null, null, null))
        builder.flushInline()
        return builder.boxes
    }

    private class InlineContext(
        val style: EpubStyle,
        val decorated: StyledDomNode?,
        val link: String?,
        val rubyGroup: Int?,
        val rubyText: String?
    )

    private class FlowBuilder(val element: StyledDomNode) {
        val boxes = ArrayList<EpubBox>()
        val inlineItems = ArrayList<EpubInlineItem>()
        var rubyCounter = 0

        fun flushInline() {
            if (inlineItems.none { it !is InlineBreakItem }) {
                inlineItems.clear()
                return
            }
            boxes += EpubInlineFlowBox(
                blockStyle = element.style,
                items = ArrayList(inlineItems),
                isHeading = element.node.tag in HEADING_TAGS,
                ownerTag = element.node.tag
            )
            inlineItems.clear()
        }
    }

    /** Walks the raw DOM children (text and elements interleaved) of [parent] in order. */
    private fun walkChildren(parent: StyledDomNode, builder: FlowBuilder, context: InlineContext) {
        var elementCursor = 0
        parent.node.children.forEach { rawChild ->
            if (rawChild.tag == "#text") {
                rawChild.anchorRange()?.let { range ->
                    builder.inlineItems += InlineTextItem(
                        textStart = range.first,
                        textEnd = range.last + 1,
                        style = context.style,
                        decoratedBox = context.decorated,
                        linkHref = context.link,
                        rubyGroup = context.rubyGroup,
                        rubyText = context.rubyText
                    )
                }
                return@forEach
            }
            val child = parent.children.getOrNull(elementCursor++) ?: return@forEach
            visitElement(child, builder, context)
        }
    }

    private fun visitElement(node: StyledDomNode, builder: FlowBuilder, context: InlineContext) {
        if (node.style.display == EpubDisplay.NONE) return
        val tag = node.node.tag
        if (tag in IMAGE_TAGS) {
            val range = node.node.anchorRange() ?: return
            val image = EpubImageBox(
                node = node,
                style = node.style,
                textStart = range.first,
                textEnd = range.last + 1,
                altText = node.node.attributes["alt"] ?: node.node.attributes["aria-label"].orEmpty(),
                linkHref = context.link,
                attrWidth = node.node.attributes["width"],
                attrHeight = node.node.attributes["height"]
            )
            when {
                node.style.float != EpubFloatValue.NONE -> builder.inlineItems += InlineFloatItem(image)
                node.isBlockLevel() -> {
                    builder.flushInline()
                    builder.boxes += image
                }
                else -> builder.inlineItems += InlineImageItem(
                    style = image.style,
                    textStart = image.textStart,
                    textEnd = image.textEnd,
                    altText = image.altText,
                    linkHref = image.linkHref,
                    attrWidth = image.attrWidth,
                    attrHeight = image.attrHeight
                )
            }
            return
        }
        if (tag == "br") {
            builder.inlineItems += InlineBreakItem
            return
        }
        if (node.style.float != EpubFloatValue.NONE) {
            builder.inlineItems += InlineFloatItem(buildBlockLevel(node))
            return
        }
        if (node.isBlockLevel()) {
            builder.flushInline()
            builder.boxes += buildBlockLevel(node)
            return
        }
        // Plain inline element: extend the inline context and keep walking.
        val ownRuby = node.node.attributes["data-ruby"]?.takeIf(String::isNotBlank)
        val next = InlineContext(
            style = node.style,
            decorated = if (node.style.hasInlineDecoration()) node else context.decorated,
            link = if (tag == "a") node.node.attributes["href"] ?: context.link else context.link,
            rubyGroup = if (ownRuby != null) ++builder.rubyCounter else context.rubyGroup,
            rubyText = ownRuby ?: context.rubyText
        )
        walkChildren(node, builder, next)
    }

    private fun buildBlockLevel(node: StyledDomNode): EpubBox {
        if (node.style.display == EpubDisplay.TABLE || node.node.tag == "table") {
            val rows = collectRows(node)
            if (rows.isNotEmpty()) return EpubTableBox(node, node.style, rows)
        }
        return EpubBlockBox(node, node.style, partitionChildren(node), node.node.tag)
    }

    private fun collectRows(table: StyledDomNode): List<EpubTableRow> {
        val rows = ArrayList<EpubTableRow>()
        fun walk(node: StyledDomNode) {
            if (node.node.tag == "tr" || node.style.display == EpubDisplay.TABLE_ROW) {
                val cells = node.children
                    .filter { cell ->
                        cell.style.display != EpubDisplay.NONE &&
                            (cell.node.tag == "td" || cell.node.tag == "th" || cell.style.display == EpubDisplay.TABLE_CELL)
                    }
                    .map { cell -> EpubTableCell(cell, cell.style, partitionChildren(cell)) }
                if (cells.isNotEmpty()) rows += EpubTableRow(node.style, cells)
            } else {
                node.children.forEach(::walk)
            }
        }
        table.children.forEach(::walk)
        return rows
    }

    /** Body text covered by a box subtree, or null when nothing in it is anchored. */
    fun boxRange(box: EpubBox): IntRange? = when (box) {
        is EpubImageBox -> box.textStart until box.textEnd
        is EpubBlockBox -> box.children.mapNotNull(::boxRange).mergeRanges()
            ?: box.node?.node?.anchorRange()
        is EpubInlineFlowBox -> box.items.mapNotNull { item ->
            when (item) {
                is InlineTextItem -> item.textStart until item.textEnd
                is InlineImageItem -> item.textStart until item.textEnd
                is InlineFloatItem -> boxRange(item.box)
                else -> null
            }
        }.mergeRanges()
        is EpubTableBox -> box.rows.flatMap { row -> row.cells }
            .flatMap { cell -> cell.children.mapNotNull(::boxRange) }
            .mergeRanges()
    }

    private fun List<IntRange>.mergeRanges(): IntRange? =
        takeIf { it.isNotEmpty() }?.let { ranges -> ranges.minOf { it.first }..ranges.maxOf { it.last } }

    /**
     * Inline-level means the box participates in a line: plain inline, or an inline-block whose
     * content is itself entirely inline (badges). Inline-blocks with block children fall back to
     * block stacking — embedding block fragments inside a line is out of scope by design.
     */
    private fun StyledDomNode.isBlockLevel(): Boolean = when (style.display) {
        EpubDisplay.INLINE -> false
        EpubDisplay.INLINE_BLOCK -> children.any { child ->
            child.style.display != EpubDisplay.NONE && child.isBlockLevel()
        }
        else -> true
    }

    private fun EpubDomNode.anchorRange(): IntRange? =
        if (textStart in 0 until textEnd) textStart until textEnd else null

    private fun EpubStyle.hasInlineDecoration(): Boolean =
        hasDecoration() ||
            paddingLeft != ZERO || paddingRight != ZERO || paddingTop != ZERO || paddingBottom != ZERO ||
            marginLeft != ZERO || marginRight != ZERO

    private val ZERO = ResolvedLength.Px(0f)
    // svg/picture are formatting containers; their anchored image/img descendants are the
    // replaced elements. Flattening picture here would discard CSS declared on picture > img.
    private val IMAGE_TAGS = setOf("img", "image", "object")
    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
}
