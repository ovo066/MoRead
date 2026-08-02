package com.mozhi.reader.feature.reader.engine

import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Platform measurement for the typesetter. Line breaking is delegated to [StaticLayout] exactly
 * like Legado's default path — only `getLineStart`/`getLineEnd` are consumed — which keeps Latin
 * words unbroken and applies the platform's CJK line-break rules. The first-line paragraph indent
 * is fed through [StaticLayout.Builder.setIndents] instead of injecting U+3000 characters so char
 * offsets stay aligned with the stored body text.
 */
class AndroidTextMeasure(
    contentSizePx: Float,
    titleSizePx: Float,
    typeface: Typeface,
    letterSpacingEm: Float = 0f
) : TextMeasure {

    val contentPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = contentSizePx
        this.typeface = typeface
        letterSpacing = letterSpacingEm
    }

    val titlePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = titleSizePx
        this.typeface = Typeface.create(typeface, Typeface.BOLD)
        letterSpacing = letterSpacingEm
    }

    private val contentMetrics = contentPaint.lineMetrics()
    private val titleMetrics = titlePaint.lineMetrics()
    private val indentWidth = StaticLayout.getDesiredWidth(INDENT_CHAR, contentPaint)

    override fun metrics(isTitle: Boolean): LineMetrics =
        if (isTitle) titleMetrics else contentMetrics

    override fun charWidths(text: String, isTitle: Boolean): FloatArray {
        val paint = if (isTitle) titlePaint else contentPaint
        val widths = FloatArray(text.length)
        paint.getTextWidths(text, 0, text.length, widths)
        return widths
    }

    override fun breakLines(
        text: String,
        isTitle: Boolean,
        availableWidth: Float,
        firstLineIndent: Float
    ): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val paint = if (isTitle) titlePaint else contentPaint
        val width = availableWidth.toInt().coerceAtLeast(1)
        val builder = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(true)
        if (firstLineIndent > 0f) {
            builder.setIndents(intArrayOf(firstLineIndent.toInt(), 0), null)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUseLineSpacingFromFallbacks(false)
        }
        val layout = builder.build()
        return IntArray(layout.lineCount) { line -> layout.getLineStart(line) }
    }

    override fun indentColumnWidth(): Float = indentWidth

    private fun TextPaint.lineMetrics(): LineMetrics {
        val metrics = fontMetrics
        return LineMetrics(
            textHeight = metrics.descent - metrics.ascent + metrics.leading,
            descent = metrics.descent
        )
    }

    private companion object {
        const val INDENT_CHAR = "　"
    }
}
