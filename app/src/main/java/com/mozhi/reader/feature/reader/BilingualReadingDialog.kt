package com.mozhi.reader.feature.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.dictionary.ParagraphTranslation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.mozhi.reader.ui.MoReadWindowWidth
import com.mozhi.reader.ui.rememberMoReadWindowWidth
import com.mozhi.reader.ui.components.blockSheetDrag

@Composable
internal fun BilingualReadingDialog(
    visible: Boolean, state: ReaderTranslationState, onVisible: (Boolean) -> Unit,
    onPage: (Boolean) -> Unit, onChapter: (Boolean) -> Unit, onStop: () -> Unit, onDismiss: () -> Unit,
    viewModel: TranslationModelViewModel = hiltViewModel()
) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var replaceCached by rememberSaveable { mutableStateOf(false) }
    ReaderTranslationDialog("中英对照", onDismiss, if (state.busy) "返回阅读" else "完成") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("在英文段落下显示译文", Modifier.weight(1f))
            Switch(checked = visible, onCheckedChange = onVisible)
        }
        Text("隐藏后保留缓存，开关仅作用于本书。", style = MaterialTheme.typography.bodySmall)
        TranslationModelSelector(enabled = !state.busy, viewModel = viewModel)
        OutlinedButton(onClick = { onPage(replaceCached) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("翻译当前页") }
        OutlinedButton(onClick = { onChapter(replaceCached) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("翻译当前章") }
        Text("已缓存的段落直接复用。长按译文，或长按英文 → 本段对照，可重翻译、删除或单独显示／隐藏一段。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起更多选项" else "更多选项") }
        if (advanced) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(replaceCached, { replaceCached = it }, enabled = !state.busy)
                Text("重新翻译已有段落", style = MaterialTheme.typography.bodyMedium)
            }
            Text("再次翻译会使用当前模型，完成后替换原译文并产生新的调用费用。", style = MaterialTheme.typography.bodySmall)
        }
        // Reserve status space so progress and completion do not move the scroll anchor.
        Column(Modifier.fillMaxWidth().height(96.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.busy) {
                LinearProgressIndicator(progress = { state.done.toFloat() / state.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${state.done} / ${state.total} 段", Modifier.weight(1f))
                    TextButton(onClick = onStop) { Text("停止") }
                }
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        }
    }
}

@Composable
internal fun ParagraphTranslationActionsDialog(
    translation: ParagraphTranslation,
    visible: Boolean,
    busy: Boolean,
    onRetranslate: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
    onDismiss: () -> Unit
) {
    ReaderTranslationDialog("本段译文", onDismiss) {
        Text(translation.chinese)
        OutlinedButton(onClick = onRetranslate, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("重新翻译本段") }
        OutlinedButton(onClick = onToggle, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (translation.hidden || !visible) "显示本段译文" else "隐藏本段译文")
        }
        OutlinedButton(onClick = onDelete, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("删除本段译文") }
        Text("重新翻译使用当前翻译模型，成功后替换旧译文。删除仅移除本段译文缓存。", style = MaterialTheme.typography.bodySmall)
        if (busy) Text("正在处理译文，完成后可操作。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ReaderTranslationDialog(title: String, onDismiss: () -> Unit, confirmLabel: String = "关闭",
    content: @Composable ColumnScope.() -> Unit) {
    if (rememberMoReadWindowWidth() == MoReadWindowWidth.EXPANDED) {
        ReaderToolDialog(onDismiss) {
            ReaderToolPage(title, onDismiss) {
                item { Column(verticalArrangement = Arrangement.spacedBy(16.dp), content = content) }
              }
        }
    } else {
        val scroll = rememberScrollState()
        AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
            Column(Modifier.blockSheetDrag(scroll).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(confirmLabel) } })
    }
}
