package com.mozhi.reader.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ui.theme.MoReadRadius
import com.mozhi.reader.ui.theme.MoReadTokens
import com.mozhi.reader.ui.theme.sectionCardColor
import com.mozhi.reader.ui.theme.sectionHairline

/**
 * 分组卡：小节抬头 + 一张素面卡把同类设置收在一起。
 *
 * **为什么不是玻璃卡**：`FrostedSurface` 是半透明 + 高光边 + 投影，本意是「浮在内容之上」。
 * 一屏里出现五六张，每张都在发光，层级信息就被抵消了 —— 观感变糊、变廉价。素面卡靠
 * 「不透明底 + 一条发丝描边 + 组间大留白」建立层级，真玻璃只留给真正的浮层
 * （导航舱、阅读页 dock、悬浮排版卡、弹层、悬浮球）。
 *
 * [footer] 是卡片下方的说明文字，用于「这一组为什么默认关」这类需要一段话解释的场合，
 * 比塞进某一行的副标题更合适。
 */
@Composable
fun MoReadSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = if (icon != null) 7.dp else 0.dp)
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MoReadRadius.CardShape)
                .background(sectionCardColor())
                .border(1.dp, sectionHairline(), MoReadRadius.CardShape),
            content = content
        )
        if (!footer.isNullOrBlank()) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 9.dp)
            )
        }
    }
}

/** 组内行间分隔线；缩进到与文字左缘对齐，让图标列成为一条连续的视觉轴。 */
@Composable
fun MoReadRowDivider(inset: androidx.compose.ui.unit.Dp = MoReadTokens.RowDividerInset) {
    HorizontalDivider(
        modifier = Modifier.padding(start = inset),
        color = sectionHairline()
    )
}

/**
 * 组内的标准行：圆角色底图标 + 标题 + 说明 + 尾部插槽。
 * [onClick] 非空且未给 [trailing] 时，尾部自动补一枚 chevron。
 */
@Composable
fun MoReadRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .defaultMinSize(minHeight = MoReadTokens.RowMinHeight)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(MoReadTokens.IconTile)
                    .clip(MoReadRadius.FieldShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MoReadTokens.IconGlyph)
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (icon != null) 14.dp else 0.dp, end = 10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 导航行：语义上等于「点进去还有一层」，尾部固定 chevron。 */
@Composable
fun MoReadNavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null
) = MoReadRow(
    title = title,
    modifier = modifier,
    icon = icon,
    subtitle = subtitle,
    onClick = onClick
)

/** 只读数值行：右侧显示当前值，不可点。 */
@Composable
fun MoReadValueRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null
) = MoReadRow(
    title = title,
    modifier = modifier,
    icon = icon,
    subtitle = subtitle,
    trailing = {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
)

/**
 * 开关行：与 [MoReadRow] 同一套版式，尾部换成 Switch，整行可点。
 *
 * [enabled] 为 false 时整行变淡且不可点 —— 用于「总开关关掉后子开关不生效」这类从属关系；
 * 此时子开关的值原样显示，重新打开总开关即恢复，不擅自改写用户的选择。
 */
@Composable
fun MoReadSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    val content = @Composable {
        MoReadRow(
            title = title,
            modifier = modifier,
            icon = icon,
            subtitle = subtitle,
            onClick = if (enabled) {
                { onCheckedChange(!checked) }
            } else {
                null
            },
            trailing = {
                Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            }
        )
    }
    if (enabled) {
        content()
    } else {
        CompositionLocalProvider(
            LocalContentColor provides LocalContentColor.current.copy(alpha = 0.38f),
            content = content
        )
    }
}

/**
 * 组内的整宽内容块（分段选择、色板、进度条这类）：不带图标底与 chevron，
 * 但保持与 [MoReadRow] 一致的左右留白，卡内左缘不会参差。
 */
@Composable
fun MoReadBlock(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (title != null) {
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
        content()
    }
}

/**
 * 展开行 + 就地展开的内容。
 *
 * 角色编辑页原来的写法是：抬头是一条**裸行**浮在渐变背景上，展开后的内容却是另一张卡，
 * 中间还隔着 14dp —— 抬头看着不属于它的内容。这里把两者放进同一张 [MoReadSection]，
 * 展开动画也发生在卡内，卡片自己长高。
 *
 * [summary] 是收起态下的当前值摘要，让用户不展开也知道里面配了什么。
 *
 * [animated] 为 false 时不做展开动画，直接出现。展开动画会逐帧重新测量整个 body——
 * body 里有几十条（比如 ST 卡带上百条世界书）时，每一帧都要量一遍，必掉帧。
 * 内容条数不定的分区请按条数关掉动画。
 */
@Composable
fun MoReadDisclosureRow(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(180),
        label = "disclosure-chevron"
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .defaultMinSize(minHeight = MoReadTokens.RowMinHeight)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            trailing?.invoke(this)
            Icon(
                // 一枚箭头转 180°，而不是换成另一个图标：展开/收起是同一件事的两个状态。
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起$title" else "展开$title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .rotate(rotation)
            )
        }
        if (animated) {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(190)) + fadeIn(tween(150)),
                exit = shrinkVertically(tween(160)) + fadeOut(tween(100))
            ) {
                DisclosureBody(body)
            }
        } else if (expanded) {
            DisclosureBody(body)
        }
    }
}

@Composable
private fun DisclosureBody(body: @Composable ColumnScope.() -> Unit) {
    Column {
        MoReadRowDivider(inset = 16.dp)
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = body
        )
    }
}
