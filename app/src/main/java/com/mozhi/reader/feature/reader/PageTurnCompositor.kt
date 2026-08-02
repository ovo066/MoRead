package com.mozhi.reader.feature.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import com.mozhi.reader.core.datastore.PageTurnAnimation
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Composites the two real page bitmaps for one animation frame, a straight port of Legado's
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
    private val path0 = Path()
    private val path1 = Path()
    private val foldMatrix = Matrix()
    private val matrixValues = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val bitmapPaint = Paint()
    private val foldPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val lightBackFilter = dimFilter(scale = 0.62f, lift = 54f)
    private val darkBackFilter = dimFilter(scale = 0.62f, lift = 26f)

    private val folderShadowLeft = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0x10333333, 0x98333333.toInt())
    )
    private val folderShadowRight = GradientDrawable(
        GradientDrawable.Orientation.RIGHT_LEFT,
        intArrayOf(0x98333333.toInt(), 0x10333333)
    )
    private val backShadowLeft = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0x6A111111, 0x00111111)
    )
    private val backShadowRight = GradientDrawable(
        GradientDrawable.Orientation.RIGHT_LEFT,
        intArrayOf(0x00111111, 0x6A111111)
    )
    private val frontShadowVerticalLeft = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0x78111111, 0x00111111)
    )
    private val frontShadowVerticalRight = GradientDrawable(
        GradientDrawable.Orientation.RIGHT_LEFT,
        intArrayOf(0x00111111, 0x78111111)
    )
    private val frontShadowTop = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0x78111111, 0x00111111)
    )
    private val frontShadowBottom = GradientDrawable(
        GradientDrawable.Orientation.BOTTOM_TOP,
        intArrayOf(0x00111111, 0x78111111)
    )
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
        darkTheme: Boolean
    ) {
        when (animation) {
            PageTurnAnimation.SIMULATION -> drawSimulation(
                canvas, direction, front, under, touchX, touchY, cornerAtTop,
                width, height, backgroundColor, darkTheme
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
        backgroundColor: Int,
        darkTheme: Boolean
    ) {
        geometry.updateFromTouch(width, height, touchX, touchY, cornerAtTop)
        if (!geometry.isFinite()) {
            // Degenerate geometry: fall back to a cover-style frame rather than a corrupt fold.
            drawCover(canvas, direction, front, under, touchX - width, width, height)
            return
        }
        val maxLength = hypot(width.toDouble(), height.toDouble()).toFloat()
        val cornerX = geometry.cornerX
        val cornerY = geometry.cornerY
        // Legado's mIsRtOrLb: with the corner always on the right edge this is "corner at top".
        val rightTop = cornerAtTop

        buildFoldPath(width, height)

        // 1. The un-turned part of the sheet.
        canvas.save()
        canvas.clipOutPath(path0)
        drawFullBitmap(canvas, front, width, height)
        canvas.restore()

        // 2. The revealed page + its shadow band along the fold.
        path1.reset()
        path1.moveTo(geometry.start1X, geometry.start1Y)
        path1.lineTo(geometry.vertex1X, geometry.vertex1Y)
        path1.lineTo(geometry.vertex2X, geometry.vertex2Y)
        path1.lineTo(geometry.start2X, geometry.start2Y)
        path1.lineTo(cornerX, cornerY)
        path1.close()
        val degrees = Math.toDegrees(
            atan2(
                (geometry.control1X - cornerX).toDouble(),
                geometry.control2Y - cornerY.toDouble()
            )
        ).toFloat()
        canvas.save()
        canvas.clipPath(path0)
        canvas.clipPath(path1)
        drawFullBitmap(canvas, under, width, height)
        canvas.rotate(degrees, geometry.start1X, geometry.start1Y)
        val backShadow = if (rightTop) backShadowLeft else backShadowRight
        val backLeft: Int
        val backRight: Int
        if (rightTop) {
            backLeft = geometry.start1X.toInt()
            backRight = (geometry.start1X + geometry.touchToCornerDistance / 4f).toInt()
        } else {
            backLeft = (geometry.start1X - geometry.touchToCornerDistance / 4f).toInt()
            backRight = geometry.start1X.toInt()
        }
        backShadow.setBounds(
            backLeft,
            geometry.start1Y.toInt(),
            backRight,
            (maxLength + geometry.start1Y).toInt()
        )
        backShadow.draw(canvas)
        canvas.restore()

        // 3. The floating shadows along the lifted sheet's edges.
        drawFrontShadows(canvas, rightTop, maxLength, height)

        // 4. The sheet's back face, mirrored through the fold line and dimmed.
        drawBackFace(canvas, front, backgroundColor, darkTheme, rightTop, maxLength)
    }

    private fun buildFoldPath(width: Float, height: Float) {
        path0.reset()
        path0.moveTo(geometry.start1X, geometry.start1Y)
        path0.quadTo(geometry.control1X, geometry.control1Y, geometry.end1X, geometry.end1Y)
        path0.lineTo(geometry.touchX, geometry.touchY)
        path0.lineTo(geometry.end2X, geometry.end2Y)
        path0.quadTo(geometry.control2X, geometry.control2Y, geometry.start2X, geometry.start2Y)
        path0.lineTo(geometry.cornerX, geometry.cornerY)
        path0.close()
    }

    private fun drawFrontShadows(
        canvas: Canvas,
        rightTop: Boolean,
        maxLength: Float,
        height: Float
    ) {
        val touchX = geometry.touchX
        val touchY = geometry.touchY
        val control1X = geometry.control1X
        val control1Y = geometry.control1Y
        val control2X = geometry.control2X
        val control2Y = geometry.control2Y

        val degree = if (rightTop) {
            Math.PI / 4 - atan2((control1Y - touchY).toDouble(), (touchX - control1X).toDouble())
        } else {
            Math.PI / 4 - atan2((touchY - control1Y).toDouble(), (touchX - control1X).toDouble())
        }
        val d1 = FRONT_SHADOW_SIZE * 1.414f * cos(degree).toFloat()
        val d2 = FRONT_SHADOW_SIZE * 1.414f * sin(degree).toFloat()
        val shadowX = touchX + d1
        val shadowY = if (rightTop) touchY + d2 else touchY - d2

        path1.reset()
        path1.moveTo(shadowX, shadowY)
        path1.lineTo(touchX, touchY)
        path1.lineTo(control1X, control1Y)
        path1.lineTo(geometry.start1X, geometry.start1Y)
        path1.close()
        canvas.save()
        canvas.clipOutPath(path0)
        canvas.clipPath(path1)
        var drawable = if (rightTop) frontShadowVerticalLeft else frontShadowVerticalRight
        var left: Int
        var right: Int
        if (rightTop) {
            left = control1X.toInt()
            right = (control1X + FRONT_SHADOW_SIZE).toInt()
        } else {
            left = (control1X - FRONT_SHADOW_SIZE).toInt()
            right = (control1X + 1f).toInt()
        }
        var rotation = Math.toDegrees(
            atan2((touchX - control1X).toDouble(), (control1Y - touchY).toDouble())
        ).toFloat()
        canvas.rotate(rotation, control1X, control1Y)
        drawable.setBounds(left, (control1Y - maxLength).toInt(), right, control1Y.toInt())
        drawable.draw(canvas)
        canvas.restore()

        path1.reset()
        path1.moveTo(shadowX, shadowY)
        path1.lineTo(touchX, touchY)
        path1.lineTo(control2X, control2Y)
        path1.lineTo(geometry.start2X, geometry.start2Y)
        path1.close()
        canvas.save()
        canvas.clipOutPath(path0)
        canvas.clipPath(path1)
        drawable = if (rightTop) frontShadowTop else frontShadowBottom
        if (rightTop) {
            left = control2Y.toInt()
            right = (control2Y + FRONT_SHADOW_SIZE).toInt()
        } else {
            left = (control2Y - FRONT_SHADOW_SIZE).toInt()
            right = (control2Y + 1f).toInt()
        }
        rotation = Math.toDegrees(
            atan2((control2Y - touchY).toDouble(), (control2X - touchX).toDouble())
        ).toFloat()
        canvas.rotate(rotation, control2X, control2Y)
        val verticalOffset = if (control2Y < 0f) control2Y - height else control2Y
        val hypotenuse = hypot(control2X.toDouble(), verticalOffset.toDouble())
        if (hypotenuse > maxLength) {
            drawable.setBounds(
                (control2X - FRONT_SHADOW_SIZE - hypotenuse).toInt(),
                left,
                (control2X + maxLength - hypotenuse).toInt(),
                right
            )
        } else {
            drawable.setBounds((control2X - maxLength).toInt(), left, control2X.toInt(), right)
        }
        drawable.draw(canvas)
        canvas.restore()
    }

    private fun drawBackFace(
        canvas: Canvas,
        front: Bitmap,
        backgroundColor: Int,
        darkTheme: Boolean,
        rightTop: Boolean,
        maxLength: Float
    ) {
        val firstMidpoint = ((geometry.start1X + geometry.control1X) / 2f).toInt()
        val firstDistance = abs(firstMidpoint - geometry.control1X)
        val secondMidpoint = ((geometry.start2Y + geometry.control2Y) / 2f).toInt()
        val secondDistance = abs(secondMidpoint - geometry.control2Y)
        val shadowWidth = min(firstDistance, secondDistance)

        path1.reset()
        path1.moveTo(geometry.vertex2X, geometry.vertex2Y)
        path1.lineTo(geometry.vertex1X, geometry.vertex1Y)
        path1.lineTo(geometry.end1X, geometry.end1Y)
        path1.lineTo(geometry.touchX, geometry.touchY)
        path1.lineTo(geometry.end2X, geometry.end2Y)
        path1.close()

        val drawable: GradientDrawable
        val left: Int
        val right: Int
        if (rightTop) {
            left = (geometry.start1X - 1f).toInt()
            right = (geometry.start1X + shadowWidth + 1f).toInt()
            drawable = folderShadowLeft
        } else {
            left = (geometry.start1X - shadowWidth - 1f).toInt()
            right = (geometry.start1X + 1f).toInt()
            drawable = folderShadowRight
        }

        canvas.save()
        canvas.clipPath(path0)
        canvas.clipPath(path1)
        canvas.drawColor(backgroundColor)

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
        foldPaint.colorFilter = if (darkTheme) darkBackFilter else lightBackFilter
        canvas.drawBitmap(front, foldMatrix, foldPaint)
        foldPaint.colorFilter = null

        val degrees = Math.toDegrees(
            atan2(
                (geometry.control1X - geometry.cornerX).toDouble(),
                geometry.control2Y - geometry.cornerY.toDouble()
            )
        ).toFloat()
        canvas.rotate(degrees, geometry.start1X, geometry.start1Y)
        drawable.setBounds(
            left,
            geometry.start1Y.toInt(),
            right,
            (geometry.start1Y + maxLength).toInt()
        )
        drawable.draw(canvas)
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

    private fun dimFilter(scale: Float, lift: Float): ColorMatrixColorFilter =
        ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    scale, 0f, 0f, 0f, lift,
                    0f, scale, 0f, 0f, lift,
                    0f, 0f, scale, 0f, lift,
                    0f, 0f, 0f, 0.86f, 0f
                )
            )
        )

    private companion object {
        const val FRONT_SHADOW_SIZE = 25f
        const val EDGE_SHADOW_WIDTH = 30f
    }
}
