package com.mozhi.reader.feature.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.core.dictionary.*
import com.mozhi.reader.ui.components.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VocabularyDialog(bookId: Long = 0, palette: ReaderPalette = companionChatPalette(),
    onDismiss: () -> Unit, viewModel: EnglishLearningViewModel = hiltViewModel()) {
    ReaderToolDialog(onDismiss) { VocabularyPage(bookId, palette, onDismiss, viewModel) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VocabularyPage(bookId: Long = 0, palette: ReaderPalette = companionChatPalette(),
    onBack: () -> Unit, viewModel: EnglishLearningViewModel = hiltViewModel(), panelBack: Boolean = false) {
    val settings by viewModel.readerSettings.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<VocabularyWord?>(null) }
    var lookup by remember { mutableStateOf<VocabularyWord?>(null) }
    val listState = rememberLazyListState()
    val searchFloating by remember { derivedStateOf { listState.firstVisibleItemIndex >= 1 } }
    ReaderToolPage(title = "生词本", onBack = onBack, listState = listState,
        modifier = Modifier.imePadding(), panelBack = panelBack) {
        stickyHeader(key = "vocabulary-search-header") {
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                MoReadSearchCapsule(filter, { filter = it }, floating = searchFloating,
                    placeholder = "搜索字词或释义", testTagPrefix = "vocabulary")
            }
        }
        val words = settings.vocabulary.filter { it.word.contains(filter.trim(), true) || it.definition.contains(filter.trim(), true) }.sortedByDescending { it.createdAt }
        if (words.isEmpty()) item {
            MoReadBlock { Text(if (filter.isNotBlank()) "没有匹配「${filter.trim()}」的生词"
                else "阅读时长按划线 → 词典 → 加入生词本。支持英文、中文和文言文词条。") }
        }
        items(words, key = { it.word }) { word ->
            MoReadSection(title = word.word) {
                MoReadRow(title = word.definition.take(180).ifBlank { "查看释义" }, subtitle = word.context.take(140).ifBlank { null }, onClick = { lookup = word })
                MoReadRowDivider()
                MoReadRow(title = "简短释义与读音", subtitle = listOf(word.gloss, word.phonetic).filter(String::isNotBlank).joinToString(" · ").ifBlank { "编辑标注" }, onClick = { editing = word })
                MoReadRowDivider()
                MoReadSwitchRow(title = "已掌握", subtitle = "掌握后不再显示英文生词标注", checked = word.learned,
                    onCheckedChange = { viewModel.updateWord(word.copy(learned = it)) })
                MoReadRow(title = "移出生词本", onClick = { viewModel.updateWord(word, remove = true) })
            }
        }
    }
    editing?.let { word ->
        var gloss by rememberSaveable(word.word) { mutableStateOf(word.gloss) }
        var phonetic by rememberSaveable(word.word) { mutableStateOf(word.phonetic) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("${word.word} 的标注") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(gloss, { gloss = it.take(24) }, label = { Text("简短释义") }, singleLine = true)
                OutlinedTextField(phonetic, { phonetic = it.take(64) }, label = { Text("读音 / 音标（可选）") }, singleLine = true)
            }
        }, confirmButton = { TextButton(onClick = { viewModel.updateWord(word.copy(gloss = gloss.trim(), phonetic = phonetic.trim())); editing = null }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } })
    }
    lookup?.let { word -> DictionaryLookupDialog(word.bookId.takeIf { it != 0L } ?: bookId,
        DictionaryLookupHit(word.word, word.context, word.chapterIndex, word.offset), settings, palette,
        onDismiss = { lookup = null }, viewModel = viewModel) }
}
