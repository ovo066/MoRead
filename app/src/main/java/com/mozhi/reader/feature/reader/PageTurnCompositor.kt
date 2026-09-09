package com.mozhi.reader.feature.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import com.mozhi.reader.core.datastore.PageTurnAnimation
import kotlin.math.hypot
import com.mozhi.reader.feature.reader.render.SpreadGeometry
import com.mozhi.reader.feature.reader.render.Leaf

/**
 * Composites the two real page bitmaps for one animation frame, adapting Legado's
 * `CoverPageDelegate` / `SlidePageDelegate` / `SimulationPageDelegate` drawing code working in
 * absolute view space:
 *
 * - NEXT: the turning sheet is the current page, the page revealed underneath is the next one.
 * - PREV: the turning sheet is the previous page unrolling back over the current one; the corner
 *   is still on the right edge (Legado maps the grab point into the right half), so the same
 *   geometry serves both directions with no canvas mirroring.
 */
class PageTurnCompositor {

    private val geometry = PageFoldGeometry()
    private val foldPath = Path()
    private val backFacePath = Path()
    private val foldMatrix = Matrix()
    private val matrixValues = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val bitmapPaint = Paint()
    private val foldPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = BACK_FACE_INK_ALPHA
    }

    /** Legado's cover shadow: darkest at the moving page's edge, fading onto the revealed page. */
    private val edgeShadow = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0x66111111, 0x00000000)
    )

    /**
     * @param front the sheet being turned (cur for NEXT, prev for PREV)
     * @param under the page revealed beneath it (next for NEXT, cur for PREV)
     */
    fun draw(
        canvas: Canvas,
        animation: PageTurnAnimation,
        direction: PageTurnDirection,
        front: Bitmap,
        under: Bitmap,
        touchX: Float,
        touchY: Float,
        startX: Float,
        cornerAtTop: Boolean,
        width: Float,
        height: Float,
        backgroundColor: Int,
        spread: SpreadGeometry? = null
    ) {
        if (animation == PageTurnAnimation.SIMULATION && spread != null) {
            // Existing flat compositor contract supplies (prev,cur) for a backward turn.
            val current = if (direction == PageTurnDirection.NEXT) front else under
            val target = if (direction == PageTurnDirection.NEXT) under else front
            drawSpreadSimulation(canvas, direction, current, target, touchX, startX, spread, backgroundColor)
            return
        }
        when (animation) {
            PageTurnAnimation.SIMULATION -> drawSimulation(
                canvas, direction, front, under, touchX, touchY, cornerAtTop,
                width, height, backgroundColor
            )
            PageTurnAnimation.COVER -> drawCover(
                canvas, direction, front, under, touchX - startX, width, height
            )
            PageTurnAnimation.SLIDE -> drawSlide(
                canvas, direction, front, under, touchX - startX, width, height
            )
            PageTurnAnimation.NONE -> drawFullBitmap(canvas, front, width, height)
        }
    }

    private fun drawSpreadSimulation(
        canvas: Canvas,
        direction: PageTurnDirection,
        current: Bitmap,
        target: Bitmap,
        touchX: Float,
        startX: Float,
        spread: SpreadGeometry,
        backgroundColor: Int
    ) {
        val turn = SpreadLeafGeometry.fromTouch(direction, touchX, startX, spread)
        if (turn.angle <= 0f || turn.angle >= 180f) {
            drawFullBitmap(canvas, if (turn.angle <= 0f) current else target, spread.paneWidth, spread.paneHeight)
            return
        }
        val count = canvas.save()
        try {
            canvas.clipRect(0f, 0f, spread.paneWidth, spread.paneHeight)
            canvas.drawColor(backgroundColor or OPAQUE_ALPHA_MASK)
            drawFullBitmap(canvas, target, spread.paneWidth, spread.paneHeight)
            val stationary = if (direction == PageTurnDirection.NEXT) Leaf.LEFT else Leaf.RIGHT
            val stationaryRect = spread.leafSrcRect(stationary)
            canvas.save()
            canvas.clipRect(stationaryRect.left, 0f, stationaryRect.right, spread.paneHeight)
            drawFullBitmap(canvas, current, spread.paneWidth, spread.paneHeight)
            canvas.restore()
            val face = if (turn.showBack) target else current
            val faceLeaf = when {
                direction == PageTurnDirection.NEXT && !turn.showBack -> Leaf.RIGHT
                direction == PageTurnDirection.PREVIOUS && turn.showBack -> Leaf.RIGHT
                else -> Leaf.LEFT
            }
            val source = spread.leafSrcRect(faceLeaf)
            val src = floatArrayOf(source.left, 0f, source.right, 0f,
                source.right, spread.paneHeight, source.left, spread.paneHeight)
            val quad = turn.dstQuad
            val facePath = Path().apply {
                moveTo(quad[0], quad[1]); lineTo(quad[2], quad[3])
                lineTo(quad[4], quad[5]); lineTo(quad[6], quad[7]); close()
            }
            val matrix = Matrix()
            if (matrix.setPolyToPoly(src, 0, quad, 0, 4)) {
                canvas.save()
                canvas.clipPath(facePath)
                canvas.drawBitmap(face, matrix, bitmapPaint)
                val shade = Paint().apply { color = android.graphics.Color.BLACK; alpha = (turn.shadowAlpha * 255).toInt() }
                canvas.drawPath(facePath, shade)
                canvas.restore()
            }
        } finally {
            canvas.restoreToCount(count)
        }
    }

    // ---- cover ----

    private fun drawCover(
        canvas: Canvas,
        direction: PageTurnDirection,
        front: Bitmap,
        under: Bitmap,
        rawOffset: Float,
        width: Float,
        height: Float
    ) {
        if (direction == PageTurnDirection.NEXT) {
            val offset = rawOffset.coerceIn(-width, 0f)
            drawBitmapAt(canvas, under, 0f, width, height)
            drawBitmapAt(canvas, front, offset, width, height)
            drawEdgeShadow(canvas, edge = width + offset, height = height)
        } else {
            val offset = rawOffset.coerceIn(0f, width)
            drawBitmapAt(canvas, under, 0f, width, height)
            drawBitmapAt(canvas, front, offset - width, width, height)
            drawEdgeShadow(canvas, edge = offset, height = height)
        }
    }

    // ---- slide ----

    private fun drawSlide(
        canvas: Canvas,
        direction: PageTurnDirection,
        front: Bitmap,
        under: Bitmap,
        rawOffset: Float,
        width: Float,
        height: Float
    ) {
        // Legado's slide draws no edge shadow: both pages travel together like a ViewPager.
        if (direction == PageTurnDirection.NEXT) {
            val offset = rawOffset.coerceIn(-width, 0f)
            drawBitmapAt(canvas, under, offset + width, width, height)
            drawBitmapAt(canvas, front, offset, width, height)
        } else {
            val offset = rawOffset.coerceIn(0f, width)
            drawBitmapAt(canvas, under, offset, width, height)
            drawBitmapAt(canvas, front, offset - width, width, height)
        }
    }

    private fun drawBitmapAt(
        canvas: Canvas,
        bitmap: Bitmap,
        left: Float,
        width: Float,
        height: Float
    ) {
        if (bitmap.isRecycled) return
        canvas.save()
        canvas.translate(left, 0f)
        if (bitmap.width.toFloat() != width || bitmap.height.toFloat() != height) {
            canvas.scale(width / bitmap.width, height / bitmap.height)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
        canvas.restore()
    }

    private fun drawEdgeShadow(canvas: Canvas, edge: Float, height: Float) {
        if (edge <= 0f) return
        edgeShadow.setBounds(
            edge.toInt(),
            0,
            (edge + EDGE_SHADOW_WIDTH).toInt(),
            height.toInt()
        )
        edgeShadow.draw(canvas)
    }

    // ---- simulation ----

    private fun drawSimulation(
        canvas: Canvas,
        direction: PageTurnDirection,
        front: Bitmap,
        under: Bitmap,
        touchX: Float,
        touchY: Float,
        cornerAtTop: Boolean,
        width: Float,
        height: Float,
        backgroundColor: Int
    ) {
        val saveCount = canvas.save()
        try {
            canvas.clipRect(0f, 0f, width, height)
            val paperColor = backgroundColor or OPAQUE_ALPHA_MASK
            // Every frame owns every viewport pixel, even with translucent theme/page snapshots.
            // Never depend on pixels left in the target Canvas by a preceding frame.
            canvas.drawColor(paperColor)
            geometry.updateFromTouch(width, height, touchX, touchY, cornerAtTop)
            if (!geometry.isFinite()) {
                // A corrupt fold must not bring back the COVER mode's shadow as a fallback.
                val current = if (direction == PageTurnDirection.NEXT) front else under
                drawFullBitmap(canvas, current, width, height)
                return
            }
            buildFoldPaths()

            // The revealed page is a full, opaque base, not a chord-clipped approximation of the
            // curl. The other two surfaces completely cover the parts that should remain hidden.
            drawFullBitmap(canvas, under, width, height)

            canvas.save()
            canvas.clipOutPath(foldPath)
            canvas.drawColor(paperColor)
            drawFullBitmap(canvas, front, width, height)
            canvas.restore()

            // Fainter mirrored ink distinguishes the back without cast shadows or fold gradients.
            drawBackFace(canvas, front, width, height, paperColor)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    private fun buildFoldPaths() {
        foldPath.reset()
        foldPath.moveTo(geometry.start1X, geometry.start1Y)
        foldPath.quadTo(geometry.control1X, geometry.control1Y, geometry.end1X, geometry.end1Y)
        foldPath.lineTo(geometry.touchX, geometry.touchY)
        foldPath.lineTo(geometry.end2X, geometry.end2Y)
        foldPath.quadTo(geometry.control2X, geometry.control2Y, geometry.start2X, geometry.start2Y)
        foldPath.lineTo(geometry.cornerX, geometry.cornerY)
        foldPath.close()

        // Retain the existing back-face polygon. Intersecting it with foldPath below keeps
        // the same curved outline as the turning sheet without changing the fold geometry.
        backFacePath.reset()
        backFacePath.moveTo(geometry.vertex2X, geometry.vertex2Y)
        backFacePath.lineTo(geometry.vertex1X, geometry.vertex1Y)
        backFacePath.lineTo(geometry.end1X, geometry.end1Y)
        backFacePath.lineTo(geometry.touchX, geometry.touchY)
        backFacePath.lineTo(geometry.end2X, geometry.end2Y)
        backFacePath.close()
    }

    private fun drawBackFace(
        canvas: Canvas,
        front: Bitmap,
        width: Float,
        height: Float,
        paperColor: Int
    ) {
        canvas.save()
        canvas.clipPath(foldPath)
        canvas.clipPath(backFacePath)
        // Fade only the reflected ink: the opaque paper still blocks the page underneath.
        canvas.drawColor(paperColor)
        if (!front.isRecycled) {
            val distance = hypot(
                (geometry.cornerX - geometry.control1X).toDouble(),
                (geometry.control2Y - geometry.cornerY).toDouble()
            ).toFloat().coerceAtLeast(0.1f)
            val ratioX = (geometry.cornerX - geometry.control1X) / distance
            val ratioY = (geometry.control2Y - geometry.cornerY) / distance
            matrixValues[0] = 1f - 2f * ratioY * ratioY
            matrixValues[1] = 2f * ratioX * ratioY
            matrixValues[3] = matrixValues[1]
            matrixValues[4] = 1f - 2f * ratioX * ratioX
            foldMatrix.reset()
            foldMatrix.setValues(matrixValues)
            foldMatrix.preTranslate(-geometry.control1X, -geometry.control1Y)
            foldMatrix.postTranslate(geometry.control1X, geometry.control1Y)
            if (front.width.toFloat() != width || front.height.toFloat() != height) {
                foldMatrix.preScale(width / front.width, height / front.height)
            }
            canvas.drawBitmap(front, foldMatrix, foldPaint)
        }
        canvas.restore()
    }

    private fun drawFullBitmap(canvas: Canvas, bitmap: Bitmap, width: Float, height: Float) {
        if (bitmap.isRecycled) return
        if (bitmap.width.toFloat() != width || bitmap.height.toFloat() != height) {
            canvas.save()
            canvas.scale(width / bitmap.width, height / bitmap.height)
            canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
            canvas.restore()
        } else {
            canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
        }
    }

    private companion object {
        const val EDGE_SHADOW_WIDTH = 30f
        const val BACK_FACE_INK_ALPHA = 128
        const val OPAQUE_ALPHA_MASK = 0xFF000000.toInt()
    }
}
