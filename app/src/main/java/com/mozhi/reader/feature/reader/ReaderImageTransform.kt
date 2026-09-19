package com.mozhi.reader.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.min

/** Gesture math is independent of bitmap loading, page coordinates and saved reading position. */
internal data class ReaderImageTransform(
    val zoom: Float = 1f,
    val offset: Offset = Offset.Zero,
    val quarterTurns: Int = 0
) {
    fun fit(image: Size, viewport: Size): Float {
        if (image.width <= 0f || image.height <= 0f || viewport.width <= 0f || viewport.height <= 0f) return 1f
        val sideways = quarterTurns % 2 != 0
        return min(viewport.width / if (sideways) image.height else image.width,
            viewport.height / if (sideways) image.width else image.height)
    }

    fun gesture(centroid: Offset, pan: Offset, zoomChange: Float, image: Size, viewport: Size): ReaderImageTransform {
        if (!zoomChange.isFinite() || !centroid.x.isFinite() || !centroid.y.isFinite() ||
            !pan.x.isFinite() || !pan.y.isFinite()) return this
        val nextZoom = (zoom * zoomChange).coerceIn(1f, 12f)
        val focal = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
        val nextOffset = focal + (offset - focal) * (nextZoom / zoom) + pan
        val scale = fit(image, viewport) * nextZoom
        val width = (if (quarterTurns % 2 == 0) image.width else image.height) * scale
        val height = (if (quarterTurns % 2 == 0) image.height else image.width) * scale
        // Allow free inspection even at fit scale, but keep a sliver in reach for recovery.
        val limitX = (viewport.width + width) / 2f - min(width, viewport.width) * .1f
        val limitY = (viewport.height + height) / 2f - min(height, viewport.height) * .1f
        return copy(zoom = nextZoom, offset = Offset(nextOffset.x.coerceIn(-limitX, limitX),
            nextOffset.y.coerceIn(-limitY, limitY)))
    }

    fun rotate() = ReaderImageTransform(quarterTurns = (quarterTurns + 1) % 4)
    fun reset() = ReaderImageTransform(quarterTurns = quarterTurns)
}
