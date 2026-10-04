package com.mozhi.reader.feature.bookdetail

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class CoverPaletteTest {
    private fun argb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance()); val lo = minOf(a.luminance(), b.luminance())
        return (hi + .05f) / (lo + .05f)
    }

    @Test fun aSmallSaturatedSealOutweighsAPaleCover() {
        val paper = argb(236, 230, 218)
        val seal = argb(190, 40, 40)
        val pixels = IntArray(400) { if (it < 24) seal else paper }
        val palette = requireNotNull(extractCoverPalette(pixels))
        val vibrant = requireNotNull(palette.vibrant)
        assertTrue(vibrant.red > .6f && vibrant.green < .3f && vibrant.blue < .3f)
        assertTrue(palette.dominant.red > .85f) // The average still reads as the paper.
    }

    @Test fun greyAndTransparentCoversHaveNoHueToLend() {
        val grey = extractCoverPalette(IntArray(100) { argb(90 + it % 40, 90 + it % 40, 90 + it % 40) })
        assertNull(requireNotNull(grey).vibrant)
        assertNull(extractCoverPalette(IntArray(50) { argb(200, 30, 30, a = 10) }))
        assertNull(extractCoverPalette(IntArray(0)))
    }

    @Test fun accentStaysReadableAndWashStaysQuietInBothThemes() {
        val light = Color(0xFFF5F4F1)
        val dark = Color(0xFF16181A)
        listOf(Color(0xFFE53935), Color(0xFFFFEB3B), Color(0xFF00BFA5), Color(0xFF283593), Color(0xFF8E24AA)).forEach { seed ->
            val palette = CoverPalette(seed, seed)
            val day = coverAtmosphere(palette, light, dark = false, fallback = Color.Black)
            val night = coverAtmosphere(palette, dark, dark = true, fallback = Color.White)
            assertTrue("day accent for $seed", contrast(day.accent, light) >= 4.5f)
            assertTrue("night accent for $seed", contrast(night.accent, dark) >= 4.5f)
            // Body text on the wash must stay comfortably legible.
            assertTrue(day.top.luminance() > .45f)
            assertTrue(night.top.luminance() < .12f)
        }
    }

    @Test fun greyCoversBorrowTheAppAccentAtReducedStrength() {
        val background = Color(0xFFF5F4F1)
        val fallback = Color(0xFF3F6FB0)
        val grey = coverAtmosphere(CoverPalette(Color.Gray, null), background, false, fallback)
        val vivid = coverAtmosphere(CoverPalette(fallback, fallback), background, false, Color.Black)
        val (greyHue, _, _) = hsl(grey.accent.red.toDouble(), grey.accent.green.toDouble(), grey.accent.blue.toDouble())
        val (fallbackHue, _, _) = hsl(fallback.red.toDouble(), fallback.green.toDouble(), fallback.blue.toDouble())
        assertEquals(fallbackHue, greyHue, 6.0)
        assertTrue(contrast(grey.top, background) < contrast(vivid.top, background))
    }

    @Test fun boxBlurKeepsSizeAndAverageWhileSofteningEdges() {
        val width = 8
        val pixels = IntArray(64) { if (it % width < 4) argb(0, 0, 0) else argb(255, 255, 255) }
        val blurred = boxBlur(pixels, width, 8, radius = 2)
        assertEquals(64, blurred.size)
        val edge = blurred[3] shr 16 and 0xFF
        assertTrue(edge in 1..254) // The hard black/white boundary becomes a ramp.
        assertEquals(0xFF, blurred[0] ushr 24)
    }

    @Test fun hslRoundTrips() {
        listOf(Color(0xFF336699), Color(0xFFCC8844), Color(0xFF22AA55)).forEach { color ->
            val (h, s, l) = hsl(color.red.toDouble(), color.green.toDouble(), color.blue.toDouble())
            val back = hslColor(h, s, l)
            assertEquals(color.red, back.red, .01f); assertEquals(color.green, back.green, .01f); assertEquals(color.blue, back.blue, .01f)
        }
    }
}
