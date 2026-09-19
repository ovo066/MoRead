package com.mozhi.reader.ui.theme

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val StandardShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

/** 舒展形状：MD3 Expressive 式的大圆角，凡读 `MaterialTheme.shapes` 的地方自动跟随。 */
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(14.dp),
    small = RoundedCornerShape(18.dp),
    medium = RoundedCornerShape(28.dp),
    large = RoundedCornerShape(36.dp),
    extraLarge = RoundedCornerShape(44.dp)
)

/** Material You 仅 Android 12+ 可用；低版本读到 DYNAMIC 也按中性灰处理。 */
fun ColorSchemePreset.isAvailable(): Boolean =
    availableOn(Build.VERSION.SDK_INT) == this

/** 主题和预览共用的取色入口，预览不会受当前自定义强调色影响。 */
@Composable
fun rememberSchemeSide(preset: ColorSchemePreset, dark: Boolean): MoReadSchemeSide {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val effective = preset.availableOn(Build.VERSION.SDK_INT)
    return remember(effective, dark, context, configuration) { schemeSide(effective, dark, context) }
}

@Composable
fun MoReadTheme(
    appearance: AppearanceSettings = AppearanceSettings(),
    content: @Composable () -> Unit
) {
    val appFont = rememberAppFontFamily(appearance.appFont?.filePath)
    val typography = remember(appFont) { MoReadTypography.withAppFont(appFont) }
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (appearance.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val preset = if (appearance.colorScheme.isAvailable()) appearance.colorScheme else ColorSchemePreset.NEUTRAL
    // 两侧都要算：阅读页纸色明暗独立于应用明暗，强调色要按纸色自身的明暗取变体。
    val lightSide = rememberSchemeSide(preset, dark = false)
    val darkSide = rememberSchemeSide(preset, dark = true)
    val side = if (darkTheme) darkSide else lightSide
    val accentLight = resolveAccentColor(
        appearance.accent, appearance.customAccentArgb, dark = false, schemePrimary = lightSide.colors.primary
    )
    val accentDark = resolveAccentColor(
        appearance.accent, appearance.customAccentArgb, dark = true, schemePrimary = darkSide.colors.primary
    )
    val accent = if (darkTheme) accentDark else accentLight
    // 「随方案」时保留方案自己调好的 primary 家族（粉彩 container 等）；
    // 用户另选强调色才用混底派生 container。
    val followsScheme = appearance.accent == AccentPreset.FOLLOW && appearance.customAccentArgb == null
    val colorScheme = remember(side, accent, followsScheme, darkTheme, preset) {
        if (followsScheme && preset != ColorSchemePreset.NEUTRAL) side.colors
        else side.colors.withAccent(accent, darkTheme)
    }
    val moReadColors = remember(side, colorScheme, accentLight, accentDark, appearance) {
        val effectiveSide = side.copy(colors = colorScheme)
        val semantic = MoReadSchemes.semantic(effectiveSide, appearance.semanticHarmony, accent, darkTheme)
        MoReadColors(
            accent = accent,
            accentLight = accentLight,
            accentDark = accentDark,
            seal = if (darkTheme) MoReadTokens.SealDark else MoReadTokens.SealLight,
            // 选中胶囊直接用强调色填充：换色能立刻在最显眼处看到效果。
            navSelected = accent,
            onNavSelected = accent.onAccent(),
            isDark = darkTheme,
            canvas = side.canvas,
            semantic = semantic,
            tagPalette = MoReadSchemes.tagPalette(side, appearance.semanticHarmony, accent, darkTheme),
            avatarGradients = MoReadSchemes.avatarGradients(preset, semantic, appearance.semanticHarmony),
            colorScheme = preset,
            semanticHarmony = appearance.semanticHarmony,
            surfaceStyle = appearance.surfaceStyle,
            navStyle = appearance.navStyle,
            shapeStyle = appearance.shapeStyle
        )
    }
    val shapes = when (appearance.shapeStyle) {
        ShapeStyle.STANDARD -> StandardShapes
        ShapeStyle.EXPRESSIVE -> ExpressiveShapes
    }
    val metrics = MoReadMetrics.of(appearance.shapeStyle)

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

    CompositionLocalProvider(
        LocalMoReadColors provides moReadColors,
        LocalMoReadMetrics provides metrics
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = shapes,
            content = content
        )
    }
}

/** 阅读器继续使用纸色与原有浮层尺寸，应用质感和舒展圆角只作用于应用界面。 */
@Composable
fun ReaderAppearanceScope(content: @Composable () -> Unit) {
    val colors = LocalMoReadColors.current
    val readerColors = remember(colors) {
        colors.copy(surfaceStyle = SurfaceStyle.GLASS, shapeStyle = ShapeStyle.STANDARD)
    }
    CompositionLocalProvider(
        LocalMoReadColors provides readerColors,
        LocalMoReadMetrics provides MoReadMetrics.Standard
    ) {
        MaterialTheme(shapes = StandardShapes, content = content)
    }
}

private fun schemeSide(preset: ColorSchemePreset, dark: Boolean, context: Context): MoReadSchemeSide =
    if (preset == ColorSchemePreset.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MoReadSchemes.dynamicSide(
            colors = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
            dark = dark
        )
    } else {
        MoReadSchemes.side(preset, dark)
    }

/**
 * 把强调色注入 primary 家族。container 由强调色按低透明度混入底色得到，
 * 这样任意强调色都能得到协调的淡底；onPrimary 按亮度取黑/白以保证对比度。
 */
internal fun androidx.compose.material3.ColorScheme.withAccent(
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
