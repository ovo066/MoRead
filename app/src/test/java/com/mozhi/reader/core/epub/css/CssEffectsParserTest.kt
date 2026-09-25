package com.mozhi.reader.core.epub.css

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gradients, translate(), position insets and vertical-text aliases as publishers write them. */
class CssEffectsParserTest {
    private fun declarations(css: String) = CssParser("OEBPS/Styles/main.css").parseDeclarations(css)
        .associateBy { it.property }

    @Test
    fun `linear gradients keep direction and colour stops`() {
        val angle = declarations("background-image: linear-gradient(135deg, #f06, rgba(72, 136, 255, 0.5) 80%)")
            .getValue("background-image").value as CssValue.Gradient
        assertEquals(135f, angle.angleDeg)
        assertEquals(false, angle.radial)
        assertEquals(2, angle.stops.size)
        assertEquals(0xFFFF0066.toInt(), angle.stops[0].argb)
        assertNull(angle.stops[0].position)
        assertEquals(CssValue.Length(80f, CssUnit.PERCENT), angle.stops[1].position)

        val side = declarations("background-image: linear-gradient(to right, red, blue)")
            .getValue("background-image").value as CssValue.Gradient
        assertEquals(90f, side.angleDeg)
    }

    @Test
    fun `repeating ruled paper gradient with double positions`() {
        val ruled = declarations(
            "background-image: repeating-linear-gradient(transparent, transparent 28px, rgba(200,180,140,0.3) 28px, rgba(200,180,140,0.3) 29px)"
        ).getValue("background-image").value as CssValue.Gradient
        assertTrue(ruled.repeating)
        assertEquals(180f, ruled.angleDeg)
        assertEquals(listOf(null, 28f, 28f, 29f), ruled.stops.map { it.position?.value })
    }

    @Test
    fun `radial gradients read shape and centre`() {
        val radial = declarations("background: radial-gradient(circle at 25% 75%, #fff 0%, #c80 100%)")
            .getValue("background-image").value as CssValue.Gradient
        assertTrue(radial.radial)
        assertTrue(radial.circle)
        assertEquals(.25f, radial.centerX)
        assertEquals(.75f, radial.centerY)
    }

    @Test
    fun `background shorthand keeps colour and gradient, multiple layers pick the top image`() {
        val single = declarations("background: #fdf linear-gradient(red, blue) no-repeat")
        assertEquals(CssValue.Color(0xFFFFDDFF.toInt()), single.getValue("background-color").value)
        assertTrue(single.getValue("background-image").value is CssValue.Gradient)
        assertEquals(CssValue.Keyword("no-repeat"), single.getValue("background-repeat").value)

        val layered = declarations("background: url(../Images/a.png) no-repeat center, linear-gradient(red, blue), #eee")
        assertEquals(CssValue.Url("OEBPS/Images/a.png"), layered.getValue("background-image").value)
        assertEquals(CssValue.Keyword("no-repeat"), layered.getValue("background-repeat").value)
        assertEquals(CssValue.Color(0xFFEEEEEE.toInt()), layered.getValue("background-color").value)
    }

    @Test
    fun `translate and insets for positioned cards`() {
        val values = declarations("position: absolute; top: 50%; left: -8px; transform: translate(-50%, -50%)")
        assertEquals(CssValue.Keyword("absolute"), values.getValue("position").value)
        assertEquals(CssValue.Length(50f, CssUnit.PERCENT), values.getValue("top").value)
        assertEquals(CssValue.Length(-8f, CssUnit.PX), values.getValue("left").value)
        assertEquals(
            CssValue.Tuple(listOf(CssValue.Length(-50f, CssUnit.PERCENT), CssValue.Length(-50f, CssUnit.PERCENT))),
            values.getValue("transform").value
        )
        val onlyY = declarations("-webkit-transform: translateY(10px)").getValue("transform").value as CssValue.Tuple
        assertEquals(CssValue.Length(10f, CssUnit.PX), onlyY.items[1])
        // 旋转、缩放不影响排版：宽容忽略，而不是拖垮同一规则里的其他声明。
        assertNull(declarations("transform: rotate(3deg); color: red")["transform"])
    }

    @Test
    fun `tate-chu-yoko and text-orientation vendor aliases normalize`() {
        assertEquals(CssValue.Keyword("all"), declarations("-webkit-text-combine: horizontal").getValue("text-combine-upright").value)
        assertEquals(CssValue.Keyword("all"), declarations("-epub-text-combine: horizontal").getValue("text-combine-upright").value)
        assertEquals(CssValue.Keyword("none"), declarations("text-combine-upright: none").getValue("text-combine-upright").value)
        assertEquals(CssValue.Keyword("upright"), declarations("-epub-text-orientation: upright").getValue("text-orientation").value)
    }
}
