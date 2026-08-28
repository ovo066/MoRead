package com.mozhi.reader.feature.reader.engine.epub

import com.mozhi.reader.core.datastore.PublisherStyleMode
import com.mozhi.reader.core.datastore.ReaderSyntaxFont
import com.mozhi.reader.core.datastore.ReaderSyntaxHighlighter
import com.mozhi.reader.core.datastore.ReaderSyntaxRule
import com.mozhi.reader.core.datastore.ReaderSyntaxStyleSpan
import com.mozhi.reader.core.epub.style.EpubClearValue
import com.mozhi.reader.core.epub.style.EpubFloatValue
import com.mozhi.reader.core.epub.style.EpubShadow
import com.mozhi.reader.core.epub.style.EpubStyle
import com.mozhi.reader.core.epub.style.ResolvedLength
import com.mozhi.reader.core.epub.style.resolve
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.feature.reader.engine.BackgroundSizeMode
import com.mozhi.reader.feature.reader.engine.EpubThemeColors
import com.mozhi.reader.feature.reader.engine.InlineImageSource
import com.mozhi.reader.feature.reader.engine.InlineMarkerReservation
import com.mozhi.reader.feature.reader.engine.MeasuredTextStyle
import com.mozhi.reader.feature.reader.engine.TextBlockDecoration
import com.mozhi.reader.feature.reader.engine.TextBoxShadow
import com.mozhi.reader.feature.reader.engine.TextLine
import com.mozhi.reader.feature.reader.engine.TextMeasure
import com.mozhi.reader.feature.reader.engine.TypesetSpec
import kotlin.math.max
import kotlin.math.min

/** Horizontal containing block in absolute content coordinates. */
internal data class ContainingBlock(val left: Float, val right: Float) {
    val width: Float get() = (right - left).coerceAtLeast(1f)
}

/** One float currently occupying part of the block formatting context. */
internal class FloatBand(
    val side: EpubFloatValue,
    val top: Float,
    val bottom: Float,
    /** For LEFT floats the occupied right edge, for RIGHT floats the occupied left edge. */
    val innerEdge: Float
)

internal class BfcState {
    val bands = ArrayList<FloatBand>()

    fun windowAt(y: Float, height: Float, cb: ContainingBlock): Pair<Float, Float> {
        var left = cb.left
        var right = cb.right
        bands.forEach { band ->
            if (band.top < y + height && band.bottom > y) {
                when (band.side) {
                    EpubFloatValue.LEFT -> left = max(left, band.innerEdge)
                    EpubFloatValue.RIGHT -> right = min(right, band.innerEdge)
                    else -> Unit
                }
            }
        }
        if (right <= left) right = left + 1f
        return left to right
    }

    fun clearY(side: EpubClearValue, y: Float): Float {
        var cleared = y
        bands.forEach { band ->
            val applies = when (side) {
                EpubClearValue.LEFT -> band.side == EpubFloatValue.LEFT
                EpubClearValue.RIGHT -> band.side == EpubFloatValue.RIGHT
                EpubClearValue.BOTH -> true
                EpubClearValue.NONE -> false
            }
            if (applies) cleared = max(cleared, band.bottom)
        }
        return cleared
    }

    fun lowestBottom(): Float = bands.maxOfOrNull { it.bottom } ?: 0f
}

/** Vertical flow cursor with CSS-style margin collapsing. */
internal class FlowCursor(var y: Float) {
    private var pendingPositive = 0f
    private var pendingNegative = 0f
    var hasPending = false
        private set

    fun addGap(gap: Float) {
        if (gap >= 0f) pendingPositive = max(pendingPositive, gap) else pendingNegative = min(pendingNegative, gap)
        hasPending = true
    }

    fun commit() {
        if (!hasPending) return
        y += pendingPositive + pendingNegative
        pendingPositive = 0f
        pendingNegative = 0f
        hasPending = false
    }
}

/** A laid-out line in continuous chapter coordinates plus its pagination metadata. */
internal class FlowLine(
    var line: TextLine,
    val paragraphId: Int,
    val orphans: Int,
    val widows: Int,
    val indexInParagraph: Int,
    var paragraphLineCount: Int,
    /** Heading lines keep at least one following content line on the same page. */
    val keepWithNext: Boolean
)

internal class FlowDecoration(
    var decoration: TextBlockDecoration,
    /** Chapter-canvas decorations yield to a user-selected paper. */
    var isCanvas: Boolean,
    /** Insertion order doubles as z-order: parents were reserved before children. */
    val zIndex: Int
)

internal class FlowOutput {
    val lines = ArrayList<FlowLine>()
    val decorations = ArrayList<FlowDecoration>()
    /** y positions where a page must end before the given coordinate. */
    val forcedBreaks = ArrayList<Float>()
    /** y ranges that should stay on one page when they fit. */
    val keepRanges = ArrayList<ClosedFloatingPointRange<Float>>()
    var nextParagraphId = 0
    var nextZIndex = 0

    fun translate(dx: Float, dy: Float) {
        lines.forEach { flowLine -> flowLine.line = translateLine(flowLine.line, dx, dy) }
        decorations.forEach { entry ->
            entry.decoration = entry.decoration.copy(
                left = entry.decoration.left + dx,
                top = entry.decoration.top + dy,
                right = entry.decoration.right + dx,
                bottom = entry.decoration.bottom + dy
            )
        }
        for (index in forcedBreaks.indices) forcedBreaks[index] += dy
        for (index in keepRanges.indices) {
            keepRanges[index] = (keepRanges[index].start + dy)..(keepRanges[index].endInclusive + dy)
        }
    }

    fun mergeFrom(other: FlowOutput) {
        val paragraphOffset = nextParagraphId
        val zOffset = nextZIndex
        other.lines.forEach { flowLine ->
            lines += FlowLine(
                line = flowLine.line,
                paragraphId = flowLine.paragraphId + paragraphOffset,
                orphans = flowLine.orphans,
                widows = flowLine.widows,
                indexInParagraph = flowLine.indexInParagraph,
                paragraphLineCount = flowLine.paragraphLineCount,
                keepWithNext = flowLine.keepWithNext
            )
        }
        other.decorations.forEach { entry ->
            decorations += FlowDecoration(entry.decoration, entry.isCanvas, entry.zIndex + zOffset)
        }
        forcedBreaks += other.forcedBreaks
        keepRanges += other.keepRanges
        nextParagraphId += other.nextParagraphId
        nextZIndex += other.nextZIndex
    }

    companion object {
        fun translateLine(line: TextLine, dx: Float, dy: Float): TextLine {
            line.lineTop += dy
            line.lineBase += dy
            line.lineBottom += dy
            line.inlineDecorations = line.inlineDecorations.map { decoration ->
                decoration.copy(
                    left = decoration.left + dx,
                    top = decoration.top + dy,
                    right = decoration.right + dx,
                    bottom = decoration.bottom + dy
                )
            }
            line.rubyPlacements = line.rubyPlacements.map { ruby ->
                ruby.copy(left = ruby.left + dx, right = ruby.right + dx, baseline = ruby.baseline + dy)
            }
            if (dx == 0f) return line
            return TextLine(
                text = line.text,
                columns = line.columns.map { column ->
                    com.mozhi.reader.feature.reader.engine.TextColumn(
                        start = column.start + dx,
                        end = column.end + dx,
                        charData = column.charData,
                        syntaxColorArgb = column.syntaxColorArgb,
                        syntaxBackgroundArgb = column.syntaxBackgroundArgb,
                        syntaxUnderline = column.syntaxUnderline,
                        syntaxFont = column.syntaxFont,
                        syntaxFontAssetId = column.syntaxFontAssetId,
                        syntaxBold = column.syntaxBold,
                        syntaxItalic = column.syntaxItalic,
                        syntaxStrikethrough = column.syntaxStrikethrough,
                        textSizeScale = column.textSizeScale,
                        fontFilePath = column.fontFilePath,
                        fontFamily = column.fontFamily,
                        baselineShiftPx = column.baselineShiftPx,
                        opacity = column.opacity,
                        sourceLength = column.sourceLength,
                        inlineMarkerKind = column.inlineMarkerKind,
                        inlineMarkerOffset = column.inlineMarkerOffset,
                        linkHref = column.linkHref
                    )
                },
                lineTop = line.lineTop,
                lineBase = line.lineBase,
                lineBottom = line.lineBottom,
                startX = line.startX + dx,
                isTitle = line.isTitle,
                isParagraphEnd = line.isParagraphEnd,
                chapterPosition = line.chapterPosition,
                charLength = line.charLength,
                justifyGapExtra = line.justifyGapExtra,
                inlineImage = line.inlineImage,
                inlineImages = line.inlineImages.map { it.copy(left = it.left + dx) },
                inlineGlyphImages = line.inlineGlyphImages.map { it.copy(left = it.left + dx) },
                rule = line.rule,
                inlineDecorations = line.inlineDecorations,
                rubyPlacements = line.rubyPlacements
            )
        }
    }
}

/** Paint-level style of one text cluster after theme mapping and font resolution. */
internal data class ResolvedRunStyle(
    val measureStyle: MeasuredTextStyle,
    val colorArgb: Int?,
    val backgroundArgb: Int?,
    val underline: Boolean,
    val strikethrough: Boolean,
    val syntaxFont: ReaderSyntaxFont,
    val syntaxFontAssetId: String?,
    val baselineShiftPx: Float,
    val lineHeightPx: Float?,
    val opacity: Float
)

/** Shared services for the block/inline/pagination layers of one chapter layout run. */
internal class EpubLayoutContext(
    val spec: TypesetSpec,
    val measure: TextMeasure,
    val body: String,
    val bundle: EpubLayoutChapterBundle,
    val inlineMarkers: List<InlineMarkerReservation>,
    val cancellationCheck: () -> Unit,
    val immersivePage: Boolean,
    val dominantBodyFamily: String?
) {
    var imageSources: Map<Int, InlineImageSource> = emptyMap()
    private val syntax = SyntaxStyleMap(body, spec.syntaxHighlightRules)
    private val fontPathCache = HashMap<FontKey, String?>()

    fun syntaxAt(offset: Int): ReaderSyntaxStyleSpan? = syntax.at(offset)

    fun mappedBackground(color: Int?): Int? = EpubThemeColors.background(color, spec.darkTheme)

    fun mappedForeground(color: Int?, background: Int?): Int? = color?.let {
        EpubThemeColors.foreground(it, background ?: spec.themeBackgroundArgb, spec.themeTextArgb)
    }

    fun baseFontSize(isTitle: Boolean): Float =
        if (isTitle) spec.titleFontSizePx else spec.contentFontSizePx

    fun defaultLineStep(isTitle: Boolean): Float =
        if (isTitle) spec.titleLineStep else spec.contentLineStep

    /** Publisher line height per publisher-style mode, in px, or null for the reader default. */
    fun requestedLineStep(style: EpubStyle, isTitle: Boolean): Float {
        val defaultStep = defaultLineStep(isTitle)
        val publisher = style.lineHeight ?: return defaultStep
        return when (spec.publisherStyleMode) {
            PublisherStyleMode.RESPECT -> publisher
            PublisherStyleMode.SMART -> {
                val userScale = (spec.contentLineStep / spec.contentFontSizePx.coerceAtLeast(1f)) /
                    DEFAULT_READER_LINE_HEIGHT
                publisher * userScale.coerceIn(MIN_LINE_HEIGHT_FACTOR, MAX_LINE_HEIGHT_FACTOR)
            }
            PublisherStyleMode.TAKE_OVER -> defaultStep
        }
    }

    /** Vertical gap owned by a block edge, respecting the publisher-style mode. */
    fun blockGap(marginPx: Float?, declared: Boolean, fallback: Float): Float {
        if (!declared || marginPx == null) return fallback
        return when (spec.publisherStyleMode) {
            PublisherStyleMode.RESPECT -> marginPx
            PublisherStyleMode.SMART -> {
                val marginEm = marginPx / spec.contentFontSizePx.coerceAtLeast(1f)
                spec.paragraphSpacing * (marginEm / DEFAULT_PUBLISHER_PARAGRAPH_EM)
                    .coerceIn(0f, MAX_PARAGRAPH_GAP_FACTOR)
            }
            PublisherStyleMode.TAKE_OVER -> fallback
        }
    }

    fun resolveRunStyle(
        style: EpubStyle,
        isTitle: Boolean,
        inheritedBackgroundArgb: Int,
        sourceOffset: Int
    ): ResolvedRunStyle {
        val syntaxSpan = sourceOffset.takeIf { it >= 0 }?.let(::syntaxAt)
        val baseSize = baseFontSize(isTitle).coerceAtLeast(1f)
        val sizeScale = (style.fontSizePx / baseSize).coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)
        val publisherFamily = style.fontFamilies.firstOrNull()
        val replaceDominantBodyFont = spec.publisherStyleMode != PublisherStyleMode.RESPECT &&
            !isTitle && publisherFamily != null && publisherFamily.lowercase() == dominantBodyFamily
        val families = if (replaceDominantBodyFont) emptyList() else style.fontFamilies
        val resolvedFont = resolveFont(families, style.fontWeight, style.italic)
        val adaptedBackground = mappedBackground(style.background.colorArgb)
        val actualBackground = adaptedBackground
            ?.let { EpubThemeColors.composite(it, inheritedBackgroundArgb) }
            ?: inheritedBackgroundArgb
        val publisherColor = style.colorArgb.takeIf { "color" in style.appliedProperties }
        val adaptedColor = publisherColor?.let { color ->
            // 整页插画上的标题色是出版商为图片手工挑选的；特殊页与「完全尊重」模式保持原色。
            if (immersivePage || spec.publisherStyleMode == PublisherStyleMode.RESPECT) {
                color
            } else {
                EpubThemeColors.foreground(color, actualBackground, spec.themeTextArgb)
            }
        }
        val shift = when (val align = style.verticalAlign) {
            is com.mozhi.reader.core.epub.style.EpubVerticalAlignment.Super -> -style.fontSizePx * 0.35f
            is com.mozhi.reader.core.epub.style.EpubVerticalAlignment.Sub -> style.fontSizePx * 0.2f
            is com.mozhi.reader.core.epub.style.EpubVerticalAlignment.Shift -> align.px
            else -> 0f
        }
        val hasPublisherFont = publisherFamily != null
        return ResolvedRunStyle(
            measureStyle = MeasuredTextStyle(
                isTitle = isTitle,
                textSizeScale = sizeScale,
                fontFilePath = resolvedFont.filePath,
                fontFamily = resolvedFont.familyName,
                bold = style.fontWeight >= 600 || syntaxSpan?.bold == true,
                italic = style.italic || syntaxSpan?.italic == true,
                letterSpacingEm = if (style.fontSizePx > 0f) style.letterSpacingPx / style.fontSizePx else 0f
            ),
            // Publisher styling wins property-by-property; user syntax rules only fill gaps.
            colorArgb = adaptedColor ?: syntaxSpan?.colorArgb,
            backgroundArgb = adaptedBackground ?: syntaxSpan?.backgroundArgb,
            underline = style.underline || syntaxSpan?.underline == true,
            strikethrough = style.strikethrough || syntaxSpan?.strikethrough == true,
            syntaxFont = if (hasPublisherFont) ReaderSyntaxFont.INHERIT else syntaxSpan?.font ?: ReaderSyntaxFont.INHERIT,
            syntaxFontAssetId = if (hasPublisherFont) null else syntaxSpan?.fontAssetId,
            baselineShiftPx = shift,
            lineHeightPx = style.lineHeight,
            opacity = style.opacity
        )
    }

    /** Walks the font fallback chain and returns the first face the book actually ships. */
    fun resolveFont(families: List<String>, weight: Int, italic: Boolean): ResolvedFontRef {
        families.forEach { family ->
            val key = FontKey(family.lowercase(), weight, italic)
            val cached = fontPathCache[key]
            if (cached != null) return ResolvedFontRef(cached, family)
            if (!fontPathCache.containsKey(key)) {
                val matches = bundle.fontFaces.filter { it.family.equals(family, true) }
                val path = matches.minByOrNull { face ->
                    val italicPenalty = if (face.italic == italic) 0 else 1000
                    italicPenalty + kotlin.math.abs((face.weight ?: 400) - weight)
                }?.filePath ?: bundle.fontPaths[family.lowercase()]
                fontPathCache[key] = path
                if (path != null) return ResolvedFontRef(path, family)
            }
            if (family.lowercase() in GENERIC_FAMILIES) return ResolvedFontRef(null, family)
        }
        val first = families.firstOrNull()
        return ResolvedFontRef(null, first)
    }

    fun themeBlockDecoration(
        style: EpubStyle,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        suppressBackground: Boolean = false
    ): TextBlockDecoration {
        val mappedBg = mappedBackground(style.background.colorArgb)
        val boxWidth = (right - left).coerceAtLeast(1f)
        val explicitW = style.background.size.getOrNull(0)?.resolve(boxWidth) ?: 0f
        val explicitH = (style.background.size.getOrNull(1) ?: style.background.size.getOrNull(0))
            ?.resolve(bottom - top) ?: 0f
        val stretch = style.background.sizeMode == "explicit" &&
            style.background.size.all { it is ResolvedLength.Percent && it.value >= 99.9f }
        return TextBlockDecoration(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            backgroundColorArgb = mappedBg.takeUnless { suppressBackground },
            backgroundImagePath = style.background.imageHref
                ?.takeUnless { suppressBackground }
                ?.let(bundle.resourcePaths::get),
            backgroundSizeMode = when {
                stretch -> BackgroundSizeMode.STRETCH
                style.background.sizeMode == "cover" -> BackgroundSizeMode.COVER
                style.background.sizeMode == "contain" -> BackgroundSizeMode.CONTAIN
                style.background.sizeMode == "explicit" -> BackgroundSizeMode.EXPLICIT
                else -> BackgroundSizeMode.AUTO
            },
            backgroundSizeWidth = explicitW,
            backgroundSizeHeight = explicitH,
            backgroundRepeatX = style.background.repeat in setOf("repeat", "repeat-x"),
            backgroundRepeatY = style.background.repeat in setOf("repeat", "repeat-y"),
            backgroundPositionX = style.background.positionX,
            backgroundPositionY = style.background.positionY,
            borderColorArgb = null,
            borderWidth = 0f,
            borderTopColorArgb = mappedForeground(style.borderColors[0], mappedBg),
            borderRightColorArgb = mappedForeground(style.borderColors[1], mappedBg),
            borderBottomColorArgb = mappedForeground(style.borderColors[2], mappedBg),
            borderLeftColorArgb = mappedForeground(style.borderColors[3], mappedBg),
            borderTopWidth = style.borderWidths[0],
            borderRightWidth = style.borderWidths[1],
            borderBottomWidth = style.borderWidths[2],
            borderLeftWidth = style.borderWidths[3],
            borderRadius = 0f,
            borderTopLeftRadius = style.borderRadii[0].resolve(boxWidth) ?: 0f,
            borderTopRightRadius = style.borderRadii[1].resolve(boxWidth) ?: 0f,
            borderBottomRightRadius = style.borderRadii[2].resolve(boxWidth) ?: 0f,
            borderBottomLeftRadius = style.borderRadii[3].resolve(boxWidth) ?: 0f,
            boxShadows = style.boxShadows.map(EpubShadow::toTextShadow),
            opacity = style.opacity
        )
    }

    data class ResolvedFontRef(val filePath: String?, val familyName: String?)

    private data class FontKey(val family: String, val weight: Int, val italic: Boolean)

    class SyntaxStyleMap(text: String, rules: List<ReaderSyntaxRule>) {
        private val styles = arrayOfNulls<ReaderSyntaxStyleSpan>(text.length)

        init {
            ReaderSyntaxHighlighter.spans(text, rules).forEach { span ->
                for (index in span.start until span.endExclusive.coerceAtMost(text.length)) styles[index] = span
            }
        }

        fun at(index: Int): ReaderSyntaxStyleSpan? = styles.getOrNull(index)
    }

    companion object {
        const val MIN_TEXT_SCALE = 0.5f
        const val MAX_TEXT_SCALE = 3f
        const val DEFAULT_READER_LINE_HEIGHT = 1.55f
        const val MIN_LINE_HEIGHT_FACTOR = 0.8f
        const val MAX_LINE_HEIGHT_FACTOR = 1.5f
        const val DEFAULT_PUBLISHER_PARAGRAPH_EM = 0.55f
        const val MAX_PARAGRAPH_GAP_FACTOR = 4f
        val GENERIC_FAMILIES = setOf("serif", "sans-serif", "monospace", "cursive", "fantasy", "system-ui")
    }
}

internal fun EpubShadow.toTextShadow(): TextBoxShadow = TextBoxShadow(
    offsetX = offsetXPx,
    offsetY = offsetYPx,
    blurRadius = blurPx,
    spreadRadius = spreadPx,
    colorArgb = colorArgb,
    inset = inset
)
