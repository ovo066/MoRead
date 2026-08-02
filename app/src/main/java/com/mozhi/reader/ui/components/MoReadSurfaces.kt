package com.mozhi.reader.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ui.theme.isDarkTheme

/**
 * 干净的纸面背景：只保留极淡的纵向渐变，不加任何装饰性色晕。
 *
 * 同时把 [LocalContentColor] 钉在 onBackground —— 背景是自绘的渐变而非 Surface，
 * 没有它，落在背景上、又没写死颜色的文字会继承外层的 Unspecified 而变成不可见。
 */
@Composable
fun MoReadBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val darkTheme = isDarkTheme()
    val gradient = if (darkTheme) {
        listOf(colors.background, colors.surfaceContainerLowest, colors.background)
    } else {
        listOf(colors.surfaceContainerLowest, colors.background, colors.background)
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
 * 玻璃卡：接近不透明的表面 + 1dp 单色细描边 + 小阴影。
 * 不用渐变描边和大投影——白卡四周出现灰晕会显得粗糙。
 */
@Composable
fun FrostedSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    color: Color = MaterialTheme.colorScheme.surface.copy(
        alpha = if (isDarkTheme()) 0.88f else 0.94f
    ),
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    shadowElevation: Dp = 4.dp,
    content: @Composable () -> Unit
) {
    val darkTheme = isDarkTheme()
    val borderColor = if (darkTheme) {
        Color.White.copy(alpha = 0.10f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f)
    }
    Surface(
        modifier = modifier.border(BorderStroke(1.dp, borderColor), shape),
        shape = shape,
        color = color,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        shadowElevation = shadowElevation,
        content = content
    )
}
