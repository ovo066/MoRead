package com.mozhi.reader.feature.bookdetail

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Colours sampled from a cover. [vibrant] is null when the cover is essentially grey. */
internal data class CoverPalette(val dominant: Color, val vibrant: Color?)

/** The book page's atmosphere: a wash at the top fading into the page, and an accent for its actions. */
internal data class CoverAtmosphere(val top: Color, val middle: Color, val accent: Color)

private const val HUE_BINS = 24

/**
 * Extracts a dominant tone and the most characterful hue from ARGB pixels (a small downsampled cover).
 * Pixels are weighted by saturation so a small red seal on a pale cover still wins over the paper.
 */
internal fun extractCoverPalette(pixels: IntArray): CoverPalette? {
    var count = 0
    var red = 0.0; var green = 0.0; var blue = 0.0
    val binWeight = DoubleArray(HUE_BINS)
    val binRed = DoubleArray(HUE_BINS); val binGreen = DoubleArray(HUE_BINS); val binBlue = DoubleArray(HUE_BINS)
    for (pixel in pixels) {
        if ((pixel ushr 24) < 128) continue
        val r = (pixel shr 16 and 0xFF) / 255.0
        val g = (pixel shr 8 and 0xFF) / 255.0
        val b = (pixel and 0xFF) / 255.0
        count++; red += r; green += g; blue += b
        val (hue, saturation, lightness) = hsl(r, g, b)
        if (saturation < .25 || lightness < .14 || lightness > .82) continue
        // Favour colours that are both saturated and not near black/white; cream paper stays out.
        val weight = saturation * saturation * (1.0 - abs(lightness - .5) * 1.4)
        val bin = ((hue / 360.0) * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
        binWeight[bin] += weight; binRed[bin] += r * weight; binGreen[bin] += g * weight; binBlue[bin] += b * weight
    }
    if (count == 0) return null
    val dominant = Color((red / count).toFloat(), (green / count).toFloat(), (blue / count).toFloat())
    // A hue needs a little presence (about 1% of the cover at full strength) before it may colour the page.
    val best = binWeight.indices.maxByOrNull { binWeight[it] }?.takeIf { binWeight[it] >= count * .01 }
    val vibrant = best?.let { Color((binRed[it] / binWeight[it]).toFloat(), (binGreen[it] / binWeight[it]).toFloat(), (binBlue[it] / binWeight[it]).toFloat()) }
    return CoverPalette(dominant, vibrant)
}

/**
 * Turns a palette into page colours for the current theme. The accent keeps the cover's hue but its
 * saturation and lightness are tamed so it reads on [background]; [fallback] (the app accent) is used for
 * grey covers or books without one.
 */
internal fun coverAtmosphere(palette: CoverPalette?, background: Color, dark: Boolean, fallback: Color): CoverAtmosphere {
    val seed = palette?.vibrant ?: fallback
    val (hue, saturation, _) = hsl(seed.red.toDouble(), seed.green.toDouble(), seed.blue.toDouble())
    val accentSaturation = saturation.coerceIn(.28, .62)
    // Start from a comfortable tone and walk toward the readable end until it clears WCAG AA on the page.
    var lightness = if (dark) .72 else .40
    var accent = hslColor(hue, accentSaturation, lightness)
    repeat(12) {
        if (contrast(accent, background) >= 4.5f) return@repeat
        lightness = (lightness + if (dark) .03 else -.03).coerceIn(.05, .95)
        accent = hslColor(hue, accentSaturation, lightness)
    }
    val wash = hslColor(hue, saturation.coerceIn(.2, .55), if (dark) .26 else .78)
    val strength = if (palette?.vibrant != null) 1f else .6f
    return CoverAtmosphere(
        top = lerp(background, wash, (if (dark) .78f else .72f) * strength),
        middle = lerp(background, wash, (if (dark) .3f else .26f) * strength),
        accent = accent
    )
}

internal fun hsl(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
    val high = max(r, max(g, b)); val low = min(r, min(g, b))
    val lightness = (high + low) / 2
    val delta = high - low
    if (delta < 1e-6) return Triple(0.0, 0.0, lightness)
    val saturation = delta / (1 - abs(2 * lightness - 1))
    val hue = when (high) {
        r -> 60 * (((g - b) / delta).mod(6.0))
        g -> 60 * ((b - r) / delta + 2)
        else -> 60 * ((r - g) / delta + 4)
    }
    return Triple(hue, saturation.coerceIn(0.0, 1.0), lightness)
}

internal fun hslColor(hue: Double, saturation: Double, lightness: Double): Color {
    val chroma = (1 - abs(2 * lightness - 1)) * saturation
    val x = chroma * (1 - abs((hue / 60).mod(2.0) - 1))
    val m = lightness - chroma / 2
    val (r, g, b) = when ((hue.mod(360.0) / 60).toInt()) {
        0 -> Triple(chroma, x, 0.0); 1 -> Triple(x, chroma, 0.0); 2 -> Triple(0.0, chroma, x)
        3 -> Triple(0.0, x, chroma); 4 -> Triple(x, 0.0, chroma); else -> Triple(chroma, 0.0, x)
    }
    return Color((r + m).toFloat().coerceIn(0f, 1f), (g + m).toFloat().coerceIn(0f, 1f), (b + m).toFloat().coerceIn(0f, 1f))
}

private fun contrast(a: Color, b: Color): Float {
    val lighter = max(a.luminance(), b.luminance()); val darker = min(a.luminance(), b.luminance())
    return (lighter + .05f) / (darker + .05f)
}
