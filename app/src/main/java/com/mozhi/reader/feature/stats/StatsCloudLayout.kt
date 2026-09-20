package com.mozhi.reader.feature.stats

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.*

internal data class CloudWord(val item: StatsCloudItem, val x: Float, val y: Float,
    val width: Int, val height: Int, val fontSize: Float, val rotation: Float, val bold: Boolean)
internal data class CloudLayout(val words: List<CloudWord>, val width: Int, val height: Int,
    val omitted: List<StatsCloudItem>)

/**
 * Largest-first spiral packing with glyph masks, inspired by the published d3-cloud algorithm:
 * https://github.com/jasondavies/d3-cloud . Independent Kotlin implementation, no JS/WebView.
 * A seeded angle and deterministic order keep refreshes from shuffling the same statistics.
 */
internal object StatsCloudLayout {
    fun arrange(input: List<StatsCloudItem>, width: Int = 420, height: Int = 300,
        cancelled: () -> Unit = {}): CloudLayout {
        if (width <= 0 || height <= 0) return CloudLayout(emptyList(), width.coerceAtLeast(1), height.coerceAtLeast(1), input)
        val values = input.filter { it.label.isNotBlank() }.sortedWith(compareByDescending<StatsCloudItem> { it.durationMs }.thenBy { it.label })
        val gridWidth = (width + 1) / 2
        val gridHeight = (height + 1) / 2
        val occupied = BooleanArray(gridWidth * gridHeight)
        val placed = mutableListOf<CloudWord>()
        val omitted = mutableListOf<StatsCloudItem>()
        val max = values.maxOfOrNull { it.durationMs }?.coerceAtLeast(1) ?: 1
        val min = values.minOfOrNull { it.durationMs } ?: 0
        values.forEachIndexed { index, item ->
            cancelled()
            val prominence = if (max == min) .5f else ((item.durationMs - min).toDouble() / (max - min)).coerceIn(0.0, 1.0).pow(.8).toFloat()
            val originalSize = if (values.size == 1) 48f else 12f + 40f * prominence
            val bold = prominence >= .58f
            val angle = if (index == 0) 0f else floatArrayOf(0f, -18f, 0f, 22f, -32f, 0f, 14f)[Math.floorMod(item.label.hashCode(), 7)]
            var result: CloudWord? = null
            for (attempt in 0..5) {
                cancelled()
                val size = (originalSize * .87f.pow(attempt)).coerceAtLeast(10f)
                val sprite = sprite(item.label, size, angle, bold)
                if (sprite.width > width - 4 || sprite.height > height - 4) continue
                val phase = Math.floorMod(item.label.hashCode(), 628) / 100.0
                for (step in 0..4500) {
                    if (step % 256 == 0) cancelled()
                    val theta = step * .085
                    val radius = theta * .63
                    val cx = width / 2f + (cos(theta + phase) * radius * width / height).toFloat()
                    val cy = height / 2f + (sin(theta + phase) * radius).toFloat()
                    val left = ((cx - sprite.width / 2f) / 2).roundToInt() * 2
                    val top = ((cy - sprite.height / 2f) / 2).roundToInt() * 2
                    if (left < 2 || top < 2 || left + sprite.width >= width - 2 || top + sprite.height >= height - 2) continue
                    // Elliptical silhouette; glyph masks allow neighboring words to tuck into spaces.
                    val dx = (abs(cx - width / 2f) + sprite.width * .35f) / (width * .51f)
                    val dy = (abs(cy - height / 2f) + sprite.height * .35f) / (height * .51f)
                    if (dx * dx + dy * dy > 1f) continue
                    val gx = left / 2; val gy = top / 2
                    if (sprite.points.any { occupied[(gy + it.second) * gridWidth + gx + it.first] }) continue
                    sprite.points.forEach { occupied[(gy + it.second) * gridWidth + gx + it.first] = true }
                    result = CloudWord(item, left.toFloat(), top.toFloat(), sprite.width, sprite.height, size, angle, bold)
                    break
                }
                if (result != null || size == 10f) break
            }
            if (result == null) omitted += item else placed += result
        }
        if (placed.isEmpty()) return CloudLayout(emptyList(), width, height, omitted)
        val top = placed.minOf { it.y }; val bottom = placed.maxOf { it.y + it.height }
        val left = placed.minOf { it.x }; val right = placed.maxOf { it.x + it.width }
        val offsetX = (width - (right - left)) / 2f - left
        return CloudLayout(placed.map { it.copy(x = it.x + offsetX, y = it.y - top + 6) }, width,
            (bottom - top + 12).roundToInt().coerceAtLeast(60), omitted)
    }

    fun paint(size: Float, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private data class Sprite(val width: Int, val height: Int, val points: List<Pair<Int, Int>>)
    private fun sprite(label: String, size: Float, angle: Float, bold: Boolean): Sprite {
        val paint = paint(size, bold)
        val textWidth = paint.measureText(label)
        val textHeight = paint.fontMetrics.let { it.descent - it.ascent }
        val radians = angle * PI / 180
        val width = ceil(abs(cos(radians)) * textWidth + abs(sin(radians)) * textHeight + 8).toInt().coerceAtLeast(2)
        val height = ceil(abs(sin(radians)) * textWidth + abs(cos(radians)) * textHeight + 8).toInt().coerceAtLeast(2)
        // Long labels should be resized before allocating an oversized raster.
        if (width > 2048 || height > 1024) return Sprite(width, height, emptyList())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(width / 2f, height / 2f)
        canvas.rotate(angle)
        canvas.drawText(label, -textWidth / 2f, -(paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f, paint)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        val points = mutableSetOf<Pair<Int, Int>>()
        for (y in 0 until height) for (x in 0 until width) if (pixels[y * width + x] ushr 24 > 20) {
            // One pixel halo gives close packing without touching strokes.
            for (dy in -1..1) for (dx in -1..1) {
                points += ((x + dx).coerceIn(0, width - 1) / 2 to (y + dy).coerceIn(0, height - 1) / 2)
            }
        }
        return Sprite(width, height, points.toList())
    }
}
