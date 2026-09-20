package com.mozhi.reader.feature.reader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.dictionary.*
import com.mozhi.reader.ui.components.NavigationSheet
import com.mozhi.reader.ui.components.blockSheetDrag

/** Selection lookup is available for every book, regardless of English reading-assistance settings. */
@Composable
internal fun DictionaryLookupDialog(bookId: Long, initialHit: DictionaryLookupHit, settings: ReaderSettings,
    palette: ReaderPalette, onDismiss: () -> Unit, viewModel: EnglishLearningViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var manage by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialHit) { viewModel.lookup(initialHit) }
    DisposableEffect(Unit) { onDispose { viewModel.cancelLookup() } }
    DictionaryLookupSheet(state, palette, settings.vocabulary, onLookup = viewModel::lookup,
        onAi = viewModel::aiLookup, onSave = { id, ai -> viewModel.saveWord(bookId, id, ai) },
        onManage = { manage = true }, onDismiss = onDismiss) { entry, modifier ->
        DictionaryWebContent(entry, palette.isDark, viewModel.dictionaries,
            onLookup = { viewModel.lookup((state.hit ?: initialHit).copy(word = it)) }, modifier = modifier)
    }
    if (manage) DictionaryManagerDialog(onDismiss = {
        manage = false
        viewModel.lookup(state.hit ?: initialHit)
    }, viewModel = viewModel)
}

@Composable
internal fun DictionaryLookupSheet(state: EnglishLearningState, palette: ReaderPalette, vocabulary: List<VocabularyWord>,
    onLookup: (DictionaryLookupHit) -> Unit, onAi: () -> Unit, onSave: (String?, Boolean) -> Unit,
    onManage: () -> Unit, onDismiss: () -> Unit,
    entryContent: @Composable (DictionaryDefinition, Modifier) -> Unit) {
    val hit = state.hit
    var source by rememberSaveable(hit?.word) { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable(hit?.word) { mutableStateOf(hit?.word.orEmpty()) }
    val available = state.dictionaries.filter { it.enabled }
    val selected = source?.takeIf { it == "ai" || available.any { dictionary -> dictionary.id == it } }
        ?: state.definitions.firstOrNull()?.dictionaryId ?: available.firstOrNull()?.id
    val entry = state.definitions.firstOrNull { it.dictionaryId == selected }
    val states = rememberSaveableStateHolder()
    // A new query needs fresh anchors; switching dictionaries for the same query retains each anchor.
    key(hit?.word) {
        NavigationSheet(onDismiss, palette.glassStrong, palette.onBackground, palette.scrim,
            contentHeightFraction = 0.76f) {
            Column(Modifier.fillMaxSize().imePadding().testTag("dictionary-lookup")) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(hit?.word ?: "词典", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { editing = !editing }) { Text("改词") }
                    TextButton(onClick = onManage) { Text("管理") }
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭词典") }
                }
                if (editing) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it.take(80) }, label = { Text("字词或短语") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { hit?.let { onLookup(it.copy(word = query)); editing = false } }, enabled = dictionaryQuery(query) != null) { Text("查询") }
                }
                val tabScroll = rememberScrollState()
                Row(Modifier.fillMaxWidth().blockSheetDrag(tabScroll).horizontalScroll(tabScroll).padding(horizontal = 16.dp)
                    .testTag("dictionary-sources"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    available.forEach { dictionary ->
                        FilterChip(selected = selected == dictionary.id, onClick = { source = dictionary.id },
                            label = { Text(dictionary.title, maxLines = 1) })
                    }
                    FilterChip(selected = selected == "ai", onClick = {
                        source = "ai"
                        if (state.aiDefinition == null && !state.aiBusy) onAi()
                    }, label = { Text("AI 词典") })
                }
                // Fixed progress slot prevents the definition viewport jumping when background work finishes.
                Box(Modifier.fillMaxWidth().height(4.dp)) {
                    if (state.lookingUp || (selected == "ai" && state.aiBusy)) LinearProgressIndicator(Modifier.fillMaxSize(), color = palette.accent)
                }
                state.message?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, maxLines = 3) }
                Box(Modifier.weight(1f).fillMaxWidth().testTag("dictionary-definition")) {
                    states.SaveableStateProvider("${hit?.word}:$selected") {
                        when {
                            selected == "ai" -> {
                                val scroll = rememberScrollState()
                                SelectionContainer(Modifier.fillMaxSize().blockSheetDrag(scroll).verticalScroll(scroll).padding(20.dp).testTag("dictionary-ai-content")) {
                                    if (!state.aiDefinition.isNullOrBlank()) AiRichText(state.aiDefinition, palette, parseSynchronously = true)
                                    else Text(if (state.aiBusy) "正在结合语境查询…" else "暂无 AI 释义，可点击重试。")
                                }
                            }
                            entry != null -> key(entry.dictionaryId, hit?.word) { entryContent(entry, Modifier.fillMaxSize()) }
                            else -> {
                                val scroll = rememberScrollState()
                                Column(Modifier.fillMaxSize().blockSheetDrag(scroll).verticalScroll(scroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(when {
                                        state.lookingUp -> "正在查询本地词典…"
                                        available.isEmpty() -> "尚未启用词典。可在设置 → 词典管理导入多本 MDX，支持英文、中文和文言文词典。"
                                        else -> "这本词典未收录「${hit?.word.orEmpty()}」，可切换词典或使用 AI 词典。"
                                    })
                                    if (hit?.context?.isNotBlank() == true) Text(hit.context, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = palette.glassBorder)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    val canSave = if (selected == "ai") !state.aiDefinition.isNullOrBlank() else entry != null
                    TextButton(onClick = { onSave(entry?.dictionaryId, selected == "ai") }, enabled = canSave) {
                        Text(if (vocabulary.any { it.word == EnglishWords.normalize(hit?.word.orEmpty()) }) "更新生词释义" else "加入生词本")
                    }
                    if (selected == "ai") TextButton(onClick = onAi, enabled = !state.aiBusy && hit != null && dictionaryQuery(hit.word) != null) { Text("重新查询") }
                }
            }
        }
    }
}
