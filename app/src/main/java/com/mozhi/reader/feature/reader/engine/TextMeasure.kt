package com.mozhi.reader.feature.reader.engine

/**
 * Measurement contract between the pure-Kotlin typesetter and the platform text stack.
 *
 * The Android implementation wraps `TextPaint` + `StaticLayout`; tests supply a fixed-metrics fake
 * so pagination, indentation and justification are verifiable on the JVM.
 */
interface TextMeasure {

    /** Natural glyph box height and descent for the given role, in pixels. */
    fun metrics(isTitle: Boolean): LineMetrics

    /**
     * Per-UTF-16-char advance widths, Legado's `getTextWidths` contract: the lead char of a
     * surrogate pair carries the full width, the trail char carries 0.
     */
    fun charWidths(text: String, isTitle: Boolean): FloatArray

    /**
     * Line break offsets for a paragraph laid out at [availableWidth] with the first line narrowed
     * by [firstLineIndent]. Returns ascending start indices; the first element is always 0.
     * Implementations must break at word boundaries for Latin text (Legado delegates this to
     * `StaticLayout`).
     */
    fun breakLines(
        text: String,
        isTitle: Boolean,
        availableWidth: Float,
        firstLineIndent: Float
    ): IntArray

    /** Width of one ideographic space (U+3000) at content size, the unit of paragraph indent. */
    fun indentColumnWidth(): Float

    fun metrics(style: MeasuredTextStyle): LineMetrics {
        val base = metrics(style.isTitle)
        return LineMetrics(
            textHeight = base.textHeight * style.textSizeScale,
            descent = base.descent * style.textSizeScale
        )
    }

    fun charWidths(text: String, style: MeasuredTextStyle): FloatArray =
        charWidths(text, style.isTitle).also { widths ->
            for (index in widths.indices) widths[index] *= style.textSizeScale
        }
}

data class MeasuredTextStyle(
    val isTitle: Boolean,
    val textSizeScale: Float = 1f,
    val fontFilePath: String? = null,
    val fontFamily: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val letterSpacingEm: Float = 0f
)

data class LineMetrics(
    val textHeight: Float,
    val descent: Float
)

/**
 * Groups UTF-16 chars into drawable clusters, ported from Legado's `measureTextSplit`: a base char
 * absorbs every following zero-advance char (surrogate trails, combining marks) except genuine
 * zero-width characters, which stay their own cluster so they remain break opportunities.
 */
internal fun clusterText(
    text: String,
    widths: FloatArray,
    from: Int,
    until: Int,
    outClusters: MutableList<String>,
    outWidths: MutableList<Float>
) {
    var index = from
    while (index < until) {
        var end = index + 1
        while (end < until && widths[end] == 0f && text[end].code !in ZERO_WIDTH_CODES) {
            end++
        }
        outClusters.add(text.substring(index, end))
        outWidths.add(widths[index])
        index = end
    }
}

private val ZERO_WIDTH_CODES = intArrayOf(8203, 8204, 8205, 8288)
