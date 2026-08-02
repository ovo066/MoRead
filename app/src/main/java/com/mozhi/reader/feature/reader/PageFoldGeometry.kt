package com.mozhi.reader.feature.reader

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Bezier control points for the simulated page fold, ported from Legado's
 * `SimulationPageDelegate.calcPoints`.
 *
 * Kept free of `android.graphics` types so the whole fold can be verified by unit tests: the
 * geometry degenerates in ways that are invisible in code review but obvious in numbers.
 *
 * The canonical space always puts the page corner on the right edge; a previous-page turn is drawn
 * by mirroring the canvas, so the same maths covers both directions.
 */
internal class PageFoldGeometry {
    var touchX = 0f
        private set
    var touchY = 0f
        private set
    var cornerX = 0f
        private set
    var cornerY = 0f
        private set
    var start1X = 0f
        private set
    var start1Y = 0f
        private set
    var start2X = 0f
        private set
    var start2Y = 0f
        private set
    var control1X = 0f
        private set
    var control1Y = 0f
        private set
    var control2X = 0f
        private set
    var control2Y = 0f
        private set
    var end1X = 0f
        private set
    var end1Y = 0f
        private set
    var end2X = 0f
        private set
    var end2Y = 0f
        private set
    var vertex1X = 0f
        private set
    var vertex1Y = 0f
        private set
    var vertex2X = 0f
        private set
    var vertex2Y = 0f
        private set
    var touchToCornerDistance = 0f
        private set
    var cornerAtTop = false
        private set

    fun update(
        width: Float,
        height: Float,
        travel: Float,
        touchYFraction: Float,
        cornerAtTop: Boolean
    ) {
        this.cornerAtTop = cornerAtTop
        cornerX = width
        cornerY = if (cornerAtTop) 0f else height
        touchX = simulationTouchX(width, travel)

        // Legado anchors the lifted corner at the finger's y, which means grabbing near a corner
        // leaves almost no vertical offset and the fold collapses into a straight sliding edge.
        // Driving the offset from how far the page has been pulled keeps a real curl for every grab
        // point; the finger's height only decides how deep that curl gets.
        val depth = foldDepthFactor(height, touchYFraction, cornerY)
        val drop = maxFoldDrop(width, cornerX - touchX) * depth
        touchY = (if (cornerAtTop) cornerY + drop else cornerY - drop)
            .coerceIn(FOLD_EDGE_INSET, height - FOLD_EDGE_INSET)

        calcPoints()
    }

    /**
     * Legado's original contract: the touch point IS the lifted page corner, straight from the
     * finger (`SimulationPageDelegate.calcPoints` reads `touchX/touchY` verbatim). Used by the
     * self-drawn reader where the drag owns the real touch stream.
     */
    fun updateFromTouch(
        width: Float,
        height: Float,
        touchX: Float,
        touchY: Float,
        cornerAtTop: Boolean
    ) {
        this.cornerAtTop = cornerAtTop
        cornerX = width
        cornerY = if (cornerAtTop) 0f else height
        this.touchX = touchX
        this.touchY = touchY.coerceIn(FOLD_EDGE_INSET, height - FOLD_EDGE_INSET)
        calcPoints()
    }

    fun isFinite(): Boolean = touchX.isFinite() && touchY.isFinite() &&
        start1X.isFinite() && start1Y.isFinite() &&
        start2X.isFinite() && start2Y.isFinite() &&
        control1X.isFinite() && control1Y.isFinite() &&
        control2X.isFinite() && control2Y.isFinite() &&
        end1X.isFinite() && end1Y.isFinite() &&
        end2X.isFinite() && end2Y.isFinite()

    /** Share of the viewport covered by the fold, used by tests to catch collapsed geometry. */
    fun foldAreaFraction(width: Float, height: Float): Float {
        if (width <= 0f || height <= 0f) return 0f
        val xs = floatArrayOf(start1X, end1X, touchX, end2X, start2X, cornerX)
        val ys = floatArrayOf(start1Y, end1Y, touchY, end2Y, start2Y, cornerY)
        var doubleArea = 0f
        for (index in xs.indices) {
            val next = (index + 1) % xs.size
            doubleArea += xs[index] * ys[next] - xs[next] * ys[index]
        }
        return abs(doubleArea) / 2f / (width * height)
    }

    private fun calcPoints() {
        var middleX = (touchX + cornerX) / 2f
        var middleY = (touchY + cornerY) / 2f
        control1X = middleX -
            (cornerY - middleY) * (cornerY - middleY) / guard(cornerX - middleX)
        control1Y = cornerY
        control2X = cornerX
        control2Y = middleY -
            (cornerX - middleX) * (cornerX - middleX) / guard(cornerY - middleY)
        start1X = control1X - (cornerX - control1X) / 2f
        start1Y = cornerY

        // Legado's safety net for extreme touch points. The depth factor above keeps the fold inside
        // the page, so this should stay dormant; it is retained because it is cheap insurance.
        if (touchX > 0f && touchX < cornerX && (start1X < 0f || start1X > cornerX)) {
            if (start1X < 0f) start1X = cornerX - start1X
            val firstDistance = abs(cornerX - touchX).coerceAtLeast(0.1f)
            val adjusted = cornerX * firstDistance / start1X.coerceAtLeast(0.1f)
            touchX = abs(cornerX - adjusted)
            touchY = abs(cornerY - abs(cornerX - touchX) * abs(cornerY - touchY) / firstDistance)
            middleX = (touchX + cornerX) / 2f
            middleY = (touchY + cornerY) / 2f
            control1X = middleX -
                (cornerY - middleY) * (cornerY - middleY) / guard(cornerX - middleX)
            control1Y = cornerY
            control2X = cornerX
            control2Y = middleY -
                (cornerX - middleX) * (cornerX - middleX) / guard(cornerY - middleY)
            start1X = control1X - (cornerX - control1X) / 2f
        }

        start2X = cornerX
        start2Y = control2Y - (cornerY - control2Y) / 2f
        touchToCornerDistance = hypot((touchX - cornerX).toDouble(), (touchY - cornerY).toDouble())
            .toFloat()

        intersect(touchX, touchY, control1X, control1Y) { x, y ->
            end1X = x
            end1Y = y
        }
        intersect(touchX, touchY, control2X, control2Y) { x, y ->
            end2X = x
            end2Y = y
        }

        vertex1X = (start1X + 2f * control1X + end1X) / 4f
        vertex1Y = (2f * control1Y + start1Y + end1Y) / 4f
        vertex2X = (start2X + 2f * control2X + end2X) / 4f
        vertex2Y = (2f * control2Y + start2Y + end2Y) / 4f
    }

    /** Intersection of the line through the two given points with the fold's base line. */
    private inline fun intersect(
        fromX: Float,
        fromY: Float,
        throughX: Float,
        throughY: Float,
        assign: (Float, Float) -> Unit
    ) {
        val firstSlope = (throughY - fromY) / guard(throughX - fromX)
        val firstIntercept = fromY - firstSlope * fromX
        val secondSlope = (start2Y - start1Y) / guard(start2X - start1X)
        val secondIntercept = start1Y - secondSlope * start1X
        val x = (secondIntercept - firstIntercept) / guard(firstSlope - secondSlope)
        assign(x, firstSlope * x + firstIntercept)
    }

    private companion object {
        const val FOLD_EDGE_INSET = 1f
        fun guard(value: Float): Float = if (abs(value) < 0.1f) 0.1f else value
    }
}

private const val TOUCH_X_TRAVEL_RATIO = 4f / 3f
private const val FOLD_DEPTH_MIN = 0.8f
private const val FOLD_DEPTH_FULL_PULL = 0.5f

/**
 * Virtual touch point that produces a fold whose leading edge sits exactly under the finger.
 * Legado's geometry places that edge at [simulationFoldEdgeX], so travel has to be scaled by 4/3
 * for the page to track the drag one to one instead of lagging at three quarters of its speed.
 */
internal fun simulationTouchX(width: Float, travel: Float): Float =
    width - width * TOUCH_X_TRAVEL_RATIO * travel

/** Where the fold meets the page edge for a straight curl, derived from Legado's `calcPoints`. */
internal fun simulationFoldEdgeX(width: Float, touchX: Float): Float = (3f * touchX + width) / 4f

/**
 * How far the lifted corner may travel away from its page corner before Legado's `calcPoints` pushes
 * the first bezier start point off the page and collapses the fold.
 *
 * That point stays on the page while `control1.x >= cornerX / 3`, and substituting
 * `control1.x = cornerX - dx / 2 - dy² / (2 · dx)` yields `dy² <= (4/3) · width · dx - dx²`.
 */
internal fun maxFoldDrop(width: Float, horizontalPull: Float): Float {
    val pull = horizontalPull.coerceAtLeast(0f)
    val limit = TOUCH_X_TRAVEL_RATIO * width * pull - pull * pull
    return if (limit <= 0f) 0f else sqrt(limit)
}

/**
 * Fraction of the available fold depth to use. Dragging from the far side of the page lifts the
 * corner fully; grabbing right at the corner still lifts it most of the way so the curl stays
 * visible instead of degenerating into a flat edge.
 */
internal fun foldDepthFactor(height: Float, touchYFraction: Float, cornerY: Float): Float {
    if (height <= 0f) return FOLD_DEPTH_MIN
    val pull = abs(touchYFraction * height - cornerY) / height / FOLD_DEPTH_FULL_PULL
    return FOLD_DEPTH_MIN + (1f - FOLD_DEPTH_MIN) * pull.coerceIn(0f, 1f)
}
