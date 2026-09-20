package com.mozhi.reader.feature.review

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.mozhi.reader.core.datastore.ReviewShareTemplate
import com.mozhi.reader.core.datastore.ReviewTemplateCss
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class ReviewCardStyle(val label: String, val background: Int, val foreground: Int, val accent: Int) {
    PAPER("纸白", 0xFFF7F5EF.toInt(), 0xFF38444B.toInt(), 0xFF7E9CB5.toInt()),
    MIST("雾蓝", 0xFFD7E6F2.toInt(), 0xFF354C5E.toInt(), 0xFF6A94B5.toInt()),
    NIGHT("暗夜", 0xFF202930.toInt(), 0xFFD3DEE8.toInt(), 0xFF9BBEDC.toInt()),
    COVER("书影", 0xFF202930.toInt(), 0xFFE8EDF3.toInt(), 0xFFB6CCE0.toInt()),
    SAGE("青竹", 0xFFE0E9DD.toInt(), 0xFF384B3B.toInt(), 0xFF739278.toInt())
}

internal data class ReviewExportOptions(val thought: Boolean = true, val book: Boolean = true,
    val date: Boolean = true, val watermark: Boolean = true, val font: ReviewFontSpec = ReviewFontSpec(),
    val template: ReviewShareTemplate? = null, val fontPaths: Map<String, String> = emptyMap(), val imagePaths: Map<String, String> = emptyMap())

/** The preview and exported PNG share exactly the same text layout, including long quotations. */
internal fun renderReviewCard(entry: ReviewEntry, style: ReviewCardStyle, options: ReviewExportOptions = ReviewExportOptions(), width: Int = 1080): Bitmap {
    require(width in 120..1080)
    val scale = width / 1080f
    val template = options.template
    val css = template?.let { ReviewTemplateCss.parse(it.css) }
    require(css?.declarations?.errors.isNullOrEmpty()) { css!!.declarations.errors.joinToString("\n") }
    val declarations = css?.declarations
    val background = declarations?.background?.takeUnless { declarations.clipText } ?: template?.backgroundArgb ?: style.background
    val foreground = declarations?.color ?: template?.textArgb ?: style.foreground
    val accent = template?.accentArgb ?: style.accent
    val em = 47f * scale
    val gutter = ((declarations?.padding ?: (96f / 47f)) + (declarations?.inset ?: 0f)).times(em).coerceIn(12f * scale, width * .4f)
    val textWidth = width - gutter.toInt() * 2
    val quote = reviewTextBlock(entry.quote.ifBlank { entry.title.ifBlank { "读书笔记" } }, textWidth,
        em * (declarations?.sizeEm ?: 1f), foreground, options, css, quotation = true)
    val thought = if (options.thought && entry.body.isNotBlank()) reviewTextBlock(reviewPlainText(entry.body), textWidth, 32f * scale,
        foreground, options, null, quotation = false) else null
    val metadata = reviewTextBlock(listOfNotNull(
        if (options.book) "${entry.book.title}\n${entry.locationLabel}" else null, entry.author,
        if (options.date) SimpleDateFormat("yyyy.MM.dd", Locale.getDefault()).format(Date(entry.timestamp)) else null
    ).joinToString(" · "), textWidth, 26f * scale, foreground, options, null, quotation = false)
    val extraTop = (declarations?.topEm ?: 0f) * em
    val extraBottom = (declarations?.bottomEm ?: 0f) * em
    val contentHeight = (480 * scale + extraTop + extraBottom + quote.height + (thought?.let { 100 * scale + it.height } ?: 0f) + metadata.height).toInt()
    require(contentHeight <= 8192 * scale) { "这条内容较长，请使用 Markdown 导出以保留全文" }
    val bitmap = Bitmap.createBitmap(width, contentHeight.coerceAtLeast((1440 * scale).toInt()), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val bounds = RectF(0f, 0f, width.toFloat(), bitmap.height.toFloat())
    val radius = ((declarations?.radius ?: 0f) * em).coerceAtMost(width / 2f)
    canvas.clipPath(android.graphics.Path().apply { addRoundRect(bounds, radius, radius, android.graphics.Path.Direction.CW) })
    canvas.drawColor(background)
    declarations?.paint?.let { drawReviewBackground(canvas, bounds, it, options.imagePaths) }
    if (template == null && style == ReviewCardStyle.COVER) entry.book.coverPath?.let { path ->
        if (drawReviewImage(canvas, bounds, path)) canvas.drawColor(Color.argb(175, 12, 23, 32))
    }
    val ornament = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; textSize = 150f * scale; typeface = Typeface.create("serif", Typeface.NORMAL)
    }
    canvas.drawText("“", gutter - 8f * scale, 204f * scale, ornament)
    fun draw(block: ReviewTextBlock, y: Float) {
        canvas.save(); canvas.translate(gutter, y); block.draw(canvas); canvas.restore()
    }
    var y = 280f * scale + extraTop
    draw(quote, y)
    y += quote.height + extraBottom
    thought?.let {
        y += 60f * scale
        canvas.drawLine(gutter, y, gutter + 80f * scale, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent; strokeWidth = 3f * scale
        })
        y += 40f * scale
        draw(it, y)
    }
    draw(metadata, bitmap.height - metadata.height - 160f * scale)
    ornament.apply { textSize = 24f * scale; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    if (options.watermark) canvas.drawText("墨知 MoRead", gutter, bitmap.height - 64f * scale, ornament)
    declarations?.borderWidth?.takeIf { it > 0f }?.let { border ->
        val inset = border * em / 2
        canvas.drawRoundRect(RectF(bounds).apply { inset(inset, inset) }, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE; strokeWidth = border * em; color = declarations.borderColor ?: accent
        })
    }
    return bitmap
}

internal class ReviewExporter @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun markdown(entries: List<ReviewEntry>): Intent = withContext(Dispatchers.IO) {
        val file = outputFile("md")
        file.writeText(reviewMarkdown(entries), Charsets.UTF_8)
        intent(file, "text/markdown")
    }

    suspend fun image(entry: ReviewEntry, style: ReviewCardStyle, options: ReviewExportOptions = ReviewExportOptions()): Intent = withContext(Dispatchers.IO) {
        val bitmap = renderReviewCard(entry, style, options)
        try {
            val file = outputFile("png")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            intent(file, "image/png")
        } finally { bitmap.recycle() }
    }

    private fun outputFile(extension: String): File =
        File(File(context.cacheDir, "export").apply { mkdirs() }, "MoRead-回顾-${UUID.randomUUID()}.$extension")

    private fun intent(file: File, mime: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "划线与笔记 · 墨知 MoRead")
            clipData = ClipData.newRawUri("划线与笔记", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
