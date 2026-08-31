package com.mozhi.reader.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 语义色与形状令牌。中性灰阶做底，强调色由用户在「设置 › 外观」中选择，
 * 朱砂 seal 只用于「正在阅读」眉标、连读环等极少数强调处。
 */
object MoReadTokens {
    val SealLight = Color(0xFFB0442B)
    val SealDark = Color(0xFFD9755A)

    /** 全面胶囊化的圆角。 */
    val CapsuleShape = RoundedCornerShape(999.dp)

    /** 页面左右留白。全 App 只有这一个值，二级页与主页共用。 */
    val PageGutter = 20.dp

    /** 分组之间的纵向留白。层级靠它建立，不靠给每张卡加发光边。 */
    val SectionGap = 22.dp

    /** 设置行的最小高度：单行 56、带副标题时自然撑高。 */
    val RowMinHeight = 56.dp

    /** 行首圆角色底图标的边长。 */
    val IconTile = 34.dp

    /** 行首图标内的图形边长。 */
    val IconGlyph = 19.dp

    /** 组内分隔线缩进量 = PageGutter 之外再让过图标列，使图标成为一条连续视觉轴。 */
    val RowDividerInset = 62.dp
}

/**
 * 间距刻度。散落的 13/17/18/21dp 一律向这几档收敛 —— 「差不多的间距」比「明显不同的间距」
 * 更伤观感，因为眼睛看得出差异却找不到规律。
 */
object MoReadSpacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
}

/** 圆角刻度。控件越小圆角越小，避免小胶囊被圆角吃掉内容区。 */
object MoReadRadius {
    val field = 14.dp
    val row = 16.dp
    val card = 20.dp
    val sheet = 28.dp
    val pill = 999.dp

    val FieldShape = RoundedCornerShape(field)
    val RowShape = RoundedCornerShape(row)
    val CardShape = RoundedCornerShape(card)
}

/**
 * 强调色上的前景色。取近黑/近白里对比更高的那个 —— 固定亮度阈值会在中间调上翻车：
 * 「朱」的夜间色 0xFFD9755A 亮度 0.28 会被判成「深色」而配白字，实测对比只有 2.9，
 * 而近黑能到 6.0。直接比对比度就不会有这种边界问题。
 */
fun Color.onAccent(): Color {
    val onDark = Color(0xFFF7F7F7)
    val onLight = Color(0xFF111111)
    return if (contrastRatio(onDark, this) >= contrastRatio(onLight, this)) onDark else onLight
}

/**
 * 由 [MoReadTheme] 一处 provide 的语义色。
 *
 * 全项目的明暗判定都必须读 [isDark] 而不是 `isSystemInDarkTheme()`——
 * 后者无视用户在应用内选的「日间/夜间」，会和 MaterialTheme 撕裂。
 *
 * [navSelected] 由强调色派生（默认「墨」下仍是墨色），这样换强调色能立刻在
 * 导航舱这种最显眼的位置看到变化。
 *
 * [accentLight]/[accentDark] 是强调色的两个明暗变体：阅读页的纸色主题独立于应用
 * 明暗（应用夜间也能开纸白底），palette 必须按纸色自身的明暗取变体，
 * 否则近白强调色会压在近白纸上。
 */
data class MoReadColors(
    val accent: Color,
    val accentLight: Color,
    val accentDark: Color,
    val seal: Color,
    val navSelected: Color,
    val onNavSelected: Color,
    val isDark: Boolean
)

val LocalMoReadColors = staticCompositionLocalOf {
    MoReadColors(
        accent = AccentPreset.Default.light,
        accentLight = AccentPreset.Default.light,
        accentDark = AccentPreset.Default.dark,
        seal = MoReadTokens.SealLight,
        navSelected = AccentPreset.Default.light,
        onNavSelected = AccentPreset.Default.light.onAccent(),
        isDark = false
    )
}

/** 当前是否深色。等价于旧代码里的 `isSystemInDarkTheme()`，但尊重应用内的主题模式。 */
@Composable
@ReadOnlyComposable
fun isDarkTheme(): Boolean = LocalMoReadColors.current.isDark

@Composable
@ReadOnlyComposable
fun sealColor(): Color = LocalMoReadColors.current.seal

@Composable
@ReadOnlyComposable
fun navSelectedColor(): Color = LocalMoReadColors.current.navSelected

@Composable
@ReadOnlyComposable
fun onNavSelectedColor(): Color = LocalMoReadColors.current.onNavSelected

/** 用户选定的强调色。阅读页调色板也以此为 accent。 */
@Composable
@ReadOnlyComposable
fun accentColor(): Color = LocalMoReadColors.current.accent

/** 强调色按给定明暗取变体：阅读页纸色独立于应用明暗时用它，而不是 [accentColor]。 */
@Composable
@ReadOnlyComposable
fun accentColorFor(dark: Boolean): Color = with(LocalMoReadColors.current) {
    if (dark) accentDark else accentLight
}

/**
 * 分组卡的底色（「克制玻璃」的主体）。
 *
 * 页面内的长列表**不再**用 [com.mozhi.reader.ui.components.FrostedSurface]：半透明 + 高光边 +
 * 投影堆在一起，一屏出现五六次就糊成一片，层级反而消失。素面卡靠「不透明底 + 一条发丝线 +
 * 组间大留白」建立层级，真玻璃留给真正浮在内容之上的东西（导航舱、阅读 dock、悬浮卡、弹层）。
 */
@Composable
@ReadOnlyComposable
fun sectionCardColor(): Color = if (isDarkTheme()) {
    MaterialTheme.colorScheme.surfaceContainerLow
} else {
    MaterialTheme.colorScheme.surface
}

/** 发丝线：卡片描边与组内分隔线共用，淡到只在需要时才看得见。 */
@Composable
@ReadOnlyComposable
fun sectionHairline(): Color =
    MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDarkTheme()) 0.45f else 0.55f)

/** 表单输入区的填充底：比卡面再深/浅一档，不描边也划得出边界。 */
@Composable
@ReadOnlyComposable
fun fieldContainerColor(): Color = if (isDarkTheme()) {
    MaterialTheme.colorScheme.surfaceContainerHigh
} else {
    MaterialTheme.colorScheme.surfaceContainer
}
