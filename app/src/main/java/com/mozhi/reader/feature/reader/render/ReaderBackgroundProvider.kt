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
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * 阅读背景的单一提供者：图片只在样式/视口变化时 center-crop、预缩放并与纸色合成一次。
 * 滚动、覆盖、平移和无动画把它画在独立静态层；仿真翻页则复用同一成品合成整页快照。
 *
 * 合成**必须离开主线程**：一张全屏 ARGB_8888 的分配加 BitmapRegionDecoder 解码是几十到
 * 上百毫秒，而本类由 PageBitmapRenderer 在组合期构造——同步做就等于把阅读页首帧顶掉，
 * 「设了背景图之后进书不再秒开」就是这么来的。没就绪时 [draw] 退回纯纸色（+ 纸纹），
 * 正文照常先出来，图片就绪后再回调重绘。成品按样式键进程内缓存一份，二次进书直接命中。
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

    private val cacheKey: BackgroundKey? = pageStyle.backgroundImagePath?.let { path ->
        BackgroundKey(
            path = path,
            opacity = pageStyle.backgroundImageOpacity,
            width = pageStyle.viewWidth,
            height = pageStyle.viewHeight,
            backgroundColor = pageStyle.backgroundColor,
            grain = pageStyle.grain
        )
    }

    /** 缓存的成品由缓存自己持有；本类只借用，绝不 recycle。 */
    @Volatile
    private var scaledImage: Bitmap? = cacheKey?.let(Cache::get)
    @Volatile
    private var released = false

    /**
     * 合成背景图。已就绪时什么都不做；否则丢到后台线程，完成后在主线程回调
     * [onReady]，由渲染层重画页面位图。样式换代（provider 被 release）后的迟到结果直接丢弃。
     */
    fun prepare(onReady: () -> Unit) {
        val key = cacheKey ?: return
        if (scaledImage != null || released) return
        executor.execute {
            val composed = runCatching { createScaledImage(key) }.getOrNull() ?: return@execute
            mainHandler.post {
                // 缓存说了算：同一把键已有成品就留旧的，正在用它绘制的 provider 才不会被抽走底图。
                val effective = Cache.putOrKeep(key, composed)
                if (!released) {
                    scaledImage = effective
                    onReady()
                }
            }
        }
    }

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

    private fun createScaledImage(key: BackgroundKey): Bitmap? {
        val width = key.width.coerceAtLeast(1)
        val height = key.height.coerceAtLeast(1)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(key.backgroundColor)
        val decoded = decodeVisibleRegion(key.path, width, height)
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
        // 纸纹是无状态的确定性图案，后台线程画同一份 shader 安全。
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
        released = true
        scaledImage = null // 成品归缓存所有，这里只松手
        grainTile?.takeUnless(Bitmap::isRecycled)?.recycle()
    }

    private data class BackgroundKey(
        val path: String,
        val opacity: Float,
        val width: Int,
        val height: Int,
        val backgroundColor: Int,
        val grain: Boolean
    )

    /**
     * 只留最近一份成品：翻页/改设置会不停重建 provider，但键几乎总是同一把，
     * 命中就完全省掉解码；键一变说明旧成品再也用不上，直接回收。
     * 全部调用都在主线程（构造在组合期、写入在 mainHandler 里），无需再加锁。
     */
    private object Cache {
        private var key: BackgroundKey? = null
        private var bitmap: Bitmap? = null

        fun get(requested: BackgroundKey): Bitmap? =
            bitmap?.takeIf { key == requested && !it.isRecycled }

        /** 返回实际生效的成品：同键已有就保留旧的、回收新的，避免抽走别人正在画的底图。 */
        fun putOrKeep(newKey: BackgroundKey, candidate: Bitmap): Bitmap {
            get(newKey)?.let { existing ->
                if (existing !== candidate) candidate.recycle()
                return existing
            }
            bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            key = newKey
            bitmap = candidate
            return candidate
        }
    }

    private companion object {
        const val GRAIN_TILE = 128
        const val GRAIN_DOTS = 220
        const val GRAIN_SEED = 42L

        /** 单线程即可：同一时刻只会有一份背景在合成，串行还能天然抛掉过期请求。 */
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "reader-background").apply { isDaemon = true }
        }
        val mainHandler = Handler(Looper.getMainLooper())
    }
}
