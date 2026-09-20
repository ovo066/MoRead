package com.mozhi.reader.feature.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.mozhi.reader.feature.reader.render.SpreadGeometry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Pixel checks must use a real GPU: Android intentionally rejects AGSL on a software Canvas. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class ModernPageCurlGpuTest {
    @Test fun modernCurlHasExactEndpointsOpaqueFramesAndDistinctIntermediateGeometry() {
        val front = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val under = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(214, 229, 211)) }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 24f }
        Canvas(front).apply { repeat(15) { drawText("春江潮水连海平 ${it + 1}", 28f, 60f + it * 34f, paint) } }
        val curl = ModernPageCurl()
        for (direction in PageTurnDirection.entries) for (step in listOf(0f, .28f, .55f, 1f)) {
            val next = direction == PageTurnDirection.NEXT
            val result = gpuFrame(360, 640) { canvas ->
                curl.draw(canvas, direction, front, under, 720f * step * if (next) -1 else 1,
                    500f, 360f, 640f, Color.WHITE, null)
            }
            val pixels = IntArray(360 * 640).also { result.getPixels(it, 0, 360, 0, 0, 360, 640) }
            assertTrue(pixels.all { it ushr 24 == 255 })
            if (step == 0f || step == 1f) {
                val expected = if ((step == 0f) == next) front else under
                assertTrue(result.sameAs(expected))
            }
            save(result, "modern-${direction.name}-$step.png")
        }
    }

    @Test fun shaderFoldsFromTheUpperRightForADownLeftDragAndReversesForAnUpLeftDrag() {
        val front = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val under = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff4477cc.toInt()) }
        val paint = android.graphics.Paint().apply { color = Color.BLACK; textSize = 22f }
        // Dense ink reaches the top-right corner which this short diagonal drag folds over.
        Canvas(front).apply { repeat(22) { drawText("春江潮水连海平海上明月共潮生 ${it + 1}", 5f, 20f + it * 28, paint) } }
        val curl = ModernPageCurl()
        fun frame(y: Float, opacity: Float = .18f): Bitmap = gpuFrame(360, 640) { canvas ->
            curl.draw(canvas, PageTurnDirection.NEXT, front, under, -160f, y, 360f, 640f, Color.WHITE, null,
                backTextOpacity = opacity, startX = 350f, startY = 320f)
        }
        val down = frame(470f)
        val up = frame(170f)
        fun isRevealed(pixel: Int) = Color.blue(pixel) - Color.red(pixel) > 60
        assertTrue(isRevealed(down.getPixel(340, 40)))
        assertFalse(isRevealed(down.getPixel(340, 600)))
        assertFalse(isRevealed(up.getPixel(340, 40)))
        assertTrue(isRevealed(up.getPixel(340, 600)))
        // Keep the narrow binding strip stable while allowing the body of the page to bend.
        for (y in 10 until 630 step 10) {
            assertPixelNear(front.getPixel(16, y), down.getPixel(16, y))
            assertPixelNear(front.getPixel(8, y), down.getPixel(8, y))
            assertPixelNear(front.getPixel(16, y), up.getPixel(16, y))
        }
        save(down, "modern-diagonal-down-left.png")
        save(up, "modern-diagonal-up-left.png")
        // A diagonal pull bends into the page body instead of staying in a narrow outer strip.
        assertTrue((100 until 220).count { x -> front.getPixel(x, 100) != down.getPixel(x, 100) } > 20)
        val opaque = frame(470f, 1f)
        val transparent = frame(470f, 0f)
        save(opaque, "modern-back-ink.png")
        save(transparent, "modern-back-paper.png")
        assertFalse(transparent.sameAs(opaque))
    }

    @Test fun spreadCurlUsesLeafTravelAndReturnsExactlyToTheOriginalWhenCancelled() {
        val geometry = SpreadGeometry(800f, 600f, 40f)
        val current = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val target = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val paint = android.graphics.Paint().apply { color = Color.GRAY }
        Canvas(current).drawRect(380f, 0f, 420f, 600f, paint)
        val curl = ModernPageCurl()
        for (direction in PageTurnDirection.entries) {
            val next = direction == PageTurnDirection.NEXT
            for (progress in listOf(.6f, .2f, 0f, 1f)) {
                val result = gpuFrame(800, 600) { canvas ->
                    curl.draw(canvas, direction, if (next) current else target, if (next) target else current,
                        geometry.leafWidth * progress * if (next) -1 else 1, 450f, 800f, 600f, Color.WHITE, geometry)
                }
                if (progress == 0f) assertTrue(result.sameAs(current))
                if (progress == 1f) assertTrue(result.sameAs(target))
                if (progress == .6f) save(result, "modern-spread-${direction.name}.png")
            }
        }
    }

    @Test fun backwardCurlLaysThePreviousSheetOverTheReadableCurrentPage() {
        val current = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val target = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val paint = android.graphics.Paint().apply { color = Color.RED }
        Canvas(current).apply {
            drawRect(230f, 160f, 290f, 520f, paint)
            paint.color = Color.BLACK
            drawRect(290f, 260f, 330f, 320f, paint)
        }
        val curl = ModernPageCurl()
        val result = gpuFrame(360, 640) { canvas ->
            curl.draw(canvas, PageTurnDirection.PREVIOUS, target, current, 216f, 320f,
                360f, 640f, Color.WHITE, null, startX = 20f, startY = 320f)
        }
        save(result, "modern-previous-readable.png")
        assertEquals(Color.RED, result.getPixel(250, 200))
        assertEquals(Color.BLACK, result.getPixel(310, 290))
        assertEquals(Color.WHITE, result.getPixel(340, 500))
        assertTrue(Color.blue(result.getPixel(10, 20)) - Color.red(result.getPixel(10, 20)) > 20)
    }

    @Test fun returningUsesTheForwardCurlPathInReverseWithoutMirroringTheBinding() {
        val sheet = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val below = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val paint = android.graphics.Paint().apply { color = Color.RED }
        Canvas(sheet).drawRect(40f, 100f, 180f, 300f, paint)
        val curl = ModernPageCurl()
        for (p in listOf(.2f, .45f, .75f)) {
            fun frame(direction: PageTurnDirection, offset: Float) = gpuFrame(360, 640) { canvas ->
                curl.draw(canvas, direction, sheet, below, offset, 320f, 360f, 640f, Color.WHITE, null,
                    startX = 350f, startY = 320f)
            }
            val forward = frame(PageTurnDirection.NEXT, -720f * p)
            val backward = frame(PageTurnDirection.PREVIOUS, 720f * (1f - p))
            // Floating point roundoff in reversing progress can change a subpixel edge by 1.
            val a = IntArray(360 * 640).also { forward.getPixels(it, 0, 360, 0, 0, 360, 640) }
            val b = IntArray(a.size).also { backward.getPixels(it, 0, 360, 0, 0, 360, 640) }
            assertTrue(a.indices.count { kotlin.math.abs(Color.red(a[it]) - Color.red(b[it])) > 2 } < 10)
        }
    }

    @Test fun shortEdgeDragsAndSteepFlicksStayOpaqueAndLandWithoutAResidualCorner() {
        val current = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val target = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val curl = ModernPageCurl()
        for (anchor in listOf(1f, 150f, 320f, 639f)) {
            for (progress in listOf(.001f, .02f, .35f, .8f, .9999f)) {
                val result = gpuFrame(360, 640) { canvas ->
                    curl.draw(canvas, PageTurnDirection.NEXT, current, target, -720f * progress,
                        anchor + 450f * (1f - progress), 360f, 640f, Color.WHITE, null,
                        startX = 350f, startY = anchor)
                }
                val pixels = IntArray(360 * 640).also { result.getPixels(it, 0, 360, 0, 0, 360, 640) }
                assertTrue(pixels.all { it ushr 24 == 255 })
                if (progress == .9999f) assertTrue("Residual corner at anchor $anchor", result.sameAs(target))
            }
        }
    }

    private fun assertPixelNear(expected: Int, actual: Int) {
        // Inverse basis transforms can move a filtered ink edge by a fraction of a pixel.
        assertEquals(Color.alpha(expected), Color.alpha(actual))
        assertTrue(kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= 2)
        assertTrue(kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= 2)
        assertTrue(kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= 2)
    }

    private fun gpuFrame(width: Int, height: Int, draw: (Canvas) -> Unit): Bitmap {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val node = RenderNode("modern-curl-pixel-test").apply { setPosition(0, 0, width, height) }
        val renderer = HardwareRenderer()
        try {
            val canvas = node.beginRecording()
            try { assertTrue(canvas.isHardwareAccelerated); draw(canvas) } finally { node.endRecording() }
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = requireNotNull(reader.acquireNextImage()) { "GPU frame was not presented" }
            try {
                val buffer = requireNotNull(image.hardwareBuffer)
                try {
                    val hardware = requireNotNull(Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)))
                    try { return requireNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false)) }
                    finally { hardware.recycle() }
                } finally { buffer.close() }
            } finally { image.close() }
        } finally { renderer.destroy(); node.discardDisplayList(); reader.close() }
    }

    private fun save(bitmap: Bitmap, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "reader-gpu-qa/$name").apply { parentFile!!.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
