package com.mozhi.reader.feature.reader.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 划线墨色派生与波浪线几何的纯函数行为。 */
class AnnotationInkTest {

    private val accent = 0xFF806043.toInt()

    @Test
    fun solidColorDiffersBetweenPaperAndDarkForEveryTag() {
        listOf("amber", "bamboo", "indigo", "rose").forEach { tag ->
            val light = AnnotationInk.solidColor(tag, isDark = false, accentColor = accent)
            val dark = AnnotationInk.solidColor(tag, isDark = true, accentColor = accent)
            assertNotEquals("$tag 深浅纸必须派生不同色值", light, dark)
            assertNotEquals("$tag 不应回落强调色", accent, light)
        }
    }

    @Test
    fun unknownOrBlankTagFallsBackToAccent() {
        assertEquals(accent, AnnotationInk.solidColor("", isDark = false, accentColor = accent))
        assertEquals(accent, AnnotationInk.solidColor(null, isDark = true, accentColor = accent))
        assertEquals(accent, AnnotationInk.solidColor("neon", isDark = false, accentColor = accent))
    }

    @Test
    fun customHexIsUsedVerbatimInBothLightAndDark() {
        val expected = 0xFF336699.toInt()
        assertEquals(expected, AnnotationInk.solidColor("#336699", isDark = false, accentColor = accent))
        assertEquals(expected, AnnotationInk.solidColor("#336699", isDark = true, accentColor = accent))
        assertEquals(expected, AnnotationInk.solidColor("#336699".uppercase(), isDark = false, accentColor = accent))
    }

    @Test
    fun malformedHexFallsBackToAccent() {
        assertEquals(accent, AnnotationInk.solidColor("#3366", isDark = false, accentColor = accent))
        assertEquals(accent, AnnotationInk.solidColor("#GGGGGG", isDark = false, accentColor = accent))
        assertEquals(null, AnnotationInk.parseCustomHex("336699"))
    }

    @Test
    fun tagLookupIsCaseAndWhitespaceTolerant() {
        val expected = AnnotationInk.solidColor("amber", isDark = false, accentColor = accent)
        assertEquals(expected, AnnotationInk.solidColor(" Amber ", isDark = false, accentColor = accent))
    }

    @Test
    fun highlightFillIsTranslucentAndDarkPaperIsLighterHanded() {
        val light = AnnotationInk.highlightFillColor("amber", isDark = false, accentColor = accent)
        val dark = AnnotationInk.highlightFillColor("amber", isDark = true, accentColor = accent)
        val lightAlpha = light ushr 24
        val darkAlpha = dark ushr 24
        assertTrue("荧光必须半透明", lightAlpha in 1..127)
        assertTrue("深纸荧光要更收敛", darkAlpha < lightAlpha)
    }

    @Test
    fun lineColorKeepsHueButAppliesAlpha() {
        val solid = AnnotationInk.solidColor("indigo", isDark = false, accentColor = accent)
        val line = AnnotationInk.lineColor("indigo", isDark = false, accentColor = accent)
        assertEquals(solid and 0x00FFFFFF, line and 0x00FFFFFF)
        assertTrue((line ushr 24) < 0xFF)
    }

    @Test
    fun wavySegmentsCoverWidthAndDegradeGracefully() {
        assertEquals(0, AnnotationInk.wavySegments(0f, 24f))
        assertEquals(0, AnnotationInk.wavySegments(-10f, 24f))
        assertEquals(0, AnnotationInk.wavySegments(100f, 0f))
        // 短划线也至少一段，不然波浪直接消失
        assertEquals(1, AnnotationInk.wavySegments(5f, 24f))
        // 宽度 120、周期 24 → 半周期 12 → 10 段
        assertEquals(10, AnnotationInk.wavySegments(120f, 24f))
    }

    @Test
    fun withAlphaClampsRange() {
        assertEquals(0x00, AnnotationInk.withAlpha(0xFFFFFFFF.toInt(), -5) ushr 24)
        assertEquals(0xFF, AnnotationInk.withAlpha(0x00FFFFFF, 999) ushr 24)
    }
}
