package com.mozhi.reader.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.core.datastore.GlobalPromptPreset
import com.mozhi.reader.ui.components.DashedAddRow
import com.mozhi.reader.ui.components.MoReadSecondaryPage

@Composable
fun AnnotationPromptSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<GlobalPromptPreset?>(null) }
    var reset by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.events.collect { snackbar.showSnackbar((it as SettingsEvent.ShowMessage).message) } }
    Box(Modifier.fillMaxSize()) {
        MoReadSecondaryPage(title = "主动段评提示词", onBack = onBack) {
            if (!state.isLoaded) return@MoReadSecondaryPage
            item {
                Text("只用于自动随读段评。可添加多条预设，分别开关、编辑并选择注入位置；按列表顺序注入。角色设定、原文定位和防剧透规则仍会生效。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.autonomy.annotationPrompts, key = GlobalPromptPreset::id) { preset ->
                PresetRow(preset, { viewModel.setAnnotationPromptEnabled(preset.id, it) },
                    { editing = preset }, { viewModel.deleteAnnotationPrompt(preset.id) })
            }
            item {
                DashedAddRow("添加段评预设", onClick = {
                    editing = GlobalPromptPreset("", "", "", enabled = true)
                })
            }
            item { TextButton(onClick = { reset = true }) { Text("恢复默认段评提示词") } }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(20.dp))
    }
    editing?.let { preset ->
        PresetEditorDialog(preset, { editing = null }, {
            viewModel.saveAnnotationPrompt(it)
            editing = null
        }, kindLabel = "段评预设")
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("恢复默认提示词？") },
        text = { Text("将用两条内置预设替换当前所有段评预设。") },
        confirmButton = { TextButton(onClick = { viewModel.resetAnnotationPrompts(); reset = false }) { Text("恢复默认") } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text("取消") } })
}
