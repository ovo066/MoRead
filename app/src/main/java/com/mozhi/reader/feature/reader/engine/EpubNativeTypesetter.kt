package com.mozhi.reader.feature.reader.engine

import com.mozhi.reader.core.datastore.ReaderSyntaxFont
import com.mozhi.reader.core.datastore.ReaderSyntaxHighlighter
import com.mozhi.reader.core.datastore.ReaderSyntaxRule
import com.mozhi.reader.core.datastore.ReaderSyntaxStyleSpan
import com.mozhi.reader.core.library.EpubComputedStyle
import com.mozhi.reader.core.library.EpubFloat
import com.mozhi.reader.core.library.EpubLayoutBlock
import com.mozhi.reader.core.library.EpubLayoutBlockKind
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.library.EpubTextAlign
import com.mozhi.reader.core.library.EpubVerticalAlign
import kotlin.math.max
import kotlin.math.min

internal class EpubNativeTypesetter(
    private val spec: TypesetSpec,
    private val measure: TextMeasure
) {
    fun typeset(
        chapterIndex: Int,
        title: String,
        body: String,
        inlineImages: List<InlineImageSource>,
        inlineMarkers: List<InlineMarkerReservation>,
        bundle: EpubLayoutChapterBundle
    ): TextChapter {
        val document = bundle.document
        val state = LayoutState()
        val syntax = SyntaxStyleMap(body, spec.syntaxHighlightRules)
        val imagesByOffset = inlineImages.associateBy(InlineImageSource::charOffset)
        val containers = document.blocks.filter { it.kind == EpubLayoutBlockKind.CONTAINER }
        val contentBlocks = document.blocks
            .asSequence()
            .filter { it.kind != EpubLayoutBlockKind.CONTAINER && !it.style.hidden }
            .sortedWith(compareBy(EpubLayoutBlock::textStart, EpubLayoutBlock::orderIndex))
            .toList()
        val contexts = contentBlocks.map { block ->
            BlockContext(
                block = block,
                parents = containers
                    .filter { it.textStart <= block.textStart && it.textEnd >= block.textEnd && !it.style.hidden }
                    .sortedWith(
                        compareByDescending<EpubLayoutBlock> { it.textEnd - it.textStart }
                            .thenBy { it.orderIndex }
                    )
            )
        }

        contexts.forEachIndexed { blockIndex, context ->
            val block = context.block
            val openingContainers = context.parents.filter { it.textStart == block.textStart }
            val closingContainers = context.parents.filter { it.textEnd == block.textEnd }.asReversed()
            val geometry = resolveGeometry(context.parents.map(EpubLayoutBlock::style) + block.style)
            if (block.style.breakBefore) state.closePage(force = false)

            openingContainers.filter { it.style.avoidBreakInside }.forEach { container ->
                val endIndex = contexts.indexOfLast { it.block.textEnd <= container.textEnd }
                if (endIndex >= blockIndex) {
                    state.keepTogether(
                        estimateRangeAdvance(
                            contexts = contexts,
                            startIndex = blockIndex,
                            endIndex = endIndex,
                            body = body,
                            inlineMarkers = inlineMarkers,
                            syntax = syntax,
                            bundle = bundle,
                            imagesByOffset = imagesByOffset
                        )
                    )
                }
            }
            if (block.style.avoidBreakInside) {
                state.keepTogether(
                    blockLeadingAdvance(block) +
                        estimateBlockAdvance(body, block, geometry, inlineMarkers, syntax, bundle, imagesByOffset) +
                        blockTrailingAdvance(block)
                )
            }

            openingContainers.forEach { container ->
                state.addSpacing(container.style.marginTopEm.toPx())
                state.addSpacing(container.style.borderTopPx() + container.style.paddingTopEm.toPx(), keepAtPageTop = true)
            }
            state.addSpacing(block.style.marginTopEm?.toPx() ?: defaultTopGap(block))
            state.addSpacing(block.style.borderTopPx() + block.style.paddingTopEm.toPx(), keepAtPageTop = true)

            when (block.kind) {
                EpubLayoutBlockKind.IMAGE, EpubLayoutBlockKind.SEPARATOR -> {
                    val source = imagesByOffset[block.textStart]
                    if (source != null) {
                        layoutImage(state, source, block, geometry)
                    } else if (block.textEnd > block.textStart) {
                        layoutTextBlock(state, body, block, geometry, inlineMarkers, syntax, bundle)
                    } else {
                        layoutRule(state, block, geometry)
                    }
                }
                EpubLayoutBlockKind.PARAGRAPH,
                EpubLayoutBlockKind.HEADING,
                EpubLayoutBlockKind.QUOTE,
                EpubLayoutBlockKind.LIST_ITEM ->
                    layoutTextBlock(state, body, block, geometry, inlineMarkers, syntax, bundle)
                EpubLayoutBlockKind.CONTAINER -> Unit
            }

            state.addSpacing(block.style.paddingBottomEm.toPx() + block.style.borderBottomPx())
            state.addSpacing(block.style.marginBottomEm?.toPx() ?: defaultBottomGap(block))
            closingContainers.forEach { container ->
                state.addSpacing(container.style.paddingBottomEm.toPx() + container.style.borderBottomPx())
                state.addSpacing(container.style.marginBottomEm.toPx())
            }
            if (block.style.breakAfter) state.closePage(force = false)
        }

        state.closePage(force = true)
        val pages = decoratePages(state.pages, bundle, containers)
        return TextChapter(chapterIndex, title, pages, body.length)
    }

    private fun layoutTextBlock(
        state: LayoutState,
        body: String,
        block: EpubLayoutBlock,
        geometry: BlockGeometry,
        inlineMarkers: List<InlineMarkerReservation>,
        syntax: SyntaxStyleMap,
        bundle: EpubLayoutChapterBundle
    ) {
        val start = block.textStart.coerceIn(0, body.length)
        val end = block.textEnd.coerceIn(start, body.length)
        if (start >= end) return
        val text = body.substring(start, end)
        val isTitle = block.kind == EpubLayoutBlockKind.HEADING
        val layoutText = buildLayoutText(text, start, inlineMarkers)
        val clusters = styledClusters(layoutText, start, block, isTitle, syntax, bundle)
        val indent = if (isTitle) 0f else (block.style.textIndentEm ?: spec.indentCharCount) * measure.indentColumnWidth()
        val lines = wrap(clusters, geometry.width, indent)
        lines.forEachIndexed { lineIndex, lineClusters ->
            if (lineClusters.isEmpty()) return@forEachIndexed
            val metrics = resolveLineMetrics(lineClusters)
            state.prepareForLine(metrics.textHeight)

            val firstLine = lineIndex == 0
            val lastLine = lineIndex == lines.lastIndex
            val indentPx = if (firstLine) indent else 0f
            val placed = placeClusters(
                clusters = lineClusters,
                contentLeft = geometry.left + indentPx,
                contentRight = geometry.right,
                align = block.style.textAlign,
                justify = (block.style.textAlign == EpubTextAlign.JUSTIFY ||
                    block.style.textAlign == null && spec.justifyContent) && !lastLine
            )
            val sourceClusters = lineClusters.filter { it.sourceOffset >= 0 }
            val sourceStart = sourceClusters.firstOrNull()?.sourceOffset ?: start
            val sourceEnd = sourceClusters.lastOrNull()?.let { it.sourceOffset + it.sourceLength } ?: sourceStart
            val lineTop = state.durY
            val lineBottom = lineTop + metrics.textHeight
            state.addLine(
                TextLine(
                    text = body.substring(sourceStart.coerceAtMost(body.length), sourceEnd.coerceAtMost(body.length)),
                    columns = placed.columns,
                    lineTop = lineTop,
                    lineBase = lineTop + metrics.ascent,
                    lineBottom = lineBottom,
                    startX = placed.startX,
                    isTitle = isTitle,
                    isParagraphEnd = lastLine,
                    chapterPosition = sourceStart,
                    charLength = sourceEnd - sourceStart,
                    justifyGapExtra = placed.justifyGapExtra,
                    inlineDecorations = inlineDecorations(placed.inlineFragments, lineTop, lineBottom, bundle),
                    rubyPlacements = rubyPlacements(placed.rubyFragments, lineTop, metrics)
                ),
                lineStep = metrics.lineStep
            )
        }
    }

    private fun styledClusters(
        layoutText: LayoutText,
        bodyOffset: Int,
        block: EpubLayoutBlock,
        isTitle: Boolean,
        syntax: SyntaxStyleMap,
        bundle: EpubLayoutChapterBundle
    ): List<StyledCluster> {
        val result = ArrayList<StyledCluster>()
        var index = 0
        while (index < layoutText.text.length) {
            val marker = layoutText.markersByIndex[index]
            var end = index + 1
            if (marker == null) {
                if (layoutText.text[index].isHighSurrogate() && end < layoutText.text.length &&
                    layoutText.text[end].isLowSurrogate()
                ) {
                    end++
                }
                while (end < layoutText.text.length && layoutText.text[end].isCombiningMark()) end++
            }
            val sourceStart = layoutText.sourceBoundary[index]
            val sourceEnd = layoutText.sourceBoundary[end]
            val absoluteOffset = if (marker == null) bodyOffset + sourceStart else -1
            val spanIndex = if (absoluteOffset >= 0) {
                block.spans.indexOfFirst { absoluteOffset in it.textStart until it.textEnd }
            } else {
                -1
            }
            val span = block.spans.getOrNull(spanIndex)
            val epubStyle = span?.style ?: block.style
            val inlineBoxStyle = epubStyle.takeIf {
                span?.elements?.isNotEmpty() == true && it.hasInlineDecoration()
            }
            val ruby = span?.rubyText?.takeIf(String::isNotBlank)
            val resolved = resolveTextStyle(epubStyle, isTitle, bundle, absoluteOffset.takeIf { it >= 0 }?.let(syntax::at))
            val clusterText = if (marker == null) layoutText.text.substring(index, end) else ""
            val width = if (marker == null) {
                measure.charWidths(clusterText, resolved.measureStyle).sum()
            } else {
                measure.indentColumnWidth()
            }
            result += StyledCluster(
                text = clusterText,
                width = width,
                sourceOffset = absoluteOffset,
                sourceLength = sourceEnd - sourceStart,
                marker = marker,
                style = resolved,
                keepTogetherId = spanIndex.takeIf {
                    it >= 0 && (span?.style?.avoidBreakInside == true || ruby != null)
                },
                inlineBoxId = spanIndex.takeIf { inlineBoxStyle != null },
                inlineBoxStyle = inlineBoxStyle,
                rubyBoxId = spanIndex.takeIf { ruby != null },
                rubyText = ruby
            )
            index = end
        }
        val inlineClusters = result.mapIndexed { clusterIndex, cluster ->
            val boxId = cluster.inlineBoxId ?: return@mapIndexed cluster
            val startsBox = result.getOrNull(clusterIndex - 1)?.inlineBoxId != boxId
            val endsBox = result.getOrNull(clusterIndex + 1)?.inlineBoxId != boxId
            val style = requireNotNull(cluster.inlineBoxStyle)
            cluster.copy(
                startsInlineBox = startsBox,
                endsInlineBox = endsBox,
                leadingMargin = if (startsBox) style.marginLeftEm.toPx() else 0f,
                leadingInset = if (startsBox) style.borderLeftPx() + style.paddingLeftEm.toPx() else 0f,
                trailingInset = if (endsBox) style.paddingRightEm.toPx() + style.borderRightPx() else 0f,
                trailingMargin = if (endsBox) style.marginRightEm.toPx() else 0f
            )
        }
        return withRubyInsets(inlineClusters)
    }

    private fun withRubyInsets(clusters: List<StyledCluster>): List<StyledCluster> {
        var resolved = clusters
        clusters.mapNotNull(StyledCluster::rubyBoxId).distinct().forEach { rubyId ->
            val indices = resolved.indices.filter { resolved[it].rubyBoxId == rubyId }
            if (indices.isEmpty()) return@forEach
            val firstIndex = indices.first()
            val lastIndex = indices.last()
            val first = resolved[firstIndex]
            val rubyText = first.rubyText ?: return@forEach
            val rubyStyle = first.style.measureStyle.copy(
                textSizeScale = first.style.measureStyle.textSizeScale * RUBY_TEXT_SCALE
            )
            val rubyWidth = measure.charWidths(rubyText, rubyStyle).sum()
            val baseWidth = indices.sumOf { resolved[it].advanceWidth.toDouble() }.toFloat()
            val sideInset = ((rubyWidth - baseWidth).coerceAtLeast(0f)) / 2f
            resolved = resolved.mapIndexed { index, cluster ->
                if (cluster.rubyBoxId != rubyId) {
                    cluster
                } else {
                    cluster.copy(
                        startsRuby = index == firstIndex,
                        endsRuby = index == lastIndex,
                        rubyLeadingInset = if (index == firstIndex) sideInset else 0f,
                        rubyTrailingInset = if (index == lastIndex) sideInset else 0f
                    )
                }
            }
        }
        return resolved
    }

    private fun wrap(clusters: List<StyledCluster>, width: Float, firstIndent: Float): List<List<StyledCluster>> {
        if (clusters.isEmpty()) return emptyList()
        val lines = ArrayList<List<StyledCluster>>()
        var start = 0
        while (start < clusters.size) {
            val lineLimit = (width - if (lines.isEmpty()) firstIndent else 0f).coerceAtLeast(1f)
            var used = 0f
            var end = start
            var preferredBreak = -1
            while (end < clusters.size) {
                val next = clusters[end]
                if (end > start && used + next.advanceWidth > lineLimit) break
                used += next.advanceWidth
                end++
                if (next.isBreakOpportunity()) preferredBreak = end
            }
            var keptGroup = false
            if (end < clusters.size) {
                val groupId = clusters[end].keepTogetherId
                if (groupId != null) {
                    var groupStart = end
                    while (groupStart > start && clusters[groupStart - 1].keepTogetherId == groupId) groupStart--
                    var groupEnd = end + 1
                    while (groupEnd < clusters.size && clusters[groupEnd].keepTogetherId == groupId) groupEnd++
                    val groupWidth = clusters.subList(groupStart, groupEnd)
                        .sumOf { it.advanceWidth.toDouble() }
                        .toFloat()
                    if (groupWidth <= lineLimit) {
                        end = if (groupStart > start) groupStart else groupEnd
                        keptGroup = true
                    }
                }
            }
            if (!keptGroup && end < clusters.size && preferredBreak > start) end = preferredBreak
            if (end <= start) end = start + 1
            lines += clusters.subList(start, end)
            start = end
        }
        return lines
    }

    private fun placeClusters(
        clusters: List<StyledCluster>,
        contentLeft: Float,
        contentRight: Float,
        align: EpubTextAlign?,
        justify: Boolean
    ): PlacedLine {
        val available = (contentRight - contentLeft).coerceAtLeast(1f)
        val desired = clusters.sumOf { it.advanceWidth.toDouble() }.toFloat()
        val residual = available - desired
        val startX = when (align) {
            EpubTextAlign.CENTER -> contentLeft + max(0f, residual) / 2f
            EpubTextAlign.END -> contentLeft + max(0f, residual)
            else -> contentLeft
        }
        var spaceExtra = 0f
        var gapExtra = 0f
        if (justify && residual > 0f && clusters.size > 1 && residual <= available * MAX_JUSTIFY_FRACTION) {
            val spaces = clusters.count { it.text == " " && it.marker == null }
            if (spaces > 0) spaceExtra = residual / spaces else gapExtra = residual / (clusters.size - 1)
        }

        val columns = ArrayList<TextColumn>(clusters.size)
        val inlineFragments = ArrayList<PlacedInlineFragment>()
        val rubyFragments = ArrayList<PlacedRubyFragment>()
        var x = startX
        clusters.forEachIndexed { index, cluster ->
            val glyphStart = x + cluster.leadingMargin + cluster.leadingInset + cluster.rubyLeadingInset
            var advance = cluster.advanceWidth
            if (spaceExtra > 0f && cluster.text == " " && cluster.marker == null && index != clusters.lastIndex) {
                advance += spaceExtra
            }
            if (gapExtra > 0f && index != clusters.lastIndex) advance += gapExtra
            val style = cluster.style
            columns += TextColumn(
                start = glyphStart,
                end = glyphStart + cluster.width,
                charData = cluster.text,
                syntaxColorArgb = style.colorArgb,
                syntaxBackgroundArgb = style.backgroundArgb.takeIf { cluster.inlineBoxId == null },
                syntaxUnderline = style.underline,
                syntaxFont = style.syntaxFont,
                syntaxFontAssetId = style.syntaxFontAssetId,
                syntaxBold = style.measureStyle.bold,
                syntaxItalic = style.measureStyle.italic,
                syntaxStrikethrough = style.strikethrough,
                textSizeScale = style.measureStyle.textSizeScale,
                fontFilePath = style.measureStyle.fontFilePath,
                fontFamily = style.measureStyle.fontFamily,
                baselineShiftPx = style.baselineShiftPx,
                opacity = style.opacity,
                sourceLength = cluster.sourceLength,
                inlineMarkerKind = cluster.marker?.kind,
                inlineMarkerOffset = cluster.marker?.charOffset
            )
            val boxId = cluster.inlineBoxId
            val boxStyle = cluster.inlineBoxStyle
            if (boxId != null && boxStyle != null) {
                val boxLeft = x + cluster.leadingMargin
                val boxRight = x + cluster.advanceWidth - cluster.trailingMargin
                val previous = inlineFragments.lastOrNull()
                if (previous != null && previous.id == boxId) {
                    inlineFragments[inlineFragments.lastIndex] = previous.copy(
                        right = boxRight,
                        drawRightEdge = cluster.endsInlineBox
                    )
                } else {
                    inlineFragments += PlacedInlineFragment(
                        id = boxId,
                        left = boxLeft,
                        right = boxRight,
                        style = boxStyle,
                        drawLeftEdge = cluster.startsInlineBox,
                        drawRightEdge = cluster.endsInlineBox
                    )
                }
            }
            val rubyId = cluster.rubyBoxId
            val rubyText = cluster.rubyText
            if (rubyId != null && rubyText != null) {
                val rubyLeft = x + cluster.leadingMargin
                val rubyRight = x + cluster.advanceWidth - cluster.trailingMargin
                val previous = rubyFragments.lastOrNull()
                if (previous != null && previous.id == rubyId) {
                    rubyFragments[rubyFragments.lastIndex] = previous.copy(right = rubyRight)
                } else {
                    rubyFragments += PlacedRubyFragment(
                        id = rubyId,
                        text = rubyText,
                        left = rubyLeft,
                        right = rubyRight,
                        style = cluster.style
                    )
                }
            }
            x += advance
        }
        return PlacedLine(columns, gapExtra, startX, inlineFragments, rubyFragments)
    }

    private fun rubyPlacements(
        fragments: List<PlacedRubyFragment>,
        lineTop: Float,
        metrics: ResolvedLineMetrics
    ): List<TextRubyPlacement> {
        val baseline = metrics.rubyBaseline?.let(lineTop::plus) ?: return emptyList()
        return fragments.map { fragment ->
            val style = fragment.style
            TextRubyPlacement(
                text = fragment.text,
                left = fragment.left,
                right = fragment.right,
                baseline = baseline,
                textSizeScale = style.measureStyle.textSizeScale * RUBY_TEXT_SCALE,
                fontFilePath = style.measureStyle.fontFilePath,
                fontFamily = style.measureStyle.fontFamily,
                colorArgb = style.colorArgb,
                bold = style.measureStyle.bold,
                italic = style.measureStyle.italic,
                opacity = style.opacity
            )
        }
    }

    private fun inlineDecorations(
        fragments: List<PlacedInlineFragment>,
        lineTop: Float,
        lineBottom: Float,
        bundle: EpubLayoutChapterBundle
    ): List<TextBlockDecoration> = fragments.mapNotNull { fragment ->
        val style = fragment.style
        val top = lineTop + style.marginTopEm.toPx()
        val bottom = lineBottom - style.marginBottomEm.toPx()
        if (fragment.right <= fragment.left || bottom <= top) return@mapNotNull null
        TextBlockDecoration(
            left = fragment.left,
            top = top,
            right = fragment.right,
            bottom = bottom,
            backgroundColorArgb = style.backgroundColorArgb,
            backgroundImagePath = style.backgroundImageHref?.let(bundle.resourcePaths::get),
            borderColorArgb = style.borderColorArgb,
            borderWidth = style.borderWidthEm.toPx(),
            borderTopColorArgb = style.borderTopColorArgb,
            borderRightColorArgb = style.borderRightColorArgb,
            borderBottomColorArgb = style.borderBottomColorArgb,
            borderLeftColorArgb = style.borderLeftColorArgb,
            borderTopWidth = style.borderTopPx(),
            borderRightWidth = style.borderRightPx(),
            borderBottomWidth = style.borderBottomPx(),
            borderLeftWidth = style.borderLeftPx(),
            borderRadius = style.borderRadiusEm.toPx(),
            boxShadows = style.textBoxShadows(),
            opacity = style.opacity,
            drawRightEdge = fragment.drawRightEdge,
            drawLeftEdge = fragment.drawLeftEdge
        )
    }

    private fun layoutImage(
        state: LayoutState,
        source: InlineImageSource,
        block: EpubLayoutBlock,
        geometry: BlockGeometry
    ) {
        val style = block.style
        val size = resolveImageSize(source, style, geometry)
        val width = size.first
        val height = size.second
        state.prepareForLine(height)
        val startX = when {
            style.float == EpubFloat.START -> geometry.left
            style.float == EpubFloat.END -> geometry.right - width
            style.textAlign == EpubTextAlign.START -> geometry.left
            style.textAlign == EpubTextAlign.END -> geometry.right - width
            else -> geometry.left + (geometry.width - width) / 2f
        }
        val lineTop = state.durY
        val bodyLength = block.textEnd - block.textStart
        state.addLine(
            TextLine(
                text = if (bodyLength > 0) EpubTextExtractorPlaceholder else "",
                columns = emptyList(),
                lineTop = lineTop,
                lineBase = lineTop + height,
                lineBottom = lineTop + height,
                startX = startX,
                isTitle = false,
                isParagraphEnd = true,
                chapterPosition = block.textStart,
                charLength = bodyLength,
                inlineImage = InlineImagePlacement(source.imagePath, width, height, source.altText)
            ),
            lineStep = height
        )
    }

    private fun resolveImageSize(
        source: InlineImageSource,
        style: EpubComputedStyle,
        geometry: BlockGeometry
    ): Pair<Float, Float> {
        val aspect = (source.pixelWidth.toFloat() / source.pixelHeight.coerceAtLeast(1))
            .coerceIn(MIN_IMAGE_ASPECT, MAX_IMAGE_ASPECT)
        var width = requestedWidth(style, geometry.width)
            ?: min(geometry.width, source.pixelWidth.coerceAtLeast(1).toFloat())
        width = min(width, requestedMaxWidth(style, geometry.width) ?: geometry.width)
        var height = style.heightEm?.toPx()
            ?: style.heightViewportFraction?.times(spec.visibleHeight)
            ?: width / aspect
        val maxHeight = requestedMaxHeight(style) ?: spec.visibleHeight * MAX_IMAGE_HEIGHT_FRACTION
        if (height > maxHeight) {
            height = maxHeight
            width = min(width, height * aspect)
        }
        return width.coerceAtLeast(1f) to height.coerceAtLeast(1f)
    }

    private fun layoutRule(state: LayoutState, block: EpubLayoutBlock, geometry: BlockGeometry) {
        val height = max(1f, block.style.borderWidthEm.toPx())
        state.prepareForLine(height)
        val lineTop = state.durY
        state.addLine(
            TextLine(
                text = "",
                columns = emptyList(),
                lineTop = lineTop,
                lineBase = lineTop + height,
                lineBottom = lineTop + height,
                startX = geometry.left,
                isTitle = false,
                isParagraphEnd = true,
                chapterPosition = block.textStart,
                charLength = 0,
                rule = TextRulePlacement(
                    width = geometry.width,
                    height = height,
                    colorArgb = block.style.borderColorArgb ?: DEFAULT_RULE_COLOR
                )
            ),
            lineStep = max(height, spec.contentFontSizePx * 0.35f)
        )
    }

    private fun estimateRangeAdvance(
        contexts: List<BlockContext>,
        startIndex: Int,
        endIndex: Int,
        body: String,
        inlineMarkers: List<InlineMarkerReservation>,
        syntax: SyntaxStyleMap,
        bundle: EpubLayoutChapterBundle,
        imagesByOffset: Map<Int, InlineImageSource>
    ): Float {
        var advance = 0f
        for (index in startIndex..endIndex.coerceAtMost(contexts.lastIndex)) {
            val context = contexts[index]
            val block = context.block
            context.parents.filter { it.textStart == block.textStart }.forEach { container ->
                advance += containerLeadingAdvance(container)
            }
            val geometry = resolveGeometry(context.parents.map(EpubLayoutBlock::style) + block.style)
            advance += blockLeadingAdvance(block)
            advance += estimateBlockAdvance(
                body,
                block,
                geometry,
                inlineMarkers,
                syntax,
                bundle,
                imagesByOffset
            )
            advance += blockTrailingAdvance(block)
            context.parents.filter { it.textEnd == block.textEnd }.forEach { container ->
                advance += containerTrailingAdvance(container)
            }
        }
        return advance
    }

    private fun estimateBlockAdvance(
        body: String,
        block: EpubLayoutBlock,
        geometry: BlockGeometry,
        inlineMarkers: List<InlineMarkerReservation>,
        syntax: SyntaxStyleMap,
        bundle: EpubLayoutChapterBundle,
        imagesByOffset: Map<Int, InlineImageSource>
    ): Float = when (block.kind) {
        EpubLayoutBlockKind.IMAGE, EpubLayoutBlockKind.SEPARATOR -> {
            val source = imagesByOffset[block.textStart]
            when {
                source != null -> resolveImageSize(source, block.style, geometry).second
                block.textEnd > block.textStart -> estimateTextAdvance(
                    body,
                    block,
                    geometry,
                    inlineMarkers,
                    syntax,
                    bundle
                )
                else -> max(max(1f, block.style.borderWidthEm.toPx()), spec.contentFontSizePx * 0.35f)
            }
        }
        EpubLayoutBlockKind.PARAGRAPH,
        EpubLayoutBlockKind.HEADING,
        EpubLayoutBlockKind.QUOTE,
        EpubLayoutBlockKind.LIST_ITEM -> estimateTextAdvance(
            body,
            block,
            geometry,
            inlineMarkers,
            syntax,
            bundle
        )
        EpubLayoutBlockKind.CONTAINER -> 0f
    }

    private fun estimateTextAdvance(
        body: String,
        block: EpubLayoutBlock,
        geometry: BlockGeometry,
        inlineMarkers: List<InlineMarkerReservation>,
        syntax: SyntaxStyleMap,
        bundle: EpubLayoutChapterBundle
    ): Float {
        val start = block.textStart.coerceIn(0, body.length)
        val end = block.textEnd.coerceIn(start, body.length)
        if (start >= end) return 0f
        val isTitle = block.kind == EpubLayoutBlockKind.HEADING
        val layoutText = buildLayoutText(body.substring(start, end), start, inlineMarkers)
        val clusters = styledClusters(layoutText, start, block, isTitle, syntax, bundle)
        val indent = if (isTitle) 0f else {
            (block.style.textIndentEm ?: spec.indentCharCount) * measure.indentColumnWidth()
        }
        return wrap(clusters, geometry.width, indent).sumOf { line ->
            resolveLineMetrics(line).lineStep.toDouble()
        }.toFloat()
    }

    private fun resolveLineMetrics(clusters: List<StyledCluster>): ResolvedLineMetrics {
        var ascent = 0f
        var descent = 0f
        var requestedStep = 0f
        var rubyBaseline: Float? = null
        clusters.forEach { cluster ->
            val metrics = measure.metrics(cluster.style.measureStyle)
            val inlineStyle = cluster.inlineBoxStyle
            val topExtra = inlineStyle?.let {
                it.marginTopEm.toPx() + it.borderTopPx() + it.paddingTopEm.toPx()
            } ?: 0f
            val bottomExtra = inlineStyle?.let {
                it.paddingBottomEm.toPx() + it.borderBottomPx() + it.marginBottomEm.toPx()
            } ?: 0f
            val rubyMetrics = cluster.rubyBoxId?.let {
                measure.metrics(
                    cluster.style.measureStyle.copy(
                        textSizeScale = cluster.style.measureStyle.textSizeScale * RUBY_TEXT_SCALE
                    )
                )
            }
            val rubyBandHeight = rubyMetrics?.let { it.textHeight + spec.contentFontSizePx * RUBY_GAP_EM } ?: 0f
            rubyMetrics?.let { value ->
                rubyBaseline = max(rubyBaseline ?: 0f, value.textHeight - value.descent)
            }
            ascent = max(ascent, metrics.textHeight - metrics.descent + topExtra + rubyBandHeight)
            descent = max(descent, metrics.descent + bottomExtra)
            val fontPx = baseFontSize(cluster.style.measureStyle.isTitle) * cluster.style.measureStyle.textSizeScale
            val requestedTextStep = cluster.style.lineHeightEm?.let { fontPx * it }
                ?: defaultLineStep(cluster.style.measureStyle.isTitle)
            requestedStep = max(requestedStep, requestedTextStep + topExtra + bottomExtra + rubyBandHeight)
        }
        val textHeight = ascent + descent
        return ResolvedLineMetrics(ascent, textHeight, max(textHeight, requestedStep), rubyBaseline)
    }

    private fun blockLeadingAdvance(block: EpubLayoutBlock): Float =
        (block.style.marginTopEm?.toPx() ?: defaultTopGap(block)) +
            block.style.borderTopPx() + block.style.paddingTopEm.toPx()

    private fun blockTrailingAdvance(block: EpubLayoutBlock): Float =
        block.style.paddingBottomEm.toPx() + block.style.borderBottomPx() +
            (block.style.marginBottomEm?.toPx() ?: defaultBottomGap(block))

    private fun containerLeadingAdvance(container: EpubLayoutBlock): Float =
        container.style.marginTopEm.toPx() + container.style.borderTopPx() +
            container.style.paddingTopEm.toPx()

    private fun containerTrailingAdvance(container: EpubLayoutBlock): Float =
        container.style.paddingBottomEm.toPx() + container.style.borderBottomPx() +
            container.style.marginBottomEm.toPx()

    private fun decoratePages(
        pages: List<TextPage>,
        bundle: EpubLayoutChapterBundle,
        containers: List<EpubLayoutBlock>
    ): List<TextPage> {
        val bodyBackground = bundle.document.bodyStyle.backgroundImageHref
            ?.let(bundle.resourcePaths::get)
        val candidates = (containers + bundle.document.blocks.filter { it.kind != EpubLayoutBlockKind.CONTAINER })
            .filter { it.style.hasDecoration() && it.textEnd > it.textStart }
            .sortedWith(compareByDescending<EpubLayoutBlock> { it.textEnd - it.textStart }.thenBy { it.orderIndex })
        return pages.map { page ->
            val decorations = candidates.mapNotNull { block ->
                val lines = page.lines.filter { line ->
                    line.charLength > 0 && line.chapterPosition < block.textEnd &&
                        line.chapterPosition + line.charLength > block.textStart
                }
                if (lines.isEmpty()) return@mapNotNull null
                val style = block.style
                val startsHere = lines.minOf(TextLine::chapterPosition) <= block.textStart
                val endsHere = lines.maxOf { it.chapterPosition + it.charLength } >= block.textEnd
                val geometry = resolveGeometry(
                    containers
                        .filter { it !== block && it.textStart <= block.textStart && it.textEnd >= block.textEnd }
                        .sortedByDescending { it.textEnd - it.textStart }
                        .map(EpubLayoutBlock::style) + style
                )
                TextBlockDecoration(
                    left = geometry.boxLeft,
                    top = if (startsHere) {
                        (lines.first().lineTop - style.paddingTopEm.toPx() - style.borderTopPx())
                            .coerceAtLeast(0f)
                    } else {
                        0f
                    },
                    right = geometry.boxRight,
                    bottom = if (endsHere) {
                        (lines.last().lineBottom + style.paddingBottomEm.toPx() + style.borderBottomPx())
                            .coerceAtMost(spec.visibleHeight)
                    } else {
                        spec.visibleHeight
                    },
                    backgroundColorArgb = style.backgroundColorArgb,
                    backgroundImagePath = style.backgroundImageHref?.let(bundle.resourcePaths::get),
                    borderColorArgb = style.borderColorArgb,
                    borderWidth = style.borderWidthEm.toPx(),
                    borderTopColorArgb = style.borderTopColorArgb,
                    borderRightColorArgb = style.borderRightColorArgb,
                    borderBottomColorArgb = style.borderBottomColorArgb,
                    borderLeftColorArgb = style.borderLeftColorArgb,
                    borderTopWidth = style.borderTopPx(),
                    borderRightWidth = style.borderRightPx(),
                    borderBottomWidth = style.borderBottomPx(),
                    borderLeftWidth = style.borderLeftPx(),
                    borderRadius = style.borderRadiusEm.toPx(),
                    boxShadows = style.textBoxShadows(),
                    opacity = style.opacity,
                    drawTopEdge = startsHere,
                    drawBottomEdge = endsHere
                )
            }
            TextPage(
                index = page.index,
                lines = page.lines,
                chapterPosition = page.chapterPosition,
                charLength = page.charLength,
                height = page.height,
                decorations = decorations,
                backgroundColorArgb = bundle.document.bodyStyle.backgroundColorArgb,
                backgroundImagePath = bodyBackground,
                backgroundOpacity = bundle.document.bodyStyle.opacity,
                trailingGap = page.trailingGap
            )
        }
    }

    private fun resolveGeometry(styles: List<EpubComputedStyle>): BlockGeometry {
        var left = 0f
        var right = spec.visibleWidth
        var boxLeft = left
        var boxRight = right
        styles.forEach { style ->
            val marginLeft = style.marginLeftEm.toPx()
            val marginRight = style.marginRightEm.toPx()
            val available = (right - left - marginLeft - marginRight).coerceAtLeast(1f)
            val requested = requestedWidth(style, available)
            val maximum = requestedMaxWidth(style, available)
            val width = min(requested ?: available, maximum ?: available).coerceIn(1f, available)
            boxLeft = when {
                style.centerBlock -> left + marginLeft + (available - width) / 2f
                style.float == EpubFloat.END -> right - marginRight - width
                else -> left + marginLeft
            }
            boxRight = boxLeft + width
            left = boxLeft + style.paddingLeftEm.toPx() + style.borderLeftPx()
            right = boxRight - style.paddingRightEm.toPx() - style.borderRightPx()
            if (right <= left) right = left + 1f
        }
        return BlockGeometry(left, right, boxLeft, boxRight)
    }

    private fun requestedWidth(style: EpubComputedStyle, available: Float): Float? =
        style.widthFraction?.times(available) ?: style.widthEm.toPx().takeIf { it > 0f }

    private fun requestedMaxWidth(style: EpubComputedStyle, available: Float): Float? =
        style.maxWidthFraction?.times(available) ?: style.maxWidthEm.toPx().takeIf { it > 0f }

    private fun requestedMaxHeight(style: EpubComputedStyle): Float? =
        style.maxHeightViewportFraction?.times(spec.visibleHeight)
            ?: style.maxHeightEm.toPx().takeIf { it > 0f }

    private fun resolveTextStyle(
        epub: EpubComputedStyle,
        isTitle: Boolean,
        bundle: EpubLayoutChapterBundle,
        syntax: ReaderSyntaxStyleSpan?
    ): ResolvedTextStyle {
        val fontSizeEm = epub.fontSizeEm ?: if (isTitle) DEFAULT_HEADING_EM else 1f
        val baseSize = baseFontSize(isTitle).coerceAtLeast(1f)
        val sizeScale = (spec.contentFontSizePx * fontSizeEm / baseSize).coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)
        val family = epub.fontFamily
        val fontPath = family?.let { name ->
            bundle.resolveFontPath(
                family = name,
                weight = epub.fontWeight ?: 400,
                italic = epub.italic
            )
        }
        val verticalShift = when (epub.verticalAlign) {
            EpubVerticalAlign.SUPER -> -spec.contentFontSizePx * fontSizeEm * 0.35f
            EpubVerticalAlign.SUB -> spec.contentFontSizePx * fontSizeEm * 0.2f
            EpubVerticalAlign.BASELINE -> 0f
        }
        return ResolvedTextStyle(
            measureStyle = MeasuredTextStyle(
                isTitle = isTitle,
                textSizeScale = sizeScale,
                fontFilePath = fontPath,
                fontFamily = family,
                bold = (epub.fontWeight ?: 400) >= 600 || syntax?.bold == true,
                italic = epub.italic || syntax?.italic == true,
                letterSpacingEm = epub.letterSpacingEm ?: 0f
            ),
            colorArgb = syntax?.colorArgb ?: epub.colorArgb,
            backgroundArgb = syntax?.backgroundArgb ?: epub.backgroundColorArgb,
            underline = epub.underline || syntax?.underline == true,
            strikethrough = epub.strikethrough || syntax?.strikethrough == true,
            syntaxFont = syntax?.font ?: ReaderSyntaxFont.INHERIT,
            syntaxFontAssetId = syntax?.fontAssetId,
            baselineShiftPx = verticalShift,
            lineHeightEm = epub.lineHeightEm,
            opacity = epub.opacity
        )
    }

    private fun defaultTopGap(block: EpubLayoutBlock): Float = when (block.kind) {
        EpubLayoutBlockKind.HEADING -> spec.titleTopSpacing
        else -> 0f
    }

    private fun defaultBottomGap(block: EpubLayoutBlock): Float = when (block.kind) {
        EpubLayoutBlockKind.HEADING -> spec.titleBottomSpacing
        else -> spec.paragraphSpacing
    }

    private fun baseFontSize(isTitle: Boolean): Float =
        if (isTitle) spec.titleFontSizePx else spec.contentFontSizePx

    private fun defaultLineStep(isTitle: Boolean): Float =
        if (isTitle) spec.titleLineStep else spec.contentLineStep

    private fun Float?.toPx(): Float = (this ?: 0f) * spec.contentFontSizePx

    private fun EpubComputedStyle.borderTopPx(): Float = (borderTopWidthEm ?: borderWidthEm).toPx()

    private fun EpubComputedStyle.borderRightPx(): Float = (borderRightWidthEm ?: borderWidthEm).toPx()

    private fun EpubComputedStyle.borderBottomPx(): Float = (borderBottomWidthEm ?: borderWidthEm).toPx()

    private fun EpubComputedStyle.borderLeftPx(): Float = (borderLeftWidthEm ?: borderWidthEm).toPx()

    private fun EpubComputedStyle.hasDecoration(): Boolean =
        backgroundColorArgb != null || backgroundImageHref != null || hasBorder() || boxShadows.isNotEmpty()

    private fun EpubComputedStyle.hasInlineDecoration(): Boolean =
        hasDecoration() || marginTopEm.toPx() != 0f || marginRightEm.toPx() != 0f ||
            marginBottomEm.toPx() != 0f || marginLeftEm.toPx() != 0f ||
            paddingTopEm.toPx() != 0f || paddingRightEm.toPx() != 0f ||
            paddingBottomEm.toPx() != 0f || paddingLeftEm.toPx() != 0f

    private fun EpubComputedStyle.hasBorder(): Boolean =
        borderTopPx() > 0f || borderRightPx() > 0f || borderBottomPx() > 0f || borderLeftPx() > 0f

    private fun EpubComputedStyle.textBoxShadows(): List<TextBoxShadow> = boxShadows.map { shadow ->
        TextBoxShadow(
            offsetX = shadow.offsetXEm.toPx(),
            offsetY = shadow.offsetYEm.toPx(),
            blurRadius = shadow.blurRadiusEm.toPx(),
            spreadRadius = shadow.spreadRadiusEm.toPx(),
            colorArgb = shadow.colorArgb,
            inset = shadow.inset
        )
    }

    private fun EpubLayoutChapterBundle.resolveFontPath(
        family: String,
        weight: Int,
        italic: Boolean
    ): String? {
        val matches = fontFaces.filter { it.family.equals(family, true) }
        return matches.minByOrNull { face ->
            val italicPenalty = if (face.italic == italic) 0 else 1000
            italicPenalty + kotlin.math.abs((face.weight ?: 400) - weight)
        }?.filePath ?: fontPaths[family.lowercase()]
    }

    private fun Char.isCombiningMark(): Boolean = when (Character.getType(this)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    private fun StyledCluster.isBreakOpportunity(): Boolean {
        if (marker != null || text.isBlank()) return true
        val codePoint = text.codePointAt(text.length - Character.charCount(text.codePointBefore(text.length)))
        return codePoint in CJK_RANGE || codePoint in CJK_EXT_A_RANGE || text.last() in BREAK_PUNCTUATION
    }

    private fun TextColumn.shifted(delta: Float) = TextColumn(
        start = start + delta,
        end = end + delta,
        charData = charData,
        syntaxColorArgb = syntaxColorArgb,
        syntaxBackgroundArgb = syntaxBackgroundArgb,
        syntaxUnderline = syntaxUnderline,
        syntaxFont = syntaxFont,
        syntaxFontAssetId = syntaxFontAssetId,
        syntaxBold = syntaxBold,
        syntaxItalic = syntaxItalic,
        syntaxStrikethrough = syntaxStrikethrough,
        textSizeScale = textSizeScale,
        fontFilePath = fontFilePath,
        fontFamily = fontFamily,
        baselineShiftPx = baselineShiftPx,
        opacity = opacity,
        sourceLength = sourceLength,
        inlineMarkerKind = inlineMarkerKind,
        inlineMarkerOffset = inlineMarkerOffset
    )

    private data class BlockGeometry(
        val left: Float,
        val right: Float,
        val boxLeft: Float,
        val boxRight: Float
    ) {
        val width: Float get() = (right - left).coerceAtLeast(1f)
    }

    private data class BlockContext(
        val block: EpubLayoutBlock,
        val parents: List<EpubLayoutBlock>
    )

    private data class ResolvedLineMetrics(
        val ascent: Float,
        val textHeight: Float,
        val lineStep: Float,
        val rubyBaseline: Float?
    )

    private data class PlacedLine(
        val columns: List<TextColumn>,
        val justifyGapExtra: Float,
        val startX: Float,
        val inlineFragments: List<PlacedInlineFragment>,
        val rubyFragments: List<PlacedRubyFragment>
    )

    private data class PlacedInlineFragment(
        val id: Int,
        val left: Float,
        val right: Float,
        val style: EpubComputedStyle,
        val drawLeftEdge: Boolean,
        val drawRightEdge: Boolean
    )

    private data class PlacedRubyFragment(
        val id: Int,
        val text: String,
        val left: Float,
        val right: Float,
        val style: ResolvedTextStyle
    )

    private data class ResolvedTextStyle(
        val measureStyle: MeasuredTextStyle,
        val colorArgb: Int?,
        val backgroundArgb: Int?,
        val underline: Boolean,
        val strikethrough: Boolean,
        val syntaxFont: ReaderSyntaxFont,
        val syntaxFontAssetId: String?,
        val baselineShiftPx: Float,
        val lineHeightEm: Float?,
        val opacity: Float
    )

    private data class StyledCluster(
        val text: String,
        val width: Float,
        val sourceOffset: Int,
        val sourceLength: Int,
        val marker: InlineMarkerReservation?,
        val style: ResolvedTextStyle,
        val keepTogetherId: Int?,
        val inlineBoxId: Int? = null,
        val inlineBoxStyle: EpubComputedStyle? = null,
        val startsInlineBox: Boolean = false,
        val endsInlineBox: Boolean = false,
        val leadingMargin: Float = 0f,
        val leadingInset: Float = 0f,
        val trailingInset: Float = 0f,
        val trailingMargin: Float = 0f,
        val rubyBoxId: Int? = null,
        val rubyText: String? = null,
        val startsRuby: Boolean = false,
        val endsRuby: Boolean = false,
        val rubyLeadingInset: Float = 0f,
        val rubyTrailingInset: Float = 0f
    ) {
        val advanceWidth: Float
            get() = leadingMargin + leadingInset + rubyLeadingInset + width + rubyTrailingInset +
                trailingInset + trailingMargin
    }

    private data class LayoutText(
        val text: String,
        val sourceBoundary: IntArray,
        val markersByIndex: Map<Int, InlineMarkerReservation>
    )

    private fun buildLayoutText(
        text: String,
        bodyOffset: Int,
        markers: List<InlineMarkerReservation>
    ): LayoutText {
        val byLocalOffset = markers
            .filter { it.charOffset in (bodyOffset + 1)..(bodyOffset + text.length) }
            .distinctBy { it.charOffset to it.kind }
            .groupBy { it.charOffset - bodyOffset }
        if (byLocalOffset.isEmpty()) return LayoutText(text, IntArray(text.length + 1) { it }, emptyMap())
        val builder = StringBuilder(text.length + byLocalOffset.size)
        val boundaries = ArrayList<Int>(text.length + byLocalOffset.size + 1).apply { add(0) }
        val markerMap = mutableMapOf<Int, InlineMarkerReservation>()
        text.forEachIndexed { index, char ->
            builder.append(char)
            boundaries += index + 1
            byLocalOffset[index + 1].orEmpty().sortedBy { it.kind.ordinal }.forEach { marker ->
                markerMap[builder.length] = marker
                builder.append(MARKER_PLACEHOLDER)
                boundaries += index + 1
            }
        }
        return LayoutText(builder.toString(), boundaries.toIntArray(), markerMap)
    }

    private inner class LayoutState {
        val pages = ArrayList<TextPage>()
        val pendingLines = ArrayList<TextLine>()
        var durY = 0f
        private var openGap = 0f

        fun addSpacing(spacing: Float, keepAtPageTop: Boolean = false) {
            if (spacing <= 0f || pendingLines.isEmpty() && !keepAtPageTop) return
            durY += spacing
            openGap += spacing
        }

        fun keepTogether(height: Float) {
            if (height <= 0f || height > spec.visibleHeight || pendingLines.isEmpty()) return
            if (durY + height > spec.visibleHeight + HEIGHT_EPSILON) closePage(force = false)
        }

        fun prepareForLine(height: Float) {
            if (pendingLines.isNotEmpty() && durY + height > spec.visibleHeight + HEIGHT_EPSILON) {
                closePage(force = false)
            } else if (pendingLines.isEmpty() && durY + height > spec.visibleHeight + HEIGHT_EPSILON) {
                durY = 0f
                openGap = 0f
            }
        }

        fun addLine(line: TextLine, lineStep: Float) {
            pendingLines += line
            durY = line.lineTop + max(lineStep, line.lineBottom - line.lineTop)
            openGap = 0f
        }

        fun closePage(force: Boolean) {
            if (pendingLines.isEmpty()) {
                if (force && pages.isEmpty()) pages += TextPage(0, emptyList(), 0, 0, 0f)
                return
            }
            if (spec.bottomAlign) bottomAlign(pendingLines)
            val first = pendingLines.firstOrNull { it.charLength > 0 }
            val start = first?.chapterPosition ?: pendingLines.first().chapterPosition
            pages += TextPage(
                index = pages.size,
                lines = ArrayList(pendingLines),
                chapterPosition = start,
                charLength = pendingLines.sumOf(TextLine::charLength),
                height = pendingLines.last().lineBottom,
                trailingGap = openGap
            )
            pendingLines.clear()
            durY = 0f
            openGap = 0f
        }

        private fun bottomAlign(lines: List<TextLine>) {
            if (lines.size < 2) return
            val surplus = spec.visibleHeight - lines.last().lineBottom
            if (surplus <= 0f || surplus >= spec.contentLineStep) return
            val step = surplus / (lines.size - 1)
            lines.forEachIndexed { index, line ->
                val shift = step * index
                line.lineTop += shift
                line.lineBase += shift
                line.lineBottom += shift
                line.inlineDecorations = line.inlineDecorations.map { decoration ->
                    decoration.copy(top = decoration.top + shift, bottom = decoration.bottom + shift)
                }
                line.rubyPlacements = line.rubyPlacements.map { ruby ->
                    ruby.copy(baseline = ruby.baseline + shift)
                }
            }
        }
    }

    private class SyntaxStyleMap(text: String, rules: List<ReaderSyntaxRule>) {
        private val styles = arrayOfNulls<ReaderSyntaxStyleSpan>(text.length)

        init {
            ReaderSyntaxHighlighter.spans(text, rules).forEach { span ->
                for (index in span.start until span.endExclusive.coerceAtMost(text.length)) styles[index] = span
            }
        }

        fun at(index: Int): ReaderSyntaxStyleSpan? = styles.getOrNull(index)
    }

    private companion object {
        const val HEIGHT_EPSILON = 0.5f
        const val MARKER_PLACEHOLDER = '\u3000'
        const val EpubTextExtractorPlaceholder = "［图片］"
        const val DEFAULT_HEADING_EM = 1.5f
        const val MIN_TEXT_SCALE = 0.5f
        const val MAX_TEXT_SCALE = 3f
        const val MAX_IMAGE_HEIGHT_FRACTION = 0.86f
        const val MIN_IMAGE_ASPECT = 0.15f
        const val MAX_IMAGE_ASPECT = 7f
        const val MAX_JUSTIFY_FRACTION = 0.35f
        const val RUBY_TEXT_SCALE = 0.5f
        const val RUBY_GAP_EM = 0.08f
        const val DEFAULT_RULE_COLOR = 0xFF888888.toInt()
        val CJK_RANGE = 0x3400..0x9FFF
        val CJK_EXT_A_RANGE = 0x20000..0x2FA1F
        val BREAK_PUNCTUATION = setOf('，', '。', '、', '；', '：', '！', '？', '”', '’', ',', '.', ';', ':', '!', '?')
    }
}
