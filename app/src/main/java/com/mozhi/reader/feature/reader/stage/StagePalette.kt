package com.mozhi.reader.feature.reader.stage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import com.mozhi.reader.core.datastore.StageTheme
import com.mozhi.reader.feature.reader.ReaderPalette
import com.mozhi.reader.feature.reader.seal
import com.mozhi.reader.ui.components.personaPaletteIndex

/** 舞台配色：纸页跟随阅读主题的纸色与字色，剧场是固定的暗场。 */
@Immutable
internal data class StagePalette(
    val theater: Boolean,
    val isDark: Boolean,
    val background: Color,
    val backdrop: Brush,
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    val accent: Color,
    val accentSoft: Color,
    val plate: Color,
    val card: Color,
    val cardBorder: Color,
    val bubble: Color,
    val bubbleMe: Color,
    val chrome: Color,
    val cold: Color,
    val warm: Color,
    val glow: Color,
    val systemPanel: Color,
    val systemInk: Color,
    val systemBorder: Color,
    val letterPaper: Color,
    val letterInk: Color,
    val dimScrim: Float,
    val roles: List<Color>,
    /** 气泡底色：左侧（他人）与右侧（主角或另一方）。 */
    val balloon: Color = plate,
    val balloonAlt: Color = accentSoft,
    /** 低声、喊叫这些气泡的描边。 */
    val balloonEdge: Color = inkFaint,
    /** 布景用的墨：纸页上是暖黑，暗场上是淡墨（白）。 */
    val sceneryInk: Color = ink,
    /** 对白签条：比纸略亮的一张小笺，与它的投影。 */
    val slip: Color = card,
    val slipShadow: Color = Color.Black.copy(alpha = 0.08f)
) {
    /** 同一个名字在任何地方都得到同一种颜色。 */
    fun roleColor(name: String): Color = roles[personaPaletteIndex(name, roles.size)]
}

/** 背景光晕预设色：青绿、天蓝、淡紫、桃红、暖橙、湖青。 */
internal val StageHaloColors = listOf(
    Color(0xFF5DB894), Color(0xFF5B9BE0), Color(0xFF9D86DA), Color(0xFFE5859A), Color(0xFFE9A955), Color(0xFF45ABA6)
)

private val RolesLight = listOf(
    Color(0xFFB0442B), Color(0xFF3F6A93), Color(0xFF3F7F58), Color(0xFF8A6A3A),
    Color(0xFF7A5A8C), Color(0xFF3F7F86), Color(0xFF9A5B6B)
)
private val RolesDark = listOf(
    Color(0xFFE08A6F), Color(0xFF8DB4D6), Color(0xFF7FBF95), Color(0xFFD2B07A),
    Color(0xFFB79AD0), Color(0xFF7FC3C9), Color(0xFFD79AA9)
)

@Composable
internal fun stagePalette(theme: StageTheme, reader: ReaderPalette): StagePalette =
    when (theme) {
        StageTheme.THEATER -> theaterPalette()
        StageTheme.PAPER -> paperPalette(reader)
        StageTheme.XUAN -> xuanPalette()
    }

/** 宣纸：不随阅读主题变化的暖白纸页，墨黑字、朱砂点睛、淡灰平铺对白框。 */
private fun xuanPalette(): StagePalette {
    val background = Color(0xFFF6F2EA)
    val ink = Color(0xFF2A2724)
    val seal = Color(0xFFB8432F)
    return StagePalette(
        theater = false,
        isDark = false,
        background = background,
        backdrop = Brush.radialGradient(listOf(Color(0xFFFAF7F1), background, Color(0xFFEBE4D6))),
        ink = ink,
        inkSoft = Color(0xFF6F6A62),
        inkFaint = Color(0xFFA29C92),
        accent = seal,
        accentSoft = seal.copy(alpha = 0.10f),
        plate = Color(0xFFECE8E0),
        card = Color(0xF2FBF9F4),
        cardBorder = ink.copy(alpha = 0.08f),
        bubble = Color(0xFFFFFFFF),
        bubbleMe = Color(0xFFF1DFD6),
        chrome = ink.copy(alpha = 0.5f),
        cold = Color(0xFF4A6782),
        warm = seal,
        glow = Color.Transparent,
        systemPanel = Color(0xF5FBFAF6),
        systemInk = Color(0xFF24434A),
        systemBorder = Color(0x5924434A),
        letterPaper = Color(0xFFFCF6E8),
        letterInk = Color(0xFF3A3226),
        dimScrim = 0.10f,
        roles = RolesLight,
        balloon = Color(0xFFEBE7E0),
        balloonAlt = Color(0xFFEFE4DC),
        balloonEdge = ink.copy(alpha = 0.28f),
        sceneryInk = Color(0xFF282522),
        slip = Color(0xFFFBF9F4),
        slipShadow = Color(0xFF503C1E).copy(alpha = 0.10f)
    )
}

private fun paperPalette(reader: ReaderPalette): StagePalette {
    val ink = reader.onBackground
    val background = reader.background
    val dark = reader.isDark
    val seal = reader.seal
    val vignetteEdge = lerp(background, if (dark) Color.Black else Color(0xFFE6DAC2), if (dark) 0.35f else 0.45f)
    return StagePalette(
        theater = false,
        isDark = dark,
        background = background,
        backdrop = Brush.radialGradient(listOf(lerp(background, Color.White, if (dark) 0.03f else 0.25f), background, vignetteEdge)),
        ink = ink,
        inkSoft = ink.copy(alpha = 0.66f).compositeOver(background),
        inkFaint = ink.copy(alpha = 0.40f).compositeOver(background),
        accent = seal,
        accentSoft = seal.copy(alpha = if (dark) 0.22f else 0.12f),
        plate = ink.copy(alpha = if (dark) 0.10f else 0.06f),
        card = reader.glassStrong,
        cardBorder = ink.copy(alpha = 0.10f),
        bubble = lerp(background, Color.White, if (dark) 0.08f else 0.7f),
        bubbleMe = seal.copy(alpha = if (dark) 0.30f else 0.16f).compositeOver(background),
        chrome = ink.copy(alpha = 0.55f),
        cold = if (dark) Color(0xFF8DB4D6) else Color(0xFF3F6A93),
        warm = seal,
        glow = Color.Transparent,
        systemPanel = if (dark) Color(0xF0162026) else Color(0xF5FAF8F2),
        systemInk = if (dark) Color(0xFFCFEFF4) else Color(0xFF24434A),
        systemBorder = if (dark) Color(0x5978DCEB) else Color(0x5924434A),
        letterPaper = if (dark) Color(0xFF2B261E) else Color(0xFFFBF4E4),
        letterInk = if (dark) Color(0xFFE6DCC8) else Color(0xFF3A3226),
        dimScrim = if (dark) 0.30f else 0.12f,
        roles = if (dark) RolesDark else RolesLight,
        balloon = ink.copy(alpha = if (dark) 0.09f else 0.055f).compositeOver(background),
        balloonAlt = seal.copy(alpha = if (dark) 0.20f else 0.10f).compositeOver(background),
        balloonEdge = ink.copy(alpha = 0.32f),
        sceneryInk = ink,
        slip = lerp(background, Color.White, if (dark) 0.06f else 0.55f),
        slipShadow = if (dark) Color.Black.copy(alpha = 0.30f) else Color(0xFF503C1E).copy(alpha = 0.10f)
    )
}

/** 墨夜：靛黑的纸、月白的字、朱砂提亮；布景用淡墨（白）画在暗处。 */
private fun theaterPalette(): StagePalette {
    val background = Color(0xFF15171D)
    val ink = Color(0xFFE8E2D4)
    val accent = Color(0xFFD67056)
    return StagePalette(
        theater = true,
        isDark = true,
        background = background,
        backdrop = Brush.radialGradient(listOf(Color(0xFF1C2029), Color(0xFF15171D), Color(0xFF0D0E12))),
        ink = ink,
        inkSoft = Color(0xFFA39C8E),
        inkFaint = Color(0xFF6E685E),
        accent = accent,
        accentSoft = accent.copy(alpha = 0.18f),
        plate = Color.White.copy(alpha = 0.07f),
        card = Color(0xDB14171E),
        cardBorder = Color.White.copy(alpha = 0.10f),
        bubble = Color.White.copy(alpha = 0.10f),
        bubbleMe = accent.copy(alpha = 0.30f),
        chrome = ink.copy(alpha = 0.55f),
        cold = Color(0xFF8DB4D6),
        warm = Color(0xFFE2A271),
        glow = accent.copy(alpha = 0.55f),
        systemPanel = Color(0xDB0E161C),
        systemInk = Color(0xFFCFEFF4),
        systemBorder = Color(0x5978DCEB),
        letterPaper = Color(0xFFF4EAD3),
        letterInk = Color(0xFF3A3226),
        dimScrim = 0.34f,
        roles = RolesDark,
        balloon = Color.White.copy(alpha = 0.075f),
        balloonAlt = accent.copy(alpha = 0.22f),
        balloonEdge = ink.copy(alpha = 0.35f),
        sceneryInk = Color(0xFFD9D3C5),
        slip = Color(0xFF20232B),
        slipShadow = Color.Black.copy(alpha = 0.35f)
    )
}
