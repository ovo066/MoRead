package com.mozhi.reader.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外观主题的颜色推导。回归的是「选了主题色没变化 / 夜间黑底黑字」那批问题：
 * 每个预设在日夜两套底色上都必须真的可读。
 */
class AppThemeTest {
    @Test
    fun `每个预设的强调色在对应底色上都达到 AA 对比度`() {
        AccentPreset.entries.forEach { preset ->
            assertTrue(
                "${preset.label} 在浅色底上对比不足",
                contrastRatio(preset.light, LIGHT_BACKGROUND) >= MIN_CONTENT_CONTRAST
            )
            assertTrue(
                "${preset.label} 在深色底上对比不足",
                contrastRatio(preset.dark, DARK_BACKGROUND) >= MIN_CONTENT_CONTRAST
            )
        }
    }

    @Test
    fun `强调色上的前景色始终可读`() {
        AccentPreset.entries.forEach { preset ->
            listOf(preset.light, preset.dark).forEach { accent ->
                assertTrue(
                    "${preset.label} 上的前景色对比不足",
                    contrastRatio(accent.onAccent(), accent) >= MIN_CONTENT_CONTRAST
                )
            }
        }
    }

    /**
     * 这条是主 bug 的回归：夜间「墨」的强调色是近白，旧代码直接把它当
     * onPrimaryContainer，而 container 本身就是这个近白混出来的 —— 浅上叠浅。
     */
    @Test
    fun `container 前景色在日夜两种模式下都可读`() {
        AccentPreset.entries.forEach { preset ->
            listOf(
                Triple(preset.light, LIGHT_SURFACE_CONTAINER, 0.16f),
                Triple(preset.dark, DARK_SURFACE_CONTAINER_HIGH, 0.22f)
            ).forEach { (accent, base, alpha) ->
                val container = accent.copy(alpha = alpha).compositeOver(base)
                val onContainer = readableOn(background = container, preferred = accent)
                assertTrue(
                    "${preset.label} 的 container 前景色对比不足",
                    contrastRatio(onContainer, container) >= MIN_CONTENT_CONTRAST
                )
            }
        }
    }

    @Test
    fun `对比足够时 readableOn 保留原色`() {
        val onBlack = readableOn(background = Color.Black, preferred = Color.White)
        assertEquals(Color.White, onBlack)
    }

    @Test
    fun `自定义色过暗时在深色底上被提亮`() {
        val adapted = adaptCustomAccent(Color(0xFF101010), dark = true)
        assertTrue(
            "深色底上的自定义色应被提亮",
            contrastRatio(adapted, DARK_BACKGROUND) > contrastRatio(Color(0xFF101010), DARK_BACKGROUND)
        )
    }

    @Test
    fun `自定义色过亮时在浅色底上被压暗`() {
        val adapted = adaptCustomAccent(Color(0xFFFAFAFA), dark = false)
        assertTrue(
            "浅色底上的自定义色应被压暗",
            contrastRatio(adapted, LIGHT_BACKGROUND) >
                contrastRatio(Color(0xFFFAFAFA), LIGHT_BACKGROUND)
        )
    }

    @Test
    fun `选中预设后自定义色不再生效`() {
        val resolved = resolveAccentColor(
            accent = AccentPreset.AZURE,
            customAccentArgb = null,
            dark = false
        )
        assertEquals(AccentPreset.AZURE.light, resolved)
    }

    private companion object {
        // 与 Theme.kt 的 LightColors / DarkColors 保持一致。
        val LIGHT_BACKGROUND = Color(0xFFFAFAFA)
        val DARK_BACKGROUND = Color(0xFF0E0E0E)
        val LIGHT_SURFACE_CONTAINER = Color(0xFFF1F1F1)
        val DARK_SURFACE_CONTAINER_HIGH = Color(0xFF272727)
    }
}
