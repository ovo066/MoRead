package com.mozhi.reader.feature.reader.engine

import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File

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

    private val styledPaints = HashMap<MeasuredTextStyle, TextPaint>()
    private val typefaces = HashMap<String, Typeface?>()

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

    override fun metrics(style: MeasuredTextStyle): LineMetrics = paintFor(style).lineMetrics()

    override fun charWidths(text: String, style: MeasuredTextStyle): FloatArray {
        val widths = FloatArray(text.length)
        paintFor(style).getTextWidths(text, 0, text.length, widths)
        return widths
    }

    private fun paintFor(style: MeasuredTextStyle): TextPaint = styledPaints.getOrPut(style) {
        val base = if (style.isTitle) titlePaint else contentPaint
        TextPaint(base).apply {
            textSize = base.textSize * style.textSizeScale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)
            letterSpacing = base.letterSpacing + style.letterSpacingEm
            val family = style.fontFilePath
                ?.let { path -> typefaces.getOrPut(path) { runCatching { Typeface.createFromFile(File(path)) }.getOrNull() } }
                ?: style.fontFamily.toSystemTypeface()
                ?: base.typeface
            val typefaceStyle = (if (style.bold) Typeface.BOLD else Typeface.NORMAL) or
                (if (style.italic) Typeface.ITALIC else Typeface.NORMAL)
            typeface = if (typefaceStyle == Typeface.NORMAL) family else Typeface.create(family, typefaceStyle)
        }
    }

    private fun TextPaint.lineMetrics(): LineMetrics {
        val metrics = fontMetrics
        return LineMetrics(
            textHeight = metrics.descent - metrics.ascent + metrics.leading,
            descent = metrics.descent
        )
    }

    private companion object {
        const val INDENT_CHAR = "　"
        const val MIN_TEXT_SCALE = 0.5f
        const val MAX_TEXT_SCALE = 3f
    }
}

private fun String?.toSystemTypeface(): Typeface? = when {
    this == null -> null
    contains("mono", true) -> Typeface.MONOSPACE
    contains("sans", true) || contains("黑体") -> Typeface.SANS_SERIF
    contains("serif", true) || contains("宋体") || contains("明朝") -> Typeface.SERIF
    else -> null
}
