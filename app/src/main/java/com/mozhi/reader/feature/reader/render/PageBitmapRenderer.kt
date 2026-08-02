package com.mozhi.reader.feature.reader.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import android.text.TextPaint
import com.mozhi.reader.feature.reader.engine.ListenHighlightSpan
import com.mozhi.reader.feature.reader.engine.ReaderAnnotationMark
import com.mozhi.reader.feature.reader.engine.RenderPage
import com.mozhi.reader.feature.reader.engine.annotationGeometry
import java.util.Locale

/**
 * Renders one [RenderPage] into a bitmap: background, header (chapter title), the laid-out body,
 * and the footer (page number, whole-book progress, time, battery). The whole page lives in one
 * bitmap so every page-turn delegate can composite real pages, which is what Legado gets from
 * screenshotting its `PageView`s.
 */
class PageBitmapRenderer(private val pageStyle: ReaderPageStyle) {

    private val tipPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = pageStyle.tipSizePx
        color = pageStyle.mutedColor
        typeface = pageStyle.typeface
    }
    private val batteryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = pageStyle.mutedColor
    }
    private val batteryFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pageStyle.mutedColor
    }
    private val placeholderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = pageStyle.contentSizePx
        color = pageStyle.mutedColor
        typeface = pageStyle.typeface
        textAlign = Paint.Align.CENTER
    }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val annotationHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pageStyle.accentColor
        alpha = if (pageStyle.isDark) 42 else 55
    }
    private val listenHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pageStyle.accentColor
        alpha = if (pageStyle.isDark) 30 else 38
    }
    private val annotationMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pageStyle.accentColor
    }
    private val annotationMarkerTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = pageStyle.tipSizePx * 0.82f
        color = pageStyle.backgroundColor
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val imageFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pageStyle.mutedColor
        alpha = 24
    }
    private val imageCache = object : LruCache<String, Bitmap>(IMAGE_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (oldValue !== newValue && !oldValue.isRecycled) oldValue.recycle()
        }
    }

    /** Tiled speckle shader for the paper theme; opacity stays under the proposal's 5% cap. */
    private val grainPaint: Paint? = if (pageStyle.grain) {
        val tile = Bitmap.createBitmap(GRAIN_TILE, GRAIN_TILE, Bitmap.Config.ARGB_8888)
        val tileCanvas = Canvas(tile)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pageStyle.textColor and 0x00FFFFFF or (0x0D shl 24)
        }
        val random = java.util.Random(GRAIN_SEED)
        repeat(GRAIN_DOTS) {
            val x = random.nextFloat() * GRAIN_TILE
            val y = random.nextFloat() * GRAIN_TILE
            tileCanvas.drawCircle(x, y, 0.6f + random.nextFloat() * 0.7f, dotPaint)
        }
        Paint().apply {
            shader = android.graphics.BitmapShader(
                tile,
                android.graphics.Shader.TileMode.REPEAT,
                android.graphics.Shader.TileMode.REPEAT
            )
        }
    } else {
        null
    }

    fun obtainBitmap(recycled: Bitmap?): Bitmap {
        val existing = recycled
            ?.takeIf { !it.isRecycled && it.width == pageStyle.viewWidth && it.height == pageStyle.viewHeight }
        return existing ?: Bitmap.createBitmap(
            pageStyle.viewWidth.coerceAtLeast(1),
            pageStyle.viewHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
    }

    fun render(
        page: RenderPage,
        into: Bitmap?,
        bookProgress: Float,
        timeText: String,
        batteryPercent: Int,
        annotations: List<ReaderAnnotationMark> = emptyList(),
        listenHighlight: ListenHighlightSpan? = null
    ): Bitmap {
        val bitmap = obtainBitmap(into)
        val canvas = Canvas(bitmap)
        canvas.drawColor(pageStyle.backgroundColor)
        grainPaint?.let {
            canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), it)
        }
        drawHeader(canvas, page)
        when (page) {
            is RenderPage.Laid -> drawBody(canvas, page, annotations, listenHighlight)
            is RenderPage.Placeholder -> drawPlaceholder(canvas, page)
        }
        drawFooter(canvas, page, bookProgress, timeText, batteryPercent)
        return bitmap
    }

    private fun drawHeader(canvas: Canvas, page: RenderPage) {
        val title = page.chapterTitle.ifBlank { return }
        val maxWidth = pageStyle.contentWidth
        val text = ellipsize(title, tipPaint, maxWidth)
        val y = pageStyle.headerHeight - pageStyle.tipSizePx * 0.45f
        canvas.drawText(text, pageStyle.paddingHorizontal, y, tipPaint)
    }

    private fun drawBody(
        canvas: Canvas,
        page: RenderPage.Laid,
        annotations: List<ReaderAnnotationMark>,
        listenHighlight: ListenHighlightSpan?
    ) {
        canvas.save()
        canvas.translate(pageStyle.paddingHorizontal, pageStyle.headerHeight)
        val markerRadius = (pageStyle.tipSizePx * 0.72f).coerceAtLeast(8f)
        // 听书当前句底色画在批注高亮之下，两者重叠时批注色仍占主导。
        if (listenHighlight != null && listenHighlight.chapterIndex == page.chapterIndex) {
            val listenGeometry = page.page.annotationGeometry(
                annotations = listOf(
                    ReaderAnnotationMark(
                        id = LISTEN_HIGHLIGHT_ID,
                        chapterIndex = listenHighlight.chapterIndex,
                        startCharOffset = listenHighlight.startCharOffset,
                        endCharOffset = listenHighlight.endCharOffset,
                        hasComment = false
                    )
                ),
                markerRadius = markerRadius,
                markerGap = markerRadius * 0.35f,
                maxRight = pageStyle.contentWidth + pageStyle.paddingHorizontal * 0.9f
            )
            listenGeometry.highlights.forEach { rect ->
                canvas.drawRoundRect(
                    RectF(rect.left, rect.top, rect.right, rect.bottom),
                    ANNOTATION_HIGHLIGHT_RADIUS,
                    ANNOTATION_HIGHLIGHT_RADIUS,
                    listenHighlightPaint
                )
            }
        }
        val geometry = page.page.annotationGeometry(
            annotations = annotations,
            markerRadius = markerRadius,
            markerGap = markerRadius * 0.35f,
            maxRight = pageStyle.contentWidth + pageStyle.paddingHorizontal * 0.9f
        )
        geometry.highlights.forEach { rect ->
            canvas.drawRoundRect(
                RectF(rect.left, rect.top, rect.right, rect.bottom),
                ANNOTATION_HIGHLIGHT_RADIUS,
                ANNOTATION_HIGHLIGHT_RADIUS,
                annotationHighlightPaint
            )
        }
        val contentPaint = pageStyle.measure.contentPaint
        val titlePaint = pageStyle.measure.titlePaint
        contentPaint.color = pageStyle.textColor
        titlePaint.color = pageStyle.textColor
        for (line in page.page.lines) {
            val inlineImage = line.inlineImage
            if (inlineImage != null) {
                drawInlineImage(
                    canvas = canvas,
                    path = inlineImage.imagePath,
                    altText = inlineImage.altText,
                    destination = RectF(
                        line.startX,
                        line.lineTop,
                        line.startX + inlineImage.width,
                        line.lineTop + inlineImage.height
                    )
                )
                continue
            }
            val paint = if (line.isTitle) titlePaint else contentPaint
            for (column in line.columns) {
                canvas.drawText(column.charData, column.start, line.lineBase, paint)
            }
        }
        geometry.markers.forEach { marker ->
            canvas.drawCircle(marker.centerX, marker.centerY, markerRadius, annotationMarkerPaint)
            val label = if (marker.annotationIds.size > 1) marker.annotationIds.size.toString() else "评"
            canvas.drawText(
                label,
                marker.centerX,
                marker.centerY - (annotationMarkerTextPaint.ascent() + annotationMarkerTextPaint.descent()) / 2f,
                annotationMarkerTextPaint
            )
        }
        canvas.restore()
    }

    private fun drawInlineImage(
        canvas: Canvas,
        path: String,
        altText: String,
        destination: RectF
    ) {
        val bitmap = loadImage(path, destination.width().toInt(), destination.height().toInt())
        if (bitmap != null) {
            canvas.save()
            canvas.clipPath(Path().apply {
                addRoundRect(
                    destination,
                    IMAGE_CORNER_RADIUS,
                    IMAGE_CORNER_RADIUS,
                    Path.Direction.CW
                )
            })
            canvas.drawBitmap(bitmap, null, destination, imagePaint)
            canvas.restore()
            return
        }
        canvas.drawRoundRect(destination, IMAGE_CORNER_RADIUS, IMAGE_CORNER_RADIUS, imageFramePaint)
        val label = altText.ifBlank { "图片" }
        val display = ellipsize("［$label］", placeholderPaint, destination.width() * 0.86f)
        canvas.drawText(
            display,
            destination.centerX(),
            destination.centerY() - (placeholderPaint.ascent() + placeholderPaint.descent()) / 2f,
            placeholderPaint
        )
    }

    private fun loadImage(path: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        imageCache.get(path)?.takeIf { !it.isRecycled }?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val readBounds = runCatching {
            BitmapFactory.decodeFile(path, bounds)
            true
        }.getOrDefault(false)
        if (!readBounds || bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val safeWidth = targetWidth.coerceAtLeast(1)
        val safeHeight = targetHeight.coerceAtLeast(1)
        while (bounds.outWidth / (sample * 2) >= safeWidth &&
            bounds.outHeight / (sample * 2) >= safeHeight
        ) {
            sample *= 2
        }
        val bitmap = runCatching {
            BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        }.getOrNull() ?: return null
        imageCache.put(path, bitmap)
        return bitmap
    }

    fun release() {
        imageCache.evictAll()
    }

    private fun drawPlaceholder(canvas: Canvas, page: RenderPage.Placeholder) {
        val centerX = pageStyle.viewWidth / 2f
        val centerY = pageStyle.headerHeight + pageStyle.contentHeight / 2f
        if (page.chapterTitle.isNotBlank()) {
            val titleText = ellipsize(page.chapterTitle, placeholderPaint, pageStyle.contentWidth)
            canvas.drawText(titleText, centerX, centerY - pageStyle.lineStep, placeholderPaint)
        }
        canvas.drawText(page.message, centerX, centerY + pageStyle.lineStep * 0.5f, placeholderPaint)
    }

    private fun drawFooter(
        canvas: Canvas,
        page: RenderPage,
        bookProgress: Float,
        timeText: String,
        batteryPercent: Int
    ) {
        val baseline = pageStyle.viewHeight - pageStyle.footerHeight + pageStyle.tipSizePx * 1.25f
        val pageText = "${page.pageIndex + 1} / ${page.pageCount} 页"
        canvas.drawText(pageText, pageStyle.paddingHorizontal, baseline, tipPaint)

        val progressText = String.format(Locale.ROOT, "%.1f%%", bookProgress * 100)
        val rightText = "$progressText · $timeText"
        val batteryWidth = pageStyle.tipSizePx * 1.7f
        val textWidth = tipPaint.measureText(rightText)
        val rightEdge = pageStyle.viewWidth - pageStyle.paddingHorizontal
        canvas.drawText(rightText, rightEdge - batteryWidth - textWidth, baseline, tipPaint)
        drawBattery(
            canvas,
            left = rightEdge - batteryWidth + pageStyle.tipSizePx * 0.35f,
            centerY = baseline - pageStyle.tipSizePx * 0.32f,
            percent = batteryPercent
        )
    }

    private fun drawBattery(canvas: Canvas, left: Float, centerY: Float, percent: Int) {
        val width = pageStyle.tipSizePx * 1.25f
        val height = pageStyle.tipSizePx * 0.62f
        val body = RectF(left, centerY - height / 2f, left + width, centerY + height / 2f)
        val radius = height * 0.22f
        canvas.drawRoundRect(body, radius, radius, batteryPaint)
        val capWidth = pageStyle.tipSizePx * 0.16f
        canvas.drawRect(
            body.right + 1f,
            centerY - height * 0.22f,
            body.right + 1f + capWidth,
            centerY + height * 0.22f,
            batteryFillPaint
        )
        val inset = 2f
        val level = (percent.coerceIn(0, 100) / 100f)
        canvas.drawRect(
            body.left + inset,
            body.top + inset,
            body.left + inset + (body.width() - inset * 2) * level,
            body.bottom - inset,
            batteryFillPaint
        )
    }

    private companion object {
        const val GRAIN_TILE = 128
        const val GRAIN_DOTS = 220
        const val GRAIN_SEED = 42L
        const val IMAGE_CACHE_KB = 32 * 1024
        const val IMAGE_CORNER_RADIUS = 12f
        const val ANNOTATION_HIGHLIGHT_RADIUS = 4f
        const val LISTEN_HIGHLIGHT_ID = -1L
    }

    private fun ellipsize(text: String, paint: TextPaint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text, 0, end) + paint.measureText("…") > maxWidth) {
            end--
        }
        return text.substring(0, end) + "…"
    }
}
