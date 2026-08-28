package com.mozhi.reader.feature.reader.engine.epub

import com.mozhi.reader.core.epub.style.EpubStyle
import com.mozhi.reader.core.epub.style.EpubTextAlignValue
import com.mozhi.reader.core.epub.style.EpubVerticalAlignment
import com.mozhi.reader.core.epub.style.ResolvedLength
import com.mozhi.reader.core.epub.style.resolve
import com.mozhi.reader.feature.reader.engine.InlineMarkerReservation
import com.mozhi.reader.feature.reader.engine.PositionedInlineImagePlacement
import com.mozhi.reader.feature.reader.engine.TextBlockDecoration
import com.mozhi.reader.feature.reader.engine.TextColumn
import com.mozhi.reader.feature.reader.engine.TextLine
import com.mozhi.reader.feature.reader.engine.TextRubyPlacement
import kotlin.math.max
import kotlin.math.min

/**
 * Inline formatting context: turns one [EpubInlineFlowBox] into [TextLine]s. Line boxes narrow
 * around float bands, replaced elements participate with vertical alignment, decorated inline
 * elements produce per-line inline decorations, ruby annotations get a raised band.
 *
 * The cluster/punctuation/justification behavior is carried over from the proven v1 typesetter so
 * CJK line breaking does not regress.
 */
internal data class EpubSizedImage(
    val path: String,
    val width: Float,
    val height: Float,
    val altText: String,
    val alignment: EpubVerticalAlignment
)

internal class EpubInlineLayout(private val ctx: EpubLayoutContext) {

    class Result(val endY: Float, val lineCount: Int)

    fun layout(
        flow: EpubInlineFlowBox,
        cb: ContainingBlock,
        startY: Float,
        bfc: BfcState,
        output: FlowOutput,
        inheritedBackgroundArgb: Int,
        placeFloat: (EpubBox, Float) -> Float
    ): Result {
        val clusters = buildClusters(flow, cb.width, inheritedBackgroundArgb)
        if (clusters.isEmpty()) return Result(startY, 0)
        val blockStyle = flow.blockStyle
        val isHeading = flow.isHeading
        val paragraphId = output.nextParagraphId++
        val alignDeclared = "text-align" in blockStyle.appliedProperties
        val indentDeclared = "text-indent" in blockStyle.appliedProperties ||
            "duokan-text-indent" in blockStyle.appliedProperties
        val firstIndent = when {
            indentDeclared -> blockStyle.textIndent.resolve(cb.width) ?: 0f
            isHeading -> 0f
            else -> ctx.spec.indentCharCount * ctx.measure.indentColumnWidth()
        }
        val singleImageOnly = clusters.count { it.image != null } == 1 &&
            clusters.none { it.image == null && it.float == null && !it.forcedBreak && it.text.isNotBlank() }
        val align = when {
            singleImageOnly && !alignDeclared -> EpubTextAlignValue.CENTER
            else -> blockStyle.textAlign
        }

        var y = startY
        var index = 0
        var firstLine = true
        val firstLineIndex = output.lines.size
        var emitted = 0
        while (index < clusters.size) {
            ctx.cancellationCheck()
            // Floats at a line start are placed immediately so this very line wraps around them.
            while (index < clusters.size && clusters[index].float != null) {
                y = placeFloat(clusters[index].float!!, y)
                index++
            }
            if (index >= clusters.size) break
            val probeHeight = ctx.defaultLineStep(isHeading)
            var window = bfc.windowAt(y, probeHeight, cb)
            val indent = if (firstLine) firstIndent else 0f
            var limit = (window.second - window.first - indent).coerceAtLeast(1f)
            var end = fillLine(clusters, index, limit)
            // A float band can leave no usable room; drop below the nearest band and retry.
            if (end == index) {
                val bandBottom = bfc.bands
                    .filter { it.top < y + probeHeight && it.bottom > y }
                    .minOfOrNull { it.bottom }
                if (bandBottom != null && bandBottom > y) {
                    y = bandBottom
                    continue
                }
                end = index + 1
            }
            val lineClusters = clusters.subList(index, end).filter { it.float == null }
            index = end
            if (lineClusters.isEmpty() || lineClusters.all { it.forcedBreak && it.text.isEmpty() }) {
                if (lineClusters.isNotEmpty()) firstLine = false
                continue
            }
            val metrics = lineMetrics(lineClusters, isHeading)
            window = bfc.windowAt(y, metrics.lineStep, cb)
            limit = (window.second - window.first - indent).coerceAtLeast(1f)
            val lastLine = clusters.drop(index).all { it.float != null || it.forcedBreak }
            val justify = !lastLine && (align == EpubTextAlignValue.JUSTIFY ||
                !alignDeclared && ctx.spec.justifyContent)
            val placed = placeLine(
                clusters = lineClusters,
                contentLeft = window.first + indent,
                contentRight = window.second,
                align = align,
                justify = justify,
                metrics = metrics,
                lineTop = y,
                isParagraphEnd = lastLine
            )
            // Lines are appended as they are produced so floats keep document order around them.
            output.lines += FlowLine(
                line = placed,
                paragraphId = paragraphId,
                orphans = blockStyle.orphans.coerceIn(1, 10),
                widows = blockStyle.widows.coerceIn(1, 10),
                indexInParagraph = emitted,
                paragraphLineCount = 0,
                keepWithNext = isHeading
            )
            emitted++
            y += metrics.lineStep
            firstLine = false
        }
        for (lineIndex in firstLineIndex until output.lines.size) {
            if (output.lines[lineIndex].paragraphId == paragraphId) {
                output.lines[lineIndex].paragraphLineCount = emitted
            }
        }
        return Result(y, emitted)
    }

    /** Min-content and max-content widths for shrink-to-fit boxes. */
    fun intrinsicWidths(flow: EpubInlineFlowBox, inheritedBackgroundArgb: Int): Pair<Float, Float> {
        val clusters = buildClusters(flow, INTRINSIC_PROBE_WIDTH, inheritedBackgroundArgb)
        var maxContent = 0f
        var minContent = 0f
        var segment = 0f
        clusters.forEach { cluster ->
            if (cluster.float != null) return@forEach
            if (cluster.forcedBreak) {
                maxContent = max(maxContent, segment)
                segment = 0f
                return@forEach
            }
            segment += cluster.advanceWidth
            minContent = max(minContent, cluster.advanceWidth)
        }
        maxContent = max(maxContent, segment)
        return minContent to maxContent
    }

    // ---------------------------------------------------------------------------------------
    // Cluster building
    // ---------------------------------------------------------------------------------------

    private class Cluster(
        val text: String,
        val width: Float,
        val sourceOffset: Int,
        val sourceLength: Int,
        val marker: InlineMarkerReservation?,
        val style: ResolvedRunStyle,
        val requestedStep: Float,
        val linkHref: String?,
        val image: EpubSizedImage? = null,
        val float: EpubBox? = null,
        val forcedBreak: Boolean = false,
        val boxKey: Any? = null,
        val boxStyle: EpubStyle? = null,
        var startsBox: Boolean = false,
        var endsBox: Boolean = false,
        var leadingMargin: Float = 0f,
        var leadingInset: Float = 0f,
        var trailingInset: Float = 0f,
        var trailingMargin: Float = 0f,
        val rubyKey: Int? = null,
        val rubyText: String? = null,
        var rubyLeadingInset: Float = 0f,
        var rubyTrailingInset: Float = 0f
    ) {
        val advanceWidth: Float
            get() = leadingMargin + leadingInset + rubyLeadingInset + width + rubyTrailingInset +
                trailingInset + trailingMargin
    }

    private fun buildClusters(
        flow: EpubInlineFlowBox,
        cbWidth: Float,
        inheritedBackgroundArgb: Int
    ): List<Cluster> {
        val result = ArrayList<Cluster>()
        val isHeading = flow.isHeading
        flow.items.forEach { item ->
            ctx.cancellationCheck()
            when (item) {
                is InlineBreakItem -> result += breakCluster()
                is InlineFloatItem -> result += floatCluster(item.box)
                is InlineImageItem -> result += imageCluster(item, cbWidth, isHeading, inheritedBackgroundArgb)
                is InlineTextItem -> appendTextClusters(item, result, isHeading, inheritedBackgroundArgb, cbWidth)
            }
        }
        resolveInlineBoxEdges(result, cbWidth)
        return withRubyInsets(result)
    }

    private fun breakCluster() = Cluster(
        text = "",
        width = 0f,
        sourceOffset = -1,
        sourceLength = 0,
        marker = null,
        style = fallbackRunStyle(),
        requestedStep = ctx.defaultLineStep(false),
        linkHref = null,
        forcedBreak = true
    )

    private fun floatCluster(box: EpubBox) = Cluster(
        text = "",
        width = 0f,
        sourceOffset = -1,
        sourceLength = 0,
        marker = null,
        style = fallbackRunStyle(),
        requestedStep = 0f,
        linkHref = null,
        float = box
    )

    private var cachedFallback: ResolvedRunStyle? = null
    private fun fallbackRunStyle(): ResolvedRunStyle = cachedFallback ?: ctx.resolveRunStyle(
        EpubStyle(fontSizePx = ctx.spec.contentFontSizePx, colorArgb = ctx.spec.themeTextArgb),
        isTitle = false,
        inheritedBackgroundArgb = ctx.spec.themeBackgroundArgb,
        sourceOffset = -1
    ).also { cachedFallback = it }

    private fun imageCluster(
        item: InlineImageItem,
        cbWidth: Float,
        isHeading: Boolean,
        inheritedBackgroundArgb: Int
    ): Cluster {
        val resolved = resolveImage(
            style = item.style,
            textStart = item.textStart,
            altText = item.altText,
            attrWidth = item.attrWidth,
            attrHeight = item.attrHeight,
            percentBase = cbWidth,
            widthLimit = cbWidth
        )
        val style = ctx.resolveRunStyle(item.style, isHeading, inheritedBackgroundArgb, item.textStart)
        if (resolved == null) {
            // 资源缺失（如 data URI 未抽取）：吞掉占位文本，不把「［图片］」画出来。
            return Cluster(
                text = "",
                width = 0f,
                sourceOffset = item.textStart,
                sourceLength = item.textEnd - item.textStart,
                marker = null,
                style = style,
                requestedStep = 0f,
                linkHref = item.linkHref
            )
        }
        return Cluster(
            text = "",
            width = resolved.width,
            sourceOffset = item.textStart,
            sourceLength = item.textEnd - item.textStart,
            marker = null,
            style = style,
            requestedStep = resolved.height,
            linkHref = item.linkHref,
            image = resolved
        )
    }

    /** Resolve a CSS/HTML-sized replaced image for both inline and block formatting contexts. */
    internal fun resolveImage(
        style: EpubStyle,
        textStart: Int,
        altText: String,
        attrWidth: String?,
        attrHeight: String?,
        percentBase: Float,
        widthLimit: Float,
        horizontalEdges: Float = 0f,
        verticalEdges: Float = 0f
    ): EpubSizedImage? {
        val source = ctx.imageSources[textStart] ?: return null
        val effective = sizingStyle(style, attrWidth, attrHeight)
        val (width, height) = usedImageSize(
            style = effective,
            pixelWidth = source.pixelWidth,
            pixelHeight = source.pixelHeight,
            percentBase = percentBase,
            widthLimit = widthLimit,
            horizontalEdges = horizontalEdges,
            verticalEdges = verticalEdges
        )
        return EpubSizedImage(
            path = source.imagePath,
            width = width,
            height = height,
            altText = altText.ifBlank { source.altText },
            alignment = style.verticalAlign
        )
    }

    /** CSS sizing wins; the HTML width/height attributes fill unstyled slots. */
    private fun sizingStyle(style: EpubStyle, attrWidth: String?, attrHeight: String?): EpubStyle {
        val width = attrWidth.toAttrLength()
        val height = attrHeight.toAttrLength()
        if (width == null && height == null) return style
        return style.copy(
            width = if (style.width == ResolvedLength.Auto) width ?: style.width else style.width,
            height = if (style.height == ResolvedLength.Auto) height ?: style.height else style.height
        )
    }

    private fun String?.toAttrLength(): ResolvedLength? {
        val value = this?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return if (value.endsWith("%")) {
            value.dropLast(1).toFloatOrNull()?.let { ResolvedLength.Percent(it) }
        } else {
            value.removeSuffix("px").toFloatOrNull()
                ?.let { ResolvedLength.Px(it * ctx.spec.contentFontSizePx / CSS_ROOT_FONT_PX) }
        }
    }

    private fun usedImageSize(
        style: EpubStyle,
        pixelWidth: Int,
        pixelHeight: Int,
        percentBase: Float,
        widthLimit: Float,
        horizontalEdges: Float,
        verticalEdges: Float
    ): Pair<Float, Float> {
        val aspect = (pixelWidth.toFloat() / pixelHeight.coerceAtLeast(1)).coerceIn(MIN_IMAGE_ASPECT, MAX_IMAGE_ASPECT)
        val intrinsicWidth = pixelWidth.coerceAtLeast(1) *
            (ctx.spec.contentFontSizePx / CSS_ROOT_FONT_PX).coerceAtLeast(1f)
        fun contentWidth(length: ResolvedLength): Float? = length.resolve(percentBase)?.let { value ->
            if (style.boxSizingBorderBox) value - horizontalEdges else value
        }?.coerceAtLeast(1f)
        fun contentHeight(length: ResolvedLength): Float? = (length as? ResolvedLength.Px)?.value?.let { value ->
            if (style.boxSizingBorderBox) value - verticalEdges else value
        }?.coerceAtLeast(1f)

        val limit = widthLimit.coerceAtLeast(1f)
        val declaredWidth = contentWidth(style.width)
        val declaredHeight = contentHeight(style.height)
        val maxWidth = min(contentWidth(style.maxWidth) ?: limit, limit).coerceAtLeast(1f)
        val fullPage = declaredWidth?.let { it >= limit * 0.9f } == true ||
            declaredHeight?.let { it >= ctx.spec.visibleHeight * 0.8f } == true ||
            aspect <= 0.85f && (declaredWidth ?: maxWidth) >= limit * 0.9f
        val maxHeight = contentHeight(style.maxHeight)
            ?: if (declaredHeight != null) Float.MAX_VALUE
            else ctx.spec.visibleHeight * if (fullPage) 1f else MAX_IMAGE_HEIGHT_FRACTION
        val minWidth = contentWidth(style.minWidth) ?: 1f
        val minHeight = contentHeight(style.minHeight) ?: 1f

        val preserveAspect = declaredWidth == null || declaredHeight == null
        var width = declaredWidth ?: declaredHeight?.times(aspect) ?: min(maxWidth, intrinsicWidth)
        var height = declaredHeight ?: width / aspect
        if (preserveAspect) {
            // One auto axis receives the intrinsic ratio. Constraints then scale both axes,
            // matching replaced-element sizing without deforming ordinary illustrations.
            val grow = max(minWidth / width.coerceAtLeast(1f), minHeight / height.coerceAtLeast(1f))
            if (grow > 1f) {
                width *= grow
                height *= grow
            }
            if (width > maxWidth) {
                val scale = maxWidth / width.coerceAtLeast(1f)
                width *= scale
                height *= scale
            }
            if (height > maxHeight) {
                val scale = maxHeight / height.coerceAtLeast(1f)
                width *= scale
                height *= scale
            }
        } else {
            // CSS width+height define the concrete replaced-element rectangle. The default
            // object-fit is fill, so preserving the intrinsic ratio here would place the image
            // and following text differently from the publication.
            width = width.coerceIn(min(minWidth, maxWidth), maxWidth)
            height = height.coerceIn(min(minHeight, maxHeight), maxHeight)
        }
        return width.coerceAtLeast(1f) to height.coerceAtLeast(1f)
    }

    private fun appendTextClusters(
        item: InlineTextItem,
        result: ArrayList<Cluster>,
        isHeading: Boolean,
        inheritedBackgroundArgb: Int,
        cbWidth: Float
    ) {
        val start = item.textStart.coerceIn(0, ctx.body.length)
        val end = item.textEnd.coerceIn(start, ctx.body.length)
        if (start >= end) return
        val text = ctx.body.substring(start, end)
        val markersByLocal = ctx.inlineMarkers
            .filter { it.charOffset in (start + 1)..end }
            .distinctBy { it.charOffset to it.kind }
            .groupBy { it.charOffset - start }
        val boxKey = item.decoratedBox
        val boxStyle = item.decoratedBox?.style
        val requestedStep = requestedStepFor(item.style, isHeading)

        var index = 0
        while (index < text.length) {
            if (index and 0xFF == 0) ctx.cancellationCheck()
            var clusterEnd = index + 1
            if (text[index].isHighSurrogate() && clusterEnd < text.length && text[clusterEnd].isLowSurrogate()) {
                clusterEnd++
            }
            while (clusterEnd < text.length && text[clusterEnd].isCombiningMark()) clusterEnd++
            val clusterText = text.substring(index, clusterEnd)
            val offset = start + index
            val style = ctx.resolveRunStyle(item.style, isHeading, inheritedBackgroundArgb, offset)
            result += Cluster(
                text = clusterText,
                width = ctx.measure.charWidths(clusterText, style.measureStyle).sum(),
                sourceOffset = offset,
                sourceLength = clusterEnd - index,
                marker = null,
                style = style,
                requestedStep = requestedStep,
                linkHref = item.linkHref,
                boxKey = boxKey,
                boxStyle = boxStyle,
                rubyKey = item.rubyGroup,
                rubyText = item.rubyText
            )
            markersByLocal[clusterEnd].orEmpty().sortedBy { it.kind.ordinal }.forEach { marker ->
                result += Cluster(
                    text = "",
                    width = ctx.measure.indentColumnWidth(),
                    sourceOffset = -1,
                    sourceLength = 0,
                    marker = marker,
                    style = style,
                    requestedStep = requestedStep,
                    linkHref = null,
                    boxKey = boxKey,
                    boxStyle = boxStyle
                )
            }
            index = clusterEnd
        }
    }

    private fun requestedStepFor(style: EpubStyle, isHeading: Boolean): Float =
        ctx.requestedLineStep(style, isHeading)

    private fun resolveInlineBoxEdges(clusters: List<Cluster>, cbWidth: Float) {
        clusters.forEachIndexed { index, cluster ->
            val key = cluster.boxKey ?: return@forEachIndexed
            val style = cluster.boxStyle ?: return@forEachIndexed
            val startsBox = clusters.getOrNull(index - 1)?.boxKey !== key
            val endsBox = clusters.getOrNull(index + 1)?.boxKey !== key
            cluster.startsBox = startsBox
            cluster.endsBox = endsBox
            if (startsBox) {
                cluster.leadingMargin = style.marginLeft.resolve(cbWidth) ?: 0f
                cluster.leadingInset = style.borderWidths[3] + (style.paddingLeft.resolve(cbWidth) ?: 0f)
            }
            if (endsBox) {
                cluster.trailingInset = (style.paddingRight.resolve(cbWidth) ?: 0f) + style.borderWidths[1]
                cluster.trailingMargin = style.marginRight.resolve(cbWidth) ?: 0f
            }
        }
    }

    private fun withRubyInsets(clusters: List<Cluster>): List<Cluster> {
        clusters.mapNotNull(Cluster::rubyKey).distinct().forEach { rubyId ->
            val indices = clusters.indices.filter { clusters[it].rubyKey == rubyId }
            if (indices.isEmpty()) return@forEach
            val first = clusters[indices.first()]
            val rubyText = first.rubyText ?: return@forEach
            val rubyStyle = first.style.measureStyle.copy(
                textSizeScale = first.style.measureStyle.textSizeScale * RUBY_TEXT_SCALE
            )
            val rubyWidth = ctx.measure.charWidths(rubyText, rubyStyle).sum()
            val baseWidth = indices.sumOf { clusters[it].advanceWidth.toDouble() }.toFloat()
            val sideInset = ((rubyWidth - baseWidth).coerceAtLeast(0f)) / 2f
            clusters[indices.first()].rubyLeadingInset = sideInset
            clusters[indices.last()].rubyTrailingInset = sideInset
        }
        return clusters
    }

    // ---------------------------------------------------------------------------------------
    // Line filling and placement
    // ---------------------------------------------------------------------------------------

    private fun fillLine(clusters: List<Cluster>, start: Int, limit: Float): Int {
        var used = 0f
        var end = start
        var preferredBreak = -1
        while (end < clusters.size) {
            val next = clusters[end]
            if (next.float != null) break
            if (next.forcedBreak) {
                end++
                return end
            }
            if (end > start && used + next.advanceWidth > limit) break
            used += next.advanceWidth
            end++
            if (next.isBreakOpportunity()) preferredBreak = end
        }
        if (end >= clusters.size || clusters[end].float != null || clusters[end].forcedBreak) return end
        var keptGroup = false
        val groupId = clusters[end].rubyKey
        if (groupId != null) {
            var groupStart = end
            while (groupStart > start && clusters[groupStart - 1].rubyKey == groupId) groupStart--
            var groupEnd = end + 1
            while (groupEnd < clusters.size && clusters[groupEnd].rubyKey == groupId) groupEnd++
            val groupWidth = clusters.subList(groupStart, groupEnd).sumOf { it.advanceWidth.toDouble() }.toFloat()
            if (groupWidth <= limit) {
                var moved = if (groupStart > start) groupStart else groupEnd
                keptGroup = true
                return adjustPunctuation(clusters, start, moved)
            }
        }
        if (!keptGroup && preferredBreak > start) end = preferredBreak
        return adjustPunctuation(clusters, start, end)
    }

    private fun adjustPunctuation(clusters: List<Cluster>, start: Int, proposed: Int): Int {
        var end = proposed
        // 中文避头尾：闭合标点不下行，起始标点不孤悬行尾。闭合标点允许轻微越界。
        while (end < clusters.size && clusters[end].startsWithForbiddenPunctuation()) end++
        while (end > start + 1 && clusters[end - 1].endsWithOpeningPunctuation()) end--
        if (end <= start) end = start + 1
        return end
    }

    private class LineMetricsResult(
        val ascent: Float,
        val descent: Float,
        val textHeight: Float,
        val lineStep: Float,
        val rubyBaseline: Float?
    )

    private fun lineMetrics(clusters: List<Cluster>, isHeading: Boolean): LineMetricsResult {
        var ascent = 0f
        var descent = 0f
        var requestedStep = 0f
        var rubyBaseline: Float? = null
        clusters.forEach { cluster ->
            if (cluster.forcedBreak) return@forEach
            val image = cluster.image
            if (image != null) {
                val (imageAscent, imageDescent) = imageExtents(image, cluster)
                ascent = max(ascent, imageAscent)
                descent = max(descent, imageDescent)
                requestedStep = max(requestedStep, imageAscent + imageDescent)
                return@forEach
            }
            val metrics = ctx.measure.metrics(cluster.style.measureStyle)
            val boxStyle = cluster.boxStyle
            val topExtra = boxStyle?.let {
                (it.marginTop.resolve(0f) ?: 0f) + it.borderWidths[0] + (it.paddingTop.resolve(0f) ?: 0f)
            } ?: 0f
            val bottomExtra = boxStyle?.let {
                (it.paddingBottom.resolve(0f) ?: 0f) + it.borderWidths[2] + (it.marginBottom.resolve(0f) ?: 0f)
            } ?: 0f
            val rubyMetrics = cluster.rubyKey?.let {
                ctx.measure.metrics(
                    cluster.style.measureStyle.copy(
                        textSizeScale = cluster.style.measureStyle.textSizeScale * RUBY_TEXT_SCALE
                    )
                )
            }
            val rubyBand = rubyMetrics?.let { it.textHeight + ctx.spec.contentFontSizePx * RUBY_GAP_EM } ?: 0f
            rubyMetrics?.let { value ->
                rubyBaseline = max(rubyBaseline ?: 0f, value.textHeight - value.descent)
            }
            ascent = max(ascent, metrics.textHeight - metrics.descent + topExtra + rubyBand)
            descent = max(descent, metrics.descent + bottomExtra)
            requestedStep = max(requestedStep, cluster.requestedStep + topExtra + bottomExtra + rubyBand)
        }
        val textHeight = ascent + descent
        return LineMetricsResult(ascent, descent, textHeight, max(textHeight, requestedStep), rubyBaseline)
    }

    private fun imageExtents(image: EpubSizedImage, cluster: Cluster): Pair<Float, Float> {
        val em = ctx.spec.contentFontSizePx * cluster.style.measureStyle.textSizeScale
        return when (val alignment = image.alignment) {
            is EpubVerticalAlignment.Shift -> {
                val drop = alignment.px.coerceAtLeast(0f)
                val rise = (-alignment.px).coerceAtLeast(0f)
                (image.height - drop + rise).coerceAtLeast(0f) to drop
            }
            EpubVerticalAlignment.Middle -> {
                val half = image.height / 2f
                half + em * 0.25f to (half - em * 0.25f).coerceAtLeast(0f)
            }
            EpubVerticalAlignment.Sub -> (image.height - em * 0.2f).coerceAtLeast(0f) to em * 0.2f
            EpubVerticalAlignment.Super -> image.height + em * 0.35f to 0f
            else -> image.height to 0f
        }
    }

    private fun placeLine(
        clusters: List<Cluster>,
        contentLeft: Float,
        contentRight: Float,
        align: EpubTextAlignValue,
        justify: Boolean,
        metrics: LineMetricsResult,
        lineTop: Float,
        isParagraphEnd: Boolean
    ): TextLine {
        val available = (contentRight - contentLeft).coerceAtLeast(1f)
        val desired = clusters.sumOf { it.advanceWidth.toDouble() }.toFloat()
        val residual = available - desired
        val startX = when (align) {
            EpubTextAlignValue.CENTER -> contentLeft + max(0f, residual) / 2f
            EpubTextAlignValue.END -> contentLeft + max(0f, residual)
            else -> contentLeft
        }
        var spaceExtra = 0f
        var gapExtra = 0f
        if (justify && residual > 0f && clusters.size > 1 && residual <= available * MAX_JUSTIFY_FRACTION) {
            val spaces = clusters.count { it.text == " " && it.marker == null }
            if (spaces > 0) spaceExtra = residual / spaces else gapExtra = residual / (clusters.size - 1)
        }

        val baseline = lineTop + metrics.ascent
        val columns = ArrayList<TextColumn>(clusters.size)
        val glyphImages = ArrayList<PositionedInlineImagePlacement>()
        val inlineFragments = ArrayList<InlineFragment>()
        val rubyFragments = ArrayList<RubyFragment>()
        var x = startX
        clusters.forEachIndexed { index, cluster ->
            if (cluster.forcedBreak) return@forEachIndexed
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
                syntaxBackgroundArgb = style.backgroundArgb.takeIf { cluster.boxKey == null },
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
            cluster.image?.let { image ->
                val (imageAscent, _) = imageExtents(image, cluster)
                val top = baseline - imageAscent
                glyphImages += PositionedInlineImagePlacement(
                    imagePath = image.path,
                    left = glyphStart,
                    topOffset = (top - lineTop).coerceAtLeast(0f),
                    width = image.width,
                    height = image.height,
                    altText = image.altText
                )
            }
            val boxKey = cluster.boxKey
            val boxStyle = cluster.boxStyle
            if (boxKey != null && boxStyle != null && cluster.image == null) {
                val boxLeft = x + cluster.leadingMargin
                val boxRight = x + advance - cluster.trailingMargin
                val previous = inlineFragments.lastOrNull()
                if (previous != null && previous.key === boxKey) {
                    previous.right = boxRight
                    previous.drawRightEdge = cluster.endsBox
                } else {
                    inlineFragments += InlineFragment(
                        key = boxKey,
                        style = boxStyle,
                        left = boxLeft,
                        right = boxRight,
                        drawLeftEdge = cluster.startsBox,
                        drawRightEdge = cluster.endsBox
                    )
                }
            }
            val rubyKey = cluster.rubyKey
            val rubyText = cluster.rubyText
            if (rubyKey != null && rubyText != null) {
                val rubyLeft = x + cluster.leadingMargin
                val rubyRight = x + advance - cluster.trailingMargin
                val previous = rubyFragments.lastOrNull()
                if (previous != null && previous.key == rubyKey) {
                    previous.right = rubyRight
                } else {
                    rubyFragments += RubyFragment(rubyKey, rubyText, rubyLeft, rubyRight, cluster.style)
                }
            }
            x += advance
        }

        val sourceClusters = clusters.filter { it.sourceOffset >= 0 }
        val sourceStart = sourceClusters.firstOrNull()?.sourceOffset ?: 0
        val sourceEnd = sourceClusters.lastOrNull()?.let { it.sourceOffset + it.sourceLength } ?: sourceStart
        val lineBottom = lineTop + metrics.textHeight
        return TextLine(
            text = ctx.body.substring(
                sourceStart.coerceIn(0, ctx.body.length),
                sourceEnd.coerceIn(sourceStart.coerceIn(0, ctx.body.length), ctx.body.length)
            ),
            columns = columns,
            lineTop = lineTop,
            lineBase = baseline,
            lineBottom = lineBottom,
            startX = startX,
            isTitle = clusters.firstOrNull()?.style?.measureStyle?.isTitle == true,
            isParagraphEnd = isParagraphEnd,
            chapterPosition = sourceStart,
            charLength = (sourceEnd - sourceStart).coerceAtLeast(0),
            justifyGapExtra = gapExtra,
            inlineGlyphImages = glyphImages,
            inlineDecorations = inlineFragments.mapNotNull { fragment ->
                inlineDecoration(fragment, lineTop, lineBottom)
            },
            rubyPlacements = metrics.rubyBaseline?.let { rubyBase ->
                rubyFragments.map { fragment ->
                    TextRubyPlacement(
                        text = fragment.text,
                        left = fragment.left,
                        right = fragment.right,
                        baseline = lineTop + rubyBase,
                        textSizeScale = fragment.style.measureStyle.textSizeScale * RUBY_TEXT_SCALE,
                        fontFilePath = fragment.style.measureStyle.fontFilePath,
                        fontFamily = fragment.style.measureStyle.fontFamily,
                        colorArgb = fragment.style.colorArgb,
                        bold = fragment.style.measureStyle.bold,
                        italic = fragment.style.measureStyle.italic,
                        opacity = fragment.style.opacity
                    )
                }
            } ?: emptyList()
        )
    }

    private class InlineFragment(
        val key: Any,
        val style: EpubStyle,
        var left: Float,
        var right: Float,
        var drawLeftEdge: Boolean,
        var drawRightEdge: Boolean
    )

    private class RubyFragment(
        val key: Int,
        val text: String,
        var left: Float,
        var right: Float,
        val style: ResolvedRunStyle
    )

    private fun inlineDecoration(fragment: InlineFragment, lineTop: Float, lineBottom: Float): TextBlockDecoration? {
        val style = fragment.style
        val top = lineTop + (style.marginTop.resolve(0f) ?: 0f)
        val bottom = lineBottom - (style.marginBottom.resolve(0f) ?: 0f)
        if (fragment.right <= fragment.left || bottom <= top) return null
        return ctx.themeBlockDecoration(style, fragment.left, top, fragment.right, bottom).copy(
            drawLeftEdge = fragment.drawLeftEdge,
            drawRightEdge = fragment.drawRightEdge
        )
    }

    private fun Cluster.isBreakOpportunity(): Boolean {
        if (marker != null || image != null || text.isBlank()) return true
        val codePoint = text.codePointAt(text.length - Character.charCount(text.codePointBefore(text.length)))
        return codePoint in CJK_RANGE || codePoint in CJK_EXT_RANGE || text.last() in BREAK_PUNCTUATION
    }

    private fun Cluster.startsWithForbiddenPunctuation(): Boolean =
        image == null && text.firstOrNull() in FORBIDDEN_LINE_START

    private fun Cluster.endsWithOpeningPunctuation(): Boolean =
        image == null && text.lastOrNull() in FORBIDDEN_LINE_END

    private fun Char.isCombiningMark(): Boolean = when (Character.getType(this)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    private companion object {
        const val INTRINSIC_PROBE_WIDTH = 10_000f
        const val RUBY_TEXT_SCALE = 0.5f
        const val RUBY_GAP_EM = 0.08f
        const val MAX_JUSTIFY_FRACTION = 0.35f
        const val MAX_IMAGE_HEIGHT_FRACTION = 0.86f
        const val CSS_ROOT_FONT_PX = 16f
        const val MIN_IMAGE_ASPECT = 0.15f
        const val MAX_IMAGE_ASPECT = 7f
        val CJK_RANGE = 0x3400..0x9FFF
        val CJK_EXT_RANGE = 0x20000..0x2FA1F
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
