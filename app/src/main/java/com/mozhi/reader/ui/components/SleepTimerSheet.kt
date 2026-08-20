package com.mozhi.reader.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.speech.SleepTimerPlan
import com.mozhi.reader.core.speech.SleepTimerPlanner
import com.mozhi.reader.core.speech.SleepTimerState

private enum class TimerMode { TIME, CHAPTER }

private data class TimerChoice(val label: String, val plan: SleepTimerPlan)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    current: SleepTimerState?,
    onDismiss: () -> Unit,
    onSelect: (SleepTimerPlan?) -> Unit
) {
    var customMode by remember { mutableStateOf<TimerMode?>(null) }
    var mode by remember(current?.plan) {
        mutableStateOf(
            if (current?.plan is SleepTimerPlan.Chapters || current?.plan == SleepTimerPlan.EndOfChapter) {
                TimerMode.CHAPTER
            } else {
                TimerMode.TIME
            }
        )
    }
    val timeChoices = remember {
        listOf(15, 30, 45, 60, 90).map { TimerChoice("$it 分钟", SleepTimerPlan.Minutes(it)) }
    }
    val chapterChoices = remember {
        listOf(
            TimerChoice("本章结束", SleepTimerPlan.EndOfChapter),
            TimerChoice("1 章", SleepTimerPlan.Chapters(1)),
            TimerChoice("2 章", SleepTimerPlan.Chapters(2)),
            TimerChoice("3 章", SleepTimerPlan.Chapters(3)),
            TimerChoice("5 章", SleepTimerPlan.Chapters(5))
        )
    }

    fun select(plan: SleepTimerPlan?) {
        onSelect(plan)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "睡眠定时",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "时间和章节是两套独立规则，只展开当前选择的一组。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TimerStatusCard(
                current = current,
                onTurnOff = { select(null) }
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                TimerMode.entries.forEachIndexed { index, item ->
                    SegmentedButton(
                        selected = mode == item,
                        onClick = { mode = item },
                        shape = SegmentedButtonDefaults.itemShape(index, TimerMode.entries.size),
                        icon = {
                            Icon(
                                imageVector = if (item == TimerMode.TIME) {
                                    Icons.Outlined.Schedule
                                } else {
                                    Icons.AutoMirrored.Outlined.MenuBook
                                },
                                contentDescription = null,
                                modifier = Modifier.size(SegmentedButtonDefaults.IconSize)
                            )
                        },
                        label = { Text(if (item == TimerMode.TIME) "按时间" else "按章节") }
                    )
                }
            }

            TimerChoiceSection(
                title = if (mode == TimerMode.TIME) "朗读时长" else "读完再停",
                description = if (mode == TimerMode.TIME) {
                    "只在正在播放时倒计时，暂停后计时也会暂停。"
                } else {
                    "按章节自然结束计数，不会在一章中间突然停下。"
                },
                choices = if (mode == TimerMode.TIME) timeChoices else chapterChoices,
                currentPlan = current?.plan,
                customLabel = if (mode == TimerMode.TIME) "自定义分钟" else "自定义章节",
                onSelect = ::select,
                onCustom = { customMode = mode }
            )
        }
    }

    customMode?.let { selectedMode ->
        CustomTimerDialog(
            title = if (selectedMode == TimerMode.TIME) "自定义时长" else "自定义章节数",
            suffix = if (selectedMode == TimerMode.TIME) "分钟" else "章",
            onDismiss = { customMode = null },
            onConfirm = { value ->
                customMode = null
                select(
                    if (selectedMode == TimerMode.TIME) SleepTimerPlan.Minutes(value)
                    else SleepTimerPlan.Chapters(value)
                )
            }
        )
    }
}

@Composable
private fun TimerStatusCard(current: SleepTimerState?, onTurnOff: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (current == null) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        contentColor = if (current == null) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (current == null) Icons.Outlined.TimerOff else Icons.Outlined.Schedule,
                contentDescription = null
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (current == null) "当前未开启" else "定时进行中",
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    text = current?.let(SleepTimerPlanner::label) ?: "听书会一直播放，直到手动停止。",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (current != null) {
                TextButton(onClick = onTurnOff) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun TimerChoiceSection(
    title: String,
    description: String,
    choices: List<TimerChoice>,
    currentPlan: SleepTimerPlan?,
    customLabel: String,
    onSelect: (SleepTimerPlan) -> Unit,
    onCustom: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            choices.forEach { choice ->
                FilterChip(
                    selected = choice.plan == currentPlan,
                    onClick = { onSelect(choice.plan) },
                    label = { Text(choice.label) }
                )
            }
            FilterChip(
                selected = currentPlan.isCustomChoice(choices),
                onClick = onCustom,
                label = { Text(customLabel) }
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}

private fun SleepTimerPlan?.isCustomChoice(choices: List<TimerChoice>): Boolean =
    this != null && choices.none { it.plan == this }

@Composable
private fun CustomTimerDialog(
    title: String,
    suffix: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var raw by remember { mutableStateOf("") }
    val value = raw.toIntOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = raw,
                onValueChange = { raw = it.filter(Char::isDigit).take(4) },
                label = { Text(suffix) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { value?.let(onConfirm) }, enabled = value != null) { Text("开始") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
