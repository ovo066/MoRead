package com.mozhi.reader.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * 中性灰阶底色：不带任何色相，界面上唯一的彩色来源是用户选的强调色。
 * primary 家族由强调色动态派生（见 [withAccent]），此处的占位值会被覆盖。
 */
private val LightColors = lightColorScheme(
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFE8E8E8),
    onSurfaceVariant = Color(0xFF5E5E5E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF1F1F1),
    surfaceContainerHigh = Color(0xFFEAEAEA),
    surfaceContainerHighest = Color(0xFFE3E3E3),
    outline = Color(0xFF767676),
    outlineVariant = Color(0xFFC7C7C7),
    inverseSurface = Color(0xFF2E2E2E),
    inverseOnSurface = Color(0xFFF2F2F2),
    // 中性化的次级家族：空书架插画等处不再靠彩色出效果。
    secondary = Color(0xFF5E5E5E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E4E4),
    onSecondaryContainer = Color(0xFF272727),
    tertiary = Color(0xFF6B6B6B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCDCDC),
    onTertiaryContainer = Color(0xFF232323)
)

private val DarkColors = darkColorScheme(
    background = Color(0xFF0E0E0E),
    onBackground = Color(0xFFE4E4E4),
    surface = Color(0xFF151515),
    onSurface = Color(0xFFE4E4E4),
    surfaceVariant = Color(0xFF3C3C3C),
    onSurfaceVariant = Color(0xFFC0C0C0),
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF181818),
    surfaceContainer = Color(0xFF1D1D1D),
    surfaceContainerHigh = Color(0xFF272727),
    surfaceContainerHighest = Color(0xFF323232),
    outline = Color(0xFF8C8C8C),
    outlineVariant = Color(0xFF414141),
    inverseSurface = Color(0xFFE4E4E4),
    inverseOnSurface = Color(0xFF2B2B2B),
    secondary = Color(0xFFB4B4B4),
    onSecondary = Color(0xFF2A2A2A),
    secondaryContainer = Color(0xFF383838),
    onSecondaryContainer = Color(0xFFE0E0E0),
    tertiary = Color(0xFFA6A6A6),
    onTertiary = Color(0xFF262626),
    tertiaryContainer = Color(0xFF303030),
    onTertiaryContainer = Color(0xFFDADADA)
)

private val MoReadShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

@Composable
fun MoReadTheme(
    appearance: AppearanceSettings = AppearanceSettings(),
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (appearance.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val accent = resolveAccentColor(
        accent = appearance.accent,
        customAccentArgb = appearance.customAccentArgb,
        dark = darkTheme
    )
    val colorScheme = remember(darkTheme, accent) {
        (if (darkTheme) DarkColors else LightColors).withAccent(accent, darkTheme)
    }
    val moReadColors = remember(darkTheme, accent, appearance.accent, appearance.customAccentArgb) {
        MoReadColors(
            accent = accent,
            // 阅读页纸色明暗独立于应用明暗，palette 按纸色自选变体。
            accentLight = resolveAccentColor(appearance.accent, appearance.customAccentArgb, dark = false),
            accentDark = resolveAccentColor(appearance.accent, appearance.customAccentArgb, dark = true),
            seal = if (darkTheme) MoReadTokens.SealDark else MoReadTokens.SealLight,
            // 选中胶囊直接用强调色填充：换色能立刻在最显眼处看到效果。
            navSelected = accent,
            onNavSelected = accent.onAccent(),
            isDark = darkTheme
        )
    }

    // 系统栏图标要跟「应用内」选的明暗走，而不是 values-night 那套（后者只认系统设置，
    // 用户在应用里手选日间/夜间时就会错）。
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as? Activity)?.window
        SideEffect {
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    CompositionLocalProvider(LocalMoReadColors provides moReadColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MoReadTypography,
            shapes = MoReadShapes,
            content = content
        )
    }
}

/**
 * 把强调色注入 primary 家族。container 由强调色按低透明度混入底色得到，
 * 这样任意强调色都能得到协调的淡底；onPrimary 按亮度取黑/白以保证对比度。
 */
private fun androidx.compose.material3.ColorScheme.withAccent(
    accent: Color,
    dark: Boolean
): androidx.compose.material3.ColorScheme {
    val container = accent
        .copy(alpha = if (dark) 0.22f else 0.16f)
        .compositeOver(if (dark) surfaceContainerHigh else surfaceContainer)
    return copy(
        primary = accent,
        onPrimary = accent.onAccent(),
        primaryContainer = container,
        // 直接用 accent 当 onPrimaryContainer 在夜间会翻车：「墨」的夜间强调色是近白
        // (0xFFE8E8E8)，而 container 本身就是这个近白混出来的浅灰 —— 浅上叠浅等于看不见。
        // 改成按 container 的明暗取前景，保证任何强调色下都有足够对比。
        onPrimaryContainer = readableOn(background = container, preferred = accent),
        inversePrimary = accent
    )
}
