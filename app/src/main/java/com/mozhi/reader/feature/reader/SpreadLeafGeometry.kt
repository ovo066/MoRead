package com.mozhi.reader.feature.reader

import com.mozhi.reader.feature.reader.render.SpreadGeometry
import kotlin.math.cos
import kotlin.math.sin

/** A rigid leaf rotates about the spine; its two faces come from different spreads. */
data class SpreadLeafGeometry(
    val angle: Float,
    val showBack: Boolean,
    /** Clockwise: top-left, top-right, bottom-right, bottom-left in pane coordinates. */
    val dstQuad: FloatArray,
    val shadowAlpha: Float
) {
    companion object {
        fun fromTouch(
            direction: PageTurnDirection,
            touchX: Float,
            startX: Float,
            geometry: SpreadGeometry
        ): SpreadLeafGeometry {
            val distance = if (direction == PageTurnDirection.NEXT) startX - touchX else touchX - startX
            val travel = (distance / geometry.leafWidth.coerceAtLeast(1f)).coerceIn(0f, 1f)
            val angle = travel * 180f
            val radians = Math.toRadians(angle.toDouble())
            val cosine = cos(radians).toFloat()
            val lift = sin(radians).toFloat().coerceAtLeast(0f)
            val sign = if (direction == PageTurnDirection.NEXT) 1f else -1f
            // The blank half-gutter rotates with the sheet, keeping the physical hinge at spineX.
            val innerX = geometry.spineX + sign * cosine * geometry.gutterPx / 2f
            val outerX = geometry.spineX + sign * cosine * (geometry.leafWidth + geometry.gutterPx / 2f)
            val left = minOf(innerX, outerX)
            val right = maxOf(innerX, outerX)
            val inset = geometry.paneHeight * 0.045f * lift
            val outerOnLeft = outerX < innerX
            val topLeft = if (outerOnLeft) inset else 0f
            val topRight = if (outerOnLeft) 0f else inset
            return SpreadLeafGeometry(
                angle = angle,
                showBack = angle >= 90f,
                dstQuad = floatArrayOf(left, topLeft, right, topRight,
                    right, geometry.paneHeight - topRight, left, geometry.paneHeight - topLeft),
                shadowAlpha = lift * 0.22f
            )
        }
    }
}
