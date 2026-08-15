package com.mozhi.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ui.theme.isDarkTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * 带少量环境色的纸面背景。
 *
 * 毛玻璃只有在背后存在很轻的明暗/色温变化时才有层次，因此背景不再是纯白或纯黑，
 * 但环境色控制得很淡，不会变成抢眼的装饰色晕。
 */
@Composable
fun MoReadBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val darkTheme = isDarkTheme()
    val gradient = if (darkTheme) {
        val ambient = colors.primaryContainer
            .copy(alpha = 0.12f)
            .compositeOver(colors.background)
        listOf(ambient, colors.background, colors.surfaceContainerLowest)
    } else {
        // 浅色模式不能用近白背景托近白玻璃，否则层级会全部融在一起。
        // 以 surfaceContainer 作为画布，再叠一层很轻的强调色和亮度变化；
        // 卡片本身仍然是白色玻璃，因此无需灰色描边也能看清边界。
        val canvas = colors.surfaceContainer
        val ambient = colors.primaryContainer
            .copy(alpha = 0.16f)
            .compositeOver(canvas)
        val lowerCanvas = colors.surfaceContainerLow
            .copy(alpha = 0.38f)
            .compositeOver(canvas)
        listOf(ambient, canvas, lowerCanvas)
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(gradient))
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
            content()
        }
    }
}

/**
 * 低成本玻璃表面。
 *
 * 用于列表卡片等大量重复元素：半透明渐变、0.5dp 高光边和极轻投影，避免实时模糊
 * 让长列表进入昂贵的离屏合成。浮动导航、底部操作舱等少量关键浮层请用
 * [BlurredGlassSurface] 获得真实背景模糊。
 */
@Composable
fun FrostedSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    color: Color = MaterialTheme.colorScheme.surface.copy(
        alpha = if (isDarkTheme()) 0.66f else 0.84f
    ),
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    shadowElevation: Dp = 3.dp,
    content: @Composable () -> Unit
) {
    val darkTheme = isDarkTheme()
    val highlight = if (darkTheme) {
        Color.White.copy(alpha = 0.14f)
    } else {
        Color.White.copy(alpha = 0.78f)
    }
    val effectiveShadow = shadowElevation.coerceAtMost(8.dp)
    val topColor = color.copy(
        alpha = (color.alpha + if (darkTheme) 0.06f else 0.08f).coerceAtMost(0.94f)
    )
    val bottomColor = color.copy(alpha = (color.alpha * 0.90f).coerceAtLeast(0.32f))

    Box(
        modifier = modifier
            .shadow(
                elevation = effectiveShadow,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = if (darkTheme) 0.22f else 0.10f),
                spotColor = Color.Black.copy(alpha = if (darkTheme) 0.28f else 0.14f)
            )
            .clip(shape)
            .background(Brush.verticalGradient(listOf(topColor, color, bottomColor)))
            .border(0.5.dp, highlight, shape)
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

/**
 * Haze 驱动的真实毛玻璃表面。
 *
 * 只用于覆盖在 [HazeState] source 之上的少量浮层；Android 12+ 使用实时背景模糊，
 * 较低版本由 Haze 自动退化为协调的半透明材质。
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun BlurredGlassSurface(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    tint: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    shadowElevation: Dp = 6.dp,
    content: @Composable () -> Unit
) {
    val darkTheme = isDarkTheme()
    val highlight = if (darkTheme) {
        Color.White.copy(alpha = 0.16f)
    } else {
        Color.White.copy(alpha = 0.72f)
    }
    val veil = Brush.verticalGradient(
        listOf(
            tint.copy(alpha = if (darkTheme) 0.12f else 0.28f),
            tint.copy(alpha = if (darkTheme) 0.06f else 0.16f)
        )
    )
    Box(
        modifier = modifier
            .shadow(
                elevation = shadowElevation.coerceAtMost(8.dp),
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = if (darkTheme) 0.24f else 0.10f),
                spotColor = Color.Black.copy(alpha = if (darkTheme) 0.30f else 0.14f)
            )
            .clip(shape)
            .hazeEffect(
                state = hazeState,
                style = HazeMaterials.thin(tint)
            )
            .background(veil)
            .border(0.5.dp, highlight, shape)
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}
