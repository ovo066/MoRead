package com.mozhi.reader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ui.components.MoReadIcons

/**
 * 伴读聊天 / 选词 AI / 段评讨论三处共用的输入胶囊。
 *
 * 形制：**一整条胶囊**，`＋` 一类的前置动作嵌在左端、发送键嵌在右端，中间是输入区。
 * 之前伴读页是「圆钮 ＋ | 胶囊 | 圆钮发送」三块分离，三个独立描边容器并排像三颗按钮，
 * 而不像一个输入框；选词 AI 则直接裸用 `OutlinedTextField`，与全局玻璃语言完全不搭。
 *
 * 发送图标统一为 [MoReadIcons.PaperPlane]（机头朝右上 45°），不再用旋转过的 Material `Send`。
 *
 * 导航栏内边距在这里统一处理（包在 Surface **外面**，胶囊本体不能被拉进导航栏区域）：
 * 键盘弹出时 `navigationBars.exclude(ime)` 归零，避免与调用点的 `imePadding()` 叠出一条空隙
 * （DECISIONS 2026-08-02 的 IME 配方）。调用点不要重复加。
 */
@Composable
internal fun ReaderComposerBar(
    input: String,
    onInputChange: (String) -> Unit,
    placeholder: String,
    canSend: Boolean,
    isStreaming: Boolean,
    palette: ReaderPalette,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = 5,
    leading: @Composable (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.exclude(WindowInsets.ime))
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = palette.glassStrong,
            contentColor = palette.onBackground,
            border = BorderStroke(1.dp, palette.glassBorder),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = COMPOSER_BAR_MIN_HEIGHT)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 7.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                leading?.invoke()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = COMPOSER_SLOT)
                        .padding(
                            start = if (leading == null) 10.dp else 4.dp,
                            end = 6.dp,
                            top = 7.dp,
                            bottom = 7.dp
                        ),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (input.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = onInputChange,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = palette.onBackground
                        ),
                        cursorBrush = SolidColor(palette.accent),
                        maxLines = maxLines,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (isStreaming) {
                    ComposerRoundAction(
                        icon = MoReadIcons.StopSquare,
                        description = "停止生成",
                        tint = palette.onAccent,
                        background = palette.accent,
                        border = palette.accent,
                        iconSize = 13.dp,
                        onClick = onStop
                    )
                } else {
                    ComposerRoundAction(
                        icon = MoReadIcons.PaperPlane,
                        description = "发送",
                        tint = if (canSend) palette.onAccent else palette.muted,
                        background = if (canSend) palette.accent else Color.Transparent,
                        border = if (canSend) palette.accent else palette.glassBorder,
                        iconSize = 16.dp,
                        enabled = canSend,
                        onClick = onSend
                    )
                }
            }
        }
    }
}

/**
 * 胶囊两端的圆形动作键。伴读页把 `＋` 传进 [ReaderComposerBar] 的 `leading`，
 * 用的就是这个，两端因此完全等大等形。
 */
@Composable
internal fun ComposerRoundAction(
    icon: ImageVector,
    description: String,
    tint: Color,
    background: Color,
    border: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 17.dp,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .size(COMPOSER_SLOT)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, border, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = iconModifier.size(iconSize)
        )
    }
}

/** 单行时胶囊内所有元素同高，两端圆钮与文字行严丝合缝。 */
private val COMPOSER_SLOT = 34.dp
private val COMPOSER_BAR_MIN_HEIGHT = 48.dp
