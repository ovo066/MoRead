package com.mozhi.reader.feature.reader.engine.epub

import com.mozhi.reader.core.epub.css.CssParser
import com.mozhi.reader.core.epub.css.CssRule
import com.mozhi.reader.core.epub.dom.EpubDomNode
import com.mozhi.reader.core.epub.style.EpubStyleResolver
import com.mozhi.reader.core.epub.style.StyledDomNode
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.library.EpubStylesheetText
import com.mozhi.reader.feature.reader.engine.InlineImageSource
import com.mozhi.reader.feature.reader.engine.InlineMarkerReservation
import com.mozhi.reader.feature.reader.engine.TextChapter
import com.mozhi.reader.feature.reader.engine.TextMeasure
import com.mozhi.reader.feature.reader.engine.TypesetSpec

/**
 * V2 entry point: raw CSS is cascaded against the persisted DOM at layout time, then a real box
 * tree (block/inline/float/table) is laid out in continuous coordinates and paginated. text.mz
 * anchors flow through every stage, so annotation/progress/listening coordinates are unchanged.
 */
internal class EpubTypesetterV2(
    private val spec: TypesetSpec,
    private val measure: TextMeasure,
    private val cancellationCheck: () -> Unit
) {
    fun typeset(
        chapterIndex: Int,
        title: String,
        body: String,
        inlineImages: List<InlineImageSource>,
        inlineMarkers: List<InlineMarkerReservation>,
        bundle: EpubLayoutChapterBundle
    ): TextChapter {
        val dom = requireNotNull(bundle.dom)
        cancellationCheck()
        // 样式表按本章 <link> 清单过滤：log.css 这类局部样式不得污染普通章节。
        val linked = bundle.document.stylesheetHrefs
        val stylesheets = if (linked.isEmpty()) {
            bundle.stylesheets
        } else {
            linked.mapNotNull { href -> bundle.stylesheets.firstOrNull { it.href.equals(href, true) } }
                .ifEmpty { bundle.stylesheets }
        }
        val styledRoot = EpubStyleResolver(
            stylesheets = stylesheets,
            viewportWidthPx = spec.visibleWidth,
            viewportHeightPx = spec.visibleHeight,
            rootFontSizePx = spec.contentFontSizePx,
            themeTextArgb = spec.themeTextArgb,
            publisherStyleMode = spec.publisherStyleMode,
            preParsedPublisherRules = cachedRules(stylesheets),
            documentHref = bundle.document.href
        ).resolve(dom.bodyNode)
        cancellationCheck()
        val ctx = EpubLayoutContext(
            spec = spec,
            measure = measure,
            body = body,
            bundle = bundle,
            inlineMarkers = inlineMarkers,
            cancellationCheck = cancellationCheck,
            immersivePage = bundle.document.immersivePage,
            dominantBodyFamily = dominantBodyFamily(styledRoot)
        )
        ctx.imageSources = inlineImages.associateBy(InlineImageSource::charOffset)
        val boxTree = EpubBoxTreeBuilder.build(styledRoot)
        val output = EpubBlockLayout(ctx).layout(boxTree)
        markCanvasDecorations(output)
        val hideHeader = firstPageHidesReaderHeader(dom.bodyNode)
        val builder = EpubPageBuilder(ctx)
        builder.firstPageExtraTop = hideHeader
        return builder.build(
            output = output,
            chapterIndex = chapterIndex,
            title = title,
            bodyStyle = styledRoot.style,
            hideHeaderFirstPage = hideHeader
        )
    }

    /**
     * A borderless decoration covering essentially the whole chapter is the publisher's canvas:
     * it must yield to a user-selected paper (DECISIONS 2026-08-25).
     */
    private fun markCanvasDecorations(output: FlowOutput) {
        val totalHeight = output.lines.maxOfOrNull { it.line.lineBottom } ?: return
        output.decorations.forEach { entry ->
            val decoration = entry.decoration
            val fullWidth = decoration.right - decoration.left >= spec.visibleWidth * MIN_CANVAS_FRACTION
            val fullHeight = decoration.bottom - decoration.top >= totalHeight * MIN_CANVAS_FRACTION
            val borderless = decoration.borderTopWidth <= 0f && decoration.borderRightWidth <= 0f &&
                decoration.borderBottomWidth <= 0f && decoration.borderLeftWidth <= 0f
            if (fullWidth && fullHeight && borderless) entry.isCanvas = true
        }
    }

    /** 正文自带明确的章标题（chapter/title/heading 标记）时首页隐藏阅读器页眉。 */
    private fun firstPageHidesReaderHeader(body: EpubDomNode): Boolean {
        var inspected = 0
        var found = false
        fun walk(node: EpubDomNode) {
            if (found || inspected >= 6) return
            if (node.tag in HEADING_TAGS) {
                inspected++
                val marker = (node.id.orEmpty() + " " + node.classes.joinToString(" ")).lowercase()
                if (marker.contains("chapter") || marker.contains("title") || marker.contains("heading")) {
                    found = true
                }
                return
            }
            if (node.tag != "#text" && node.textStart >= 0 && node.tag == "p") inspected++
            node.children.forEach(::walk)
        }
        walk(body)
        return found
    }

    private fun dominantBodyFamily(root: StyledDomNode): String? {
        val weights = HashMap<String, Int>()
        fun walk(node: StyledDomNode) {
            if (node.node.tag in HEADING_TAGS) return
            val family = node.style.fontFamilies.firstOrNull()?.lowercase()
            if (family != null) {
                node.node.children.forEach { child ->
                    if (child.tag == "#text" && child.textStart in 0 until child.textEnd) {
                        weights[family] = (weights[family] ?: 0) + (child.textEnd - child.textStart)
                    }
                }
            }
            node.children.forEach(::walk)
        }
        walk(root)
        return weights.maxByOrNull { it.value }?.key
    }

    private companion object {
        const val MIN_CANVAS_FRACTION = 0.9f
        val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

        /** Parsed publisher stylesheets, keyed by content, shared across chapters and re-typesets. */
        private val ruleCache = object : LinkedHashMap<List<Pair<String, Int>>, List<CssRule>>(8, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<List<Pair<String, Int>>, List<CssRule>>
            ): Boolean = size > 8
        }

        fun cachedRules(stylesheets: List<EpubStylesheetText>): List<CssRule> {
            val key = stylesheets.map { it.href to it.css.hashCode() }
            synchronized(ruleCache) {
                ruleCache[key]?.let { return it }
            }
            var order = 0
            val rules = stylesheets.flatMap { sheet ->
                val parsed = CssParser(sheet.href, order).parse(sheet.css).stylesheet.rules
                order += parsed.size
                parsed
            }
            synchronized(ruleCache) { ruleCache[key] = rules }
            return rules
        }
    }
}
