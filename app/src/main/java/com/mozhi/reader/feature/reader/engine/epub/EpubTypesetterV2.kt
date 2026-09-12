package com.mozhi.reader.feature.reader.engine.epub

import com.mozhi.reader.core.epub.css.CssParser
import com.mozhi.reader.core.epub.css.CssRule
import com.mozhi.reader.core.epub.dom.EpubDomNode
import com.mozhi.reader.core.epub.style.EpubLayoutCapabilityAnalyzer
import com.mozhi.reader.core.epub.style.EpubDisplay
import com.mozhi.reader.core.epub.style.EpubFloatValue
import com.mozhi.reader.core.epub.style.EpubStyleResolver
import com.mozhi.reader.core.epub.style.ResolvedLength
import com.mozhi.reader.core.epub.style.StyledDomNode
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.library.EpubStylesheetText
import com.mozhi.reader.feature.reader.engine.ImmersiveArtworkFit
import com.mozhi.reader.feature.reader.engine.InlineImageSource
import com.mozhi.reader.feature.reader.engine.InlineMarkerReservation
import com.mozhi.reader.feature.reader.engine.TextChapter
import com.mozhi.reader.feature.reader.engine.TextLine
import com.mozhi.reader.feature.reader.engine.TextMeasure
import com.mozhi.reader.feature.reader.engine.TypesetSpec
import java.util.IdentityHashMap

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
        val availableStylesheets = bundle.stylesheets + dom.embeddedStylesheets
        val stylesheets = if (linked.isEmpty()) {
            availableStylesheets
        } else {
            linked.mapNotNull { href -> availableStylesheets.firstOrNull { it.href.equals(href, true) } }
                .ifEmpty { availableStylesheets }
        }
        val publisherRoot = EpubStyleResolver(
            stylesheets = stylesheets,
            viewportWidthPx = spec.visibleWidth,
            viewportHeightPx = spec.visibleHeight,
            rootFontSizePx = spec.contentFontSizePx,
            themeTextArgb = spec.themeTextArgb,
            publisherStyleMode = spec.publisherStyleMode,
            preParsedPublisherRules = cachedRules(stylesheets),
            documentHref = bundle.document.href
        ).resolve(dom.bodyNode)
        val immersive = bundle.document.immersivePage || isBackgroundArtwork(publisherRoot)
        val styledRoot = applyReaderPaper(publisherRoot, immersive)
        cancellationCheck()
        val ctx = EpubLayoutContext(
            spec = spec,
            measure = measure,
            body = body,
            bundle = bundle,
            inlineMarkers = inlineMarkers,
            cancellationCheck = cancellationCheck,
            immersivePage = immersive,
            dominantBodyFamily = dominantBodyFamily(styledRoot)
        )
        ctx.imageSources = inlineImages.associateBy(InlineImageSource::charOffset)
        // 布局前先做能力判定：竖排等尚不支持的书写模式按结构化原因降级到横排，而不是排到一半才发现。
        val capability = EpubLayoutCapabilityAnalyzer.analyze(styledRoot)
        val boxTree = EpubBoxTreeBuilder.build(styledRoot)
        val output = EpubBlockLayout(ctx).layout(boxTree)
        val fullPageArtwork = fitImmersiveArtwork(ctx, output)
        val hideHeader = firstPageHidesReaderHeader(dom.bodyNode)
        val builder = EpubPageBuilder(ctx)
        builder.firstPageExtraTop = hideHeader
        return builder.build(
            output = output,
            chapterIndex = chapterIndex,
            title = title,
            bodyStyle = styledRoot.style,
            hideHeaderFirstPage = hideHeader,
            layoutCapability = capability,
            fullPageArtwork = fullPageArtwork
        )
    }

    private fun isBackgroundArtwork(root: StyledDomNode): Boolean {
        fun hasVisibleContent(node: StyledDomNode): Boolean {
            if (node.style.display == EpubDisplay.NONE) return false
            return node.node.children.any { it.tag == "#text" && it.textStart >= 0 && it.textEnd > it.textStart } ||
                node.children.any(::hasVisibleContent)
        }
        fun hasBackgroundImage(node: StyledDomNode): Boolean =
            node.style.display != EpubDisplay.NONE &&
                (node.style.background.imageHref != null || node.children.any(::hasBackgroundImage))
        return !hasVisibleContent(root) && hasBackgroundImage(root)
    }

    /**
     * 封面/整页插画（immersivePage 且全章只有一张图）：按整个沉浸页可用区居中容纳，而不是沿用
     * 正文插图「贴内容框左上、按宽度铺开」的落位。翻页模式的绘制层会再按屏幕整屏落位；这里保证
     * 滚动条带与坐标层（点击、选区）看到的是同一张居中的图。滚动条带不裁边，只容纳。
     */
    private fun fitImmersiveArtwork(ctx: EpubLayoutContext, output: FlowOutput): Boolean {
        if (!ctx.immersivePage) return false
        val only = output.lines.singleOrNull() ?: return false
        val line = only.line
        val artwork = ImmersiveArtworkFit.singleArtwork(listOf(line)) ?: return false
        val positioned = (line.inlineImages + line.inlineGlyphImages).singleOrNull() ?: return false
        val availableWidth = spec.visibleWidth
        val availableHeight = spec.visibleHeight + spec.immersiveExtraTopPx + spec.immersiveExtraBottomPx
        if (!ImmersiveArtworkFit.fillsContentAxis(artwork, availableWidth, availableHeight)) return false
        val fitted = ImmersiveArtworkFit.fit(
            imageWidth = artwork.width,
            imageHeight = artwork.height,
            availableWidth = availableWidth,
            availableHeight = availableHeight,
            allowFill = false
        )
        only.line = TextLine(
            text = line.text,
            columns = line.columns,
            lineTop = 0f,
            lineBase = availableHeight,
            lineBottom = availableHeight,
            startX = fitted.left,
            isTitle = false,
            isParagraphEnd = true,
            chapterPosition = line.chapterPosition,
            charLength = line.charLength,
            inlineImages = listOf(
                positioned.copy(
                    left = fitted.left,
                    topOffset = fitted.top,
                    width = fitted.width,
                    height = fitted.height
                )
            )
        )
        output.keepRanges.clear()
        output.keepRanges += 0f..availableHeight
        return true
    }

    /** Remove page paper before layout, so text contrast uses the background actually painted. */
    private fun applyReaderPaper(root: StyledDomNode, immersive: Boolean): StyledDomNode {
        // Persisted element nodes have no own character range; only text/image leaves do.
        val ranges = IdentityHashMap<EpubDomNode, IntRange?>()
        fun indexRange(node: EpubDomNode): IntRange? {
            var start = node.textStart.takeIf { it >= 0 && node.textEnd > it } ?: Int.MAX_VALUE
            var end = node.textEnd.takeIf { start != Int.MAX_VALUE } ?: -1
            node.children.forEach { child ->
                indexRange(child)?.let { range ->
                    start = minOf(start, range.first)
                    end = maxOf(end, range.last + 1)
                }
            }
            return (if (end > start) start until end else null).also { ranges[node] = it }
        }
        val chapterRange = indexRange(root.node)
        fun visit(node: StyledDomNode): StyledDomNode {
            val style = node.style
            val fullChapter = chapterRange != null && ranges[node.node] == chapterRange
            val plainWrapper = node.node.tag in CANVAS_TAGS && fullChapter &&
                node.children.any { it.style.display == EpubDisplay.BLOCK } &&
                style.float == EpubFloatValue.NONE && !style.hasBorder() &&
                style.borderRadii.all { it == ResolvedLength.Px(0f) } && style.boxShadows.isEmpty() &&
                (style.width == ResolvedLength.Auto ||
                    (style.width as? ResolvedLength.Percent)?.value?.let { it >= 90f } == true)
            val paper = node === root || plainWrapper
            val preserveArtwork = immersive || node !== root && style.background.imageHref != null
            val background = if (paper && !preserveArtwork) {
                style.background.copy(colorArgb = null)
            } else style.background
            return node.copy(style = style.copy(background = background), children = node.children.map(::visit))
        }
        return visit(root)
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
        val CANVAS_TAGS = setOf("div", "section", "main", "article")
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
