package com.mozhi.reader.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.ai.knowledge.*
import com.mozhi.reader.core.database.entity.BookCharacterGuideEntity
import com.mozhi.reader.core.database.entity.BookTocEntryEntity
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.database.entity.ChapterKnowledgeEntity
import com.mozhi.reader.core.retrieval.ReadingScope
import com.mozhi.reader.ui.components.blockSheetDrag

internal data class ReaderKnowledgeActions(
    val preview: (Int) -> Unit = {}, val cancel: (Int) -> Unit = {}, val delete: (Int) -> Unit = {},
    val locate: (ChapterKnowledgeEntity, KnowledgeFact) -> Unit = { _, _ -> },
    val previewCharacters: () -> Unit = {}, val cancelCharacters: () -> Unit = {}, val deleteCharacters: () -> Unit = {},
    val locateCharacter: (BookCharacterGuideEntity, CharacterEvidence) -> Unit = { _, _ -> }
)

@Composable
internal fun ReaderKnowledgeContentsSheet(
    bookId: Long, contentRevision: Int, chapters: List<ChapterEntity>, tocEntries: List<BookTocEntryEntity>,
    currentChapterIndex: Int, palette: ReaderPalette, onChapterClick: (Int, String) -> Unit,
    onLocate: (Int, Int) -> Unit, onDismiss: () -> Unit, viewModel: ReaderKnowledgeViewModel = hiltViewModel()
) {
    val observed by viewModel.state.collectAsStateWithLifecycle()
    val state = observed.takeIf { it.bookId == bookId } ?: KnowledgeUiState(bookId)
    val locate by rememberUpdatedState(onLocate)
    LaunchedEffect(bookId, contentRevision) { viewModel.bind(bookId) }
    LaunchedEffect(viewModel) { viewModel.locateEvents.collect { (chapter, offset) -> locate(chapter, offset) } }
    ReaderKnowledgePages(state, chapters, tocEntries, currentChapterIndex, palette, onChapterClick, onDismiss,
        ReaderKnowledgeActions(viewModel::preview, viewModel::cancel, viewModel::delete, viewModel::locate,
            viewModel::previewCharacters, viewModel::cancelCharacters, viewModel::deleteCharacters, viewModel::locateCharacter))
    state.pending?.let { plan -> AlertDialog(
        onDismissRequest = viewModel::dismissPreview,
        title = { Text("生成章节大纲？") },
        text = { Text("${plan.source.chapterTitle}\n${if (plan.source.partial) "已读部分" else "完整章节"} · ${plan.source.text.length} 字\n模型：${plan.modelLabel}\n最多 ${plan.maximumRequests} 次调用，按服务商计费。\n\n大纲会保存在本机。可以同时生成其他章节，离开页面后仍会继续。") },
        confirmButton = { TextButton(onClick = viewModel::generate) { Text("生成大纲") } },
        dismissButton = { TextButton(onClick = viewModel::dismissPreview) { Text("取消") } }
    ) }
    state.pendingCharacters?.let { plan -> AlertDialog(
        onDismissRequest = viewModel::dismissPreview,
        title = { Text(if (plan.resuming) "继续提取全书人物？" else "提取全书人物？") },
        text = { Text("${plan.bookTitle}\n整本书 · ${plan.chapterCount} 章 · ${plan.sourceCharacters} 字\n模型：${plan.modelLabel}\n\n将逐章发送正文给模型，包含尚未阅读的内容，资料可能涉及后续情节。全程最多 ${plan.maximumRequests} 次调用，按服务商计费。${if (plan.resuming) "\n已保存 ${plan.completedParts} 段进度，核对通过后会直接复用。" else ""}\n\n可以随时停止，完成后整份保存。") },
        confirmButton = { TextButton(onClick = viewModel::generateCharacters) { Text(if (plan.resuming) "继续提取" else "提取全书") } },
        dismissButton = { TextButton(onClick = viewModel::dismissPreview) { Text("取消") } }
    ) }
}

/** The same page body is exercised inside the real NavigationSheet by gesture regression tests. */
@Composable
internal fun ReaderKnowledgePages(
    state: KnowledgeUiState, chapters: List<ChapterEntity>, tocEntries: List<BookTocEntryEntity>,
    currentChapterIndex: Int, palette: ReaderPalette, onChapterClick: (Int, String) -> Unit,
    onDismiss: () -> Unit, actions: ReaderKnowledgeActions = ReaderKnowledgeActions(), initialTab: Int = 0
) {
    var tab by rememberSaveable(state.bookId) { mutableIntStateOf(initialTab) }
    val pages = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it) } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ReaderKnowledgeTabs(tab, palette, onDismiss) { tab = it }
            Box(Modifier.weight(1f)) {
                pages.SaveableStateProvider("${state.bookId}:$tab") {
                    when (tab) {
                        0 -> ContentsSheet(chapters, tocEntries, currentChapterIndex, palette, onChapterClick)
                        1 -> ChapterKnowledgePanel(state, chapters, currentChapterIndex, palette, actions)
                        else -> BookCharactersPanel(state, chapters, palette, actions)
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
internal fun ReaderKnowledgeTabs(selectedTab: Int, palette: ReaderPalette, onDismiss: () -> Unit = {}, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(58.dp).padding(start = 16.dp, end = 8.dp)
        .selectableGroup().testTag("knowledge-tabs"), verticalAlignment = Alignment.CenterVertically) {
        listOf("目录", "大纲", "人物").forEachIndexed { index, label ->
            val selected = index == selectedTab
            Column(Modifier.weight(1f).selectable(selected, role = Role.Tab, onClick = { onSelect(index) })
                .padding(top = 12.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) palette.onBackground else palette.muted)
                Spacer(Modifier.height(9.dp))
                Box(Modifier.width(24.dp).height(3.dp).clip(CircleShape).background(if (selected) palette.accent else Color.Transparent))
            }
        }
        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭目录与资料", tint = palette.muted) }
    }
}

@Composable
private fun KnowledgeHeading(title: String, subtitle: String, palette: ReaderPalette, onInfo: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(78.dp).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = palette.onBackground)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = palette.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
        }
        IconButton(onClick = onInfo) { Icon(Icons.Outlined.Info, "书籍资料说明", tint = palette.muted, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
internal fun ChapterKnowledgePanel(
    state: KnowledgeUiState, chapters: List<ChapterEntity>, currentChapterIndex: Int,
    palette: ReaderPalette, actions: ReaderKnowledgeActions
) {
    val snapshot = state.snapshot
    val scope = snapshot.book?.let(ReadingScope::uptoProgress)
    val eligible = remember(chapters, scope) { chapters.filter { chapter ->
        chapter.charCount > 0 && scope != null && scope.allowsChapter(chapter.chapterIndex) &&
            (chapter.chapterIndex < scope.maxChapterIndex || scope.maxCharOffset > 0)
    } }
    val current = eligible.firstOrNull { it.chapterIndex == currentChapterIndex } ?: eligible.lastOrNull()
    val savedByChapter = remember(snapshot.chapters) { snapshot.chapters.associateBy { it.entry.chapterIndex } }
    var expandedChapters by rememberSaveable(state.bookId) { mutableStateOf(listOf(currentChapterIndex)) }
    var deleting by remember { mutableStateOf<Int?>(null) }
    var information by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        KnowledgeHeading("章节大纲", "已保存 ${snapshot.chapters.size} 章 · 展开回顾本章来龙去脉", palette) { information = true }
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            FilledTonalButton(onClick = { current?.let { actions.preview(it.chapterIndex) } },
                enabled = current != null && !state.loading && state.tasks[current.chapterIndex]?.active != true && state.previewingChapter == null,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = palette.accentContainer, contentColor = palette.accent)) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (current?.chapterIndex == currentChapterIndex) "生成当前章" else "生成已读章")
            }
            TextButton(onClick = {
                expandedChapters = if (expandedChapters.isEmpty()) eligible.map { it.chapterIndex } else emptyList()
            }) { Text(if (expandedChapters.isEmpty()) "全部展开" else "全部收起", color = palette.accent) }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()
            .blockSheetDrag(listState).testTag("outline-list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (eligible.isEmpty()) item(key = "empty") {
                KnowledgeEmpty("留住每一章的脉络", if (state.loading) "正在读取章节…" else "读过正文后，手动生成章节梗概，随时展开回顾。", palette)
            }
            items(eligible, key = { "outline-${it.chapterIndex}" }, contentType = { "outline" }) { chapter ->
                val saved = savedByChapter[chapter.chapterIndex]
                val expanded = chapter.chapterIndex in expandedChapters
                val task = state.tasks[chapter.chapterIndex]
                val busy = task?.active == true
                Column(Modifier.testTag("outline-${chapter.chapterIndex}")) {
                    Surface(onClick = {
                        expandedChapters = if (expanded) expandedChapters - chapter.chapterIndex else expandedChapters + chapter.chapterIndex
                    }, shape = RoundedCornerShape(16.dp), color = palette.glass, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.heightIn(min = 60.dp).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                                if (expanded) "收起章节大纲" else "展开章节大纲", tint = palette.muted, modifier = Modifier.size(20.dp))
                            Text(chapter.title, Modifier.weight(1f).padding(horizontal = 8.dp), color = palette.onBackground,
                                style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(when { busy -> "生成中"; task?.error != null -> "待重试"; saved != null -> "已保存"; else -> "待生成" },
                                color = if (busy || saved != null) palette.accent else palette.muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (expanded) Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(if (saved == null) "还没有这一章的大纲。" else if (saved.entry.sourceEnd == chapter.charCount)
                            "完整章节" else "已读部分 · 前 ${saved.entry.sourceEnd} 字",
                            color = palette.muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 10.dp))
                        if (saved != null) {
                            Text(saved.content.readableOutline, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 29.sp),
                                color = palette.onBackground)
                            var evidence by rememberSaveable(chapter.chapterIndex) { mutableStateOf(false) }
                            TextButton(onClick = { evidence = !evidence }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                                Text(if (evidence) "收起原文依据" else "原文依据 · ${saved.content.summary.size} 处", color = palette.muted)
                            }
                            if (evidence) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                saved.content.summary.forEach { fact ->
                                    Text(fact.text, color = palette.onBackground, style = MaterialTheme.typography.bodyMedium)
                                    Text("“${fact.quote}”", color = palette.muted, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 22.sp))
                                    TextButton(onClick = { actions.locate(saved.entry, fact) }, contentPadding = PaddingValues(0.dp)) {
                                        Text("核对原文", color = palette.accent)
                                    }
                                }
                            }
                        }
                        // Status shares a fixed action row, so progress updates never resize the item.
                        Row(Modifier.height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (busy) TextButton(onClick = { actions.cancel(chapter.chapterIndex) }) { Text("停止本章", color = palette.accent) }
                            else TextButton(onClick = { actions.preview(chapter.chapterIndex) }, enabled = state.previewingChapter != chapter.chapterIndex) {
                                Text(if (state.previewingChapter == chapter.chapterIndex) "准备中…" else if (saved == null) "生成概括" else "重新生成", color = palette.accent)
                            }
                            if (saved != null) TextButton(onClick = { deleting = chapter.chapterIndex }, enabled = !busy) { Text("删除", color = palette.muted) }
                            Text(task?.error ?: task?.progress.orEmpty(), modifier = Modifier.weight(1f).padding(start = 8.dp),
                                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                                color = if (task?.error != null) MaterialTheme.colorScheme.error else palette.muted)
                        }
                    }
                }
            }
            if (snapshot.staleChapters > 0) item(key = "stale") {
                Text("有 ${snapshot.staleChapters} 章大纲因正文或已读范围变化而隐藏，可重新生成。", color = palette.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (information) KnowledgeInfoDialog("章节大纲", "从已经读过的内容生成连贯梗概，长章会整合为完整脉络。\n\n每章独立生成和停止，可同时提交多章，最多同时请求两个模型任务，其余自动排队。离开页面后继续运行；退出应用后需重新生成未完成的章节。\n\n大纲保存在本机，原文依据可展开核对。修改正文或重置已读范围后，旧大纲会隐藏。", { information = false })
    deleting?.let { index -> ConfirmKnowledgeDelete("删除本章大纲？", "保存的大纲及其原文依据将被删除。", { deleting = null }) {
        deleting = null; actions.delete(index)
    } }
}

@Composable
internal fun BookCharactersPanel(state: KnowledgeUiState, chapters: List<ChapterEntity>, palette: ReaderPalette, actions: ReaderKnowledgeActions) {
    val saved = state.characters.saved
    val people = saved?.guide?.characters.orEmpty()
    var query by rememberSaveable(state.bookId) { mutableStateOf("") }
    var information by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val task = state.characterTask
    val busy = task?.active == true
    val listState = rememberLazyListState()
    val filtered = remember(people, query) { people.filter { it.name.contains(query.trim(), ignoreCase = true) } }
    val titles = remember(chapters) { chapters.associate { it.chapterIndex to it.title } }
    Column(Modifier.fillMaxSize()) {
        KnowledgeHeading("书中人物", if (saved == null) "扫描整本书，汇集人物与关系" else
            "${people.size} 位人物 · 已扫描全书 ${saved.guide.scannedChapters} 章", palette) { information = true }
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = if (busy) actions.cancelCharacters else actions.previewCharacters,
                enabled = !state.loading && !state.preparingCharacters,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = palette.accentContainer, contentColor = palette.accent)) {
                Icon(if (busy) Icons.Outlined.Stop else Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(when { busy -> "停止提取"; state.preparingCharacters -> "准备中…"; state.characters.checkpointParts > 0 -> "继续提取";
                    saved != null -> "更新全书人物"; else -> "提取全书人物" })
            }
            Spacer(Modifier.weight(1f))
            if (saved != null || state.characters.checkpointParts > 0) IconButton(onClick = { deleting = true }, enabled = !busy) {
                Icon(Icons.Outlined.DeleteOutline, "删除人物资料", tint = palette.muted, modifier = Modifier.size(20.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
            Text(task?.error ?: when {
                busy -> task.progress + " · 可离开此页"
                state.characters.checkpointParts > 0 -> "已保存 ${state.characters.checkpointParts} 段进度，可继续提取"
                state.characters.outdated -> "正文已变化，请重新提取"
                else -> "覆盖未读章节 · 资料可能涉及后续情节"
            }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = if (task?.error != null) MaterialTheme.colorScheme.error else palette.muted)
        }
        OutlinedTextField(query, { query = it }, placeholder = { Text("查找书中人物") }, singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null, tint = palette.muted) },
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = palette.onBackground, unfocusedTextColor = palette.onBackground,
                focusedBorderColor = palette.accent, unfocusedBorderColor = palette.glassBorder,
                focusedPlaceholderColor = palette.muted, unfocusedPlaceholderColor = palette.muted),
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().blockSheetDrag(listState).testTag("characters-list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (filtered.isEmpty()) item(key = "empty") {
                KnowledgeEmpty(if (query.isNotBlank()) "没有找到这个人物" else if (saved != null) "本书未提取到人物" else "人物，一处看清",
                    if (query.isNotBlank()) "试试原文中的姓名或称呼。" else if (saved != null) "已扫描整本书，没有发现可核对的人物资料。" else
                        "手动提取整本书的人物、身份与关系。完成后随时查看，也能回到原文。", palette)
            }
            items(filtered, key = { it.name }, contentType = { "character" }) { person ->
                var expanded by rememberSaveable(person.name) { mutableStateOf(false) }
                Surface(shape = RoundedCornerShape(18.dp), color = palette.glass,
                    modifier = Modifier.fillMaxWidth().testTag("person-${person.name}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(Modifier.size(36.dp), shape = CircleShape, color = palette.accentContainer) {
                                Box(contentAlignment = Alignment.Center) { Text(person.name.take(1), color = palette.accent, style = MaterialTheme.typography.titleSmall) }
                            }
                            Text(person.name, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold, color = palette.onBackground)
                            Text("${person.evidence.map { it.chapterIndex }.distinct().size} 章", color = palette.muted, style = MaterialTheme.typography.labelSmall)
                        }
                        (if (expanded) person.evidence else person.evidence.take(1)).forEach { evidence ->
                            key(evidence.chapterIndex, evidence.fact.start, evidence.fact.text) {
                                KnowledgeFactRow(evidence.fact, titles[evidence.chapterIndex] ?: "第 ${evidence.chapterIndex + 1} 章", palette) {
                                    saved?.let { actions.locateCharacter(it.entry, evidence) }
                                }
                            }
                        }
                        if (person.evidence.size > 1) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Text(if (expanded) "收起人物资料" else "展开 ${person.evidence.size - 1} 条资料", color = palette.accent)
                        }
                    }
                }
            }
        }
    }
    if (information) KnowledgeInfoDialog("全书人物", "由 AI 逐章扫描整本书，包括还没读到的章节。资料可能涉及后续情节。\n\n每条资料保留原文依据；仅合并相同姓名，不擅自猜测别名，以免认错人物。长篇中优先保留人物最初的介绍和后续的重要事实。\n\n随时停止会保留已核对的进度，再次提取时可继续。更新期间仍能查看上次完成的资料。", { information = false })
    if (deleting) ConfirmKnowledgeDelete("删除人物资料？", "删除这本书的人物资料及未完成的提取进度。", { deleting = false }) {
        deleting = false; actions.deleteCharacters()
    }
}

@Composable
private fun KnowledgeEmpty(title: String, description: String, palette: ReaderPalette) {
    Column(Modifier.fillMaxWidth().padding(vertical = 30.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.AutoStories, null, tint = palette.muted, modifier = Modifier.size(32.dp))
        Text(title, color = palette.onBackground, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(description, color = palette.muted, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 23.sp),
            modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun KnowledgeInfoDialog(title: String, description: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(description) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } })
}

@Composable
private fun ConfirmKnowledgeDelete(title: String, description: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(description) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun KnowledgeFactRow(fact: KnowledgeFact, chapterTitle: String, palette: ReaderPalette, onLocate: () -> Unit) {
    var expanded by rememberSaveable(fact.start, fact.quote) { mutableStateOf(false) }
    Column {
        Text(fact.text, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 27.sp), color = palette.onBackground)
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(if (expanded) "收起依据" else "原文依据", color = palette.muted, style = MaterialTheme.typography.labelSmall)
        }
        if (expanded) Column(Modifier.padding(start = 10.dp).fillMaxWidth()) {
            Text("“${fact.quote}”", style = MaterialTheme.typography.bodySmall.copy(lineHeight = 22.sp), color = palette.muted)
            TextButton(onClick = onLocate, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text("$chapterTitle · 核对原文", color = palette.accent)
            }
        }
    }
}
