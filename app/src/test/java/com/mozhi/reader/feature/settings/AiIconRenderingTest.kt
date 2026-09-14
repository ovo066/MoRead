package com.mozhi.reader.feature.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import com.mozhi.reader.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiIconRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun androidVectorsMatchOriginalSvgShapesAndGradients() {
        val icons = listOf(
            R.drawable.ic_ai_gemini to "gemini-color.svg",
            R.drawable.ic_ai_minimax to "minimax-color.svg",
            R.drawable.ic_ai_zhipu to "zai.svg",
            R.drawable.ic_ai_volcengine to "volcengine-color.svg",
            R.drawable.ic_ai_siliconflow to "siliconcloud-color.svg"
        )
        lateinit var view: View
        compose.setContent {
            val current = LocalView.current
            SideEffect { view = current }
            Column(Modifier.fillMaxSize().background(Color.White)) {
                icons.forEach { (id, name) ->
                    Image(painterResource(id), null, Modifier.size(96.dp).testTag(name))
                }
            }
        }
        compose.waitForIdle()
        val captured = compose.runOnIdle {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        icons.forEach { (_, name) ->
            val bounds = compose.onNodeWithTag(name).fetchSemanticsNode().boundsInRoot
            val width = bounds.width.roundToInt()
            val height = bounds.height.roundToInt()
            val actual = Bitmap.createBitmap(captured, bounds.left.roundToInt(), bounds.top.roundToInt(), width, height)
            val expected = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val svg = javaClass.getResourceAsStream("/ai-icon-reference/" + name).use { SVG.getFromInputStream(requireNotNull(it)) }
            svg.setDocumentWidth(width.toFloat())
            svg.setDocumentHeight(height.toFloat())
            val canvas = Canvas(expected)
            canvas.drawColor(android.graphics.Color.WHITE)
            svg.renderToCanvas(canvas)
            var shapeDifference = 0
            var colorDifference = 0L
            var referenceInk = 0
            for (y in 0 until height) for (x in 0 until width) {
                val a = actual.getPixel(x, y)
                val b = expected.getPixel(x, y)
                fun ink(pixel: Int) = (0..2).any { shift -> (pixel ushr (shift * 8) and 255) < 245 }
                if (ink(b)) referenceInk++
                if (ink(a) != ink(b)) shapeDifference++
                for (shift in 0..2) colorDifference += abs((a ushr (shift * 8) and 255) - (b ushr (shift * 8) and 255))
            }
            assertTrue(name + " reference must contain a visible glyph", referenceInk > width * height * 0.1)
            assertTrue(name + " outline differs: " + shapeDifference, shapeDifference < width * height * 0.025)
            assertTrue(name + " gradients differ", colorDifference.toDouble() / (width * height * 3 * 255) < 0.04)
            actual.recycle()
            expected.recycle()
        }
        captured.recycle()
    }
}
