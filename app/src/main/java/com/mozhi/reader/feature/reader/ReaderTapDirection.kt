package com.mozhi.reader.feature.reader

import com.mozhi.reader.feature.reader.render.Leaf
import com.mozhi.reader.feature.reader.render.SpreadGeometry

/** The chrome target expands beyond the painted spine; no leaf/layout geometry is changed. */
internal fun readerTapDirection(
    x: Float,
    paneWidth: Float,
    spread: SpreadGeometry?,
    minimumChromeWidthPx: Float = SpreadLayoutPolicy.CHROME_TOUCH_DP
): PageTurnDirection? {
    if (spread != null) {
        val halfTarget = maxOf(spread.gutterPx, minimumChromeWidthPx) / 2f
        if (x >= spread.spineX - halfTarget && x < spread.spineX + halfTarget) return null
        return when (spread.leafAt(x)) {
            Leaf.LEFT -> PageTurnDirection.PREVIOUS
            Leaf.RIGHT -> PageTurnDirection.NEXT
            null -> null
        }
    }
    val fraction = x / paneWidth.coerceAtLeast(1f)
    return when {
        fraction < PREV_TAP_ZONE -> PageTurnDirection.PREVIOUS
        fraction > NEXT_TAP_ZONE -> PageTurnDirection.NEXT
        else -> null
    }
}
