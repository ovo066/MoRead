package com.mozhi.reader.feature.reader.engine

/**
 * Fixed-metrics measurement so layout is verifiable on the JVM: every CJK char is 10px, ASCII
 * letters 5px, spaces 3px; lines break greedily. The greedy break is not StaticLayout, but the
 * typesetter only consumes break offsets, so pagination/indent/justify logic is exercised exactly
 * as in production.
 */
internal class FakeMeasure : TextMeasure {
    override fun metrics(isTitle: Boolean): LineMetrics =
        if (isTitle) LineMetrics(textHeight = 27f, descent = 5f)
        else LineMetrics(textHeight = 20f, descent = 4f)

    override fun charWidths(text: String, isTitle: Boolean): FloatArray =
        FloatArray(text.length) { index -> charWidth(text[index]) }

    override fun breakLines(
        text: String,
        isTitle: Boolean,
        availableWidth: Float,
        firstLineIndent: Float
    ): IntArray {
        val starts = ArrayList<Int>()
        starts.add(0)
        var lineWidth = firstLineIndent
        for (index in text.indices) {
            val width = charWidth(text[index])
            if (lineWidth + width > availableWidth && index > starts.last()) {
                starts.add(index)
                lineWidth = 0f
            }
            lineWidth += width
        }
        return starts.toIntArray()
    }

    override fun indentColumnWidth(): Float = 10f

    private fun charWidth(char: Char): Float = when {
        char == ' ' -> 3f
        char.code < 128 -> 5f
        else -> 10f
    }
}
