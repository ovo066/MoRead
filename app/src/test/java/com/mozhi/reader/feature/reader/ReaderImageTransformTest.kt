package com.mozhi.reader.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

class ReaderImageTransformTest {
    private val image = Size(800f, 400f)
    private val viewport = Size(400f, 600f)
    @Test fun zoomKeepsTheTouchedImagePointUnderTheFingers() {
        val result = ReaderImageTransform().gesture(Offset(250f, 320f), Offset.Zero, 2f, image, viewport)
        assertEquals(2f, result.zoom, .001f)
        assertEquals(Offset(-50f, -20f), result.offset)
    }
    @Test fun panWorksAtFitScaleAndCannotLoseTheImage() {
        val result = ReaderImageTransform().gesture(Offset(200f, 300f), Offset(30f, 45f), 1f, image, viewport)
        assertEquals(Offset(30f, 45f), result.offset)
        val bounded = result.gesture(Offset.Zero, Offset(1e8f, -1e8f), 100f, image, viewport)
        assertEquals(12f, bounded.zoom, .001f)
        assertTrue(bounded.offset.x < 3000f)
        assertTrue(bounded.offset.y > -3000f)
    }
    @Test fun rotationRefitsTheRotatedBoundsAndFourTurnsRestoreTheOriginal() {
        var result = ReaderImageTransform(3f, Offset(90f, 50f))
        result = result.rotate()
        assertEquals(.75f, result.fit(image, viewport), .001f)
        assertEquals(1f, result.zoom, .001f)
        assertEquals(Offset.Zero, result.offset)
        repeat(3) { result = result.rotate() }
        assertEquals(ReaderImageTransform(), result)
        assertEquals(.5f, result.fit(image, viewport), .001f)
    }
    @Test fun invalidGestureDoesNotPoisonTheTransform() {
        val state = ReaderImageTransform()
        assertEquals(state, state.gesture(Offset.Zero, Offset.Zero, Float.NaN, image, viewport))
        assertEquals(1f, state.gesture(Offset.Zero, Offset.Zero, .01f, image, viewport).zoom, .001f)
    }
}
