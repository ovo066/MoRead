package com.mozhi.reader.feature.reader.engine.epub

import com.mozhi.reader.core.epub.style.EpubClearValue
import com.mozhi.reader.core.epub.style.EpubDisplay
import com.mozhi.reader.core.epub.style.EpubFloatValue
import com.mozhi.reader.core.epub.style.EpubStyle
import com.mozhi.reader.core.epub.style.EpubVerticalAlignment
import com.mozhi.reader.core.epub.style.ResolvedLength
import com.mozhi.reader.core.epub.style.resolve
import com.mozhi.reader.feature.reader.engine.PositionedInlineImagePlacement
import com.mozhi.reader.feature.reader.engine.TextLine
import com.mozhi.reader.feature.reader.engine.TextRulePlacement
import kotlin.math.max
import kotlin.math.min

/**
 * Block formatting: containing-block driven widths (percentages, negative margins, auto
 * centering), real floats with shrink-to-fit, simple fixed-layout tables, and explicit-height
 * containers. Produces a continuous-y [FlowOutput]; pagination happens afterwards.
 */
internal class EpubBlockLayout(private val ctx: EpubLayoutContext) {

    private val inline = EpubInlineLayout(ctx)

    fun layout(root: EpubBlockBox): FlowOutput {
        val output = FlowOutput()
        val bfc = BfcState()
        val cursor = FlowCursor(0f)
        val cb = contentBox(root.style, ContainingBlock(0f, ctx.spec.visibleWidth)).contentCb
        val background = ctx.mappedBackground(root.style.background.colorArgb)
            ?.let { com.mozhi.reader.feature.reader.engine.EpubThemeColors.composite(it, ctx.spec.themeBackgroundArgb) }
            ?: ctx.spec.themeBackgroundArgb
        root.children.forEach { child ->
            layoutChild(child, cb, cursor, bfc, output, background)
        }
        cursor.commit()
        cursor.y = max(cursor.y, bfc.lowestBottom())
        return output
    }

    private fun layoutChild(
        box: EpubBox,
        cb: ContainingBlock,
        cursor: FlowCursor,
        bfc: BfcState,
        output: FlowOutput,
        inheritedBackgroundArgb: Int
    ) {
        ctx.cancellationCheck()
        when (box) {
            is EpubInlineFlowBox -> layoutFlow(box, cb, cursor, bfc, output, inheritedBackgroundArgb)
            is EpubTableBox -> layoutTable(box, cb, cursor, bfc, output, inheritedBackgroundArgb)
            is EpubImageBox -> if (box.style.float != EpubFloatValue.NONE) {
                placeFloat(box, cb, cursor, bfc, output, inheritedBackgroundArgb)
            } else {
                layoutImage(box, cb, cursor, bfc, output)
            }
            is EpubBlockBox -> if (box.style.float != EpubFloatValue.NONE) {
                placeFloat(box, cb, cursor, bfc, output, inheritedBackgroundArgb)
            } else {
                layoutBlock(box, cb, cursor, bfc, output, inheritedBackgroundArgb)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Normal-flow blocks
    // ---------------------------------------------------------------------------------------

    private fun layoutBlock(
        box: EpubBlockBox,
        cb: ContainingBlock,
        cursor: FlowCursor,
        bfc: BfcState,
        output: FlowOutput,
        inheritedBackgroundArgb: Int
    ) {
        val style = box.style
        if (style.clear != EpubClearValue.NONE) {
            cursor.commit()
            cursor.y = bfc.clearY(style.clear, cursor.y)
        }
        if (style.breakBefore) {
            cursor.commit()
            output.forcedBreaks += cursor.y
        }
        val geometry = contentBox(style, cb)
        cursor.addGap(blockGapTop(box))

        val hasTopEdge = geometry.borderTop + geometry.paddingTop > 0f
        val decorated = style.hasDecoration()
        val explicitHeight = (style.height as? ResolvedLength.Px)?.value
        if (hasTopEdge || decorated || explicitHeight != null) cursor.commit()
        val borderBoxTop = cursor.y
        cursor.y += geometry.borderTop + geometry.paddingTop
        if (hasTopEdge) cursor.commit()

        val decorationSlot = if (decorated) reserveDecoration(output) else -1
        val contentTop = cursor.y
        val lineStartIndex = output.lines.size
        val decorationStartIndex = output.decorations.size

        val childBackground = style.background.colorArgb
            ?.let(ctx::mappedBackground)
            ?.let { com.mozhi.reader.feature.reader.engine.EpubThemeColors.composite(it, inheritedBackgroundArgb) }
            ?: inheritedBackgroundArgb
        box.children.forEach { child ->
            layoutChild(child, geometry.contentCb, cursor, bfc, output, childBackground)
        }
        if (box.children.isEmpty() && box.tag == "hr") {
            emitRule(box, geometry, cursor, output)
        }

        // Bottom edge closes the box: commit collapsed child margins into the padding box.
        if (geometry.paddingBottom + geometry.borderBottom > 0f || decorated || explicitHeight != null) cursor.commit()
        var contentBottom = cursor.y
        if (explicitHeight != null) {
            val target = if (style.boxSizingBorderBox) {
                borderBoxTop + explicitHeight
            } else {
                contentTop + explicitHeight + geometry.paddingBottom + geometry.borderBottom
            } - geometry.paddingBottom - geometry.borderBottom
            if (target > contentBottom) {
                // 显式高度的容器（如 100vh 整页图）垂直居中其内容——精排书的惯用形态。
                val surplus = target - contentBottom
                val shift = if (style.display == EpubDisplay.FLEX || centersContent(box)) surplus / 2f else 0f
                if (shift > 0f) {
                    for (index in lineStartIndex until output.lines.size) {
                        FlowOutput.translateLine(output.lines[index].line, 0f, shift)
                    }
                    for (index in decorationStartIndex until output.decorations.size) {
                        val entry = output.decorations[index]
                        entry.decoration = entry.decoration.copy(
                            top = entry.decoration.top + shift,
                            bottom = entry.decoration.bottom + shift
                        )
                    }
                }
                contentBottom = target
                cursor.y = contentBottom
            }
        }
        val minHeight = (style.minHeight as? ResolvedLength.Px)?.value
        if (minHeight != null) {
            val target = borderBoxTop + geometry.borderTop + geometry.paddingTop + minHeight
            if (target > contentBottom) {
                contentBottom = target
                cursor.y = contentBottom
            }
        }
        cursor.y += geometry.paddingBottom + geometry.borderBottom
        val borderBoxBottom = cursor.y

        if (decorationSlot >= 0 && borderBoxBottom > borderBoxTop) {
            output.decorations[decorationSlot].decoration = ctx.themeBlockDecoration(
                style = style,
                left = geometry.borderBoxLeft,
                top = borderBoxTop,
                right = geometry.borderBoxRight,
                bottom = borderBoxBottom
            )
        }
        if (style.breakInsideAvoid && borderBoxBottom > borderBoxTop) {
            output.keepRanges += borderBoxTop..borderBoxBottom
        }
        if (style.breakAfter) output.forcedBreaks += cursor.y
        cursor.addGap(blockGapBottom(box))
    }

    /** Layout a display:block replaced image with its own margins, padding, border and centering. */
    private fun layoutImage(
        box: EpubImageBox,
        cb: ContainingBlock,
        cursor: FlowCursor,
        bfc: BfcState,
        output: FlowOutput
    ) {
        val style = box.style
        if (style.clear != EpubClearValue.NONE) {
            cursor.commit()
            cursor.y = bfc.clearY(style.clear, cursor.y)
        }
        if (style.breakBefore) {
            cursor.commit()
            output.forcedBreaks += cursor.y
        }
        cursor.addGap(style.marginTop.resolve(cb.width) ?: 0f)
        cursor.commit()

        val paddingLeft = style.paddingLeft.resolve(cb.width) ?: 0f
        val paddingRight = style.paddingRight.resolve(cb.width) ?: 0f
        val paddingTop = style.paddingTop.resolve(cb.width) ?: 0f
        val paddingBottom = style.paddingBottom.resolve(cb.width) ?: 0f
        val borderLeft = style.borderWidths[3]
        val borderRight = style.borderWidths[1]
        val borderTop = style.borderWidths[0]
        val borderBottom = style.borderWidths[2]
        val horizontalEdges = paddingLeft + paddingRight + borderLeft + borderRight
        val verticalEdges = paddingTop + paddingBottom + borderTop + borderBottom
        val resolvedLeft = style.marginLeft.resolve(cb.width)
        val resolvedRight = style.marginRight.resolve(cb.width)
        val fixedLeft = resolvedLeft ?: 0f
        val fixedRight = resolvedRight ?: 0f
        val widthLimit = (cb.width - fixedLeft - fixedRight - horizontalEdges).coerceAtLeast(1f)
        val image = inline.resolveImage(
            style = style,
            textStart = box.textStart,
            altText = box.altText,
            attrWidth = box.attrWidth,
            attrHeight = box.attrHeight,
            percentBase = cb.width,
            widthLimit = widthLimit,
            horizontalEdges = horizontalEdges,
            verticalEdges = verticalEdges
        )
        val contentWidth = image?.width ?: 1f
        val borderBoxWidth = contentWidth + horizontalEdges
        val slack = cb.width - fixedLeft - fixedRight - borderBoxWidth
        // Auto margins absorb only positive remaining width. On overflow CSS resolves them to
        // zero instead of pulling the replaced element outside both sides of its containing box.
        val distributable = slack.coerceAtLeast(0f)
        val marginLeft = when {
            resolvedLeft == null && resolvedRight == null -> distributable / 2f
            resolvedLeft == null -> distributable
            else -> fixedLeft
        }
        val borderBoxLeft = cb.left + marginLeft
        val borderBoxTop = cursor.y
        val imageLeft = borderBoxLeft + borderLeft + paddingLeft
        val imageTop = borderBoxTop + borderTop + paddingTop
        val borderBoxBottom = imageTop + (image?.height ?: 1f) + paddingBottom + borderBottom

        emitImageLine(
            output = output,
            box = box,
            image = image,
            lineLeft = borderBoxLeft,
            lineTop = borderBoxTop,
            lineBottom = borderBoxBottom,
            imageLeft = imageLeft,
            imageTopOffset = imageTop - borderBoxTop
        )
        if (style.hasDecoration()) {
            output.decorations += FlowDecoration(
                decoration = ctx.themeBlockDecoration(
                    style = style,
                    left = borderBoxLeft,
                    top = borderBoxTop,
                    right = borderBoxLeft + borderBoxWidth,
                    bottom = borderBoxBottom
                ),
                isCanvas = false,
                zIndex = output.nextZIndex++
            )
        }
        if (style.breakInsideAvoid || borderBoxBottom - borderBoxTop <= ctx.spec.visibleHeight) {
            output.keepRanges += borderBoxTop..borderBoxBottom
        }
        cursor.y = borderBoxBottom
        if (style.breakAfter) output.forcedBreaks += cursor.y
        cursor.addGap(style.marginBottom.resolve(cb.width) ?: 0f)
    }

    private fun emitImageLine(
        output: FlowOutput,
        box: EpubImageBox,
        image: EpubSizedImage?,
        lineLeft: Float,
        lineTop: Float,
        lineBottom: Float,
        imageLeft: Float,
        imageTopOffset: Float
    ) {
        output.lines += FlowLine(
            line = TextLine(
                text = "",
                columns = emptyList(),
                lineTop = lineTop,
                lineBase = lineBottom,
                lineBottom = lineBottom,
                startX = lineLeft,
                isTitle = false,
                isParagraphEnd = true,
                chapterPosition = box.textStart,
                charLength = (box.textEnd - box.textStart).coerceAtLeast(0),
                inlineImages = image?.let {
                    listOf(
                        PositionedInlineImagePlacement(
                            imagePath = it.path,
                            left = imageLeft,
                            topOffset = imageTopOffset,
                            width = it.width,
                            height = it.height,
                            altText = it.altText
                        )
                    )
                }.orEmpty()
            ),
            paragraphId = output.nextParagraphId++,
            orphans = 1,
            widows = 1,
            indexInParagraph = 0,
            paragraphLineCount = 1,
            keepWithNext = false
        )
    }

    private fun centersContent(box: EpubBlockBox): Boolean {
        val flows = box.children.filterIsInstance<EpubInlineFlowBox>()
        return box.children.size == 1 && flows.size == 1 &&
            flows[0].items.all { it is InlineImageItem || it is InlineBreakItem }
    }

    private fun emitRule(box: EpubBlockBox, geometry: BoxGeometry, cursor: FlowCursor, output: FlowOutput) {
        cursor.commit()
        val style = box.style
        val height = max(1f, style.borderWidths.max())
        val lastCovered = output.lines.lastOrNull()?.line?.let { it.chapterPosition + it.charLength } ?: 0
        output.lines += FlowLine(
            line = TextLine(
                text = "",
                columns = emptyList(),
                lineTop = cursor.y,
                lineBase = cursor.y + height,
                lineBottom = cursor.y + height,
                startX = geometry.contentCb.left,
                isTitle = false,
                isParagraphEnd = true,
                chapterPosition = lastCovered,
                charLength = 0,
                rule = TextRulePlacement(
                    width = geometry.contentCb.width,
                    height = height,
                    colorArgb = style.borderColors.firstOrNull { it != null } ?: DEFAULT_RULE_COLOR
                )
            ),
            paragraphId = output.nextParagraphId++,
            orphans = 1,
            widows = 1,
            indexInParagraph = 0,
            paragraphLineCount = 1,
            keepWithNext = false
        )
        cursor.y += max(height, ctx.spec.contentFontSizePx * 0.35f)
    }

    // ---------------------------------------------------------------------------------------
    // Inline flows
    // ---------------------------------------------------------------------------------------

    private fun layoutFlow(
        flow: EpubInlineFlowBox,
        cb: ContainingBlock,
        cursor: FlowCursor,
        bfc: BfcState,
        output: FlowOutput,
        inheritedBackgroundArgb: Int
    ) {
        // 匿名段落（div/body 里的裸文本或独立图片行）没有自己的盒子，段距在这里补。
        val anonymousParagraph = flow.ownerTag !in TEXT_OWNER_TAGS
        cursor.commit()
        val result = inline.layout(
            flow = flow,
            cb = cb,
            startY = cursor.y,
            bfc = bfc,
            output = output,
            inheritedBackgroundArgb = inheritedBackgroundArgb,
            placeFloat = { box, y ->
                cursor.y = y
                placeFloat(box, cb, cursor, bfc, output, inheritedBackgroundArgb)
                cursor.y
            }
        )
        cursor.y = max(cursor.y, result.endY)
        if (anonymousParagraph && result.lineCount > 0) {
            cursor.addGap(if (flow.isHeading) ctx.spec.titleBottomSpacing else ctx.spec.paragraphSpacing)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Floats
    // ---------------------------------------------------------------------------------------

    private fun placeFloat(
        box: EpubBox,
        cb: ContainingBlock,
        cursor: FlowCursor,
        bfc: BfcState,
        output: FlowOutput,
        inheritedBackgroundArgb: Int
    ) {
        cursor.commit()
        val style = boxStyle(box) ?: return
        if (style.clear != EpubClearValue.NONE) {
            cursor.y = bfc.clearY(style.clear, cursor.y)
        }
        if (style.breakBefore) output.forcedBreaks += cursor.y
        val side = if (style.float == EpubFloatValue.RIGHT) EpubFloatValue.RIGHT else EpubFloatValue.LEFT
        val marginLeft = style.marginLeft.resolve(cb.width) ?: 0f
        val marginRight = style.marginRight.resolve(cb.width) ?: 0f
        val marginTop = style.marginTop.resolve(cb.width) ?: 0f
        val marginBottom = style.marginBottom.resolve(cb.width) ?: 0f
        val paddingLeft = style.paddingLeft.resolve(cb.width) ?: 0f
        val paddingRight = style.paddingRight.resolve(cb.width) ?: 0f
        val paddingTop = style.paddingTop.resolve(cb.width) ?: 0f
        val paddingBottom = style.paddingBottom.resolve(cb.width) ?: 0f
        val borderLeft = style.borderWidths[3]
        val borderRight = style.borderWidths[1]
        val borderTop = style.borderWidths[0]
        val borderBottom = style.borderWidths[2]
        val edgeExtras = paddingLeft + paddingRight + borderLeft + borderRight

        val available = (cb.width - marginLeft - marginRight).coerceAtLeast(1f)
        val declaredWidth = style.width.resolve(cb.width)?.let { width ->
            if (style.boxSizingBorderBox) (width - edgeExtras).coerceAtLeast(1f) else width
        }
        val contentWidth = (declaredWidth ?: shrinkToFit(box, (available - edgeExtras).coerceAtLeast(1f)))
            .coerceIn(1f, (available - edgeExtras).coerceAtLeast(1f))
            .let { width ->
                val maxWidth = style.maxWidth.resolve(cb.width)
                if (maxWidth != null) min(width, if (style.boxSizingBorderBox) maxWidth - edgeExtras else maxWidth) else width
            }
            .coerceAtLeast(1f)
        val outerWidth = contentWidth + edgeExtras + marginLeft + marginRight

        // Lay the float's content out at the origin first; its height decides where it fits.
        val sub = FlowOutput()
        val subBfc = BfcState()
        val subCursor = FlowCursor(0f)
        val childBackground = style.background.colorArgb
            ?.let(ctx::mappedBackground)
            ?.let { com.mozhi.reader.feature.reader.engine.EpubThemeColors.composite(it, inheritedBackgroundArgb) }
            ?: inheritedBackgroundArgb
        val subCb = ContainingBlock(0f, contentWidth)
        val decorationSlot = if (style.hasDecoration()) reserveDecoration(sub) else -1
        subCursor.y = paddingTop + borderTop
        when (box) {
            is EpubImageBox -> {
                val image = inline.resolveImage(
                    style = style,
                    textStart = box.textStart,
                    altText = box.altText,
                    attrWidth = box.attrWidth,
                    attrHeight = box.attrHeight,
                    percentBase = cb.width,
                    widthLimit = contentWidth,
                    horizontalEdges = edgeExtras,
                    verticalEdges = paddingTop + paddingBottom + borderTop + borderBottom
                )
                val contentTop = subCursor.y
                val contentEnd = contentTop + (image?.height ?: 1f)
                emitImageLine(
                    output = sub,
                    box = box,
                    image = image,
                    lineLeft = -paddingLeft - borderLeft,
                    lineTop = 0f,
                    lineBottom = contentEnd + paddingBottom + borderBottom,
                    imageLeft = 0f,
                    imageTopOffset = contentTop
                )
                subCursor.y = contentEnd
            }
            is EpubBlockBox -> box.children.forEach { child ->
                layoutChild(child, subCb, subCursor, subBfc, sub, childBackground)
            }
            is EpubInlineFlowBox -> layoutFlow(box, subCb, subCursor, subBfc, sub, childBackground)
            is EpubTableBox -> layoutTable(box, subCb, subCursor, subBfc, sub, childBackground)
        }
        subCursor.commit()
        var contentBottom = max(subCursor.y, subBfc.lowestBottom())
        if (box !is EpubImageBox) {
            (style.height as? ResolvedLength.Px)?.value?.let { explicit ->
                val contentHeight = if (style.boxSizingBorderBox) {
                    (explicit - paddingTop - paddingBottom - borderTop - borderBottom).coerceAtLeast(0f)
                } else {
                    explicit
                }
                val target = borderTop + paddingTop + contentHeight
                if (target > contentBottom) contentBottom = target
            }
        }
        val borderBoxHeight = contentBottom + paddingBottom + borderBottom
        val outerHeight = marginTop + borderBoxHeight + marginBottom
        // Negative vertical margins may collapse the occupied extent, but a float band must still
        // advance and remain queryable instead of ending above its own top edge.
        val bandHeight = max(outerHeight, 1f)
        if (decorationSlot >= 0) {
            sub.decorations[decorationSlot].decoration = ctx.themeBlockDecoration(
                style = style,
                left = 0f - paddingLeft - borderLeft,
                top = 0f,
                right = contentWidth + paddingRight + borderRight,
                bottom = borderBoxHeight
            )
        }

        // Find the first y at or below the flow cursor where the float fits between bands.
        var fitY = cursor.y
        var guard = 0
        while (guard++ < 64) {
            val window = bfc.windowAt(fitY, bandHeight, cb)
            if (window.second - window.first >= outerWidth - EPSILON) break
            val nextBottom = bfc.bands
                .filter { it.top < fitY + bandHeight && it.bottom > fitY }
                .minOfOrNull { it.bottom }
                ?: break
            fitY = max(nextBottom, fitY + 1f)
        }
        val window = bfc.windowAt(fitY, bandHeight, cb)
        val borderBoxLeft = if (side == EpubFloatValue.LEFT) {
            window.first + marginLeft
        } else {
            window.second - marginRight - contentWidth - edgeExtras
        }
        val contentLeft = borderBoxLeft + borderLeft + paddingLeft
        val top = fitY + marginTop

        // The sub-layout's decoration was recorded around x=0 content; translate everything.
        sub.translate(contentLeft, top)
        output.mergeFrom(sub)
        if (style.breakInsideAvoid || borderBoxHeight <= ctx.spec.visibleHeight * 0.5f) {
            output.keepRanges += top..(top + borderBoxHeight)
        }
        bfc.bands += FloatBand(
            side = side,
            top = fitY,
            bottom = fitY + bandHeight,
            innerEdge = if (side == EpubFloatValue.LEFT) {
                borderBoxLeft + edgeExtras + contentWidth + marginRight
            } else {
                borderBoxLeft - marginLeft
            }
        )
        if (style.breakAfter) output.forcedBreaks += fitY + bandHeight
    }

    private fun boxStyle(box: EpubBox): EpubStyle? = when (box) {
        is EpubImageBox -> box.style
        is EpubBlockBox -> box.style
        is EpubTableBox -> box.style
        is EpubInlineFlowBox -> box.blockStyle
    }

    /** max-content width clamped to availability, never below min-content. */
    private fun shrinkToFit(box: EpubBox, available: Float): Float {
        val (minContent, maxContent) = intrinsicWidths(box)
        return min(max(minContent, min(maxContent, available)), available).coerceAtLeast(1f)
    }

    private fun intrinsicWidths(box: EpubBox): Pair<Float, Float> = when (box) {
        is EpubImageBox -> {
            val image = inline.resolveImage(
                style = box.style,
                textStart = box.textStart,
                altText = box.altText,
                attrWidth = box.attrWidth,
                attrHeight = box.attrHeight,
                percentBase = ctx.spec.visibleWidth,
                widthLimit = ctx.spec.visibleWidth
            )
            (image?.width ?: 1f).let { it to it }
        }
        is EpubInlineFlowBox -> inline.intrinsicWidths(box, ctx.spec.themeBackgroundArgb)
        is EpubBlockBox -> {
            var minContent = 0f
            var maxContent = 0f
            box.children.forEach { child ->
                val (childMin, childMax) = intrinsicWidths(child)
                val style = boxStyle(child)
                val extras = style?.let {
                    (it.paddingLeft.resolve(0f) ?: 0f) + (it.paddingRight.resolve(0f) ?: 0f) +
                        it.borderWidths[1] + it.borderWidths[3] +
                        (it.marginLeft.resolve(0f) ?: 0f).coerceAtLeast(0f) +
                        (it.marginRight.resolve(0f) ?: 0f).coerceAtLeast(0f)
                } ?: 0f
                minContent = max(minContent, childMin + extras)
                maxContent = max(maxContent, childMax + extras)
            }
            val declared = (box.style.width as? ResolvedLength.Px)?.value
            if (declared != null) declared to declared else minContent to maxContent
        }
        is EpubTableBox -> {
            val declared = (box.style.width as? ResolvedLength.Px)?.value
            (declared ?: ctx.spec.visibleWidth * 0.9f).let { it to it }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Tables
    // ---------------------------------------------------------------------------------------

    private fun layoutTable(
        table: EpubTableBox,
        cb: ContainingBlock,
        cursor: FlowCursor,
        bfc: BfcState,
        output: FlowOutput,
        inheritedBackgroundArgb: Int
    ) {
        val style = table.style
        cursor.addGap(style.marginTop.resolve(cb.width) ?: 0f)
        cursor.commit()
        val marginLeft = style.marginLeft.resolve(cb.width)
        val marginRight = style.marginRight.resolve(cb.width)
        val tableWidth = (style.width.resolve(cb.width) ?: (cb.width - (marginLeft ?: 0f) - (marginRight ?: 0f)))
            .coerceIn(1f, cb.width)
        val autoCenter = style.marginLeft == ResolvedLength.Auto && style.marginRight == ResolvedLength.Auto
        val left = when {
            autoCenter -> cb.left + (cb.width - tableWidth) / 2f
            else -> cb.left + (marginLeft ?: 0f)
        }
        val tableTop = cursor.y
        val decorationSlot = if (style.hasDecoration()) reserveDecoration(output) else -1

        val columnCount = table.rows.maxOfOrNull { it.cells.size } ?: 0
        if (columnCount == 0) return
        val declaredWidths = arrayOfNulls<Float>(columnCount)
        table.rows.forEach { row ->
            row.cells.forEachIndexed { columnIndex, cell ->
                if (columnIndex < columnCount && declaredWidths[columnIndex] == null) {
                    declaredWidths[columnIndex] = cell.style.width.resolve(tableWidth)
                }
            }
        }
        val declaredTotal = declaredWidths.filterNotNull().sum()
        val freeColumns = declaredWidths.count { it == null }
        val freeWidth = ((tableWidth - declaredTotal) / freeColumns.coerceAtLeast(1)).coerceAtLeast(1f)
        val columnWidths = FloatArray(columnCount) { index -> declaredWidths[index] ?: freeWidth }

        table.rows.forEach { row ->
            ctx.cancellationCheck()
            val rowTop = cursor.y
            val cellOutputs = row.cells.mapIndexed { columnIndex, cell ->
                val columnWidth = columnWidths.getOrElse(columnIndex) { freeWidth }
                val paddingLeft = cell.style.paddingLeft.resolve(columnWidth) ?: 0f
                val paddingRight = cell.style.paddingRight.resolve(columnWidth) ?: 0f
                val paddingTop = cell.style.paddingTop.resolve(columnWidth) ?: 0f
                val paddingBottom = cell.style.paddingBottom.resolve(columnWidth) ?: 0f
                val contentWidth = (columnWidth - paddingLeft - paddingRight -
                    cell.style.borderWidths[1] - cell.style.borderWidths[3]).coerceAtLeast(1f)
                val sub = FlowOutput()
                val subCursor = FlowCursor(0f)
                val subBfc = BfcState()
                cell.children.forEach { child ->
                    layoutChild(child, ContainingBlock(0f, contentWidth), subCursor, subBfc, sub, inheritedBackgroundArgb)
                }
                subCursor.commit()
                val contentHeight = max(subCursor.y, subBfc.lowestBottom())
                CellLayout(cell, sub, contentHeight, paddingLeft, paddingTop, paddingBottom)
            }
            val rowHeight = cellOutputs.maxOf { layout ->
                layout.contentHeight + layout.paddingTop + layout.paddingBottom +
                    layout.cell.style.borderWidths[0] + layout.cell.style.borderWidths[2]
            }.coerceAtLeast(ctx.spec.contentLineStep)

            var cellLeft = left
            cellOutputs.forEachIndexed { columnIndex, layout ->
                val columnWidth = columnWidths.getOrElse(columnIndex) { freeWidth }
                val cellStyle = layout.cell.style
                if (cellStyle.hasDecoration()) {
                    output.decorations += FlowDecoration(
                        decoration = ctx.themeBlockDecoration(
                            style = cellStyle,
                            left = cellLeft,
                            top = rowTop,
                            right = cellLeft + columnWidth,
                            bottom = rowTop + rowHeight
                        ),
                        isCanvas = false,
                        zIndex = output.nextZIndex++
                    )
                }
                val innerHeight = layout.contentHeight
                val verticalSlack = rowHeight - innerHeight - layout.paddingTop - layout.paddingBottom -
                    cellStyle.borderWidths[0] - cellStyle.borderWidths[2]
                val verticalShift = when (cellStyle.verticalAlign) {
                    EpubVerticalAlignment.Middle -> (verticalSlack / 2f).coerceAtLeast(0f)
                    EpubVerticalAlignment.TextBottom -> verticalSlack.coerceAtLeast(0f)
                    else -> 0f
                }
                layout.output.translate(
                    cellLeft + layout.paddingLeft + cellStyle.borderWidths[3],
                    rowTop + layout.paddingTop + cellStyle.borderWidths[0] + verticalShift
                )
                output.mergeFrom(layout.output)
                cellLeft += columnWidth
            }
            cursor.y = rowTop + rowHeight
            output.keepRanges += rowTop..cursor.y
        }

        if (decorationSlot >= 0) {
            output.decorations[decorationSlot].decoration = ctx.themeBlockDecoration(
                style = style,
                left = left,
                top = tableTop,
                right = left + tableWidth,
                bottom = cursor.y
            )
        }
        cursor.addGap(style.marginBottom.resolve(cb.width) ?: 0f)
    }

    private class CellLayout(
        val cell: EpubTableCell,
        val output: FlowOutput,
        val contentHeight: Float,
        val paddingLeft: Float,
        val paddingTop: Float,
        val paddingBottom: Float
    )

    // ---------------------------------------------------------------------------------------
    // Geometry helpers
    // ---------------------------------------------------------------------------------------

    private class BoxGeometry(
        val borderBoxLeft: Float,
        val borderBoxRight: Float,
        val contentCb: ContainingBlock,
        val borderTop: Float,
        val borderBottom: Float,
        val paddingTop: Float,
        val paddingBottom: Float
    )

    private fun contentBox(style: EpubStyle, cb: ContainingBlock): BoxGeometry {
        val width = cb.width
        var marginLeft = style.marginLeft.resolve(width)
        var marginRight = style.marginRight.resolve(width)
        val paddingLeft = style.paddingLeft.resolve(width) ?: 0f
        val paddingRight = style.paddingRight.resolve(width) ?: 0f
        val paddingTop = style.paddingTop.resolve(width) ?: 0f
        val paddingBottom = style.paddingBottom.resolve(width) ?: 0f
        val borderLeft = style.borderWidths[3]
        val borderRight = style.borderWidths[1]
        val edgeExtras = paddingLeft + paddingRight + borderLeft + borderRight
        val declared = style.width.resolve(width)?.let { value ->
            if (style.boxSizingBorderBox) (value - edgeExtras).coerceAtLeast(1f) else value
        }
        val maxWidth = style.maxWidth.resolve(width)?.let { value ->
            if (style.boxSizingBorderBox) value - edgeExtras else value
        }
        val contentWidth = when {
            declared != null -> min(declared, maxWidth ?: Float.MAX_VALUE)
            else -> {
                val auto = width - (marginLeft ?: 0f) - (marginRight ?: 0f) - edgeExtras
                min(auto, maxWidth ?: Float.MAX_VALUE)
            }
        }.coerceAtLeast(1f)
        if (declared != null || maxWidth != null && contentWidth < width - edgeExtras) {
            val slack = width - contentWidth - edgeExtras
            when {
                marginLeft == null && marginRight == null -> {
                    marginLeft = slack / 2f
                    marginRight = slack / 2f
                }
                marginLeft == null -> marginLeft = slack - (marginRight ?: 0f)
                marginRight == null -> marginRight = slack - marginLeft
            }
        }
        val borderBoxLeft = cb.left + (marginLeft ?: 0f)
        val borderBoxRight = borderBoxLeft + contentWidth + edgeExtras
        return BoxGeometry(
            borderBoxLeft = borderBoxLeft,
            borderBoxRight = borderBoxRight,
            contentCb = ContainingBlock(borderBoxLeft + borderLeft + paddingLeft, borderBoxRight - borderRight - paddingRight),
            borderTop = style.borderWidths[0],
            borderBottom = style.borderWidths[2],
            paddingTop = paddingTop,
            paddingBottom = paddingBottom
        )
    }

    private fun reserveDecoration(output: FlowOutput): Int {
        output.decorations += FlowDecoration(
            decoration = com.mozhi.reader.feature.reader.engine.TextBlockDecoration(0f, 0f, 0f, 0f),
            isCanvas = false,
            zIndex = output.nextZIndex++
        )
        return output.decorations.lastIndex
    }

    private fun blockGapTop(box: EpubBlockBox): Float {
        val style = box.style
        val declared = "margin-top" in style.appliedProperties
        val fallback = if (box.tag in HEADING_TAGS) ctx.spec.titleTopSpacing else 0f
        return ctx.blockGap(style.marginTop.resolve(ctx.spec.visibleWidth), declared, fallback)
    }

    private fun blockGapBottom(box: EpubBlockBox): Float {
        val style = box.style
        val declared = "margin-bottom" in style.appliedProperties
        val fallback = when {
            box.tag in HEADING_TAGS -> ctx.spec.titleBottomSpacing
            box.tag in PARAGRAPH_TAGS -> ctx.spec.paragraphSpacing
            else -> 0f
        }
        return ctx.blockGap(style.marginBottom.resolve(ctx.spec.visibleWidth), declared, fallback)
    }

    private companion object {
        const val EPSILON = 0.5f
        const val DEFAULT_RULE_COLOR = 0xFF888888.toInt()
        val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
        val PARAGRAPH_TAGS = setOf("p", "li", "dd", "dt", "figcaption", "blockquote")
        val TEXT_OWNER_TAGS = setOf(
            "p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "dd", "dt",
            "figcaption", "td", "th", "caption", "pre"
        )
    }
}
