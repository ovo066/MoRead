package com.mozhi.reader.feature.review

import android.graphics.*
import android.text.*
import android.text.style.MetricAffectingSpan
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.feature.reader.render.shader

internal fun reviewTemplateOptions(base: ReviewExportOptions, template: ReviewShareTemplate?, settings: ReaderSettings,
    bookId: Long, dark: Boolean): ReviewExportOptions = base.copy(template = template,
    font = if (template?.fontChoice.isNullOrBlank()) base.font else reviewFontSpec(settings.copy(reviewFont = template.fontChoice), bookId, dark),
    fontPaths = settings.fontLibrary.associate { it.id to it.filePath }, imagePaths = settings.imageLibrary.associate { it.id to it.filePath })

private fun syntaxTypeface(font: ReaderSyntaxFont?, id: String?, fallback: Typeface, paths: Map<String, String>): Typeface = when (font) {
    ReaderSyntaxFont.SYSTEM -> Typeface.DEFAULT
    ReaderSyntaxFont.SERIF -> Typeface.SERIF
    ReaderSyntaxFont.SANS_SERIF -> Typeface.SANS_SERIF
    ReaderSyntaxFont.MONOSPACE -> Typeface.MONOSPACE
    ReaderSyntaxFont.CUSTOM -> paths[id]?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() } ?: fallback
    else -> fallback
}

private class ReviewTextSpan(private val style: ReaderSyntaxStyleSpan, private val paths: Map<String, String>) : MetricAffectingSpan() {
    var gradient: Shader? = null
    override fun updateMeasureState(paint: TextPaint) {
        val base = syntaxTypeface(style.font, style.fontAssetId, paint.typeface ?: Typeface.DEFAULT, paths)
        paint.typeface = Typeface.create(base, (if (style.bold) Typeface.BOLD else 0) or (if (style.italic) Typeface.ITALIC else 0))
    }
    override fun updateDrawState(paint: TextPaint) {
        updateMeasureState(paint)
        style.colorArgb?.let { paint.color = it }
        paint.shader = gradient
        if (gradient != null) paint.alpha = 255
        paint.isUnderlineText = style.underline
        paint.isStrikeThruText = style.strikethrough
    }
}

internal class ReviewTextBlock(val layout: StaticLayout, private val backgrounds: List<Pair<ReaderSyntaxStyleSpan, Path>>,
    private val imagePaths: Map<String, String>) {
    val height get() = layout.height
    fun draw(canvas: Canvas) {
        backgrounds.forEach { (style, path) ->
            val bounds = RectF().also { path.computeBounds(it, true) }
            canvas.save()
            canvas.clipPath(path)
            style.backgroundArgb?.let { canvas.drawColor(it) }
            style.paintSpan?.paint?.let { drawReviewBackground(canvas, bounds, it, imagePaths) }
            canvas.restore()
        }
        layout.draw(canvas)
    }
}

internal fun reviewTextBlock(text: String, width: Int, size: Float, color: Int, options: ReviewExportOptions,
    css: ReviewTemplateCss?, quotation: Boolean): ReviewTextBlock {
    val declarations = css?.declarations
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = if (quotation) options.font.typeface() else Typeface.create("sans-serif", Typeface.NORMAL)
        if (quotation && declarations != null) {
            typeface = syntaxTypeface(declarations.font, declarations.fontAssetId, typeface, options.fontPaths)
            if (declarations.bold != null || declarations.italic != null) typeface = Typeface.create(typeface,
                (if (declarations.bold == true) Typeface.BOLD else 0) or (if (declarations.italic == true) Typeface.ITALIC else 0))
            isUnderlineText = declarations.underline == true
            isStrikeThruText = declarations.strike == true
            letterSpacing = css.letterSpacing
        }
    }
    val rules = options.template?.takeIf { it.syntaxEnabled }?.syntaxRules.orEmpty()
    val spans = if (quotation) ReaderSyntaxHighlighter.spans(text, rules) else emptyList()
    val characters = SpannableString(text)
    val paints = spans.map { style -> ReviewTextSpan(style, options.fontPaths).also {
        characters.setSpan(it, style.start, style.endExclusive, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    } }
    val alignment = when (declarations?.alignment.takeIf { quotation }) {
        ReaderTitleAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
        ReaderTitleAlignment.END -> Layout.Alignment.ALIGN_OPPOSITE
        else -> Layout.Alignment.ALIGN_NORMAL
    }
    val layout = StaticLayout.Builder.obtain(characters, 0, characters.length, paint, width)
        .setAlignment(alignment).setIncludePad(false).setLineSpacing(size * ((css?.lineHeight ?: 1.55f) - 1f), 1f).build()
    if (quotation) {
        paint.shader = declarations?.paint?.textGradient?.shader(RectF(0f, 0f, width.toFloat(), layout.height.toFloat()))
        if (paint.shader != null) paint.alpha = 255
    }
    val backgrounds = spans.mapIndexed { index, style ->
        val path = Path().also { layout.getSelectionPath(style.start, style.endExclusive, it) }
        val bounds = RectF().also { path.computeBounds(it, true) }
        paints[index].gradient = style.paintSpan?.paint?.textGradient?.shader(bounds)
        style to path
    }
    return ReviewTextBlock(layout, backgrounds, options.imagePaths)
}

internal fun drawReviewBackground(canvas: Canvas, bounds: RectF, style: ReaderStylePaint, paths: Map<String, String>) {
    style.backgroundGradient?.let { gradient ->
        canvas.drawRect(bounds, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradient.shader(bounds) })
    }
    style.backgroundImageId?.let { paths[it] }?.let { drawReviewImage(canvas, bounds, it) }
}

internal fun drawReviewImage(canvas: Canvas, bounds: RectF, path: String): Boolean {
    val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, info)
    if (info.outWidth <= 0 || info.outHeight <= 0) return false
    val decode = BitmapFactory.Options().apply {
        inSampleSize = 1
        val target = maxOf(bounds.width(), bounds.height()).coerceIn(180f, 1080f) * 2
        while (maxOf(info.outWidth, info.outHeight) / inSampleSize > target) inSampleSize *= 2
    }
    val bitmap = BitmapFactory.decodeFile(path, decode) ?: return false
    try {
        val zoom = maxOf(bounds.width() / bitmap.width, bounds.height() / bitmap.height)
        val x = bounds.centerX() - bitmap.width * zoom / 2
        val y = bounds.centerY() - bitmap.height * zoom / 2
        canvas.drawBitmap(bitmap, null, RectF(x, y, x + bitmap.width * zoom, y + bitmap.height * zoom), Paint(Paint.FILTER_BITMAP_FLAG))
    } finally { bitmap.recycle() }
    return true
}
