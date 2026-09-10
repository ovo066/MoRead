package com.mozhi.reader.feature.bookshelf

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

internal data class BookMenuPlacement(val preview: Rect, val menu: Rect)

/** Coordinates are local to the pane; the caller reserves system bars and optionally the dock. */
internal fun placeBookMenu(
    anchor: Rect,
    safeBounds: Rect,
    previewSize: Size,
    menuSize: Size,
    gap: Float
): BookMenuPlacement {
    fun boundedSize(size: Size) = Size(
        size.width.coerceIn(0f, safeBounds.width.coerceAtLeast(0f)),
        size.height.coerceIn(0f, safeBounds.height.coerceAtLeast(0f))
    )
    val preview = boundedSize(previewSize).let { size ->
        val left = (anchor.center.x - size.width / 2f)
            .coerceIn(safeBounds.left, safeBounds.right - size.width)
        val top = anchor.top.coerceIn(safeBounds.top, safeBounds.bottom - size.height)
        Rect(left, top, left + size.width, top + size.height)
    }
    val size = boundedSize(menuSize)
    fun menuAt(x: Float, y: Float) = Rect(x, y, x + size.width, y + size.height)
    val centeredX = (preview.center.x - size.width / 2f)
        .coerceIn(safeBounds.left, safeBounds.right - size.width)
    val centeredY = (preview.center.y - size.height / 2f)
        .coerceIn(safeBounds.top, safeBounds.bottom - size.height)
    val candidates = listOf(
        menuAt(centeredX, preview.bottom + gap),
        menuAt(centeredX, preview.top - gap - size.height),
        menuAt(preview.right + gap, centeredY),
        menuAt(preview.left - gap - size.width, centeredY)
    )
    val separate = candidates.firstOrNull {
        it.left >= safeBounds.left && it.top >= safeBounds.top &&
            it.right <= safeBounds.right && it.bottom <= safeBounds.bottom
    }
    // In small windows the menu takes priority; cover overlap is preferable to hidden actions.
    val menu = separate ?: listOf(
        menuAt(safeBounds.left, safeBounds.top),
        menuAt(safeBounds.right - size.width, safeBounds.top),
        menuAt(safeBounds.left, safeBounds.bottom - size.height),
        menuAt(safeBounds.right - size.width, safeBounds.bottom - size.height)
    ).minWith(compareBy<Rect> {
        val overlapWidth = (minOf(it.right, preview.right) - maxOf(it.left, preview.left)).coerceAtLeast(0f)
        val overlapHeight = (minOf(it.bottom, preview.bottom) - maxOf(it.top, preview.top)).coerceAtLeast(0f)
        overlapWidth * overlapHeight
    }.thenBy { (it.center - preview.center).getDistanceSquared() })
    return BookMenuPlacement(preview, menu)
}
