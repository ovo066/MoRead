package com.mozhi.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mozhi.reader.ui.theme.MoReadSpacing

/** 单选弹窗里的一项：[badge] 是左侧的短文字徽标（如语言的 En / あ），[tint] 决定徽标底色。 */
data class MoReadChoice<T>(
    val value: T,
    val label: String,
    val supporting: String? = null,
    val badge: String? = null,
    val tint: Color? = null
)

/**
 * 通用单选弹窗：一列带徽标的选项，当前项打勾，点选即关闭。
 * 选项多于一屏时列表自身滚动，弹窗高度不随选项数无限增长。
 */
@Composable
fun <T> MoReadChoiceDialog(
    title: String,
    choices: List<MoReadChoice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = modifier.widthIn(max = 400.dp).fillMaxWidth().testTag("choice-dialog")
        ) {
            Column(Modifier.padding(vertical = MoReadSpacing.l)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = MoReadSpacing.s)
                )
                LazyColumn(Modifier.heightIn(max = 460.dp).padding(top = MoReadSpacing.s)) {
                    items(choices, key = { it.label }) { choice ->
                        val active = choice.value == selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(choice.value); onDismiss() }
                                .background(if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f) else Color.Transparent)
                                .padding(horizontal = 24.dp, vertical = 10.dp)
                                .testTag("choice-${choice.label}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            choice.badge?.let { MoReadTextBadge(it, choice.tint) }
                            Column(Modifier.weight(1f)) {
                                Text(choice.label, style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                                choice.supporting?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (active) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 圆角方块里一两个字符的徽标；不用 emoji 与国旗，语言与地区不是一一对应的。 */
@Composable
fun MoReadTextBadge(text: String, tint: Color? = null, size: Dp = 34.dp) {
    val color = tint ?: MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = .16f)),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = color, fontWeight = FontWeight.SemiBold,
            fontSize = if (text.length > 1) 13.sp else 16.sp, maxLines = 1)
    }
}

/** 设置行：左侧徽标、标题，右侧是当前值与箭头，点开选择弹窗。 */
@Composable
fun MoReadChoiceRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    tint: Color? = null,
    subtitle: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        badge?.let { MoReadTextBadge(it, tint) }
        Column(Modifier.weight(1f).padding(start = if (badge != null) 14.dp else 0.dp, end = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp).size(20.dp))
    }
}
