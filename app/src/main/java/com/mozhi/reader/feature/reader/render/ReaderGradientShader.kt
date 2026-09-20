package com.mozhi.reader.feature.reader.render

import android.graphics.LinearGradient
import android.graphics.RectF
import android.graphics.Shader
import com.mozhi.reader.core.datastore.ReaderLinearGradient
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** CSS angles: 0deg points up, 90deg right. All glyphs of a match share this shader. */
internal fun ReaderLinearGradient.shader(bounds: RectF): LinearGradient {
    val radians = Math.toRadians(angle.toDouble())
    val dx = sin(radians).toFloat()
    val dy = -cos(radians).toFloat()
    val half = (abs(dx) * bounds.width() + abs(dy) * bounds.height()).coerceAtLeast(1f) / 2f
    return LinearGradient(bounds.centerX() - dx * half, bounds.centerY() - dy * half,
        bounds.centerX() + dx * half, bounds.centerY() + dy * half,
        colors.toIntArray(), stops.toFloatArray(), Shader.TileMode.CLAMP)
}
