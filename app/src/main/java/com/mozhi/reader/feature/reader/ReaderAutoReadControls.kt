package com.mozhi.reader.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.AutoReadSettings
import com.mozhi.reader.core.datastore.PageMode
import kotlin.math.roundToInt

/** Observe rather than consume: the same touch still selects text or opens the intended menu. */
internal fun Modifier.pauseAutoReadOnTouch(session: AutoReadSession): Modifier = pointerInput(session) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.any { it.changedToDownIgnoreConsumed() }) session.pause(AutoReadPauseReason.TOUCH)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderAutoReadSheet(
    initial: AutoReadSettings,
    speechActive: Boolean,
    companionPaneVisible: Boolean,
    palette: ReaderPalette,
    onDismiss: () -> Unit,
    onStart: (AutoReadSettings) -> Unit,
    onStop: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.glassStrong,
        contentColor = palette.onBackground,
        scrimColor = palette.scrim
    ) {
        AutoReadSettingsSheet(initial, speechActive, onStart, onStop, companionPaneVisible)
    }
}

@Composable
internal fun AutoReadSettingsSheet(
    initial: AutoReadSettings,
    speechActive: Boolean,
    onStart: (AutoReadSettings) -> Unit,
    onStop: () -> Unit,
    companionPaneVisible: Boolean = false
) {
    var draft by remember { mutableStateOf(initial.normalized()) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("自动阅读", style = MaterialTheme.typography.titleMedium)
            Text("触摸即暂停", style = MaterialTheme.typography.labelSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = draft.mode == PageMode.SCROLL, onClick = { draft = draft.copy(mode = PageMode.SCROLL) }, label = { Text("匀速滚动") })
            FilterChip(selected = draft.mode == PageMode.PAGINATED, onClick = { draft = draft.copy(mode = PageMode.PAGINATED) }, label = { Text("定时翻页") })
        }
        if (draft.mode == PageMode.SCROLL) {
            Text("滚动速度 · ${String.format(java.util.Locale.ROOT, "%.1f", draft.scrollDpPerSecond / 24f)}×")
            Slider(value = draft.scrollDpPerSecond, onValueChange = { draft = draft.copy(scrollDpPerSecond = it) }, valueRange = 8f..96f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("慢", style = MaterialTheme.typography.labelSmall)
                Text("快", style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("导读线", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = draft.showGuide, onCheckedChange = { draft = draft.copy(showGuide = it) })
            }
        } else {
            Text("每 ${draft.pageIntervalSeconds} 秒翻一页")
            Slider(value = draft.pageIntervalSeconds.toFloat(), onValueChange = { draft = draft.copy(pageIntervalSeconds = it.roundToInt()) }, valueRange = 3f..120f)
        }
        if (speechActive) Text("请先暂停听书", color = MaterialTheme.colorScheme.error)
        if (companionPaneVisible) Text("请先收起伴读面板", color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onStop) { Text("关闭") }
            Button(enabled = !speechActive && !companionPaneVisible, onClick = { onStart(draft.normalized()) }) { Text("开始阅读") }
        }
    }
}

@Composable
internal fun AutoReadControls(
    session: AutoReadSession,
    palette: ReaderPalette,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = palette.glassStrong, contentColor = palette.onBackground) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (session.phase) {
                    AutoReadPhase.RUNNING -> "自动阅读中"
                    AutoReadPhase.PREPARING -> "正在准备…"
                    else -> session.reason?.label ?: "自动阅读已暂停"
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp)
            )
            TextButton(onClick = onConfigure) { Text(if (session.engaged) "暂停 / 设置" else "继续 / 设置", color = palette.accent) }
            TextButton(onClick = session::stop) { Text("退出", color = palette.muted) }
        }
    }
}

@Composable
internal fun AutoReadGuide(palette: ReaderPalette) {
    Canvas(Modifier.fillMaxSize()) {
        val y = size.height * 0.36f
        drawLine(palette.accent.copy(alpha = 0.5f), Offset(20.dp.toPx(), y), Offset(size.width - 20.dp.toPx(), y), 1.dp.toPx())
    }
}
