package com.mozhi.reader.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.mozhi.reader.ui.theme.isDarkTheme

/**
 * 全局统一的弹出菜单外观：大圆角 + 近不透明表面 + 1dp 细描边 + 小阴影，与
 * [FrostedSurface] 是同一套语言。M3 默认的 DropdownMenu 是方角实心卡，落在
 * 玻璃层界面上很突兀（尤其阅读页的纸色/夜间底），所有菜单一律走这里。
 */
@Composable
fun MoReadDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    minWidth: Dp = 208.dp,
    maxWidth: Dp = 260.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val darkTheme = isDarkTheme()
    MoReadDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface.copy(
            alpha = if (darkTheme) 0.96f else 0.98f
        ),
        contentColor = MaterialTheme.colorScheme.onSurface,
        borderColor = if (darkTheme) {
            Color.White.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        },
        modifier = modifier,
        offset = offset,
        minWidth = minWidth,
        maxWidth = maxWidth,
        content = content
    )
}

/**
 * 顶边固定在触发按钮下方的菜单。Material 默认菜单会在内容高度变化时重新比较上下
 * 空间，手风琴展开后因此会整块跳位；筛选菜单更适合只向下生长，达到上限后内部滚动。
 */
@Composable
fun MoReadStableDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 6.dp),
    width: Dp = 244.dp,
    maxHeight: Dp = 390.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!expanded) return

    val darkTheme = isDarkTheme()
    val density = LocalDensity.current
    val positionProvider = remember(density, offset) {
        StableBelowEndPositionProvider(
            offset = with(density) {
                IntOffset(offset.x.roundToPx(), offset.y.roundToPx())
            },
            horizontalMargin = with(density) { 8.dp.roundToPx() }
        )
    }
    val containerColor = MaterialTheme.colorScheme.surface.copy(
        alpha = if (darkTheme) 0.96f else 0.98f
    )
    val borderColor = if (darkTheme) {
        Color.White.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = modifier.widthIn(min = width, max = width),
            shape = RoundedCornerShape(18.dp),
            color = containerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, borderColor)
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                content = content
            )
        }
    }
}

/** 始终按 anchor 的 bottom/end 定位；纵向不做“放不下就翻到上面”的二次选择。 */
private class StableBelowEndPositionProvider(
    private val offset: IntOffset,
    private val horizontalMargin: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val desiredX = when (layoutDirection) {
            LayoutDirection.Ltr -> anchorBounds.right - popupContentSize.width + offset.x
            LayoutDirection.Rtl -> anchorBounds.left - offset.x
        }
        val maxX = (windowSize.width - popupContentSize.width - horizontalMargin)
            .coerceAtLeast(horizontalMargin)
        return IntOffset(
            x = desiredX.coerceIn(horizontalMargin, maxX),
            y = anchorBounds.bottom + offset.y
        )
    }
}

/**
 * 配色显式版：阅读页的菜单要跟纸色走（`palette.glassStrong` 等），不能用应用主题色。
 * 自定义三色主题下同样成立。
 */
@Composable
fun MoReadDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    minWidth: Dp = 208.dp,
    maxWidth: Dp = 260.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        // 宽度收窄、高度设上限：菜单是从一枚小圆按钮里长出来的，撑满半屏就不像一路货色了。
        modifier = modifier.widthIn(min = minWidth, max = maxWidth),
        offset = offset,
        shape = RoundedCornerShape(18.dp),
        containerColor = containerColor,
        // tonalElevation 会在容器色上再叠一层主题色，把我们钉好的玻璃色染歪。
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, borderColor),
        content = {
            val columnScope = this
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                columnScope.content()
            }
        }
    )
}

/**
 * 菜单项：20dp 图标 + 文字，选中出右侧对勾，破坏性操作走 error 色。
 * [tint] 为空时跟随菜单的内容色（阅读页由 palette 决定）。
 */
@Composable
fun MoReadMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    indent: Dp = 0.dp,
    tint: Color? = null,
    accentColor: Color? = null,
    /** 右侧的当前取值摘要（如「未分组」「3 个」）；与 [selected] 勾互斥，勾优先。 */
    trailingText: String? = null
) {
    val inherited = LocalContentColor.current
    val resolved = when {
        destructive -> MaterialTheme.colorScheme.error
        selected -> accentColor ?: MaterialTheme.colorScheme.primary
        else -> tint ?: inherited
    }
    DropdownMenuItem(
        modifier = modifier.heightIn(max = 44.dp),
        enabled = enabled,
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = resolved,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = icon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = resolved,
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        trailingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = resolved,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else if (!trailingText.isNullOrBlank()) {
            {
                Text(
                    text = trailingText,
                    style = MaterialTheme.typography.labelSmall,
                    color = resolved.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 90.dp)
                )
            }
        } else {
            null
        },
        contentPadding = PaddingValues(start = 14.dp + indent, end = 14.dp),
        onClick = onClick
    )
}

/**
 * 可折叠的分组行：默认只占一行，右侧显示当前取值；点开才把选项就地展开。
 * 筛选类菜单有三四组选项，全摊开会长到半屏、和触发它的小圆按钮完全不协调。
 */
@Composable
fun MoReadMenuExpandableItem(
    text: String,
    valueText: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(180),
        label = "menu-section-chevron"
    )
    val contentColor = LocalContentColor.current
    DropdownMenuItem(
        modifier = modifier.heightIn(max = 46.dp),
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    maxLines = 1
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        },
        leadingIcon = icon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = chevronRotation }
            )
        },
        contentPadding = PaddingValues(horizontal = 14.dp),
        onClick = onToggle
    )
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(tween(180)) + fadeIn(tween(140)),
        exit = shrinkVertically(tween(160)) + fadeOut(tween(100))
    ) {
        Column(content = content)
    }
}

/** 菜单内小节标题：筛选类菜单要分「布局 / 状态 / 标签」几栏。 */
@Composable
fun MoReadMenuSection(
    label: String,
    modifier: Modifier = Modifier,
    tint: Color? = null
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)
    )
}

/** 菜单内分隔线：把破坏性操作与常规操作隔开。 */
@Composable
fun MoReadMenuDivider(color: Color? = null) {
    Box(Modifier.padding(vertical = 4.dp)) {
        HorizontalDivider(
            color = color ?: MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}

/** 菜单项之间的默认竖直留白，供需要自绘内容的菜单对齐用。 */
val MoReadMenuItemPadding: PaddingValues = MenuDefaults.DropdownMenuItemContentPadding
