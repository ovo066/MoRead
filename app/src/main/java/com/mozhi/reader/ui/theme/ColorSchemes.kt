package com.mozhi.reader.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/** 一个语义色位的三件套：可作文字/图标的实色、淡底、淡底上的前景。 */
data class MoReadTone(
    val accent: Color,
    val container: Color,
    val onContainer: Color
)

/** 语义色位。用它给分组/标签/角色指派语义，而不是直接写颜色。 */
enum class SemanticSlot { READING, KNOWLEDGE, AI, CAUTION }

/**
 * 四个语义色位。中性方案下四个都是灰，与历史外观一致；莫兰迪方案下分别是
 * 主色 / 灰绿 / 灰紫 / 灰粉这类低饱和色相，只出现在语义位（图标底、标签、角色），不做装饰。
 */
data class MoReadSemanticColors(
    val reading: MoReadTone,
    val knowledge: MoReadTone,
    val ai: MoReadTone,
    val caution: MoReadTone
) {
    val all: List<MoReadTone> get() = listOf(reading, knowledge, ai, caution)

    operator fun get(slot: SemanticSlot): MoReadTone = when (slot) {
        SemanticSlot.READING -> reading
        SemanticSlot.KNOWLEDGE -> knowledge
        SemanticSlot.AI -> ai
        SemanticSlot.CAUTION -> caution
    }
}

/** 方案在一个明暗侧的全部静态规格。 */
data class MoReadSchemeSide(
    val colors: ColorScheme,
    /** 扁平质感下的页面底色：要比 surface 卡面深一档，卡片不描边也分得出来。 */
    val canvas: Color,
    /** 第四语义色（M3 没有第四家族，单独给）。 */
    val rose: MoReadTone,
    /** 书架标签四色：琥珀 / 青竹 / 黛蓝 / 绯红。 */
    val tagPalette: List<Color>
)

data class MoReadSchemeSpec(val light: MoReadSchemeSide, val dark: MoReadSchemeSide)

object MoReadSchemes {

    /** 静态方案规格；[ColorSchemePreset.DYNAMIC] 没有静态规格，由 [dynamicSide] 运行时拼装。 */
    fun spec(preset: ColorSchemePreset): MoReadSchemeSpec = when (preset) {
        ColorSchemePreset.NEUTRAL, ColorSchemePreset.DYNAMIC -> Neutral
        ColorSchemePreset.HAZE_BLUE -> HazeBlue
        ColorSchemePreset.SAGE -> Sage
        ColorSchemePreset.ROSE_DUST -> RoseDust
    }

    fun side(preset: ColorSchemePreset, dark: Boolean): MoReadSchemeSide =
        spec(preset).let { if (dark) it.dark else it.light }

    /** Material You 取到的整套 ColorScheme 补上画布、第四语义色与标签色。 */
    fun dynamicSide(colors: ColorScheme, dark: Boolean): MoReadSchemeSide {
        val roseContainer = colors.error
            .copy(alpha = if (dark) 0.28f else 0.16f)
            .compositeOver(colors.surfaceContainer)
        val roseAccent = colors.error
            .copy(alpha = 0.72f)
            .compositeOver(colors.onSurfaceVariant)
        return MoReadSchemeSide(
            colors = colors,
            canvas = if (dark) colors.surfaceContainerLowest else colors.surfaceContainer,
            rose = MoReadTone(
                accent = readableOn(colors.surface, roseAccent),
                container = roseContainer,
                onContainer = readableOn(roseContainer, colors.error)
            ),
            tagPalette = listOf(
                if (dark) Color(0xFFD8B37F) else Color(0xFF9A7A3C),
                colors.secondary,
                colors.primary,
                colors.tertiary
            )
        )
    }

    /**
     * 四个语义色位。多色相直接取方案的 primary/secondary/tertiary/rose 家族；
     * 统一色相全部从 [accent] 派生，四档淡底透明度拉开层次，但色相只有一个。
     */
    fun semantic(
        side: MoReadSchemeSide,
        harmony: SemanticHarmony,
        accent: Color,
        dark: Boolean
    ): MoReadSemanticColors {
        val c = side.colors
        return when (harmony) {
            SemanticHarmony.MULTI -> MoReadSemanticColors(
                reading = MoReadTone(c.primary, c.primaryContainer, c.onPrimaryContainer),
                knowledge = MoReadTone(c.secondary, c.secondaryContainer, c.onSecondaryContainer),
                ai = MoReadTone(c.tertiary, c.tertiaryContainer, c.onTertiaryContainer),
                caution = side.rose
            )
            SemanticHarmony.MONO -> {
                val base = if (dark) c.surfaceContainerHigh else c.surfaceContainer
                fun tone(alpha: Float): MoReadTone {
                    val container = accent.copy(alpha = alpha).compositeOver(base)
                    return MoReadTone(
                        accent = accent,
                        container = container,
                        onContainer = readableOn(container, accent)
                    )
                }
                MoReadSemanticColors(
                    reading = tone(if (dark) 0.30f else 0.22f),
                    knowledge = tone(if (dark) 0.20f else 0.14f),
                    ai = tone(if (dark) 0.25f else 0.18f),
                    caution = tone(if (dark) 0.14f else 0.09f)
                )
            }
        }
    }

    /** 单色策略也覆盖内置标签，自定义标签颜色仍由用户决定。 */
    fun tagPalette(
        side: MoReadSchemeSide,
        harmony: SemanticHarmony,
        accent: Color,
        dark: Boolean
    ): List<Color> = if (harmony == SemanticHarmony.MULTI) {
        side.tagPalette
    } else {
        listOf(1f, 0.82f, 0.66f, 0.5f).map { alpha ->
            accent.copy(alpha = alpha).compositeOver(if (dark) Color.White else Color.Black)
        }
    }

    /** 角色头像使用协调的淡色底，避免深渐变末端吃掉前景文字。 */
    fun avatarGradients(
        preset: ColorSchemePreset,
        semantic: MoReadSemanticColors,
        harmony: SemanticHarmony = SemanticHarmony.MULTI
    ): List<Pair<Color, Color>> = if (preset == ColorSchemePreset.NEUTRAL && harmony == SemanticHarmony.MULTI) {
        NeutralAvatarGradients
    } else {
        semantic.all.map { tone ->
            tone.container to tone.accent.copy(alpha = 0.1f).compositeOver(tone.container)
        }
    }

    private val NeutralAvatarGradients = listOf(
        Color(0xFF5E5E5E) to Color(0xFF262626),
        Color(0xFF767676) to Color(0xFF3A3A3A),
        Color(0xFF4A4A4A) to Color(0xFF1E1E1E),
        Color(0xFF8A8A8A) to Color(0xFF4A4A4A)
    )

    private val NeutralTags = listOf(
        Color(0xFFD59B2D),
        Color(0xFF4E8B62),
        Color(0xFF4A6785),
        Color(0xFFA84D55)
    )

    // ---------------------------------------------------------------- 中性灰（历史外观）

    /**
     * 中性灰阶底色：不带任何色相，界面上唯一的彩色来源是用户选的强调色。
     * primary 家族在 Theme 里由强调色动态派生，此处的值只在「随方案」时作为占位。
     */
    private val Neutral = MoReadSchemeSpec(
        light = MoReadSchemeSide(
            colors = lightColorScheme(
                primary = Color(0xFF1F1F1F),
                onPrimary = Color(0xFFF7F7F7),
                primaryContainer = Color(0xFFDCDCDC),
                onPrimaryContainer = Color(0xFF1F1F1F),
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
            ),
            canvas = Color(0xFFF0F0F0),
            rose = MoReadTone(Color(0xFF6B6B6B), Color(0xFFDCDCDC), Color(0xFF232323)),
            tagPalette = NeutralTags
        ),
        dark = MoReadSchemeSide(
            colors = darkColorScheme(
                primary = Color(0xFFE8E8E8),
                onPrimary = Color(0xFF111111),
                primaryContainer = Color(0xFF3A3A3A),
                onPrimaryContainer = Color(0xFFE8E8E8),
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
            ),
            canvas = Color(0xFF0E0E0E),
            rose = MoReadTone(Color(0xFFA6A6A6), Color(0xFF303030), Color(0xFFDADADA)),
            tagPalette = NeutralTags
        )
    )

    // ---------------------------------------------------------------- 莫兰迪·雾蓝

    private val HazeBlue = MoReadSchemeSpec(
        light = morandiLight(
            background = Color(0xFFEDF1F4),
            surface = Color(0xFFF8F9FB),
            onSurface = Color(0xFF2B2F33),
            onSurfaceVariant = Color(0xFF58626B),
            surfaceVariant = Color(0xFFDDE4EA),
            containers = listOf(0xFFFFFFFF, 0xFFF3F6F9, 0xFFE9EEF3, 0xFFE0E7ED, 0xFFD7DFE6),
            outline = Color(0xFF7A858F),
            outlineVariant = Color(0xFFC4CDD5),
            primary = Color(0xFF3F6B96), primaryContainer = Color(0xFFD4E3F0), onPrimaryContainer = Color(0xFF1F3A52),
            secondary = Color(0xFF4F7A66), secondaryContainer = Color(0xFFDCEAE1), onSecondaryContainer = Color(0xFF1F3529),
            tertiary = Color(0xFF6C5F8A), tertiaryContainer = Color(0xFFE5E1F0), onTertiaryContainer = Color(0xFF2F2742),
            rose = MoReadTone(Color(0xFF925E69), Color(0xFFF1E1E3), Color(0xFF4A2C33)),
            tags = listOf(0xFF9A7A3C, 0xFF4F7A66, 0xFF3F6B96, 0xFF925E69)
        ),
        dark = morandiDark(
            background = Color(0xFF14181C),
            surface = Color(0xFF1A1F24),
            onSurface = Color(0xFFE1E6EB),
            onSurfaceVariant = Color(0xFFB5BEC7),
            surfaceVariant = Color(0xFF3A434B),
            containers = listOf(0xFF0F1316, 0xFF1F252B, 0xFF252C33, 0xFF2E363E, 0xFF384149),
            outline = Color(0xFF8A949E),
            outlineVariant = Color(0xFF454E57),
            primary = Color(0xFF9DBEDD), primaryContainer = Color(0xFF2F4A63), onPrimaryContainer = Color(0xFFD4E3F0),
            secondary = Color(0xFFA3C6B3), secondaryContainer = Color(0xFF2E463A), onSecondaryContainer = Color(0xFFDCEAE1),
            tertiary = Color(0xFFBBAFD6), tertiaryContainer = Color(0xFF40365A), onTertiaryContainer = Color(0xFFE5E1F0),
            rose = MoReadTone(Color(0xFFD2A0A9), Color(0xFF4C353B), Color(0xFFF1E1E3)),
            tags = listOf(0xFFD8B37F, 0xFFA3C6B3, 0xFF9DBEDD, 0xFFD2A0A9)
        )
    )

    // ---------------------------------------------------------------- 莫兰迪·苔绿

    private val Sage = MoReadSchemeSpec(
        light = morandiLight(
            background = Color(0xFFEEF2EE),
            surface = Color(0xFFF8FAF8),
            onSurface = Color(0xFF2A302B),
            onSurfaceVariant = Color(0xFF566258),
            surfaceVariant = Color(0xFFDCE4DD),
            containers = listOf(0xFFFFFFFF, 0xFFF3F6F3, 0xFFE9EEE9, 0xFFE0E7E1, 0xFFD7DFD8),
            outline = Color(0xFF78857A),
            outlineVariant = Color(0xFFC3CDC5),
            primary = Color(0xFF44715A), primaryContainer = Color(0xFFD5E6DA), onPrimaryContainer = Color(0xFF1E3A2A),
            secondary = Color(0xFF4E6F92), secondaryContainer = Color(0xFFDAE5EF), onSecondaryContainer = Color(0xFF213547),
            tertiary = Color(0xFF7A6A4A), tertiaryContainer = Color(0xFFEBE4D2), onTertiaryContainer = Color(0xFF3A3020),
            rose = MoReadTone(Color(0xFF925E69), Color(0xFFF1E1E3), Color(0xFF4A2C33)),
            tags = listOf(0xFF9A7A3C, 0xFF44715A, 0xFF4E6F92, 0xFF925E69)
        ),
        dark = morandiDark(
            background = Color(0xFF141915),
            surface = Color(0xFF1A201B),
            onSurface = Color(0xFFE0E6E1),
            onSurfaceVariant = Color(0xFFB3BEB5),
            surfaceVariant = Color(0xFF39433B),
            containers = listOf(0xFF0F140F, 0xFF1F261F, 0xFF252D26, 0xFF2E372F, 0xFF384239),
            outline = Color(0xFF88948A),
            outlineVariant = Color(0xFF444F46),
            primary = Color(0xFFA2C7AF), primaryContainer = Color(0xFF2F4A3A), onPrimaryContainer = Color(0xFFD5E6DA),
            secondary = Color(0xFFA6BFD8), secondaryContainer = Color(0xFF30435A), onSecondaryContainer = Color(0xFFDAE5EF),
            tertiary = Color(0xFFCDBC9A), tertiaryContainer = Color(0xFF4A3F2C), onTertiaryContainer = Color(0xFFEBE4D2),
            rose = MoReadTone(Color(0xFFD2A0A9), Color(0xFF4C353B), Color(0xFFF1E1E3)),
            tags = listOf(0xFFD8B37F, 0xFFA2C7AF, 0xFFA6BFD8, 0xFFD2A0A9)
        )
    )

    // ---------------------------------------------------------------- 莫兰迪·灰粉

    private val RoseDust = MoReadSchemeSpec(
        light = morandiLight(
            background = Color(0xFFF4EFF0),
            surface = Color(0xFFFBF8F8),
            onSurface = Color(0xFF312A2C),
            onSurfaceVariant = Color(0xFF62575A),
            surfaceVariant = Color(0xFFE6DDDF),
            containers = listOf(0xFFFFFFFF, 0xFFF7F2F3, 0xFFEFE8EA, 0xFFE7DFE1, 0xFFDED5D8),
            outline = Color(0xFF857A7D),
            outlineVariant = Color(0xFFCFC3C6),
            primary = Color(0xFF8F5665), primaryContainer = Color(0xFFF0DCE1), onPrimaryContainer = Color(0xFF46242F),
            secondary = Color(0xFF6C5F8A), secondaryContainer = Color(0xFFE5E1F0), onSecondaryContainer = Color(0xFF2F2742),
            tertiary = Color(0xFF4F7A66), tertiaryContainer = Color(0xFFDCEAE1), onTertiaryContainer = Color(0xFF1F3529),
            rose = MoReadTone(Color(0xFF8C6832), Color(0xFFF1E5D0), Color(0xFF46341A)),
            tags = listOf(0xFF8C6832, 0xFF4F7A66, 0xFF6C5F8A, 0xFF8F5665)
        ),
        dark = morandiDark(
            background = Color(0xFF1B1618),
            surface = Color(0xFF221C1E),
            onSurface = Color(0xFFE8E0E2),
            onSurfaceVariant = Color(0xFFC2B6BA),
            surfaceVariant = Color(0xFF433A3D),
            containers = listOf(0xFF151112, 0xFF271F22, 0xFF2D2528, 0xFF362D30, 0xFF40373A),
            outline = Color(0xFF938789),
            outlineVariant = Color(0xFF4E4346),
            primary = Color(0xFFD9A5B3), primaryContainer = Color(0xFF573440), onPrimaryContainer = Color(0xFFF0DCE1),
            secondary = Color(0xFFBBAFD6), secondaryContainer = Color(0xFF40365A), onSecondaryContainer = Color(0xFFE5E1F0),
            tertiary = Color(0xFFA3C6B3), tertiaryContainer = Color(0xFF2E463A), onTertiaryContainer = Color(0xFFDCEAE1),
            rose = MoReadTone(Color(0xFFD8B37F), Color(0xFF4F3D22), Color(0xFFF1E5D0)),
            tags = listOf(0xFFD8B37F, 0xFFA3C6B3, 0xFFBBAFD6, 0xFFD9A5B3)
        )
    )

    // ---------------------------------------------------------------- 构造辅助

    @Suppress("LongParameterList")
    private fun morandiLight(
        background: Color,
        surface: Color,
        onSurface: Color,
        onSurfaceVariant: Color,
        surfaceVariant: Color,
        containers: List<Long>,
        outline: Color,
        outlineVariant: Color,
        primary: Color,
        primaryContainer: Color,
        onPrimaryContainer: Color,
        secondary: Color,
        secondaryContainer: Color,
        onSecondaryContainer: Color,
        tertiary: Color,
        tertiaryContainer: Color,
        onTertiaryContainer: Color,
        rose: MoReadTone,
        tags: List<Long>
    ): MoReadSchemeSide = MoReadSchemeSide(
        colors = lightColorScheme(
            primary = primary,
            onPrimary = primary.onAccent(),
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = primaryContainer,
            secondary = secondary,
            onSecondary = secondary.onAccent(),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = tertiary.onAccent(),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceContainerLowest = Color(containers[0]),
            surfaceContainerLow = Color(containers[1]),
            surfaceContainer = Color(containers[2]),
            surfaceContainerHigh = Color(containers[3]),
            surfaceContainerHighest = Color(containers[4]),
            outline = outline,
            outlineVariant = outlineVariant,
            inverseSurface = onSurface,
            inverseOnSurface = surface
        ),
        canvas = background,
        rose = rose,
        tagPalette = tags.map { Color(it) }
    )

    @Suppress("LongParameterList")
    private fun morandiDark(
        background: Color,
        surface: Color,
        onSurface: Color,
        onSurfaceVariant: Color,
        surfaceVariant: Color,
        containers: List<Long>,
        outline: Color,
        outlineVariant: Color,
        primary: Color,
        primaryContainer: Color,
        onPrimaryContainer: Color,
        secondary: Color,
        secondaryContainer: Color,
        onSecondaryContainer: Color,
        tertiary: Color,
        tertiaryContainer: Color,
        onTertiaryContainer: Color,
        rose: MoReadTone,
        tags: List<Long>
    ): MoReadSchemeSide = MoReadSchemeSide(
        colors = darkColorScheme(
            primary = primary,
            onPrimary = primary.onAccent(),
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = primaryContainer,
            secondary = secondary,
            onSecondary = secondary.onAccent(),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = tertiary.onAccent(),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceContainerLowest = Color(containers[0]),
            surfaceContainerLow = Color(containers[1]),
            surfaceContainer = Color(containers[2]),
            surfaceContainerHigh = Color(containers[3]),
            surfaceContainerHighest = Color(containers[4]),
            outline = outline,
            outlineVariant = outlineVariant,
            inverseSurface = onSurface,
            inverseOnSurface = surface
        ),
        canvas = background,
        rose = rose,
        tagPalette = tags.map { Color(it) }
    )
}
