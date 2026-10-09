package com.mozhi.reader.feature.reader.render

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import com.mozhi.reader.core.epub.style.EpubDecorationStyle
import kotlin.math.PI
import kotlin.math.sin

/** Phase is based on page coordinates so decorations join across individually drawn clusters. */
internal class TextDecorationPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun draw(canvas: Canvas, left: Float, right: Float, y: Float, textSize: Float, color: Int,
        opacity: Float, style: EpubDecorationStyle) {
        if (right <= left) return
        val thickness = (textSize * .055f).coerceAtLeast(1f)
        paint.color = color
        paint.alpha = ((color ushr 24) * opacity).toInt().coerceIn(0, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = thickness
        paint.strokeCap = Paint.Cap.BUTT
        paint.pathEffect = null
        when (style) {
            EpubDecorationStyle.WAVY -> {
                val wavelength = (textSize * .35f).coerceAtLeast(6f)
                val amplitude = thickness * 1.3f
                path.reset()
                var x = left
                path.moveTo(x, y + amplitude * sin(2 * PI * x / wavelength).toFloat())
                while (x < right) {
                    x = (x + .75f).coerceAtMost(right)
                    path.lineTo(x, y + amplitude * sin(2 * PI * x / wavelength).toFloat())
                }
                canvas.drawPath(path, paint)
            }
            EpubDecorationStyle.DOUBLE -> {
                canvas.drawLine(left, y - thickness, right, y - thickness, paint)
                canvas.drawLine(left, y + thickness, right, y + thickness, paint)
            }
            EpubDecorationStyle.DOTTED, EpubDecorationStyle.DASHED -> {
                val dash = if (style == EpubDecorationStyle.DOTTED) thickness else thickness * 3f
                val gap = thickness * 2f
                if (style == EpubDecorationStyle.DOTTED) paint.strokeCap = Paint.Cap.ROUND
                paint.pathEffect = DashPathEffect(floatArrayOf(dash, gap), left % (dash + gap))
                canvas.drawLine(left, y, right, y, paint)
            }
            EpubDecorationStyle.SOLID -> canvas.drawLine(left, y, right, y, paint)
        }
    }
}
