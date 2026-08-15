package com.mozhi.reader.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 顶部安全留白，替代直接用 `statusBarsPadding()`。
 *
 * 沉浸阅读会隐藏状态栏，此时 `statusBarsPadding()` 归零——从阅读页推进来的全屏页
 * （书籍详情、伴读聊天）标题就直接贴上屏幕物理上沿，挖孔屏更是把返回键压在摄像头下面。
 *
 * 这里取状态栏与显示切口的较大者：切口 inset 不随状态栏隐藏而消失，因此挖孔/刘海
 * 永远让得开；再兜一个最小值，保证纯全面屏在沉浸模式下也有呼吸空间而不是齐边顶格。
 */
fun Modifier.safeTopPadding(minimum: Dp = DEFAULT_MINIMUM_TOP): Modifier = composed {
    padding(top = safeTopInset(minimum))
}

/** 需要把数值用在别处（如 contentPadding）时取它。 */
@Composable
fun safeTopInset(minimum: Dp = DEFAULT_MINIMUM_TOP): Dp {
    val density = LocalDensity.current
    val insets = WindowInsets.statusBars.union(WindowInsets.displayCutout)
    val top = with(density) { insets.getTop(density).toDp() }
    return maxOf(top, minimum)
}

private val DEFAULT_MINIMUM_TOP = 10.dp
