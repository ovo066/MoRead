package com.mozhi.reader.core.epub.dom

import com.mozhi.reader.core.library.EpubComputedStyle
import com.mozhi.reader.core.library.EpubElementRef
import com.mozhi.reader.core.library.EpubLayoutBlock
import com.mozhi.reader.core.library.EpubLayoutBlockKind
import com.mozhi.reader.core.library.EpubLayoutChapter

/** Best-effort read-only adapter for books whose original EPUB is unavailable. */
object EpubV9DomAdapter {
    fun adapt(chapter: EpubLayoutChapter): EpubDomChapter {
        val visible = chapter.blocks
            .filter { it.kind != EpubLayoutBlockKind.CONTAINER }
            .sortedWith(compareBy(EpubLayoutBlock::textStart, EpubLayoutBlock::orderIndex))
        val typeCounts = HashMap<String, Int>()
        val children = visible.mapIndexed { index, block ->
            val tag = block.element.tag.lowercase()
            val typeIndex = typeCounts.getOrDefault(tag, 0).also { typeCounts[tag] = it + 1 }
            block.toNode(index, typeIndex)
        }
        return EpubDomChapter(
            chapterIndex = chapter.chapterIndex,
            href = chapter.href,
            documentTitle = chapter.documentTitle,
            bodyNode = EpubDomNode(
                tag = "body",
                attributes = mapOf("style" to chapter.bodyStyle.toInlineCss()),
                children = children
            ),
            textLength = chapter.textLength,
            diagnostics = chapter.diagnostics
        )
    }

    private fun EpubLayoutBlock.toNode(index: Int, typeIndex: Int): EpubDomNode {
        val attributes = buildMap {
            element.inlineStyle?.let { put("style", it) }
                ?: blockStyle().takeIf(String::isNotBlank)?.let { put("style", it) }
            resourceHref?.let { put("src", it) }
            altText.takeIf(String::isNotBlank)?.let { put("alt", it) }
        }
        val textNode = if (kind == EpubLayoutBlockKind.IMAGE || kind == EpubLayoutBlockKind.SEPARATOR) {
            emptyList()
        } else {
            listOf(EpubDomNode("#text", textStart = textStart, textEnd = textEnd))
        }
        return EpubDomNode(
            tag = if (kind == EpubLayoutBlockKind.IMAGE) "img" else element.tag.lowercase(),
            id = element.id,
            classes = element.classes,
            attributes = attributes,
            childIndex = index,
            childIndexOfType = typeIndex,
            textStart = if (kind == EpubLayoutBlockKind.IMAGE) textStart else -1,
            textEnd = if (kind == EpubLayoutBlockKind.IMAGE) textEnd else -1,
            children = textNode
        )
    }

    private fun EpubLayoutBlock.blockStyle(): String = style.toInlineCss()

    private fun EpubComputedStyle.toInlineCss(): String = buildList {
        fontFamily?.let { add("font-family:'${it.replace("'", "\\'")}'") }
        fontSizeEm?.let { add("font-size:${it}em") }
        fontWeight?.let { add("font-weight:$it") }
        if (italic) add("font-style:italic")
        colorArgb?.let { add("color:${it.cssColor()}") }
        backgroundColorArgb?.let { add("background-color:${it.cssColor()}") }
        backgroundImageHref?.let { add("background-image:url('$it')") }
        marginTopEm?.let { add("margin-top:${it}em") }
        marginRightEm?.let { add("margin-right:${it}em") }
        marginBottomEm?.let { add("margin-bottom:${it}em") }
        marginLeftEm?.let { add("margin-left:${it}em") }
        paddingTopEm?.let { add("padding-top:${it}em") }
        paddingRightEm?.let { add("padding-right:${it}em") }
        paddingBottomEm?.let { add("padding-bottom:${it}em") }
        paddingLeftEm?.let { add("padding-left:${it}em") }
        widthEm?.let { add("width:${it}em") }
        widthFraction?.let { add("width:${it * 100f}%") }
        textIndentEm?.let { add("text-indent:${it}em") }
        lineHeightEm?.let { add("line-height:$it") }
        if (hidden) add("display:none")
    }.joinToString(";")

    private fun Int.cssColor(): String = "#%06x%02x".format(this and 0xFFFFFF, this ushr 24)
}
