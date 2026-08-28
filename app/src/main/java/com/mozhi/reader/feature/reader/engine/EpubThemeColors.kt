package com.mozhi.reader.feature.reader.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** EPUB 小面积色块的主题映射与可读性修正，不依赖 Android，排版测试也可直接验证。 */
internal object EpubThemeColors {
    fun background(color: Int?, darkTheme: Boolean): Int? {
        color ?: return null
        if (!darkTheme) return color
        val alpha = color ushr 24 and 0xFF
        val hsl = color.toHsl()
        if (hsl[2] > 0.5f) {
            // 原色越接近白，压暗后越靠近 30%；保留色相和饱和度，卡片仍能辨认来源色。
            hsl[2] = (0.15f + (hsl[2] - 0.5f) * 0.30f).coerceIn(0.15f, 0.30f)
        }
        return hsl.toColor(alpha)
    }

    fun composite(foreground: Int, background: Int): Int {
        val alpha = (foreground ushr 24 and 0xFF) / 255f
        if (alpha >= 0.999f) return foreground
        fun channel(shift: Int): Int {
            val front = foreground ushr shift and 0xFF
            val back = background ushr shift and 0xFF
            return (front * alpha + back * (1f - alpha)).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun foreground(color: Int?, actualBackground: Int, fallback: Int): Int {
        val source = color ?: fallback
        if (contrast(source, actualBackground) >= MIN_CONTRAST) return source
        val alpha = source ushr 24 and 0xFF
        val hsl = source.toHsl()
        val backgroundLuminance = luminance(actualBackground)
        val towardLight = backgroundLuminance < 0.5
        var best = source
        var bestContrast = contrast(source, actualBackground)
        repeat(100) { step ->
            val candidateL = if (towardLight) {
                hsl[2] + (1f - hsl[2]) * ((step + 1) / 100f)
            } else {
                hsl[2] * (1f - (step + 1) / 100f)
            }
            val candidate = floatArrayOf(hsl[0], hsl[1], candidateL).toColor(alpha)
            val ratio = contrast(candidate, actualBackground)
            if (ratio > bestContrast) {
                best = candidate
                bestContrast = ratio
            }
            if (ratio >= MIN_CONTRAST) return candidate
        }
        return best
    }

    fun contrast(first: Int, second: Int): Double {
        val lighter = max(luminance(first), luminance(second))
        val darker = min(luminance(first), luminance(second))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.03928) normalized / 12.92
            else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
        val red = channel(color ushr 16 and 0xFF)
        val green = channel(color ushr 8 and 0xFF)
        val blue = channel(color and 0xFF)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    private fun Int.toHsl(): FloatArray {
        val red = (this ushr 16 and 0xFF) / 255f
        val green = (this ushr 8 and 0xFF) / 255f
        val blue = (this and 0xFF) / 255f
        val maxChannel = max(red, max(green, blue))
        val minChannel = min(red, min(green, blue))
        val lightness = (maxChannel + minChannel) / 2f
        if (abs(maxChannel - minChannel) < 0.00001f) return floatArrayOf(0f, 0f, lightness)
        val delta = maxChannel - minChannel
        val saturation = delta / (1f - abs(2f * lightness - 1f))
        val hue = when (maxChannel) {
            red -> 60f * (((green - blue) / delta) % 6f)
            green -> 60f * ((blue - red) / delta + 2f)
            else -> 60f * ((red - green) / delta + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return floatArrayOf(hue, saturation.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f))
    }

    private fun FloatArray.toColor(alpha: Int): Int {
        val hue = this[0]
        val saturation = this[1].coerceIn(0f, 1f)
        val lightness = this[2].coerceIn(0f, 1f)
        val chroma = (1f - abs(2f * lightness - 1f)) * saturation
        val x = chroma * (1f - abs((hue / 60f) % 2f - 1f))
        val m = lightness - chroma / 2f
        val (r1, g1, b1) = when {
            hue < 60f -> Triple(chroma, x, 0f)
            hue < 120f -> Triple(x, chroma, 0f)
            hue < 180f -> Triple(0f, chroma, x)
            hue < 240f -> Triple(0f, x, chroma)
            hue < 300f -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
        val red = ((r1 + m) * 255f).toInt().coerceIn(0, 255)
        val green = ((g1 + m) * 255f).toInt().coerceIn(0, 255)
        val blue = ((b1 + m) * 255f).toInt().coerceIn(0, 255)
        return (alpha.coerceIn(0, 255) shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private const val MIN_CONTRAST = 4.5
}
