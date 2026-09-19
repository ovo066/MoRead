package com.mozhi.reader.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.*
import org.junit.Test

class ColorSchemeTest {
    private val presets = ColorSchemePreset.entries.filter { it != ColorSchemePreset.DYNAMIC }

    @Test fun presetTextAndSemanticSurfacesRemainReadableInBothModes() {
        presets.forEach { preset ->
            listOf(false, true).forEach { dark ->
                val side = MoReadSchemes.side(preset, dark)
                val c = side.colors
                val label = "$preset dark=$dark"
                listOf(
                    c.onBackground to c.background, c.onSurface to c.surface,
                    c.primary to side.canvas, c.onPrimary to c.primary,
                    c.onPrimaryContainer to c.primaryContainer,
                    c.onSecondary to c.secondary, c.onTertiary to c.tertiary,
                    c.onSurfaceVariant to c.surfaceContainerHighest
                ).forEach { (foreground, background) -> readable(label, foreground, background) }
                SemanticHarmony.entries.forEach { harmony ->
                    val semantic = MoReadSchemes.semantic(side, harmony, c.primary, dark)
                    semantic.all.forEach { readable("$label $harmony", it.onContainer, it.container) }
                    if (harmony == SemanticHarmony.MONO) {
                        assertEquals("Single hue still needs four distinguishable surfaces", 4,
                            semantic.all.map { it.container }.distinct().size)
                        assertEquals(listOf(c.primary), semantic.all.map { it.accent }.distinct())
                    }
                    MoReadSchemes.avatarGradients(preset, semantic, harmony).forEach { (start, end) ->
                        // 旧中性头像保留原来的大字渐变；新方案的两个端点都须能承载正文。
                        if (preset != ColorSchemePreset.NEUTRAL || harmony == SemanticHarmony.MONO) {
                            readable("$label avatar start", end.onAccent(), start)
                            readable("$label avatar end", end.onAccent(), end)
                        }
                    }
                }
            }
        }
    }

    @Test fun followingSchemeAndOverridingAccentUseDifferentSources() {
        presets.forEach { preset ->
            listOf(false, true).forEach { dark ->
                val primary = MoReadSchemes.side(preset, dark).colors.primary
                assertEquals(primary, resolveAccentColor(AccentPreset.FOLLOW, null, dark, primary))
                assertEquals(if (dark) AccentPreset.AMBER.dark else AccentPreset.AMBER.light,
                    resolveAccentColor(AccentPreset.AMBER, null, dark, primary))
                assertNotEquals(primary, resolveAccentColor(AccentPreset.FOLLOW, 0xFF234A67.toInt(), dark, primary))
            }
        }
    }

    @Test fun customColorsAreAdjustedOnlyAsFarAsNeededAndStayReadable() {
        val adjustedWhite = adaptCustomAccent(Color.White, false)
        assertTrue("White should be darkened to a visible grey, not collapsed to black", adjustedWhite.red > 0.3f)
        assertTrue(adjustedWhite.red < 0.5f)
        listOf(Color.Black, Color.White, Color.Red, Color.Green, Color.Blue, Color.Yellow, Color(0xFF777777)).forEach { custom ->
            presets.forEach { preset ->
                listOf(false, true).forEach { dark ->
                    val side = MoReadSchemes.side(preset, dark)
                    val accent = adaptCustomAccent(custom, dark)
                    val c = side.colors.withAccent(accent, dark)
                    readable("$preset custom=$custom dark=$dark", accent, side.canvas)
                    readable("custom button", c.onPrimary, c.primary)
                    readable("custom tonal button", c.onPrimaryContainer, c.primaryContainer)
                }
            }
        }
    }

    @Test fun foregroundSelectionHandlesIntermediateLuminance() {
        for (channel in 0..255) {
            val background = Color(channel, channel, channel)
            readable("grey=$channel", background.onAccent(), background)
            readable("readable grey=$channel", readableOn(background, Color(0xFF888888)), background)
        }
    }

    @Test fun monochromeTagsFollowAccentAndMulticolorTagsKeepTheirPalette() {
        val side = MoReadSchemes.side(ColorSchemePreset.SAGE, false)
        assertEquals(side.tagPalette, MoReadSchemes.tagPalette(side, SemanticHarmony.MULTI, Color.Red, false))
        val redTags = MoReadSchemes.tagPalette(side, SemanticHarmony.MONO, Color.Red, false)
        assertEquals(4, redTags.distinct().size)
        assertTrue(redTags.all { it.green == 0f && it.blue == 0f })
    }

    @Test fun neutralFollowUsesTheExistingAccentContainer() {
        listOf(false, true).forEach { dark ->
            val side = MoReadSchemes.side(ColorSchemePreset.NEUTRAL, dark)
            val accent = if (dark) AccentPreset.INK.dark else AccentPreset.INK.light
            val actual = side.colors.withAccent(accent, dark)
            val expected = accent.copy(alpha = if (dark) 0.22f else 0.16f)
                .compositeOver(if (dark) side.colors.surfaceContainerHigh else side.colors.surfaceContainer)
            assertEquals(expected, actual.primaryContainer)
            assertEquals(accent, actual.primary)
        }
    }

    @Test fun dynamicColorsFallBackOnlyOnUnsupportedDevices() {
        assertEquals(ColorSchemePreset.NEUTRAL, ColorSchemePreset.DYNAMIC.availableOn(30))
        assertEquals(ColorSchemePreset.DYNAMIC, ColorSchemePreset.DYNAMIC.availableOn(31))
        presets.forEach { assertEquals(it, it.availableOn(26)) }
    }

    private fun readable(label: String, foreground: Color, background: Color) {
        val ratio = contrastRatio(foreground, background)
        assertTrue("$label: contrast=$ratio, foreground=$foreground, background=$background", ratio >= MIN_CONTENT_CONTRAST)
    }
}
