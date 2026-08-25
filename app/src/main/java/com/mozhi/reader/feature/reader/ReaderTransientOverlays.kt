package com.mozhi.reader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mozhi.reader.core.database.entity.AnnotationColors
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationStyle
import com.mozhi.reader.core.datastore.PendingReaderFont

@Composable
internal fun BoxScope.ReaderSelectionMediaStatus(
    state: SelectionMediaUiState,
    palette: ReaderPalette,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onStop: () -> Unit,
    onCancel: () -> Unit
) {
    val status = state.status ?: return
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = palette.glassStrong,
        contentColor = palette.onBackground,
        shadowElevation = 8.dp,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(start = 20.dp, end = 20.dp, bottom = bottomPadding)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.isWorking) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = palette.accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (state.isWorking) 9.dp else 0.dp)
            )
            TextButton(onClick = if (state.isPlaying) onStop else onCancel) {
                Text(if (state.isPlaying) "停止" else "取消", color = palette.accent)
            }
        }
    }
}

@Composable
internal fun BoxScope.ReaderAnnotationInkOverlay(
    annotation: AnnotationEntity?,
    topPx: Int?,
    palette: ReaderPalette,
    onDismiss: () -> Unit,
    onChange: (AnnotationStyle, String) -> Unit
) {
    if (topPx == null) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    )
    if (annotation != null) {
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, topPx) },
            shape = RoundedCornerShape(17.dp),
            color = palette.glassStrong,
            contentColor = palette.onBackground,
            border = BorderStroke(1.dp, palette.glassBorder),
            shadowElevation = 8.dp
        ) {
            AnnotationStylePanel(
                selectedStyle = AnnotationStyle.fromWire(annotation.style),
                selectedColor = AnnotationColors.normalize(annotation.colorTag),
                palette = palette,
                onChange = onChange
            )
        }
    }
}

@Composable
internal fun ReaderSpeechConfirmDialog(
    text: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    text ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("朗读当前页") },
        text = { Text("将朗读当前页正文（约 ${text.length} 字）。引擎与音色可在「设置 › 语音朗读」调整。") },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("开始朗读") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun ReaderGeneratedImageDialog(
    state: SelectionMediaUiState,
    palette: ReaderPalette,
    onDismiss: () -> Unit,
    onReroll: (String, String) -> Unit
) {
    val imagePath = state.imagePath ?: return
    var editablePrompt by remember(imagePath) { mutableStateOf(state.imagePrompt.orEmpty()) }
    var improvement by remember(imagePath) { mutableStateOf("") }
    val imageGeneration = state.imageGeneration
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选段插图") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AsyncImage(
                    model = imagePath,
                    contentDescription = "根据选段生成的插图",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)
                )
                if (imageGeneration != null) {
                    OutlinedTextField(
                        value = editablePrompt,
                        onValueChange = { editablePrompt = it.take(8_000) },
                        label = { Text("生图提示词（可编辑）") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = improvement,
                        onValueChange = { improvement = it.take(2_000) },
                        label = { Text("告诉 AI 如何改进（可选）") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    state.status?.let { status ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.isWorking) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    color = palette.accent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                status,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = if (state.isWorking) 8.dp else 0.dp)
                            )
                        }
                    }
                } else {
                    state.imagePrompt?.let { prompt ->
                        Text(
                            prompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Text(
                    "图片已保存到本机应用目录。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            if (imageGeneration != null) {
                Row {
                    TextButton(
                        enabled = !state.isWorking,
                        onClick = { onReroll(imageGeneration.basePrompt, "") }
                    ) { Text("直接重绘") }
                    TextButton(
                        enabled = !state.isWorking && editablePrompt.isNotBlank(),
                        onClick = {
                            onReroll(editablePrompt, improvement)
                            improvement = ""
                        }
                    ) { Text("按提示词重绘") }
                }
            } else {
                TextButton(onClick = onDismiss) { Text("完成") }
            }
        },
        dismissButton = {
            if (imageGeneration != null) {
                TextButton(onClick = onDismiss) { Text("完成") }
            }
        }
    )
}

@Composable
internal fun ReaderTextEditDialog(
    draft: TextEditDraft?,
    onDismiss: () -> Unit,
    onSave: (TextEditDraft, String) -> Unit
) {
    draft ?: return
    var editedText by remember(draft) { mutableStateOf(draft.originalText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑选中文本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "保存后会更新本书本地正文。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it.take(20_000) },
                    label = { Text("正文") },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = editedText != draft.originalText,
                onClick = { onSave(draft, editedText) }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun ReaderFontImportDialog(
    font: PendingReaderFont?,
    name: String,
    onNameChange: (String) -> Unit,
    onDismiss: (PendingReaderFont) -> Unit,
    onConfirm: (PendingReaderFont, String) -> Unit
) {
    font ?: return
    AlertDialog(
        onDismissRequest = { onDismiss(font) },
        title = { Text("确认导入字体") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "已从 ${font.originalFileName} 识别字体名称，可在保存前修改。",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { onNameChange(it.take(48)) },
                    label = { Text("字体名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(font, name) }) {
                Text("导入并应用")
            }
        },
        dismissButton = { TextButton(onClick = { onDismiss(font) }) { Text("取消") } }
    )
}
