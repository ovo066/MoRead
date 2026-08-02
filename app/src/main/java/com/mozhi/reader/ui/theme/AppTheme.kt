package com.mozhi.reader.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/** 应用级明暗模式。与阅读页的 ReaderTheme（7 套纸色）是两件事，互不干涉。 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

/**
 * 预设强调色。中性灰阶做底，强调色是界面上唯一的彩色来源，
 * 默认 [INK] 让开箱即是纯黑白。日夜各给一个值：深色底上需要更亮的强调色才够对比。
 */
enum class AccentPreset(
    val label: String,
    val light: Color,
    val dark: Color
) {
    INK("墨", Color(0xFF1F1F1F), Color(0xFFE8E8E8)),
    SEAL("朱", Color(0xFFB0442B), Color(0xFFD9755A)),
    AZURE("蓝", Color(0xFF1F5FA9), Color(0xFF87B4E8)),
    VIOLET("紫", Color(0xFF6246A8), Color(0xFFB9A5E8)),
    AMBER("橙", Color(0xFF9A5B10), Color(0xFFE0AB63)),
    CYAN("青", Color(0xFF126B70), Color(0xFF79C8CC)),
    GRAPHITE("灰", Color(0xFF5A5F63), Color(0xFFB0B6BA));

    companion object {
        val Default = INK
    }
}

/** 外观偏好三件套，由 DataStore 持久化。[customAccentArgb] 非空时优先于 [accent]。 */
data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: AccentPreset = AccentPreset.Default,
    val customAccentArgb: Int? = null
)

/** 纯函数，便于单测：自定义色优先，否则取预设的日/夜值。 */
fun resolveAccentColor(
    accent: AccentPreset,
    customAccentArgb: Int?,
    dark: Boolean
): Color = customAccentArgb
    ?.let { adaptCustomAccent(Color(it), dark) }
    ?: if (dark) accent.dark else accent.light

/**
 * 用户自选的颜色不保证在当前底色上可读 —— 深色底上太暗、浅色底上太亮的都往回拉，
 * 保证强调色始终能从背景里跳出来。
 */
internal fun adaptCustomAccent(color: Color, dark: Boolean): Color {
    val luminance = color.luminance()
    return when {
        dark && luminance < MIN_DARK_LUMINANCE -> color.lightenTo(MIN_DARK_LUMINANCE)
        !dark && luminance > MAX_LIGHT_LUMINANCE -> color.darkenTo(MAX_LIGHT_LUMINANCE)
        else -> color
    }
}

/** 朝白色插值直到亮度达标；二分足够精确，也避免除零。 */
private fun Color.lightenTo(target: Float): Color = adjustTowards(Color.White, target)

private fun Color.darkenTo(target: Float): Color = adjustTowards(Color.Black, target)

private fun Color.adjustTowards(bound: Color, target: Float): Color {
    var low = 0f
    var high = 1f
    var result = this
    repeat(ADJUST_ITERATIONS) {
        val mid = (low + high) / 2f
        result = lerpColor(this, bound, mid)
        if (result.luminance() < target) low = mid else high = mid
    }
    return result
}

private fun lerpColor(from: Color, to: Color, fraction: Float): Color = Color(
    red = from.red + (to.red - from.red) * fraction,
    green = from.green + (to.green - from.green) * fraction,
    blue = from.blue + (to.blue - from.blue) * fraction,
    alpha = from.alpha
)

private const val MIN_DARK_LUMINANCE = 0.35f
private const val MAX_LIGHT_LUMINANCE = 0.45f
private const val ADJUST_ITERATIONS = 12

/**
 * 在 [background] 上给出可读的 [preferred]：对比够就直接用原色，不够就朝可读方向推。
 * 这样彩色强调尽量保住色相，只在真会糊掉时才退到中性前景。
 */
internal fun readableOn(background: Color, preferred: Color): Color {
    if (contrastRatio(preferred, background) >= MIN_CONTENT_CONTRAST) return preferred
    val target = if (background.luminance() > MAX_LIGHT_LUMINANCE) {
        Color(0xFF111111)
    } else {
        Color(0xFFF2F2F2)
    }
    // 先把原色朝目标混掉一半，保住一点色相；仍不达标才用纯中性色。
    val blended = preferred.copy(alpha = 0.55f).compositeOver(target)
    return if (contrastRatio(blended, background) >= MIN_CONTENT_CONTRAST) blended else target
}

/** WCAG 相对对比度。[Color.luminance] 已是相对亮度，直接套公式。 */
internal fun contrastRatio(a: Color, b: Color): Float {
    val lighter = maxOf(a.luminance(), b.luminance())
    val darker = minOf(a.luminance(), b.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

/** WCAG AA 正文对比度下限。 */
internal const val MIN_CONTENT_CONTRAST = 4.5f
