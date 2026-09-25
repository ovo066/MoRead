package com.mozhi.reader.feature.reader.engine

/** True when this page's lines are columns in the rotated vertical-rl frame. */
val TextPage.isVertical: Boolean get() = verticalFrameWidth != null

/** Content-local physical point -> page coordinates (identity on horizontal pages). */
fun TextPage.physicalToFrame(x: Float, y: Float): Pair<Float, Float> {
    val width = verticalFrameWidth ?: return x to y
    return y to width - x
}

/** Page coordinates -> content-local physical point (identity on horizontal pages). */
fun TextPage.frameToPhysical(x: Float, y: Float): Pair<Float, Float> {
    val width = verticalFrameWidth ?: return x to y
    return width - y to x
}

/** A page-coordinate rectangle as the physical rectangle it covers on screen. */
fun TextPage.frameToPhysical(rect: SelectionRect): SelectionRect {
    val width = verticalFrameWidth ?: return rect
    return SelectionRect(left = width - rect.bottom, top = rect.left, right = width - rect.top, bottom = rect.right)
}

/** Inline markers may not run past the end of a line: the content width, or the column length. */
fun TextPage.lineExtent(contentWidth: Float, contentHeight: Float): Float =
    if (isVertical) contentHeight else contentWidth
