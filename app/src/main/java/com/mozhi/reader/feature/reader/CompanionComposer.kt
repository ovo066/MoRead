package com.mozhi.reader.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import coil3.compose.AsyncImage

/** 扩展面板里的一格：图标 + 一行小字；[selected] 非 null 时它是个开关格。 */
internal data class ComposerAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val selected: Boolean? = null,
    val enabled: Boolean = true
)

/**
 * 输入区：独立圆形按钮 + 自然加高的输入胶囊，避免整块半圆底板形成梯形观感。
 * 扩展面板作为输入条上方的内联卡片展开。
 *
 * 之所以不用 DropdownMenu：它锚在按钮上、浮在输入条上方一段距离，
 * 中间那条空白既没法消掉，也让面板看着不属于输入区。
 */
@Composable
internal fun CompanionComposer(
    input: String,
    onInputChange: (String) -> Unit,
    attachments: List<PendingAttachment>,
    onRemoveAttachment: (Int) -> Unit,
    actions: List<ComposerAction>,
    isStreaming: Boolean,
    palette: ReaderPalette,
    onSend: () -> Unit,
    onStop: () -> Unit,
    placeholder: String = "问角色，也可以聊你的感受…"
) {
    var panelExpanded by remember { mutableStateOf(false) }
    val plusRotation by animateFloatAsState(
        targetValue = if (panelExpanded) 45f else 0f,
        animationSpec = tween(180),
        label = "composer-plus"
    )
    val canSend = input.isNotBlank() || attachments.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 键盘弹出时 imePadding 已把整块内容抬到键盘上方，此时导航栏
            // 的内边距必须归零，否则输入条和键盘之间会多出一条导航栏高的空白。
            .windowInsetsPadding(WindowInsets.navigationBars.exclude(WindowInsets.ime))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        AnimatedVisibility(
            visible = panelExpanded,
            enter = expandVertically(tween(180)) + fadeIn(tween(140)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(100))
        ) {
            Surface(
                color = palette.glass,
                contentColor = palette.onBackground,
                border = BorderStroke(1.dp, palette.glassBorder),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                ComposerPanel(
                    actions = actions,
                    palette = palette,
                    onPicked = { panelExpanded = false }
                )
            }
        }
        if (attachments.isNotEmpty()) {
            AttachmentStrip(attachments, palette, onRemoveAttachment)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ComposerCircleButton(
                icon = Icons.Outlined.Add,
                description = if (panelExpanded) "收起更多输入" else "更多输入",
                tint = palette.accent,
                background = palette.glassStrong,
                border = palette.glassBorder,
                onClick = { panelExpanded = !panelExpanded },
                iconModifier = Modifier.rotate(plusRotation),
                modifier = Modifier.padding(bottom = 5.dp)
            )
            Surface(
                shape = RoundedCornerShape(27.dp),
                color = palette.glassStrong,
                contentColor = palette.onBackground,
                border = BorderStroke(1.dp, palette.glassBorder),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 54.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp)
                        .padding(horizontal = 16.dp, vertical = 15.dp),
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
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (isStreaming) {
                ComposerCircleButton(
                    icon = Icons.Outlined.StopCircle,
                    description = "停止",
                    tint = palette.onAccent,
                    background = palette.accent,
                    border = palette.accent,
                    onClick = onStop,
                    modifier = Modifier.padding(bottom = 5.dp)
                )
            } else {
                ComposerCircleButton(
                    icon = Icons.AutoMirrored.Filled.Send,
                    description = "发送",
                    tint = if (canSend) palette.onAccent else palette.muted,
                    background = if (canSend) palette.accent else palette.glassStrong,
                    border = if (canSend) palette.accent else palette.glassBorder,
                    enabled = canSend,
                    onClick = onSend,
                    iconModifier = Modifier.rotate(-90f),
                    modifier = Modifier.padding(bottom = 5.dp)
                )
            }
        }
    }
}

/** 输入胶囊两侧的独立圆钮。发送图标旋转为尖头朝上。 */
@Composable
private fun ComposerCircleButton(
    icon: ImageVector,
    description: String,
    tint: androidx.compose.ui.graphics.Color,
    background: androidx.compose.ui.graphics.Color,
    border: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, border, CircleShape)
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = tint,
            modifier = iconModifier.size(19.dp)
        )
    }
}

/** 扩展面板：一排排胶囊格子，开关型的格子选中时点亮，点一下就地翻转不收面板。 */
@Composable
private fun ComposerPanel(
    actions: List<ComposerAction>,
    palette: ReaderPalette,
    onPicked: () -> Unit
) {
    Column(
        modifier = Modifier.padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        actions.chunked(PANEL_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { action ->
                    ComposerCell(
                        action = action,
                        palette = palette,
                        modifier = Modifier.weight(1f),
                        onPicked = onPicked
                    )
                }
                // 最后一行不足列数时补空位，格子宽度与上面几行保持一致。
                repeat(PANEL_COLUMNS - row.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ComposerCell(
    action: ComposerAction,
    palette: ReaderPalette,
    modifier: Modifier = Modifier,
    onPicked: () -> Unit
) {
    val isToggle = action.selected != null
    val active = action.selected == true
    val alpha = if (action.enabled) 1f else 0.38f
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = action.enabled) {
                action.onClick()
                // 开关型格子留在面板里，让用户看见它翻转了；一次性动作则收起面板。
                if (!isToggle) onPicked()
            }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = if (active) palette.accent else palette.glassStrong,
            border = BorderStroke(1.dp, palette.glassBorder)
        ) {
            Icon(
                action.icon,
                contentDescription = null,
                tint = (if (active) palette.onAccent else palette.accent).copy(alpha = alpha),
                modifier = Modifier
                    .padding(9.dp)
                    .size(18.dp)
            )
        }
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelSmall,
            color = (if (active) palette.accent else palette.muted).copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/** 待发附件：图片直接出缩略图，文件出图标格；右上角小叉移除。 */
@Composable
private fun AttachmentStrip(
    attachments: List<PendingAttachment>,
    palette: ReaderPalette,
    onRemove: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 8.dp, start = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEachIndexed { index, pending ->
            Box {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = palette.glassStrong,
                    border = BorderStroke(1.dp, palette.glassBorder),
                    modifier = Modifier.size(56.dp)
                ) {
                    if (pending.isImage) {
                        AsyncImage(
                            model = pending.uri,
                            contentDescription = "图片附件",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp)
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = pending.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.muted,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .width(52.dp)
                                    .padding(horizontal = 3.dp)
                            )
                        }
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = palette.scrim,
                    contentColor = palette.onAccent,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(16.dp)
                        .clickable { onRemove(index) }
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "移除附件",
                        modifier = Modifier.padding(3.dp)
                    )
                }
            }
        }
    }
}

/** 输入框上方的横排建议胶囊：点按即发送，尾部小叉收起。 */
@Composable
internal fun SuggestionStrip(
    suggestions: List<String>,
    palette: ReaderPalette,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp)
    ) {
        suggestions.forEach { suggestion ->
            Surface(
                onClick = { onPick(suggestion) },
                shape = CircleShape,
                color = palette.glassStrong,
                contentColor = palette.accent,
                border = BorderStroke(1.dp, palette.glassBorder),
                shadowElevation = 4.dp
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 250.dp)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
        Surface(
            onClick = onDismiss,
            shape = CircleShape,
            color = palette.glassStrong,
            contentColor = palette.muted,
            border = BorderStroke(1.dp, palette.glassBorder)
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "收起建议",
                modifier = Modifier
                    .padding(6.dp)
                    .size(13.dp)
            )
        }
    }
}

private const val PANEL_COLUMNS = 4
