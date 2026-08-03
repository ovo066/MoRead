package com.mozhi.reader.feature.reader.render

/**
 * 划线样式的墨色派生：AnnotationColors 色名 ×（浅纸/深纸）→ ARGB。
 * 纯函数，便于单测；渲染与样式选择面板共用同一套值，保证所见即所得。
 */
object AnnotationInk {

    /** 划线本色（marker、直线、波浪、色板圆点用它）；"#RRGGBB" 自定义原色直用，空/未知回落强调色。 */
    fun solidColor(colorTag: String?, isDark: Boolean, accentColor: Int): Int {
        val tag = colorTag?.trim()?.lowercase().orEmpty()
        parseCustomHex(tag)?.let { return it }
        val palette = if (isDark) DARK else LIGHT
        return palette[tag] ?: accentColor
    }

    /** "#RRGGBB" → 不透明 ARGB；不合法返回 null。自定义色不做明暗派生（用户的选择即所见）。 */
    internal fun parseCustomHex(tag: String): Int? {
        if (!tag.startsWith("#") || tag.length != 7) return null
        val value = tag.substring(1).toLongOrNull(16) ?: return null
        return (0xFF000000L or value).toInt()
    }

    /** 荧光填充：本色压 alpha，深纸更低避免糊字。 */
    fun highlightFillColor(colorTag: String?, isDark: Boolean, accentColor: Int): Int {
        val alpha = if (isDark) HIGHLIGHT_ALPHA_DARK else HIGHLIGHT_ALPHA_LIGHT
        return withAlpha(solidColor(colorTag, isDark, accentColor), alpha)
    }

    /** 直线/波浪线色：本色略压 alpha，保持在正文之下不抢戏。 */
    fun lineColor(colorTag: String?, isDark: Boolean, accentColor: Int): Int {
        val alpha = if (isDark) LINE_ALPHA_DARK else LINE_ALPHA_LIGHT
        return withAlpha(solidColor(colorTag, isDark, accentColor), alpha)
    }

    /** 波浪线一个完整波形的段数（每段一个 quad）；保证短划线也至少一段。 */
    fun wavySegments(width: Float, period: Float): Int {
        if (width <= 0f || period <= 0f) return 0
        return (width / (period / 2f)).toInt().coerceAtLeast(1)
    }

    internal fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    // 浅纸取中饱和、深纸降饱和提亮度，与 accentColorFor 的明暗惯例一致。
    private val LIGHT = mapOf(
        "amber" to 0xFFB07A1A.toInt(),
        "bamboo" to 0xFF3E7C52.toInt(),
        "indigo" to 0xFF3A6291.toInt(),
        "rose" to 0xFFA84A5E.toInt()
    )
    private val DARK = mapOf(
        "amber" to 0xFFE0B36A.toInt(),
        "bamboo" to 0xFF7FBF95.toInt(),
        "indigo" to 0xFF89AEDC.toInt(),
        "rose" to 0xFFDD8A9B.toInt()
    )

    private const val HIGHLIGHT_ALPHA_LIGHT = 62
    private const val HIGHLIGHT_ALPHA_DARK = 46
    private const val LINE_ALPHA_LIGHT = 215
    private const val LINE_ALPHA_DARK = 195
}
