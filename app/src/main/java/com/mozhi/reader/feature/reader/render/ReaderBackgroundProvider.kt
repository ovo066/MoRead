package com.mozhi.reader.feature.reader.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader

/**
 * 阅读背景的单一提供者：图片只在样式/视口变化时 center-crop、预缩放并与纸色合成一次。
 * 滚动、覆盖、平移和无动画把它画在独立静态层；仿真翻页则复用同一成品合成整页快照。
 */
internal class ReaderBackgroundProvider(private val pageStyle: ReaderPageStyle) {

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = (pageStyle.backgroundImageOpacity.coerceIn(0.05f, 1f) * 255).toInt()
    }
    private val scaledImagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val grainTile: Bitmap? = createGrainTile()
    private val grainPaint: Paint? = grainTile?.let { tile ->
        Paint().apply {
            shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }
    private val scaledImage: Bitmap? = createScaledImage()

    fun draw(canvas: Canvas, width: Float, height: Float) {
        val image = scaledImage?.takeUnless(Bitmap::isRecycled)
        if (image == null) {
            canvas.drawColor(pageStyle.backgroundColor)
            grainPaint?.let { canvas.drawRect(0f, 0f, width, height, it) }
            return
        }
        if (image.width.toFloat() == width && image.height.toFloat() == height) {
            canvas.drawBitmap(image, 0f, 0f, null)
        } else {
            canvas.drawBitmap(image, null, RectF(0f, 0f, width, height), scaledImagePaint)
        }
    }

    private fun createScaledImage(): Bitmap? {
        val path = pageStyle.backgroundImagePath ?: return null
        val width = pageStyle.viewWidth.coerceAtLeast(1)
        val height = pageStyle.viewHeight.coerceAtLeast(1)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(pageStyle.backgroundColor)
        val decoded = decodeVisibleRegion(path, width, height)
        if (decoded != null) {
            try {
                canvas.drawBitmap(
                    decoded,
                    null,
                    RectF(0f, 0f, width.toFloat(), height.toFloat()),
                    imagePaint
                )
            } finally {
                if (!decoded.isRecycled) decoded.recycle()
            }
        }
        grainPaint?.let { canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), it) }
        return result
    }

    private fun createGrainTile(): Bitmap? {
        if (!pageStyle.grain) return null
        val tile = Bitmap.createBitmap(GRAIN_TILE, GRAIN_TILE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pageStyle.textColor and 0x00FFFFFF or (0x0D shl 24)
        }
        val random = java.util.Random(GRAIN_SEED)
        repeat(GRAIN_DOTS) {
            val x = random.nextFloat() * GRAIN_TILE
            val y = random.nextFloat() * GRAIN_TILE
            canvas.drawCircle(x, y, 0.6f + random.nextFloat() * 0.7f, dotPaint)
        }
        return tile
    }

    /** 只解码 center-crop 最终可见的源区域，避免横向大图在竖屏中整张展开。 */
    @Suppress("DEPRECATION")
    private fun decodeVisibleRegion(path: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val readBounds = runCatching {
            BitmapFactory.decodeFile(path, bounds)
            true
        }.getOrDefault(false)
        if (!readBounds || bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val source = centerCropRect(
            sourceWidth = bounds.outWidth,
            sourceHeight = bounds.outHeight,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )
        var sample = 1
        val safeWidth = targetWidth.coerceAtLeast(1)
        val safeHeight = targetHeight.coerceAtLeast(1)
        while (source.width() / (sample * 2) >= safeWidth &&
            source.height() / (sample * 2) >= safeHeight
        ) {
            sample *= 2
        }
        return runCatching {
            val decoder = BitmapRegionDecoder.newInstance(path, false)
            try {
                decoder.decodeRegion(
                    source,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                )
            } finally {
                decoder.recycle()
            }
        }.getOrNull()
    }

    private fun centerCropRect(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Rect {
        val targetAspect = targetWidth.toFloat() / targetHeight.coerceAtLeast(1)
        val sourceAspect = sourceWidth.toFloat() / sourceHeight.coerceAtLeast(1)
        return if (sourceAspect > targetAspect) {
            val cropWidth = (sourceHeight * targetAspect).toInt().coerceIn(1, sourceWidth)
            val left = ((sourceWidth - cropWidth) / 2).coerceAtLeast(0)
            Rect(left, 0, left + cropWidth, sourceHeight)
        } else {
            val cropHeight = (sourceWidth / targetAspect.coerceAtLeast(0.01f)).toInt()
                .coerceIn(1, sourceHeight)
            val top = ((sourceHeight - cropHeight) / 2).coerceAtLeast(0)
            Rect(0, top, sourceWidth, top + cropHeight)
        }
    }

    fun release() {
        scaledImage?.takeUnless(Bitmap::isRecycled)?.recycle()
        grainTile?.takeUnless(Bitmap::isRecycled)?.recycle()
    }

    private companion object {
        const val GRAIN_TILE = 128
        const val GRAIN_DOTS = 220
        const val GRAIN_SEED = 42L
    }
}
