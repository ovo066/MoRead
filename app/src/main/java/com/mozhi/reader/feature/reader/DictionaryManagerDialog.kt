package com.mozhi.reader.feature.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.core.dictionary.LocalDictionary
import com.mozhi.reader.ui.components.*

@Composable
internal fun DictionaryManagerDialog(onDismiss: () -> Unit, viewModel: EnglishLearningViewModel = hiltViewModel()) {
    ReaderToolDialog(onDismiss) { DictionaryManagerPage(onDismiss, viewModel) }
}

@Composable
internal fun DictionaryManagerPage(onBack: () -> Unit, viewModel: EnglishLearningViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var resourceTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<LocalDictionary?>(null) }
    val importMdx = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.importMdx(uris)
    }
    val importMdd = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        resourceTarget?.let { if (uris.isNotEmpty()) viewModel.importMdd(it, uris) }
    }
    LaunchedEffect(Unit) { viewModel.refresh() }
    ReaderToolPage(title = "词典管理", onBack = onBack) {
        item {
            MoReadSection(title = "本地词典", footer = "相同文件内容自动去重；不同版本可同时保留。阅读时长按划线，选择「词典」查阅。") {
                MoReadRow(title = if (state.importing) "正在导入…" else "导入 MDX 词典（可多选）",
                    onClick = { if (!state.importing) importMdx.launch(arrayOf("*/*")) })
            }
        }
        state.message?.let { message -> item { MoReadBlock { Text(message, style = MaterialTheme.typography.bodySmall) } } }
        items(state.dictionaries, key = { it.id }) { dictionary ->
            MoReadSection(title = dictionary.title) {
                MoReadSwitchRow(title = "参与查词", subtitle = if (dictionary.enabled) "划线查词时可切换到此词典" else "保留文件，暂停查询",
                    checked = dictionary.enabled, onCheckedChange = { viewModel.setDictionaryEnabled(dictionary.id, it) })
                MoReadRowDivider()
                MoReadRow(title = "添加 MDD 资源包", subtitle = "已导入 ${dictionary.resourceCount} 个 · 图片、样式、字体",
                    onClick = { if (!state.importing) { resourceTarget = dictionary.id; importMdd.launch(arrayOf("*/*")) } })
                MoReadRowDivider()
                MoReadRow(title = "删除词典", onClick = { if (!state.importing) deleting = dictionary })
            }
        }
        item { MoReadBlock { Text("支持 MDX / MDD v1、v2；暂不支持授权加密词典。词典及资源保存在本机，无需联网。", style = MaterialTheme.typography.bodySmall) } }
    }
    deleting?.let { dictionary ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除 ${dictionary.title}？") },
            text = { Text("删除应用内的这本词典和配套资源包；原始文件、其他词典与生词本保留。") },
            confirmButton = { TextButton(onClick = { viewModel.deleteDictionary(dictionary.id); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}
