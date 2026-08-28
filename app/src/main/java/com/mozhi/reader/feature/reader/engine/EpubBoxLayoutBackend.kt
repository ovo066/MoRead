package com.mozhi.reader.feature.reader.engine

import com.mozhi.reader.core.datastore.ReaderSyntaxFont
import com.mozhi.reader.core.datastore.ReaderSyntaxHighlighter
import com.mozhi.reader.core.datastore.ReaderSyntaxRule
import com.mozhi.reader.core.datastore.ReaderSyntaxStyleSpan
import com.mozhi.reader.core.datastore.PublisherStyleMode
import com.mozhi.reader.core.library.EpubBackgroundSizeMode
import com.mozhi.reader.core.library.EpubComputedStyle
import com.mozhi.reader.core.library.EpubFloat
import com.mozhi.reader.core.library.EpubLayoutBlock
import com.mozhi.reader.core.library.EpubLayoutBlockKind
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.library.EpubLayoutMode
import com.mozhi.reader.core.library.EpubTextAlign
import com.mozhi.reader.core.library.EpubVerticalAlign
import kotlin.math.max
import kotlin.math.min

internal class EpubBoxLayoutBackend(
    private val spec: TypesetSpec,
    private val measure: TextMeasure,
    private val cancellationCheck: () -> Unit
) {
    private var publisherBodyLineHeight = DEFAULT_PUBLISHER_LINE_HEIGHT
    private var dominantBodyFontFamily: String? = null
    private var immersivePage = false
    private var firstPageHidesReaderHeader = false

    fun typeset(
        chapterIndex: Int,
        title: String,
        body: String,
        inlineImages: List<InlineImageSource>,
        inlineMarkers: List<InlineMarkerReservation>,
        bundle: EpubLayoutChapterBundle
    ): TextChapter {
        cancellationCheck()
        val document = bundle.document
        immersivePage = document.immersivePage
        publisherBodyLineHeight = document.bodyStyle.lineHeightEm
            ?.coerceIn(MIN_PUBLISHER_LINE_HEIGHT, MAX_PUBLISHER_LINE_HEIGHT)
            ?: DEFAULT_PUBLISHER_LINE_HEIGHT
        dominantBodyFontFamily = document.blocks
            .asSequence()
            .filter { it.kind != EpubLayoutBlockKind.HEADING && it.textEnd > it.textStart }
            .flatMap { block ->
                (block.spans.ifEmpty {
                    listOf(com.mozhi.reader.core.library.EpubLayoutSpan(block.textStart, block.textEnd, style = block.style))
                }).asSequence()
            }
            .mapNotNull { span -> span.style.fontFamily?.lowercase()?.let { it to (span.textEnd - span.textStart) } }
            .groupBy({ it.first }, { it.second })
            .maxByOrNull { (_, lengths) -> lengths.sum() }
            ?.key
        val layoutBlocks = document.blocks.map { block ->
            block.copy(
                style = effectiveStyle(block.style),
                spans = block.spans.map { span -> span.copy(style = effectiveStyle(span.style)) }
            )
        }
        val state = LayoutState()
        val syntax = SyntaxStyleMap(body, spec.syntaxHighlightRules)
        val imagesByOffset = inlineImages.associateBy(InlineImageSource::charOffset)
        val containers = layoutBlocks.filter { it.kind == EpubLayoutBlockKind.CONTAINER }
        val contentBlocks = layoutBlocks
            .asSequence()
            .filter { it.kind != EpubLayoutBlockKind.CONTAINER && !it.style.hidden }
            .sortedWith(compareBy(EpubLayoutBlock::textStart, EpubLayoutBlock::orderIndex))
            .toList()
        // 不能把所有 h1 都当章首：“制作说明/校对说明”也是 h1，但仍是普通正文页。
        // 只认语义明确的 chapter/title/heading 标记；封面和卷首页由 immersivePage 单独识别。
        firstPageHidesReaderHeader = contentBlocks.take(4).any { block ->
            block.kind == EpubLayoutBlockKind.HEADING &&
                (block.element.id.orEmpty() + " " + block.element.classes.joinToString(" "))
                    .lowercase()
                    .let { marker ->
                        marker.contains("chapter") || marker.contains("title") || marker.contains("heading")
                    }
        }
        val containersByStart = containers
            .asSequence()
            .filterNot { it.style.hidden }
            .sortedWith(compareBy<EpubLayoutBlock> { it.textStart }.thenByDescending { it.textEnd })
            .toList()
        val activeContainers = ArrayList<EpubLayoutBlock>()
        var nextContainer = 0
        val contexts = contentBlocks.map { block ->
            cancellationCheck()
            while (nextContainer < containersByStart.size &&
                containersByStart[nextContainer].textStart <= block.textStart
            ) {
                activeContainers += containersByStart[nextContainer++]
            }
            activeContainers.removeAll { it.textEnd < block.textEnd }
            BlockContext(
                block = block,
                parents = activeContainers
                    .filter { it.textStart <= block.textStart && it.textEnd >= block.textEnd }
                    .sortedWith(
                        compareByDescending<EpubLayoutBlock> { it.textEnd - it.textStart }
                            .thenBy { it.orderIndex }
                    )
            )
        }

        var consumedThrough = -1
        contexts.forEachIndexed { blockIndex, context ->
            cancellationCheck()
            if (blockIndex <= consumedThrough) return@forEachIndexed
            val block = context.block
            val galleryParent = context.parents.lastOrNull { parent ->
                parent.style.layoutMode != EpubLayoutMode.FLOW
            }
            val galleryContexts = if (
                block.kind == EpubLayoutBlockKind.IMAGE && galleryParent != null
            ) {
                contexts.drop(blockIndex).takeWhile { candidate ->
                    candidate.block.kind == EpubLayoutBlockKind.IMAGE &&
                        candidate.parents.any { it.orderIndex == galleryParent.orderIndex }
                }.takeIf { it.size > 1 }
            } else {
                null
            }
            val trailingContext = galleryContexts?.lastOrNull() ?: context
            val trailingBlock = trailingContext.block
            val openingContainers = context.parents.filter { it.textStart == block.textStart }
            val closingContainers = trailingContext.parents
                .filter { it.textEnd == trailingBlock.textEnd }
                .asReversed()
            val parentStyles = context.parents.map(EpubLayoutBlock::style)
            val styleStack = parentStyles + block.style
            val imageContainerHeight = explicitImageContainerHeight(context.parents, block)
            val autoFloatStyle = styleStack.lastOrNull { style ->
                style.float != EpubFloat.NONE && style.widthEm == null && style.widthFraction == null
            }
            val intrinsicFloatWidth = autoFloatStyle?.let { floatStyle ->
                val safeStart = block.textStart.coerceIn(0, body.length)
                val text = body.substring(safeStart, block.textEnd.coerceIn(safeStart, body.length))
                measure.charWidths(text, block.kind == EpubLayoutBlockKind.HEADING).sum() +
                    floatStyle.paddingLeftEm.toPx() + floatStyle.paddingRightEm.toPx() +
                    floatStyle.borderLeftPx() + floatStyle.borderRightPx()
            }
            val geometry = resolveGeometry(styleStack, intrinsicFloatWidth)
            val textBackgroundArgb = styleStack.asReversed()
                .firstNotNullOfOrNull { style -> style.backgroundColorArgb?.let(::mappedBackground) }
                ?.let { color -> EpubThemeColors.composite(color, spec.themeBackgroundArgb) }
                ?: spec.themeBackgroundArgb
            if (block.style.breakBefore || openingContainers.any { it.style.breakBefore }) {
                state.closePage(force = false)
            }

            openingContainers.filter { it.style.avoidBreakInside }.forEach { container ->
                val endIndex = contexts.indexOfLast { it.block.textEnd <= container.textEnd }
                if (endIndex >= blockIndex) {
                    state.keepTogether(
                        min(
                            estimateRangeAdvance(
                                contexts = contexts,
                                startIndex = blockIndex,
                                endIndex = endIndex,
                                body = body,
                                inlineMarkers = inlineMarkers,
                                syntax = syntax,
                                bundle = bundle,
                                imagesByOffset = imagesByOffset
                            ),
                            spec.contentLineStep * MIN_BLOCK_START_LINES
                        )
                    )
                }
            }
            if (block.style.avoidBreakInside) {
                state.keepTogether(
                    min(
                        blockLeadingAdvance(block) +
                            estimateBlockAdvance(
                                body, block, geometry, imageContainerHeight,
                                inlineMarkers, syntax, bundle, imagesByOffset
                            ) + blockTrailingAdvance(block),
                        spec.contentLineStep * MIN_BLOCK_START_LINES
                    )
                )
            }
            if (block.kind == EpubLayoutBlockKind.HEADING || block.style.avoidBreakAfter) {
                // Keep a heading with at least one following body line. Estimating the whole next
                // block would make long paragraphs exceed a page and disable the rule entirely.
                state.keepTogether(
                    blockLeadingAdvance(block) + spec.titleLineStep +
                        blockTrailingAdvance(block) + spec.contentLineStep
                )
            }

            openingContainers.forEach { container ->
                state.addSpacing(container.style.marginTopEm.toPx(), collapse = true)
                state.addSpacing(container.style.borderTopPx() + container.style.paddingTopEm.toPx(), keepAtPageTop = true)
            }
            state.addSpacing(blockTopGap(block), collapse = true)
            state.addSpacing(block.style.borderTopPx() + block.style.paddingTopEm.toPx(), keepAtPageTop = true)

            when {
                galleryContexts != null && galleryParent != null -> {
                    layoutImageGallery(
                        state = state,
                        blocks = galleryContexts.map(BlockContext::block),
                        sources = imagesByOffset,
                        geometry = geometry,
                        galleryStyle = galleryParent.style
                    )
                    consumedThrough = blockIndex + galleryContexts.lastIndex
                }
                block.kind == EpubLayoutBlockKind.IMAGE ||
                    block.kind == EpubLayoutBlockKind.SEPARATOR -> {
                    val source = imagesByOffset[block.textStart]
                    if (source != null) {
                        layoutImage(state, source, block, geometry, imageContainerHeight)
                    } else if (block.textEnd > block.textStart) {
                        layoutTextBlock(
                            state, body, block, geometry, inlineMarkers, syntax, bundle, textBackgroundArgb
                        )
                    } else {
                        layoutRule(state, block, geometry)
                    }
                }
                block.kind == EpubLayoutBlockKind.PARAGRAPH ||
                    block.kind == EpubLayoutBlockKind.HEADING ||
                    block.kind == EpubLayoutBlockKind.QUOTE ||
                    block.kind == EpubLayoutBlockKind.LIST_ITEM ->
                    layoutTextBlock(
                        state, body, block, geometry, inlineMarkers, syntax, bundle, textBackgroundArgb
                    )
                else -> Unit
            }

            state.addSpacing(
                trailingBlock.style.paddingBottomEm.toPx() + trailingBlock.style.borderBottomPx()
            )
            state.addSpacing(blockBottomGap(trailingBlock), collapse = true)
            closingContainers.forEach { container ->
                state.addSpacing(container.style.paddingBottomEm.toPx() + container.style.borderBottomPx())
                state.addSpacing(container.style.marginBottomEm.toPx(), collapse = true)
            }
            if (trailingBlock.style.breakAfter) state.closePage(force = false)
        }

        state.closePage(force = true)
        val pages = decoratePages(state.pages, bundle, containers, contentBlocks)
        return TextChapter(chapterIndex, title, pages, body.length)
    }

    private fun layoutTextBlock(
        state: LayoutState,
        body: String,
        block: EpubLayoutBlock,
        geometry: BlockGeometry,
        inlineMarkers: List<InlineMarkerReservation>,
        syntax: SyntaxStyleMap,
        bundle: EpubLayoutChapterBundle,
        actualBackgroundArgb: Int = spec.themeBackgroundArgb
    ) {
        val start = block.textStart.coerceIn(0, body.length)
        val end = block.textEnd.coerceIn(start, body.length)
        if (start >= end) return
        val text = body.substring(start, end)
        val isTitle = block.kind == EpubLayoutBlockKind.HEADING
        val layoutText = buildLayoutText(text, start, inlineMarkers)
        val clusters = styledClusters(
            layoutText, start, block, isTitle, syntax, bundle, actualBackgroundArgb
        )
        val indent = if (isTitle) 0f else (block.style.textIndentEm ?: spec.indentCharCount) * measure.indentColumnWidth()
        val lines = wrap(clusters, geometry.width, indent)
        val lineMetrics = lines.map { line -> resolveLineMetrics(line) }
        val orphanCount = min(block.style.orphans, lines.size)
        if (orphanCount > 1) {
            state.keepTogether(lineMetrics.take(orphanCount).sumOf { it.lineStep.toDouble() }.toFloat())
        }
        lines.forEachIndexed { lineIndex, lineClusters ->
            cancellationCheck()
            if (lineClusters.isEmpty()) return@forEachIndexed
            val metrics = lineMetrics[lineIndex]
            val widowCount = min(block.style.widows, lines.size)
            if (widowCount > 1 && lineIndex == lines.size - widowCount) {
                state.keepTogether(lineMetrics.takeLast(widowCount).sumOf { it.lineStep.toDouble() }.toFloat())
            }
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
        bundle: EpubLayoutChapterBundle,
        actualBackgroundArgb: Int = spec.themeBackgroundArgb
    ): List<StyledCluster> {
        val result = ArrayList<StyledCluster>()
        var index = 0
        var nextSpanIndex = 0
        while (index < layoutText.text.length) {
            if (index and 0xFF == 0) cancellationCheck()
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
                while (nextSpanIndex < block.spans.size &&
                    absoluteOffset >= block.spans[nextSpanIndex].textEnd
                ) {
                    nextSpanIndex++
                }
                nextSpanIndex.takeIf { candidate ->
                    candidate < block.spans.size &&
                        absoluteOffset in block.spans[candidate].textStart until block.spans[candidate].textEnd
                } ?: -1
            } else {
                -1
            }
            val span = block.spans.getOrNull(spanIndex)
            val epubStyle = span?.style ?: block.style
            val inlineBoxStyle = epubStyle.takeIf {
                span?.elements?.isNotEmpty() == true && it.hasInlineDecoration()
            }
            val ruby = span?.rubyText?.takeIf(String::isNotBlank)
            val resolved = resolveTextStyle(
                epubStyle,
                isTitle,
                bundle,
                absoluteOffset.takeIf { it >= 0 }?.let(syntax::at),
                actualBackgroundArgb
            )
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
                linkHref = span?.linkHref,
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
            cancellationCheck()
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
            // 中文避头尾：句号、逗号、右引号等不能落到下一行开头，左括号、左引号
            // 不能孤零零留在行尾。闭合标点允许轻微越过测量宽度，比另起一行自然得多。
            while (end < clusters.size && clusters[end].startsWithForbiddenPunctuation()) end++
            while (end > start + 1 && clusters[end - 1].endsWithOpeningPunctuation()) end--
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
                inlineMarkerOffset = cluster.marker?.charOffset,
                linkHref = cluster.linkHref
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
        val mappedBackground = mappedBackground(style.backgroundColorArgb)
        TextBlockDecoration(
            left = fragment.left,
            top = top,
            right = fragment.right,
            bottom = bottom,
            backgroundColorArgb = mappedBackground,
            backgroundImagePath = style.backgroundImageHref?.let(bundle.resourcePaths::get),
            backgroundSizeMode = style.backgroundSizeMode.toPageMode(),
            backgroundSizeWidth = style.backgroundSizeWidthEm.toPx(),
            backgroundSizeHeight = style.backgroundSizeHeightEm.toPx(),
            backgroundRepeatX = style.backgroundRepeatX,
            backgroundRepeatY = style.backgroundRepeatY,
            backgroundPositionX = style.backgroundPositionX,
            backgroundPositionY = style.backgroundPositionY,
            borderColorArgb = mappedForeground(style.borderColorArgb, mappedBackground),
            borderWidth = style.borderWidthEm.toPx(),
            borderTopColorArgb = mappedForeground(style.borderTopColorArgb, mappedBackground),
            borderRightColorArgb = mappedForeground(style.borderRightColorArgb, mappedBackground),
            borderBottomColorArgb = mappedForeground(style.borderBottomColorArgb, mappedBackground),
            borderLeftColorArgb = mappedForeground(style.borderLeftColorArgb, mappedBackground),
            borderTopWidth = style.borderTopPx(),
            borderRightWidth = style.borderRightPx(),
            borderBottomWidth = style.borderBottomPx(),
            borderLeftWidth = style.borderLeftPx(),
            borderRadius = style.borderRadiusEm.toPx(),
            borderTopLeftRadius = (style.borderTopLeftRadiusEm ?: style.borderRadiusEm).toPx(),
            borderTopRightRadius = (style.borderTopRightRadiusEm ?: style.borderRadiusEm).toPx(),
            borderBottomRightRadius = (style.borderBottomRightRadiusEm ?: style.borderRadiusEm).toPx(),
            borderBottomLeftRadius = (style.borderBottomLeftRadiusEm ?: style.borderRadiusEm).toPx(),
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
        geometry: BlockGeometry,
        containerHeight: Float?
    ) {
        val style = block.style
        val size = resolveImageSize(source, style, geometry, containerHeight)
        var width = size.first
        var height = size.second
        var occupiedHeight = max(height, containerHeight ?: height).coerceAtMost(spec.visibleHeight)
        val remaining = state.remainingHeight()
        // 普通插图若只差一点放不下，按比例缩进当前页；不要把整张图推到下一页，
        // 在本页留下接近半屏的空白。100vh/显式整页容器仍保持独立页面。
        if (state.hasContent() && containerHeight == null && occupiedHeight > remaining &&
            remaining >= spec.contentLineStep * MIN_INLINE_IMAGE_REMAINING_LINES
        ) {
            val scale = (remaining / occupiedHeight).coerceIn(0.2f, 1f)
            width *= scale
            height *= scale
            occupiedHeight = height
        }
        state.prepareForLine(occupiedHeight)
        val startX = when {
            style.float == EpubFloat.START -> geometry.left
            style.float == EpubFloat.END -> geometry.right - width
            style.textAlign == EpubTextAlign.START -> geometry.left
            style.textAlign == EpubTextAlign.END -> geometry.right - width
            else -> geometry.left + (geometry.width - width) / 2f
        }
        // Flex-like full-page illustration containers center the image in their 100vh box. Keep
        // that occupied box in pagination while drawing only the aspect-correct bitmap inside it.
        val imageOffsetY = ((occupiedHeight - height) / 2f).coerceAtLeast(0f)
        val lineTop = state.durY + imageOffsetY
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
            lineStep = (occupiedHeight - imageOffsetY).coerceAtLeast(height)
        )
    }

    private fun layoutImageGallery(
        state: LayoutState,
        blocks: List<EpubLayoutBlock>,
        sources: Map<Int, InlineImageSource>,
        geometry: BlockGeometry,
        galleryStyle: EpubComputedStyle
    ) {
        if (blocks.isEmpty()) return
        val declaredFraction = blocks.firstNotNullOfOrNull { it.style.widthFraction }
        val inferredColumns = declaredFraction
            ?.takeIf { it > 0f }
            ?.let { kotlin.math.round(1f / it).toInt().coerceIn(1, 8) }
        val columns = (galleryStyle.layoutColumns ?: inferredColumns ?: when (galleryStyle.layoutMode) {
            EpubLayoutMode.FLEX -> blocks.size.coerceAtMost(6)
            EpubLayoutMode.GRID -> 2
            EpubLayoutMode.FLOW -> 1
        }).coerceIn(1, blocks.size)
        val gap = (galleryStyle.layoutGapEm ?: DEFAULT_GALLERY_GAP_EM).toPx().coerceAtLeast(0f)
        val cellWidth = ((geometry.width - gap * (columns - 1)) / columns).coerceAtLeast(1f)

        blocks.chunked(columns).forEach { row ->
            val sizes = row.map { block ->
                val source = sources[block.textStart]
                val aspect = source?.let {
                    (it.pixelWidth.toFloat() / it.pixelHeight.coerceAtLeast(1))
                        .coerceIn(MIN_IMAGE_ASPECT, MAX_IMAGE_ASPECT)
                } ?: DEFAULT_MISSING_IMAGE_ASPECT
                val requested = block.style.widthFraction?.times(geometry.width)
                    ?: block.style.widthEm.toPx().takeIf { it > 0f }
                    ?: cellWidth
                val width = requested.coerceIn(1f, cellWidth)
                width to (width / aspect).coerceAtMost(spec.visibleHeight * MAX_GALLERY_ROW_HEIGHT_FRACTION)
            }
            var rowHeight = sizes.maxOf { it.second }.coerceAtLeast(spec.contentLineStep)
            val remaining = state.remainingHeight()
            val rowScale = if (state.hasContent() && rowHeight > remaining &&
                remaining >= spec.contentLineStep * MIN_INLINE_IMAGE_REMAINING_LINES
            ) {
                (remaining / rowHeight).coerceIn(0.25f, 1f)
            } else {
                1f
            }
            rowHeight *= rowScale
            state.prepareForLine(rowHeight)
            val lineTop = state.durY
            val placements = row.mapIndexed { index, block ->
                val source = sources[block.textStart]
                val width = sizes[index].first * rowScale
                val height = sizes[index].second * rowScale
                val cellLeft = geometry.left + index * (cellWidth + gap)
                PositionedInlineImagePlacement(
                    imagePath = source?.imagePath.orEmpty(),
                    left = cellLeft + (cellWidth - width) / 2f,
                    topOffset = (rowHeight - height) / 2f,
                    width = width,
                    height = height,
                    altText = source?.altText ?: block.altText
                )
            }
            val start = row.first().textStart
            val end = row.last().textEnd
            state.addLine(
                TextLine(
                    text = "",
                    columns = emptyList(),
                    lineTop = lineTop,
                    lineBase = lineTop + rowHeight,
                    lineBottom = lineTop + rowHeight,
                    startX = geometry.left,
                    isTitle = false,
                    isParagraphEnd = true,
                    chapterPosition = start,
                    charLength = (end - start).coerceAtLeast(0),
                    inlineImages = placements
                ),
                lineStep = rowHeight + gap
            )
        }
    }

    private fun resolveImageSize(
        source: InlineImageSource,
        style: EpubComputedStyle,
        geometry: BlockGeometry,
        containerHeight: Float? = null
    ): Pair<Float, Float> {
        val aspect = (source.pixelWidth.toFloat() / source.pixelHeight.coerceAtLeast(1))
            .coerceIn(MIN_IMAGE_ASPECT, MAX_IMAGE_ASPECT)
        val intrinsicWidth = source.pixelWidth.coerceAtLeast(1) *
            (spec.contentFontSizePx / CSS_ROOT_FONT_PX).coerceAtLeast(1f)
        val requestedWidth = requestedWidth(style, geometry.width)
        val requestedHeight = style.heightEm?.toPx()
            ?: style.heightViewportFraction?.times(spec.visibleHeight)
        val widthLimit = min(
            geometry.width,
            requestedMaxWidth(style, geometry.width) ?: geometry.width
        )
        val fullPageImage = style.widthFraction?.let { it >= 0.9f } == true ||
            style.heightViewportFraction?.let { it >= 0.8f } == true ||
            aspect <= 0.85f && (requestedWidth ?: widthLimit) >= geometry.width * 0.9f
        val heightLimit = requestedMaxHeight(style, containerHeight)
            ?: containerHeight
            ?: spec.visibleHeight * if (fullPageImage) 1f else MAX_IMAGE_HEIGHT_FRACTION

        // A large class of illustrated EPUBs declares both width:100% and height:100% on a page
        // image. Treat those as a containing box, like object-fit:contain, rather than stretching
        // the bitmap independently in both axes. The painter can then draw 1:1 into this box
        // without the visibly squeezed pages produced by the old width/height calculation.
        var width = when {
            requestedWidth != null -> requestedWidth
            requestedHeight != null -> requestedHeight * aspect
            else -> min(widthLimit, intrinsicWidth)
        }
        var height = when {
            requestedHeight != null && requestedWidth == null -> requestedHeight
            else -> width / aspect
        }
        if (requestedWidth != null && requestedHeight != null) {
            val scale = min(requestedWidth / aspect, requestedHeight)
            height = scale
            width = scale * aspect
        }
        if (width > widthLimit) {
            width = widthLimit
            height = width / aspect
        }
        if (height > heightLimit) {
            height = heightLimit
            width = min(widthLimit, height * aspect)
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
            cancellationCheck()
            val context = contexts[index]
            val block = context.block
            context.parents.filter { it.textStart == block.textStart }.forEach { container ->
                advance += containerLeadingAdvance(container)
            }
            val parentStyles = context.parents.map(EpubLayoutBlock::style)
            val imageContainerHeight = explicitImageContainerHeight(context.parents, block)
            val geometry = resolveGeometry(parentStyles + block.style)
            advance += blockLeadingAdvance(block)
            advance += estimateBlockAdvance(
                body,
                block,
                geometry,
                imageContainerHeight,
                inlineMarkers,
                syntax,
                bundle,
                imagesByOffset
            )
            advance += blockTrailingAdvance(block)
            context.parents.filter { it.textEnd == block.textEnd }.forEach { container ->
                advance += containerTrailingAdvance(container)
            }
            if (advance > spec.visibleHeight) return advance
        }
        return advance
    }

    private fun estimateBlockAdvance(
        body: String,
        block: EpubLayoutBlock,
        geometry: BlockGeometry,
        imageContainerHeight: Float?,
        inlineMarkers: List<InlineMarkerReservation>,
        syntax: SyntaxStyleMap,
        bundle: EpubLayoutChapterBundle,
        imagesByOffset: Map<Int, InlineImageSource>
    ): Float = when (block.kind) {
        EpubLayoutBlockKind.IMAGE, EpubLayoutBlockKind.SEPARATOR -> {
            val source = imagesByOffset[block.textStart]
            when {
                source != null -> max(
                    resolveImageSize(
                        source, block.style, geometry, imageContainerHeight
                    ).second,
                    imageContainerHeight ?: 0f
                ).coerceAtMost(spec.visibleHeight)
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
            val defaultStep = defaultLineStep(cluster.style.measureStyle.isTitle)
            val requestedTextStep = when (spec.publisherStyleMode) {
                PublisherStyleMode.RESPECT -> cluster.style.lineHeightEm?.let { fontPx * it } ?: defaultStep
                PublisherStyleMode.SMART -> cluster.style.lineHeightEm?.let { publisherLineHeight ->
                    val userScale = (spec.contentLineStep / spec.contentFontSizePx.coerceAtLeast(1f)) /
                        DEFAULT_READER_LINE_HEIGHT
                    fontPx * publisherLineHeight * userScale.coerceIn(
                        MIN_LINE_HEIGHT_FACTOR,
                        MAX_LINE_HEIGHT_FACTOR
                    )
                } ?: defaultStep
                PublisherStyleMode.TAKE_OVER -> defaultStep
            }
            requestedStep = max(requestedStep, requestedTextStep + topExtra + bottomExtra + rubyBandHeight)
        }
        val textHeight = ascent + descent
        return ResolvedLineMetrics(ascent, textHeight, max(textHeight, requestedStep), rubyBaseline)
    }

    private fun blockLeadingAdvance(block: EpubLayoutBlock): Float =
        blockTopGap(block) + block.style.borderTopPx() + block.style.paddingTopEm.toPx()

    private fun blockTrailingAdvance(block: EpubLayoutBlock): Float =
        block.style.paddingBottomEm.toPx() + block.style.borderBottomPx() + blockBottomGap(block)

    private fun containerLeadingAdvance(container: EpubLayoutBlock): Float =
        container.style.marginTopEm.toPx() + container.style.borderTopPx() +
            container.style.paddingTopEm.toPx()

    private fun containerTrailingAdvance(container: EpubLayoutBlock): Float =
        container.style.paddingBottomEm.toPx() + container.style.borderBottomPx() +
            container.style.marginBottomEm.toPx()

    private fun decoratePages(
        pages: List<TextPage>,
        bundle: EpubLayoutChapterBundle,
        containers: List<EpubLayoutBlock>,
        contentBlocks: List<EpubLayoutBlock>
    ): List<TextPage> {
        // 普通正文的页面纸色归用户；封面/卷首页的 body 背景本身就是内容，必须保留。
        val bodyBackground = bundle.document.bodyStyle.backgroundImageHref
            ?.takeIf { bundle.document.immersivePage }
            ?.let(bundle.resourcePaths::get)
        val candidates = (containers + contentBlocks)
            .filter { it.style.hasDecoration() && it.textEnd > it.textStart }
            .sortedWith(compareByDescending<EpubLayoutBlock> { it.textEnd - it.textStart }.thenBy { it.orderIndex })
        val chapterStart = contentBlocks.minOfOrNull(EpubLayoutBlock::textStart) ?: 0
        val chapterEnd = contentBlocks.maxOfOrNull(EpubLayoutBlock::textEnd) ?: bundle.document.textLength
        val publisherCanvases = if (spec.preferReaderBackground) {
            candidates.asSequence()
                .filter { it.isChapterCanvas(chapterStart, chapterEnd) }
                .map(EpubLayoutBlock::orderIndex)
                .toSet()
        } else {
            emptySet()
        }
        val pageBackgroundColor = bundle.document.bodyStyle.backgroundColorArgb
            ?.takeIf { bundle.document.immersivePage }
            ?.let(::mappedBackground)
        val resolvedBodyBackground = bodyBackground
        return pages.map { page ->
            cancellationCheck()
            val pageLimit = spec.visibleHeight + if (page.index == 0) {
                (if (immersivePage || firstPageHidesReaderHeader) spec.immersiveExtraTopPx else 0f) +
                    (if (immersivePage) spec.immersiveExtraBottomPx else 0f)
            } else {
                0f
            }
            val positionedLines = page.lines.filter { it.charLength > 0 }
            val pageStart = positionedLines.minOfOrNull(TextLine::chapterPosition) ?: page.chapterPosition
            val pageEnd = positionedLines.maxOfOrNull { it.chapterPosition + it.charLength } ?: pageStart
            val decorations = candidates.asSequence()
                .filter { block -> block.textStart < pageEnd && block.textEnd > pageStart }
                .mapNotNull { block ->
                    cancellationCheck()
                    val lines = positionedLines.filter { line ->
                        line.chapterPosition < block.textEnd &&
                            line.chapterPosition + line.charLength > block.textStart
                    }
                    if (lines.isEmpty()) return@mapNotNull null
                    val style = block.style
                    val suppressCanvasPaint = block.orderIndex in publisherCanvases ||
                        block.isChapterCanvas(chapterStart, chapterEnd) && !block.style.hasBorder()
                    val mappedBackground = mappedBackground(style.backgroundColorArgb)
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
                            pageLimit
                        },
                        backgroundColorArgb = mappedBackground.takeUnless { suppressCanvasPaint },
                        backgroundImagePath = style.backgroundImageHref
                            ?.takeUnless { suppressCanvasPaint }
                            ?.let(bundle.resourcePaths::get),
                        backgroundSizeMode = style.backgroundSizeMode.toPageMode(),
                        backgroundSizeWidth = style.backgroundSizeWidthEm.toPx(),
                        backgroundSizeHeight = style.backgroundSizeHeightEm.toPx(),
                        backgroundRepeatX = style.backgroundRepeatX,
                        backgroundRepeatY = style.backgroundRepeatY,
                        backgroundPositionX = style.backgroundPositionX,
                        backgroundPositionY = style.backgroundPositionY,
                        borderColorArgb = mappedForeground(style.borderColorArgb, mappedBackground),
                        borderWidth = style.borderWidthEm.toPx(),
                        borderTopColorArgb = mappedForeground(style.borderTopColorArgb, mappedBackground),
                        borderRightColorArgb = mappedForeground(style.borderRightColorArgb, mappedBackground),
                        borderBottomColorArgb = mappedForeground(style.borderBottomColorArgb, mappedBackground),
                        borderLeftColorArgb = mappedForeground(style.borderLeftColorArgb, mappedBackground),
                        borderTopWidth = style.borderTopPx(),
                        borderRightWidth = style.borderRightPx(),
                        borderBottomWidth = style.borderBottomPx(),
                        borderLeftWidth = style.borderLeftPx(),
                        borderRadius = style.borderRadiusEm.toPx(),
                        borderTopLeftRadius = (style.borderTopLeftRadiusEm ?: style.borderRadiusEm).toPx(),
                        borderTopRightRadius = (style.borderTopRightRadiusEm ?: style.borderRadiusEm).toPx(),
                        borderBottomRightRadius = (style.borderBottomRightRadiusEm ?: style.borderRadiusEm).toPx(),
                        borderBottomLeftRadius = (style.borderBottomLeftRadiusEm ?: style.borderRadiusEm).toPx(),
                        boxShadows = style.textBoxShadows(),
                        opacity = style.opacity,
                        // 分页片段在断口补齐上下描边，避免卡片像被屏幕硬生生裁断；
                        // padding 仍只在真实首尾结算，所以不会凭空多出一圈内边距。
                        drawTopEdge = true,
                        drawBottomEdge = true
                    )
                }
                .toList()
            TextPage(
                index = page.index,
                lines = page.lines,
                chapterPosition = page.chapterPosition,
                charLength = page.charLength,
                height = page.height,
                decorations = decorations,
                backgroundColorArgb = pageBackgroundColor,
                backgroundImagePath = resolvedBodyBackground,
                backgroundOpacity = bundle.document.bodyStyle.opacity,
                immersive = immersivePage,
                hideHeader = page.index == 0 && firstPageHidesReaderHeader,
                trailingGap = page.trailingGap
            )
        }
    }

    private fun resolveGeometry(
        styles: List<EpubComputedStyle>,
        intrinsicFloatWidth: Float? = null
    ): BlockGeometry {
        var left = 0f
        var right = spec.visibleWidth
        var boxLeft = left
        var boxRight = right
        styles.forEach { style ->
            val marginLeft = style.marginLeftEm.toPx()
            val marginRight = style.marginRightEm.toPx()
            val available = (right - left - marginLeft - marginRight).coerceAtLeast(1f)
            val requested = requestedWidth(style, available)
                ?: intrinsicFloatWidth?.takeIf { style.float != EpubFloat.NONE }
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

    private fun requestedMaxHeight(style: EpubComputedStyle, containerHeight: Float?): Float? =
        style.maxHeightViewportFraction?.times(spec.visibleHeight)
            ?: style.maxHeightFraction?.times(containerHeight ?: spec.visibleHeight)
            ?: style.maxHeightEm.toPx().takeIf { it > 0f }

    private fun explicitImageContainerHeight(
        parents: List<EpubLayoutBlock>,
        image: EpubLayoutBlock
    ): Float? = parents
        .asReversed()
        .firstOrNull { container ->
            container.textStart == image.textStart && container.textEnd == image.textEnd
        }
        ?.style
        ?.let { style ->
            style.heightViewportFraction?.times(spec.visibleHeight)
                ?: style.heightEm.toPx().takeIf { it > 0f }
        }
        ?.coerceIn(1f, spec.visibleHeight)

    private fun resolveTextStyle(
        epub: EpubComputedStyle,
        isTitle: Boolean,
        bundle: EpubLayoutChapterBundle,
        syntax: ReaderSyntaxStyleSpan?,
        inheritedBackgroundArgb: Int
    ): ResolvedTextStyle {
        val fontSizeEm = when (spec.publisherStyleMode) {
            PublisherStyleMode.TAKE_OVER -> if (isTitle) DEFAULT_HEADING_EM else 1f
            else -> epub.fontSizeEm ?: if (isTitle) DEFAULT_HEADING_EM else 1f
        }
        val baseSize = baseFontSize(isTitle).coerceAtLeast(1f)
        val sizeScale = (spec.contentFontSizePx * fontSizeEm / baseSize).coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)
        val publisherFamily = epub.fontFamily
        val replaceDominantBodyFont = spec.publisherStyleMode != PublisherStyleMode.RESPECT &&
            !isTitle && publisherFamily?.lowercase() == dominantBodyFontFamily
        val family = publisherFamily.takeUnless { replaceDominantBodyFont }
        val fontPath = family?.let { name ->
            bundle.resolveFontPath(
                family = name,
                weight = epub.fontWeight ?: 400,
                italic = epub.italic
            )
        }
        val adaptedBackground = EpubThemeColors.background(epub.backgroundColorArgb, spec.darkTheme)
        val actualBackground = adaptedBackground
            ?.let { EpubThemeColors.composite(it, inheritedBackgroundArgb) }
            ?: inheritedBackgroundArgb
        val adaptedColor = epub.colorArgb?.let { publisherColor ->
            // 整页插画上的标题色通常是出版商针对图片手工挑选的（样书为铂金色），
            // 图片底色无法靠单一 HSL 值推断，因此特殊页与“完全尊重”模式保持原色。
            if (immersivePage || spec.publisherStyleMode == PublisherStyleMode.RESPECT) {
                publisherColor
            } else {
                EpubThemeColors.foreground(
                    color = publisherColor,
                    actualBackground = actualBackground,
                    fallback = spec.themeTextArgb
                )
            }
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
                bold = epub.fontWeight?.let { it >= 600 } ?: (syntax?.bold == true),
                italic = epub.italic || syntax?.italic == true,
                letterSpacingEm = epub.letterSpacingEm ?: 0f
            ),
            // Publisher styling wins property-by-property. User syntax highlighting only fills
            // unspecified slots, so dialogue rules cannot repaint an EPUB badge/font/background.
            colorArgb = adaptedColor ?: syntax?.colorArgb,
            backgroundArgb = adaptedBackground ?: syntax?.backgroundArgb,
            underline = epub.underline || syntax?.underline == true,
            strikethrough = epub.strikethrough || syntax?.strikethrough == true,
            // Replacing the dominant body face with the reader font must not accidentally hand
            // ownership to a syntax rule's unrelated font. The publisher still owns this slot;
            // only the glyph source is substituted.
            syntaxFont = if (publisherFamily != null) {
                ReaderSyntaxFont.INHERIT
            } else {
                syntax?.font ?: ReaderSyntaxFont.INHERIT
            },
            syntaxFontAssetId = if (publisherFamily != null) null else syntax?.fontAssetId,
            baselineShiftPx = verticalShift,
            lineHeightEm = epub.lineHeightEm,
            opacity = epub.opacity
        )
    }

    private fun effectiveStyle(style: EpubComputedStyle): EpubComputedStyle {
        if (spec.publisherStyleMode != PublisherStyleMode.TAKE_OVER) return style
        return style.copy(
            fontFamily = null,
            fontSizeEm = null,
            colorArgb = null,
            backgroundColorArgb = null,
            backgroundImageHref = null,
            lineHeightEm = null,
            letterSpacingEm = null,
            marginTopEm = null,
            marginRightEm = null,
            marginBottomEm = null,
            marginLeftEm = null,
            paddingTopEm = null,
            paddingRightEm = null,
            paddingBottomEm = null,
            paddingLeftEm = null,
            borderWidthEm = null,
            borderColorArgb = null,
            borderTopWidthEm = null,
            borderRightWidthEm = null,
            borderBottomWidthEm = null,
            borderLeftWidthEm = null,
            borderTopColorArgb = null,
            borderRightColorArgb = null,
            borderBottomColorArgb = null,
            borderLeftColorArgb = null,
            borderRadiusEm = null,
            borderTopLeftRadiusEm = null,
            borderTopRightRadiusEm = null,
            borderBottomRightRadiusEm = null,
            borderBottomLeftRadiusEm = null,
            boxShadows = emptyList(),
            widthEm = null,
            widthFraction = null,
            maxWidthEm = null,
            maxWidthFraction = null,
            layoutMode = EpubLayoutMode.FLOW,
            layoutColumns = null,
            layoutGapEm = null,
            centerBlock = false
        )
    }

    private fun mappedBackground(color: Int?): Int? =
        EpubThemeColors.background(color, spec.darkTheme)

    private fun mappedForeground(color: Int?, background: Int?): Int? = color?.let {
        EpubThemeColors.foreground(it, background ?: spec.themeBackgroundArgb, spec.themeTextArgb)
    }

    private fun defaultTopGap(block: EpubLayoutBlock): Float = when (block.kind) {
        EpubLayoutBlockKind.HEADING -> spec.titleTopSpacing
        else -> 0f
    }

    private fun defaultBottomGap(block: EpubLayoutBlock): Float = when (block.kind) {
        EpubLayoutBlockKind.HEADING -> spec.titleBottomSpacing
        else -> spec.paragraphSpacing
    }

    private fun blockTopGap(block: EpubLayoutBlock): Float = publisherBlockGap(
        publisherEm = block.style.marginTopEm,
        fallback = defaultTopGap(block)
    )

    private fun blockBottomGap(block: EpubLayoutBlock): Float = publisherBlockGap(
        publisherEm = block.style.marginBottomEm,
        fallback = defaultBottomGap(block)
    )

    private fun publisherBlockGap(publisherEm: Float?, fallback: Float): Float {
        publisherEm ?: return fallback
        return when (spec.publisherStyleMode) {
            PublisherStyleMode.RESPECT -> publisherEm.toPx()
            PublisherStyleMode.SMART -> spec.paragraphSpacing *
                (publisherEm / DEFAULT_PUBLISHER_PARAGRAPH_EM).coerceIn(0f, MAX_PARAGRAPH_GAP_FACTOR)
            PublisherStyleMode.TAKE_OVER -> fallback
        }
    }

    private fun baseFontSize(isTitle: Boolean): Float =
        if (isTitle) spec.titleFontSizePx else spec.contentFontSizePx

    private fun defaultLineStep(isTitle: Boolean): Float =
        if (isTitle) spec.titleLineStep else spec.contentLineStep

    private fun EpubBackgroundSizeMode.toPageMode(): BackgroundSizeMode = when (this) {
        EpubBackgroundSizeMode.AUTO -> BackgroundSizeMode.AUTO
        EpubBackgroundSizeMode.COVER -> BackgroundSizeMode.COVER
        EpubBackgroundSizeMode.CONTAIN -> BackgroundSizeMode.CONTAIN
        EpubBackgroundSizeMode.STRETCH -> BackgroundSizeMode.STRETCH
        EpubBackgroundSizeMode.EXPLICIT -> BackgroundSizeMode.EXPLICIT
    }

    private fun Float?.toPx(): Float = (this ?: 0f) * spec.contentFontSizePx

    private fun EpubComputedStyle.borderTopPx(): Float = (borderTopWidthEm ?: borderWidthEm).toPx()

    private fun EpubComputedStyle.borderRightPx(): Float = (borderRightWidthEm ?: borderWidthEm).toPx()

    private fun EpubComputedStyle.borderBottomPx(): Float = (borderBottomWidthEm ?: borderWidthEm).toPx()

    private fun EpubComputedStyle.borderLeftPx(): Float = (borderLeftWidthEm ?: borderWidthEm).toPx()

    private fun EpubComputedStyle.hasDecoration(): Boolean =
        backgroundColorArgb != null || backgroundImageHref != null || hasBorder() || boxShadows.isNotEmpty()

    private fun EpubLayoutBlock.isChapterCanvas(chapterStart: Int, chapterEnd: Int): Boolean =
        kind == EpubLayoutBlockKind.CONTAINER && textStart <= chapterStart && textEnd >= chapterEnd &&
            ancestors.size <= MAX_CANVAS_ANCESTOR_DEPTH &&
            (style.widthFraction == null || style.widthFraction >= MIN_CANVAS_WIDTH_FRACTION)

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

    private fun StyledCluster.startsWithForbiddenPunctuation(): Boolean =
        text.firstOrNull() in FORBIDDEN_LINE_START

    private fun StyledCluster.endsWithOpeningPunctuation(): Boolean =
        text.lastOrNull() in FORBIDDEN_LINE_END

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
        inlineMarkerOffset = inlineMarkerOffset,
        linkHref = linkHref
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
        val linkHref: String?,
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

        fun hasContent(): Boolean = pendingLines.isNotEmpty()

        fun remainingHeight(): Float = (pageHeightLimit() - durY).coerceAtLeast(0f)

        fun addSpacing(
            spacing: Float,
            keepAtPageTop: Boolean = false,
            collapse: Boolean = false
        ) {
            if (spacing <= 0f || pendingLines.isEmpty() && !keepAtPageTop) return
            if (collapse && openGap > 0f) {
                durY += (spacing - openGap).coerceAtLeast(0f)
                openGap = max(openGap, spacing)
            } else {
                durY += spacing
                openGap = if (collapse) spacing else 0f
            }
        }

        fun keepTogether(height: Float) {
            val pageLimit = pageHeightLimit()
            if (height <= 0f || height > pageLimit || pendingLines.isEmpty()) return
            if (durY + height > pageLimit + HEIGHT_EPSILON) closePage(force = false)
        }

        fun prepareForLine(height: Float) {
            val pageLimit = pageHeightLimit()
            if (pendingLines.isNotEmpty() && durY + height > pageLimit + HEIGHT_EPSILON) {
                closePage(force = false)
            } else if (pendingLines.isEmpty() && durY + height > pageLimit + HEIGHT_EPSILON) {
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
                immersive = immersivePage,
                hideHeader = pages.isEmpty() && firstPageHidesReaderHeader,
                trailingGap = openGap
            )
            pendingLines.clear()
            durY = 0f
            openGap = 0f
        }

        private fun pageHeightLimit(): Float = spec.visibleHeight + if (pages.isEmpty()) {
            (if (immersivePage || firstPageHidesReaderHeader) spec.immersiveExtraTopPx else 0f) +
                (if (immersivePage) spec.immersiveExtraBottomPx else 0f)
        } else {
            0f
        }

        private fun bottomAlign(lines: List<TextLine>) {
            if (lines.size < 2) return
            val surplus = pageHeightLimit() - lines.last().lineBottom
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
        const val MIN_BLOCK_START_LINES = 2f
        const val MIN_INLINE_IMAGE_REMAINING_LINES = 3f
        const val DEFAULT_PUBLISHER_LINE_HEIGHT = 1.5f
        const val DEFAULT_READER_LINE_HEIGHT = 1.55f
        const val MIN_PUBLISHER_LINE_HEIGHT = 0.8f
        const val MAX_PUBLISHER_LINE_HEIGHT = 3f
        const val MIN_LINE_HEIGHT_FACTOR = 0.8f
        const val MAX_LINE_HEIGHT_FACTOR = 1.5f
        const val DEFAULT_PUBLISHER_PARAGRAPH_EM = 0.55f
        const val MAX_PARAGRAPH_GAP_FACTOR = 4f
        const val MIN_TEXT_SCALE = 0.5f
        const val MAX_TEXT_SCALE = 3f
        const val MAX_IMAGE_HEIGHT_FRACTION = 0.86f
        const val MAX_GALLERY_ROW_HEIGHT_FRACTION = 0.42f
        const val DEFAULT_GALLERY_GAP_EM = 0.35f
        const val DEFAULT_MISSING_IMAGE_ASPECT = 1.4f
        const val CSS_ROOT_FONT_PX = 16f
        const val MAX_CANVAS_ANCESTOR_DEPTH = 2
        const val MIN_CANVAS_WIDTH_FRACTION = 0.9f
        const val MIN_IMAGE_ASPECT = 0.15f
        const val MAX_IMAGE_ASPECT = 7f
        const val MAX_JUSTIFY_FRACTION = 0.35f
        const val RUBY_TEXT_SCALE = 0.5f
        const val RUBY_GAP_EM = 0.08f
        const val DEFAULT_RULE_COLOR = 0xFF888888.toInt()
        val CJK_RANGE = 0x3400..0x9FFF
        val CJK_EXT_A_RANGE = 0x20000..0x2FA1F
        val BREAK_PUNCTUATION = setOf('，', '。', '、', '；', '：', '！', '？', '”', '’', ',', '.', ';', ':', '!', '?')
        val FORBIDDEN_LINE_START = setOf(
            '，', '。', '、', '；', '：', '！', '？', '）', '》', '】', '〉', '〕',
            '」', '』', '”', '’', '…', '—', ',', '.', ';', ':', '!', '?', ')', ']', '}'
        )
        val FORBIDDEN_LINE_END = setOf(
            '（', '《', '【', '〈', '〔', '「', '『', '“', '‘', '(', '[', '{'
        )
    }
}
