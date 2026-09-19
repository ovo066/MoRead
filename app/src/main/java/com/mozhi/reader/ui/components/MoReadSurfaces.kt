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
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ui.theme.canvasColor
import com.mozhi.reader.ui.theme.isDarkTheme
import com.mozhi.reader.ui.theme.isFlatSurface
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
    // 只替换背景 modifier，保留内容的组合位置。分成两套 Box 会在切换质感时丢失导航与滚动状态。
    val background = if (isFlatSurface()) Modifier.background(canvasColor())
        else Modifier.background(Brush.verticalGradient(gradient))
    Box(modifier = modifier.fillMaxSize().then(background)) {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
            content()
        }
    }
}

/**
 * 低成本玻璃表面。
 *
 * 用于浮层的半透明渐变、0.5dp 高光边和极轻投影。页面内列表使用 MoReadSection。
 * 浮动导航、底部操作舱等少量关键浮层请用
 * [BlurredGlassSurface] 获得真实背景模糊。
 */
@Composable
fun FrostedSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    color: Color = Color.Unspecified,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    shadowElevation: Dp = 3.dp,
    content: @Composable () -> Unit
) {
    val darkTheme = isDarkTheme()
    val flat = isFlatSurface()
    val glassColor = if (color.isSpecified) color else MaterialTheme.colorScheme.surface.copy(
        alpha = if (darkTheme) 0.66f else 0.84f
    )
    val highlight = if (darkTheme) {
        Color.White.copy(alpha = 0.14f)
    } else {
        Color.White.copy(alpha = 0.78f)
    }
    val effectiveShadow = shadowElevation.coerceAtMost(if (flat) 2.dp else 8.dp)
    val topColor = glassColor.copy(
        alpha = (glassColor.alpha + if (darkTheme) 0.06f else 0.08f).coerceAtMost(0.94f)
    )
    val bottomColor = glassColor.copy(alpha = (glassColor.alpha * 0.90f).coerceAtLeast(0.32f))
    val material = if (flat) {
        val base = MaterialTheme.colorScheme.surfaceContainerHigh
        Modifier.background(if (color.isSpecified) color.compositeOver(base) else base)
    } else {
        Modifier.background(Brush.verticalGradient(listOf(topColor, glassColor, bottomColor)))
            .border(0.5.dp, highlight, shape)
    }

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
            .then(material)
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
    tint: Color = Color.Unspecified,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    shadowElevation: Dp = 6.dp,
    content: @Composable () -> Unit
) {
    val darkTheme = isDarkTheme()
    val flat = isFlatSurface()
    val glassTint = if (tint.isSpecified) tint else MaterialTheme.colorScheme.surface
    val highlight = if (darkTheme) {
        Color.White.copy(alpha = 0.16f)
    } else {
        Color.White.copy(alpha = 0.72f)
    }
    val veil = Brush.verticalGradient(
        listOf(
            glassTint.copy(alpha = if (darkTheme) 0.12f else 0.28f),
            glassTint.copy(alpha = if (darkTheme) 0.06f else 0.16f)
        )
    )
    val material = if (flat) {
        val base = MaterialTheme.colorScheme.surfaceContainerHigh
        Modifier.background(if (tint.isSpecified) tint.compositeOver(base) else base)
    } else {
        Modifier.hazeEffect(state = hazeState, style = HazeMaterials.thin(glassTint))
            .background(veil)
            .border(0.5.dp, highlight, shape)
    }
    Box(
        modifier = modifier
            .shadow(
                elevation = shadowElevation.coerceAtMost(if (flat) 2.dp else 8.dp),
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = if (darkTheme) 0.24f else 0.10f),
                spotColor = Color.Black.copy(alpha = if (darkTheme) 0.30f else 0.14f)
            )
            .clip(shape)
            .then(material)
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}
