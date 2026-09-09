package com.mozhi.reader.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.mozhi.reader.core.datastore.PageTurnAnimation
import com.mozhi.reader.feature.reader.render.SpreadGeometry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpreadCompositorTest {
    private val spread = SpreadGeometry(440f, 200f, 40f)
    private val bitmaps = mutableListOf<Bitmap>()

    @After
    fun recycle() = bitmaps.forEach { if (!it.isRecycled) it.recycle() }

    private fun bitmap(left: Int, right: Int): Bitmap = Bitmap.createBitmap(440, 200, Bitmap.Config.ARGB_8888).also {
        bitmaps += it
        it.eraseColor(Color.WHITE)
        val canvas = Canvas(it)
        canvas.drawRect(0f, 0f, 200f, 200f, Paint().apply { color = left })
        canvas.drawRect(240f, 0f, 440f, 200f, Paint().apply { color = right })
    }

    private fun frame(
        current: Bitmap,
        target: Bitmap,
        progress: Float,
        direction: PageTurnDirection,
        animation: PageTurnAnimation = PageTurnAnimation.SIMULATION,
        geometry: SpreadGeometry? = spread
    ): Bitmap {
        val output = bitmap(Color.WHITE, Color.WHITE)
        PageTurnCompositor().draw(
            canvas = Canvas(output), animation = animation, direction = direction,
            front = if (direction == PageTurnDirection.NEXT) current else target,
            under = if (direction == PageTurnDirection.NEXT) target else current,
            touchX = if (direction == PageTurnDirection.NEXT) -spread.leafWidth * progress else spread.leafWidth * progress,
            touchY = spread.paneHeight, startX = 0f, cornerAtTop = false,
            width = spread.paneWidth, height = spread.paneHeight, backgroundColor = Color.WHITE,
            spread = geometry
        )
        return output
    }

    @Test
    fun `single hinge uses current front and target back at zero 45 135 and 180 degrees`() {
        val current = bitmap(Color.RED, Color.GREEN)
        val target = bitmap(Color.BLUE, Color.YELLOW)
        for (direction in PageTurnDirection.entries) {
            val initial = frame(current, target, 0f, direction)
            val final = frame(current, target, 1f, direction)
            assertTrue(initial.sameAs(current))
            assertTrue(final.sameAs(target))
            for (progress in listOf(0.25f, 0.75f)) {
                val output = frame(current, target, progress, direction)
                val geometry = SpreadLeafGeometry.fromTouch(direction,
            (if (direction == PageTurnDirection.NEXT) -1f else 1f) * spread.leafWidth * progress,
            0f, spread)
                val x = ((geometry.dstQuad[0] + geometry.dstQuad[2]) / 2f).toInt()
                val actual = output.getPixel(x, 100)
                val expected = when {
                    direction == PageTurnDirection.NEXT && progress < 0.5f -> Color.GREEN
                    direction == PageTurnDirection.NEXT -> Color.BLUE
                    progress < 0.5f -> Color.RED
                    else -> Color.YELLOW
                }
                // Lighting may darken a face, but must not replace it with mirrored current ink.
                fun channel(reference: Int, value: Int) {
                    if (reference == 0) assertEquals(0, value) else assertTrue(value in 190..255)
                }
                channel(Color.red(expected), Color.red(actual))
                channel(Color.green(expected), Color.green(actual))
                channel(Color.blue(expected), Color.blue(actual))
                if (direction == PageTurnDirection.NEXT) {
                    assertEquals(Color.YELLOW, output.getPixel(420, 100))
                    if (progress < 0.5f) assertEquals(Color.RED, output.getPixel(20, 100))
                } else {
                    assertEquals(Color.BLUE, output.getPixel(20, 100))
                    if (progress < 0.5f) assertEquals(Color.GREEN, output.getPixel(420, 100))
                }
            }
        }
    }

    @Test
    fun `slide and cover treat full spread as the existing atomic bitmap unit`() {
        val current = bitmap(Color.RED, Color.GREEN)
        val target = bitmap(Color.BLUE, Color.YELLOW)
        for (animation in listOf(PageTurnAnimation.COVER, PageTurnAnimation.SLIDE, PageTurnAnimation.NONE)) {
            for (direction in PageTurnDirection.entries) {
                for (progress in listOf(0f, 0.5f, 1f)) {
                    val spreadFrame = frame(current, target, progress, direction, animation)
                    val flatFrame = frame(current, target, progress, direction, animation, geometry = null)
                    assertTrue("$animation $direction $progress", spreadFrame.sameAs(flatFrame))
                }
            }
        }
    }
}
