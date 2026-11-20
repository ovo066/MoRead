package com.mozhi.reader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mozhi.reader.R
import com.mozhi.reader.core.datastore.ReaderChromeItem
import com.mozhi.reader.core.datastore.ReaderChromeLayout
import com.mozhi.reader.core.datastore.ReaderChromeSlot
import com.mozhi.reader.ui.theme.MoReadSpacing

/**
 * 页眉或页脚的一条缩略：像页面边缘那样横排左、中、右三格，每格写着它现在放了什么，点一格去改。
 * 关掉页眉/页脚时整条变淡、不可点。
 */
@Composable
internal fun ReaderChromeSlotBar(
    layout: ReaderChromeLayout,
    header: Boolean,
    enabled: Boolean,
    palette: ReaderPalette,
    onEdit: (ReaderChromeSlot) -> Unit
) {
    val slots = if (header) {
        listOf(ReaderChromeSlot.HEADER_LEFT, ReaderChromeSlot.HEADER_CENTER, ReaderChromeSlot.HEADER_RIGHT)
    } else {
        listOf(ReaderChromeSlot.FOOTER_LEFT, ReaderChromeSlot.FOOTER_CENTER, ReaderChromeSlot.FOOTER_RIGHT)
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, palette.glassBorder),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).alpha(if (enabled) 1f else 0.45f)
            .testTag(if (header) "chrome-bar-header" else "chrome-bar-footer")
    ) {
        // 三格等高：按最高的一格撑开，分隔线跟着拉满。
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            slots.forEachIndexed { i, slot ->
                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 10.dp).background(palette.glassBorder))
                val items = layout.items(slot)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(enabled = enabled) { onEdit(slot) }
                        .padding(horizontal = MoReadSpacing.s, vertical = MoReadSpacing.xs)
                        .testTag("chrome-slot-${slot.name}"),
                    horizontalAlignment = when (i) {
                        0 -> Alignment.Start
                        1 -> Alignment.CenterHorizontally
                        else -> Alignment.End
                    },
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(chromePositionLabel(slot), style = MaterialTheme.typography.labelSmall, color = palette.muted)
                    val labels = items.map { chromeItemLabel(it) }
                    Text(
                        // 一项一行：一格只有三分之一宽，横排几项必然从词中间折断。
                        text = if (labels.isEmpty()) stringResource(R.string.reader_chrome_empty) else labels.joinToString("\n"),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (items.isEmpty()) palette.muted.copy(alpha = 0.6f) else palette.accent,
                        fontWeight = if (items.isEmpty()) FontWeight.Normal else FontWeight.Medium,
                        maxLines = ReaderChromeItem.entries.size,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = when (i) {
                            0 -> TextAlign.Start
                            1 -> TextAlign.Center
                            else -> TextAlign.End
                        }
                    )
                }
            }
        }
    }
}

/**
 * 一个槽位的多选：每项一行，勾上就放进这个位置；已经在别处的项标出它现在的位置，勾上会从那里挪过来。
 */
@Composable
internal fun ReaderChromeSlotDialog(
    layout: ReaderChromeLayout,
    slot: ReaderChromeSlot,
    onApply: (ReaderChromeLayout) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(slot, layout) { mutableStateOf(layout.items(slot).map { it.name }) }
    val owner = remember(layout) {
        ReaderChromeSlot.entries.flatMap { s -> layout.items(s).map { it to s } }.toMap()
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth().testTag("chrome-slot-dialog")
        ) {
            Column(Modifier.padding(vertical = MoReadSpacing.l)) {
                Text(
                    stringResource(R.string.reader_chrome_slot_title, chromeRowLabel(slot), chromePositionLabel(slot)),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = MoReadSpacing.s)
                )
                Text(
                    stringResource(R.string.reader_chrome_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = MoReadSpacing.s)
                )
                ReaderChromeItem.entries.forEach { item ->
                    val checked = item.name in draft
                    val elsewhere = owner[item]?.takeIf { it != slot }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { draft = if (checked) draft - item.name else draft + item.name }
                            .background(if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f) else Color.Transparent)
                            .padding(horizontal = 24.dp, vertical = 10.dp)
                            .testTag("chrome-item-${item.name}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                chromeItemLabel(item),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal
                            )
                            if (elsewhere != null) Text(
                                stringResource(R.string.reader_chrome_item_elsewhere,
                                    stringResource(R.string.reader_chrome_slot_title, chromeRowLabel(elsewhere), chromePositionLabel(elsewhere))),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (checked) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = MoReadSpacing.l), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        onApply(layout.with(slot, ReaderChromeItem.entries.filter { it.name in draft }))
                        onDismiss()
                    }, modifier = Modifier.testTag("chrome-slot-done")) {
                        Text(stringResource(R.string.reader_chrome_done))
                    }
                }
            }
        }
    }
}

@Composable
internal fun chromeItemLabel(item: ReaderChromeItem): String = stringResource(
    when (item) {
        ReaderChromeItem.BOOK_TITLE -> R.string.reader_chrome_item_book_title
        ReaderChromeItem.CHAPTER_TITLE -> R.string.reader_chrome_item_chapter_title
        ReaderChromeItem.CHAPTER_PAGE -> R.string.reader_chrome_item_chapter_page
        ReaderChromeItem.CHAPTER_PERCENT -> R.string.reader_chrome_item_chapter_percent
        ReaderChromeItem.BOOK_PERCENT -> R.string.reader_chrome_item_book_percent
        ReaderChromeItem.TIME -> R.string.reader_chrome_item_time
        ReaderChromeItem.BATTERY -> R.string.reader_chrome_item_battery
    }
)

@Composable
private fun chromeRowLabel(slot: ReaderChromeSlot): String =
    stringResource(if (slot.isHeader) R.string.reader_chrome_header else R.string.reader_chrome_footer)

@Composable
private fun chromePositionLabel(slot: ReaderChromeSlot): String = stringResource(
    when (slot) {
        ReaderChromeSlot.HEADER_LEFT, ReaderChromeSlot.FOOTER_LEFT -> R.string.reader_chrome_left
        ReaderChromeSlot.HEADER_CENTER, ReaderChromeSlot.FOOTER_CENTER -> R.string.reader_chrome_center
        ReaderChromeSlot.HEADER_RIGHT, ReaderChromeSlot.FOOTER_RIGHT -> R.string.reader_chrome_right
    }
)
