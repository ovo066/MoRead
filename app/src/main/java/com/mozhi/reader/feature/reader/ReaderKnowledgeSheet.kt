package com.mozhi.reader.feature.reader

import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
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
    val previewCharacters: (Boolean) -> Unit = {}, val cancelCharacters: () -> Unit = {}, val deleteCharacters: () -> Unit = {},
    val locateCharacter: (BookCharacterGuideEntity, CharacterEvidence) -> Unit = { _, _ -> },
    val saveCharacterCard: (ExtractedCharacterCard) -> Unit = {},
    val saveCharacter: (String?, String, String) -> Unit = { _, _, _ -> }
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
            viewModel::previewCharacters, viewModel::cancelCharacters, viewModel::deleteCharacters, viewModel::locateCharacter,
            viewModel::saveCharacterCard, viewModel::saveCharacter))
    state.pending?.let { plan -> AlertDialog(
        onDismissRequest = viewModel::dismissPreview,
        title = { Text("生成章节大纲？") },
        text = { Text("${plan.source.chapterTitle}\n${if (plan.source.partial) "已读部分" else "完整章节"} · ${plan.source.text.length} 字\n模型：${plan.modelLabel}\n最多 ${plan.maximumRequests} 次调用，按服务商计费。\n\n大纲会保存在本机。可以同时生成其他章节，离开页面后仍会继续。") },
        confirmButton = { TextButton(onClick = viewModel::generate) { Text("生成大纲") } },
        dismissButton = { TextButton(onClick = viewModel::dismissPreview) { Text("取消") } }
    ) }
    state.pendingCharacters?.let { plan -> AlertDialog(
        onDismissRequest = viewModel::dismissPreview,
        title = { Text(when {
            plan.resuming -> if (plan.progressBounded) "继续提取读过的人物？" else "继续提取全书人物？"
            plan.progressBounded -> "提取读过的人物？"
            else -> "提取全书人物？"
        }) },
        text = { Text(buildString {
            append(plan.bookTitle).append('\n')
            append(if (plan.progressBounded) "读到此处 · 前 ${plan.chapterCount} 章" else "整本书 · ${plan.chapterCount} 章")
            append(" · ${plan.sourceCharacters} 字\n模型：${plan.modelLabel}\n\n")
            append(if (plan.progressBounded) {
                "只发送已经读过的正文，最后一章截到当前进度，资料不会剧透后续情节。"
            } else {
                "将逐章发送正文给模型，包含尚未阅读的内容，资料可能涉及后续情节。"
            })
            append("全程最多 ${plan.maximumRequests} 次调用，按服务商计费。")
            append("已核对过的段落会直接复用，不再重复计费。")
            if (plan.resuming) append("\n已保存 ${plan.completedParts} 段进度，核对通过后会直接复用。")
            append("\n\n可以随时停止，完成后整份保存。")
        }) },
        confirmButton = { TextButton(onClick = viewModel::generateCharacters) {
            Text(when {
                plan.resuming -> "继续提取"
                plan.progressBounded -> "提取到此处"
                else -> "提取全书"
            })
        } },
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
            ReaderKnowledgeTabs(tab, palette, onDismiss,
                bookTitle = state.snapshot.book?.title.orEmpty(),
                position = if (chapters.isEmpty()) "书中导航" else "阅读导航 · 第 ${currentChapterIndex + 1} / ${chapters.size} 章"
            ) { tab = it }
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
internal fun ReaderKnowledgeTabs(
    selectedTab: Int, palette: ReaderPalette, onDismiss: () -> Unit = {},
    bookTitle: String = "", position: String = "阅读导航", onSelect: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(82.dp).padding(start = 22.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(position, style = MaterialTheme.typography.labelSmall, color = palette.muted, maxLines = 1)
                Text(bookTitle.ifBlank { "书中导航" }, style = MaterialTheme.typography.titleLarge,
                    color = palette.onBackground, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Surface(shape = CircleShape, color = palette.glass) {
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭目录与资料", tint = palette.muted, modifier = Modifier.size(20.dp)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(54.dp)
            .clip(RoundedCornerShape(18.dp)).background(palette.glass).padding(4.dp)
            .selectableGroup().testTag("knowledge-tabs"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val icons = listOf(Icons.Outlined.FormatListBulleted, Icons.Outlined.AccountTree, Icons.Outlined.PeopleAlt)
            listOf("目录", "大纲", "人物").forEachIndexed { index, label ->
                val selected = index == selectedTab
                val background by animateColorAsState(if (selected) palette.accentContainer else Color.Transparent, label = "navigation-tab")
                Row(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(background)
                    .selectable(selected, role = Role.Tab, onClick = { onSelect(index) }),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(icons[index], null, Modifier.size(18.dp), tint = if (selected) palette.accent else palette.muted)
                    Spacer(Modifier.width(7.dp))
                    Text(label, style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) palette.accent else palette.muted)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun KnowledgeHeading(title: String, subtitle: String, palette: ReaderPalette, onInfo: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(78.dp).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = palette.onBackground)
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
    var card by remember(state.bookId) { mutableStateOf<ExtractedCharacterCard?>(null) }
    var editing by remember(state.bookId) { mutableStateOf<BookCharacter?>(null) }
    var editorVisible by rememberSaveable(state.bookId) { mutableStateOf(false) }
    var query by rememberSaveable(state.bookId) { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var options by rememberSaveable { mutableStateOf(false) }
    var information by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val task = state.characterTask
    val busy = task?.active == true
    val listState = rememberLazyListState()
    val filtered = remember(people, query) { people.filter { person ->
        (listOf(person.name, person.identity) + person.aliases).any { it.contains(query.trim(), ignoreCase = true) }
    } }
    val titles = remember(chapters) { chapters.associate { it.chapterIndex to it.title } }
    var progressBounded by rememberSaveable(state.bookId, saved?.guide?.progressBounded) { mutableStateOf(saved?.guide?.progressBounded ?: true) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp).testTag("character-tools"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text("${people.size} 位人物", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = palette.onBackground)
                if (query.isNotBlank()) Text("${filtered.size} 位匹配 · $query", style = MaterialTheme.typography.labelSmall,
                    color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Surface(shape = CircleShape, color = palette.glass) {
                IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "查找书中人物", tint = palette.muted, modifier = Modifier.size(20.dp)) }
            }
            Surface(shape = CircleShape, color = palette.glass) {
                IconButton(onClick = { editing = null; editorVisible = true }, enabled = !state.loading) {
                    Icon(Icons.Outlined.Add, "新增人物", tint = palette.accent, modifier = Modifier.size(20.dp))
                }
            }
            FilledTonalButton(onClick = { options = true }, shape = CircleShape, contentPadding = PaddingValues(horizontal = 14.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = palette.accentContainer, contentColor = palette.accent)) {
                Text(if (busy) "提取中" else "提取")
            }
        }
        // Fixed status line: progress changes never move the list or its scroll anchor.
        Box(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
            Text(task?.error ?: when {
                busy -> task.progress + " · 可离开此页"
                state.characters.checkpointParts > 0 -> "已有提取进度，点击「提取」继续"
                state.characters.outdated -> "正文已变化，可重新提取；手动资料保留"
                saved?.guide?.scannedChapters == 0 -> "手动整理"
                saved != null -> if (saved.guide.progressBounded) "已扫描到第 ${saved.guide.scannedChapters} 章" else "已扫描全书 ${saved.guide.scannedChapters} 章"
                else -> "可从正文提取，也可手动添加"
            }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = if (task?.error != null) MaterialTheme.colorScheme.error else palette.muted)
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().blockSheetDrag(listState).testTag("characters-list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (filtered.isEmpty()) item(key = "empty") {
                KnowledgeEmpty(if (query.isNotBlank()) "没有找到这个人物" else "还没有人物",
                    if (query.isNotBlank()) "试试其他姓名，或清除搜索。" else "点击「提取」汇集书中人物，或用 + 手动添加。", palette)
            }
            items(filtered, key = { it.identity }, contentType = { "character" }) { person ->
                var expanded by rememberSaveable(person.identity) { mutableStateOf(false) }
                var menu by remember { mutableStateOf(false) }
                Surface(shape = RoundedCornerShape(18.dp), color = palette.glass, modifier = Modifier.fillMaxWidth().testTag("person-${person.name}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(Modifier.size(42.dp), shape = CircleShape, color = palette.accentContainer) {
                                Box(contentAlignment = Alignment.Center) { Text(person.name.take(1), color = palette.accent, style = MaterialTheme.typography.titleMedium) }
                            }
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(person.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = palette.onBackground)
                                Text(if (person.manualDescription != null) "手动整理" else "${person.evidence.map { it.chapterIndex }.distinct().size} 章原文依据",
                                    color = palette.muted, style = MaterialTheme.typography.labelSmall)
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, "${person.name}的人物操作", tint = palette.muted) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("编辑人物") }, onClick = { menu = false; editing = person; editorVisible = true })
                                    DropdownMenuItem(text = { Text("提取角色卡") }, onClick = { menu = false; card = ExtractedCharacterCard.from(person) })
                                }
                            }
                        }
                        CharacterProfileDetails(person, expanded, palette) { evidence ->
                            saved?.let { actions.locateCharacter(it.entry, evidence) }
                        }
                        person.manualDescription?.takeIf(String::isNotBlank)?.let {
                            Text(it, color = palette.onBackground, style = MaterialTheme.typography.bodyMedium,
                                maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                        }
                        val evidence = if (expanded) person.evidence else if (person.manualDescription == null) person.evidence.take(1) else emptyList()
                        evidence.forEach { item -> key(item.chapterIndex, item.fact.start, item.fact.text) {
                            KnowledgeFactRow(item.fact, titles[item.chapterIndex] ?: "第 ${item.chapterIndex + 1} 章", palette) {
                                saved?.let { actions.locateCharacter(it.entry, item) }
                            }
                        } }
                        if (person.evidence.size > 1 || person.manualDescription != null || person.attributes.isNotEmpty() || person.relationships.isNotEmpty()) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                            Text(if (expanded) "收起人物资料" else if (person.manualDescription != null || person.attributes.isNotEmpty() || person.relationships.isNotEmpty()) "展开资料与原文依据" else "展开 ${person.evidence.size - 1} 条资料", color = palette.accent)
                        }
                    }
                }
            }
        }
    }
    if (searching) {
        var draft by rememberSaveable { mutableStateOf(query) }
        androidx.compose.ui.window.Dialog(onDismissRequest = { searching = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            // A bounded window also keeps IME and text-field remeasurement away from the sheet.
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
                Surface(Modifier.widthIn(max = 400.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("查找书中人物", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(draft, { draft = it.take(80) }, label = { Text("姓名或称呼") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("character-search"))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { query = ""; searching = false }) { Text("清除搜索") }
                            TextButton(onClick = { searching = false }) { Text("取消") }
                            TextButton(onClick = { query = draft.trim(); searching = false }) { Text("查找") }
                        }
                    }
                }
            }
        }
    }
    if (options) AlertDialog(onDismissRequest = { options = false }, title = { Text("人物提取") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().selectableGroup().testTag("character-scope"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(true to "读到此处", false to "全书").forEach { (bounded, label) ->
                    FilterChip(selected = progressBounded == bounded, onClick = { progressBounded = bounded }, enabled = !busy, label = { Text(label) })
                }
            }
            Text(if (progressBounded) "只扫描读过的正文。" else "包含未读章节，资料可能涉及后续情节。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { options = false; information = true }) { Icon(Icons.Outlined.Info, "书籍资料说明"); Spacer(Modifier.width(6.dp)); Text("提取说明") }
            if (saved != null || state.characters.checkpointParts > 0) TextButton(onClick = { options = false; deleting = true }, enabled = !busy) { Text("删除人物资料") }
        }
    }, confirmButton = {
        TextButton(onClick = { options = false; if (busy) actions.cancelCharacters() else actions.previewCharacters(progressBounded) },
            enabled = !state.loading && !state.preparingCharacters) {
            Text(when {
                busy -> "停止提取"
                state.preparingCharacters -> "准备中…"
                state.characters.checkpointParts > 0 -> "继续提取"
                saved != null -> if (progressBounded) "更新到当前进度" else "更新全书人物"
                else -> if (progressBounded) "提取读过的人物" else "提取全书人物"
            })
        }
    }, dismissButton = { TextButton(onClick = { options = false }) { Text("关闭") } })
    if (editorVisible) BookCharacterEditorDialog(editing, people, onDismiss = { editorVisible = false }, onSave = { key, name, description ->
        actions.saveCharacter(key, name, description); editorVisible = false
    })
    card?.let { draft -> ExtractedCharacterCardDialog(draft, onDismiss = { card = null }, onSave = { actions.saveCharacterCard(it); card = null }) }
    if (information) KnowledgeInfoDialog("书中人物", "由 AI 逐章扫描正文，提取人物、身份与关系。\n\n「读到此处」只发送已经读过的内容，最后一章截到当前进度；「全书」包括还没读到的章节，可能涉及后续情节。\n\nAI 资料保留原文依据，可随时核对；手动新增或编辑的资料会标注为「手动整理」，后续提取不会覆盖。\n\n已核对的段落保留缓存，更新只为新内容计费。停止后可继续，更新期间仍能查看已有资料。", { information = false })
    if (deleting) ConfirmKnowledgeDelete("删除人物资料？", "删除这本书的人物资料（包括手动整理）及未完成的提取进度。", { deleting = false }) {
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
