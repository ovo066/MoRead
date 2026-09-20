package com.mozhi.reader.feature.review

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.feature.reader.AnnotationDiscussionViewModel
import com.mozhi.reader.feature.reader.render.AnnotationInk
import com.mozhi.reader.ui.components.*
import com.mozhi.reader.ui.theme.isDarkTheme
import com.mozhi.reader.ui.theme.moReadMetrics
import com.mozhi.reader.ui.theme.ReadingReviewTheme

@Composable
internal fun ReadingReviewScreen(
    onBack: () -> Unit,
    onLocate: (ReviewEntry) -> Unit,
    viewModel: ReadingReviewViewModel = hiltViewModel(),
    discussion: AnnotationDiscussionViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val thread by discussion.uiState.collectAsStateWithLifecycle()
    val readerSettings by viewModel.readerSettings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(viewModel) {
        viewModel.shares.collect { intent ->
            runCatching { context.startActivity(Intent.createChooser(intent, "导出划线与笔记")) }
                .onFailure { Toast.makeText(context, "没有可用的分享应用", Toast.LENGTH_SHORT).show() }
        }
    }
    ReadingReviewTheme { CompositionLocalProvider(LocalReviewReaderSettings provides readerSettings) {
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewKeys by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var exportKey by rememberSaveable { mutableStateOf<String?>(null) }
    var composerKeys by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var comment by rememberSaveable { mutableStateOf(false) }
    val selected = state.entries.firstOrNull { it.key == detailKey }
    LaunchedEffect(detailKey, selected?.annotation?.id) {
        val annotation = selected?.annotation
        if (annotation != null) discussion.open(listOf(annotation.id)) else discussion.close()
    }
    DisposableEffect(Unit) { onDispose { discussion.close() } }

    ReadingReviewContent(state, onBack,
        onOpen = { detailKey = it.key },
        onReview = { reviewKeys = ArrayList(it.map(ReviewEntry::key)) },
        onCompose = { composerKeys = ArrayList(it.map(ReviewEntry::key)); comment = false },
        onExport = { viewModel.share(it) }, onFontChange = viewModel::setReviewFont)

    selected?.let { entry ->
        ReviewDetailDialog(entry, state.personas, thread,
            onDismiss = { detailKey = null; discussion.close() },
            onLocate = { detailKey = null; discussion.close(); onLocate(entry) },
            onEdit = { title, body -> viewModel.edit(entry, title, body) },
            onDelete = { viewModel.delete(entry); detailKey = null; discussion.close() },
            onExport = { exportKey = entry.key },
            onSendReply = { text, personaId -> entry.annotation?.let {
                discussion.sendUserReply(entry.book.id, it, text, personaId)
            } },
            onStopReply = discussion::cancelStreaming,
            onCommentNote = { composerKeys = arrayListOf(entry.key); comment = true },
            onStyleChange = { style, color -> viewModel.style(entry, style, color) })
    }
    reviewKeys?.let { keys ->
        val entries = keys.mapNotNull { key -> state.entries.firstOrNull { it.key == key } }
        ReviewPagerDialog(entries, onDismiss = { reviewKeys = null },
            onOpen = { reviewKeys = null; detailKey = it.key },
            onLocate = { reviewKeys = null; onLocate(it) }, onExport = { exportKey = it.key })
    }
    state.entries.firstOrNull { it.key == exportKey }?.let { entry ->
        ReviewExportDialog(entry, onDismiss = { exportKey = null },
            onMarkdown = { viewModel.share(listOf(entry)) },
            onSaveTemplate = viewModel::saveTemplate, onDeleteTemplate = viewModel::deleteTemplate,
            onImage = { style, options -> viewModel.share(listOf(entry), image = true, style = style, options = options) })
    }
    composerKeys?.let { keys ->
        val entries = keys.mapNotNull { key -> state.entries.firstOrNull { it.key == key } }
        ReviewComposerDialog(entries, state.personas, draft, comment,
            onDismiss = { composerKeys = null; viewModel.discardDraft() },
            onGenerate = { sources, personaId, instruction -> viewModel.generate(sources, personaId, instruction, comment) },
            onEdit = viewModel::updateDraft, onStop = viewModel::stopGeneration,
            onSave = viewModel::saveDraft)
    }
    } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReadingReviewContent(
    state: ReadingReviewState, onBack: () -> Unit, onOpen: (ReviewEntry) -> Unit,
    onReview: (List<ReviewEntry>) -> Unit, onCompose: (List<ReviewEntry>) -> Unit,
    onExport: (List<ReviewEntry>) -> Unit,
    onFontChange: (String) -> Unit = {}
) {
    ReadingReviewTheme {
    var query by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf(ReviewSource.MINE) }
    var kind by rememberSaveable { mutableStateOf(ReviewKind.ALL) }
    var bookIds by rememberSaveable { mutableStateOf(arrayListOf<Long>()) }
    var personaId by rememberSaveable { mutableStateOf<Long?>(null) }
    var oldest by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var booksOpen by rememberSaveable { mutableStateOf(false) }
    val filter = ReviewFilter(query, bookIds.toSet(), source, personaId, kind, oldest)
    val filtered = remember(state.entries, filter) { filterReview(state.entries, filter) }
    val bookCount = remember(filtered) { filtered.map { it.book.id }.distinct().size }
    val holder = rememberSaveableStateHolder()
    MoReadBackdrop {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 960.dp).fillMaxSize().safeTopPadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = moReadMetrics().topBarHeight).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = onBack, shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            contentColor = MaterialTheme.colorScheme.primary)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("划线与笔记", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        Text("${filtered.size} 条 · $bookCount 本书 · ${if (query.isBlank()) source.label else "搜索结果"}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    FilledTonalIconButton(onClick = { grid = !grid }, shape = CircleShape, modifier = Modifier.testTag("review-layout-toggle"),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                        Icon(if (grid) Icons.Outlined.GridView else Icons.Outlined.ViewAgenda,
                            if (grid) "切换单列" else "切换瀑布流")
                    }
                    FilledTonalIconButton(onClick = { filterOpen = true }, shape = CircleShape, modifier = Modifier.testTag("review-filter-button"),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            contentColor = MaterialTheme.colorScheme.primary)) {
                        Icon(Icons.Outlined.Tune, "筛选与回顾操作")
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val columns = if (!grid) 1 else if (maxWidth >= 700.dp) 3 else 2
                    holder.SaveableStateProvider("$source:${bookIds.sorted()}:$personaId:$kind:$oldest:$grid") {
                        val scroll = rememberLazyStaggeredGridState()
                        var previousQuery by rememberSaveable { mutableStateOf(query) }
                        LaunchedEffect(query) {
                            if (previousQuery != query) { scroll.scrollToItem(0); previousQuery = query }
                        }
                        LazyVerticalStaggeredGrid(
                            columns = StaggeredGridCells.Fixed(columns), state = scroll,
                            modifier = Modifier.fillMaxSize().testTag("review-grid"),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 112.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalItemSpacing = 12.dp
                        ) {
                            if (state.loading || filtered.isEmpty() || state.error != null) item(span = StaggeredGridItemSpan.FullLine) {
                                Column(Modifier.fillMaxWidth().padding(vertical = 52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (state.loading) CircularProgressIndicator(Modifier.size(28.dp))
                                    else {
                                        Icon(Icons.Outlined.FormatQuote, null, Modifier.size(46.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.height(18.dp))
                                        Text(state.error ?: if (state.entries.isEmpty()) "把触动你的句子留在这里" else "还没有符合筛选的记录",
                                            style = MaterialTheme.typography.titleMedium)
                                        Text(if (state.entries.isEmpty()) "阅读时长按划线，写下此刻的想法。" else "试试其他书籍、来源或关键词。",
                                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 8.dp))
                                    }
                                }
                            }
                            items(filtered, key = { it.key }, contentType = { it.kindLabel }) { entry ->
                                ReviewQuoteCard(entry, compact = grid, onClick = { onOpen(entry) })
                            }
                            if (state.hiddenCount > 0) item(span = StaggeredGridItemSpan.FullLine) {
                                Text("未读范围内的 AI 内容会在读到后出现", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                            }
                        }
                    }
                    FilledTonalIconButton(onClick = { booksOpen = true }, shape = CircleShape,
                        modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 20.dp, bottom = 20.dp)
                            .size(52.dp).testTag("review-books-button"),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                        Icon(Icons.Outlined.Book, if (bookIds.isEmpty()) "选择书籍" else "选择书籍，已选 ${bookIds.size} 本", Modifier.size(22.dp))
                    }
                    FilledTonalIconButton(onClick = { searchOpen = true }, shape = CircleShape,
                        modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 20.dp)
                            .size(56.dp).shadow(5.dp, CircleShape).testTag("review-search-button"),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            contentColor = MaterialTheme.colorScheme.primary)) {
                        Icon(Icons.Outlined.Search, "搜索划线与笔记", Modifier.size(24.dp))
                    }
                }
            }
        }
    }
    if (filterOpen) ReviewFiltersDialog(state, filter,
        onDismiss = { filterOpen = false }, onApply = {
            query = it.query; bookIds = ArrayList(it.bookIds); kind = it.kind; source = it.source; personaId = it.personaId; oldest = it.oldestFirst; filterOpen = false
        }, onCompose = { filterOpen = false; onCompose(it) }, onExport = { filterOpen = false; onExport(it) },
        onReview = { filterOpen = false; onReview(it) }, onFontChange = onFontChange)
    if (searchOpen) ReviewSearchDialog(query, onDismiss = { searchOpen = false }, onSearch = { query = it; searchOpen = false })
    if (booksOpen) ReviewBookSelector(state, bookIds.toSet(), onDismiss = { booksOpen = false }, onApply = {
        bookIds = ArrayList(it.sorted()); booksOpen = false
    })
    }
}

@Composable
internal fun ReviewQuoteCard(entry: ReviewEntry, compact: Boolean, onClick: () -> Unit) {
    val surface = if (entry.personaId == null) {
        if (isDarkTheme()) MaterialTheme.colorScheme.surfaceContainer
        else if (entry.book.id % 2L == 0L) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest
    } else
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDarkTheme()) .10f else .065f)
            .compositeOver(if (isDarkTheme()) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer)
    val dot = Color(AnnotationInk.solidColor(entry.annotation?.colorTag.orEmpty(), isDarkTheme(), MaterialTheme.colorScheme.primary.toArgb()))
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = surface,
        modifier = Modifier.fillMaxWidth().testTag("review-card-${entry.key}")) {
        Column(Modifier.padding(if (compact) 18.dp else 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("“", fontFamily = FontFamily.Serif, fontSize = 32.sp, lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = .4f), modifier = Modifier.height(24.dp))
                Spacer(Modifier.width(6.dp))
                if (entry.personaId != null) Row(Modifier.weight(1f).semantics { contentDescription = entry.author }, horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    PersonaAvatarImage(entry.author.removePrefix("AI · "), entry.authorAvatarPath,
                        Modifier.size(24.dp).testTag("review-avatar-${entry.key}"), MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp))
                    Text(entry.author.removePrefix("AI · "), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).padding(start = 5.dp))
                }
                else {
                    Spacer(Modifier.weight(1f))
                    if (entry.note != null) Icon(Icons.Outlined.EditNote, "读书笔记", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(20.dp))
            if (entry.title.isNotBlank()) Text(entry.title, style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 10.dp))
            var clipped by remember(entry.key) { mutableStateOf(false) }
            Text(entry.quote.ifBlank { reviewPlainText(entry.body) }, style = reviewQuoteStyle(entry.book.id, MaterialTheme.typography.bodyLarge.copy(
                fontSize = if (compact) 16.sp else 19.sp, lineHeight = if (compact) 29.sp else 33.sp)),
                maxLines = if (compact) 6 else 5, overflow = TextOverflow.Clip,
                onTextLayout = { clipped = it.hasVisualOverflow },
                modifier = Modifier.drawWithContent {
                    drawContent()
                    if (clipped) drawRect(Brush.verticalGradient(listOf(Color.Transparent, surface.copy(alpha = .72f), surface), startY = size.height * .48f))
                })
            if (!compact && entry.quote.isNotBlank() && entry.body.isNotBlank()) Text(entry.body,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                Text(entry.book.title, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp))
            }
            Text("${entry.locationLabel}${if (entry.annotation != null && entry.body.isNotBlank()) " · 有想法" else ""}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f),
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewFiltersDialog(state: ReadingReviewState, initial: ReviewFilter, onDismiss: () -> Unit,
    onApply: (ReviewFilter) -> Unit, onCompose: (List<ReviewEntry>) -> Unit, onExport: (List<ReviewEntry>) -> Unit,
    onReview: (List<ReviewEntry>) -> Unit, onFontChange: (String) -> Unit) {
    var kind by rememberSaveable { mutableStateOf(initial.kind) }
    var source by rememberSaveable { mutableStateOf(initial.source) }
    var persona by rememberSaveable { mutableStateOf(initial.personaId) }
    var oldest by rememberSaveable { mutableStateOf(initial.oldestFirst) }
    var query by rememberSaveable { mutableStateOf(initial.query) }
    val current = ReviewFilter(query, initial.bookIds, source, persona, kind, oldest)
    val selected = remember(state.entries, current) { filterReview(state.entries, current) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("回顾选项") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            ReviewFilterChoice("来源", source.name, ReviewSource.entries.map { it.name to it.label },
                { source = ReviewSource.valueOf(it) }, "source")
            ReviewFilterChoice("内容", kind.name, ReviewKind.entries.map { it.name to it.label },
                { kind = ReviewKind.valueOf(it) }, "kind")
            if (source == ReviewSource.AI) ReviewFilterChoice("伴读角色", persona?.toString() ?: "all",
                listOf("all" to "全部角色") + state.entries.mapNotNull { it.personaId }.distinct().map { id ->
                    id.toString() to (state.personas.firstOrNull { it.id == id }?.name ?: "已删除角色")
                }, { persona = it.toLongOrNull() }, "persona")
            ReviewFilterChoice("排序", oldest.toString(), listOf("false" to "最近记录", "true" to "最早记录"),
                { oldest = it.toBoolean() }, "sort")
            ReviewFilterChoice("划线字体", LocalReviewReaderSettings.current.reviewFont,
                reviewFontChoices(LocalReviewReaderSettings.current), onFontChange, "font")
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(onClick = { onReview(selected) }, enabled = selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.AutoStories, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text("全屏翻阅")
            }
            TextButton(onClick = { onExport(selected) }, enabled = selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.IosShare, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text("导出这 ${selected.size} 条记录")
            }
            TextButton(onClick = { onCompose(selected) }, enabled = selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text("与 AI 共创笔记（可选）")
            }
        }
    }, confirmButton = { TextButton(onClick = { onApply(current) }) { Text("应用筛选") } },
        dismissButton = { TextButton(onClick = { onApply(ReviewFilter(source = ReviewSource.MINE)) }) { Text("重置") } })
}

@Composable
private fun ReviewSearchDialog(initial: String, onDismiss: () -> Unit, onSearch: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf(initial) }
    NavigationSheet(onDismissRequest = onDismiss, contentHeightFraction = .38f,
        containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = .35f)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("找回一句话", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭搜索") }
            }
            Spacer(Modifier.height(12.dp))
            MoReadSearchCapsule(query, { query = it }, placeholder = "搜索摘录、想法或书名", testTagPrefix = "review")
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (initial.isNotBlank()) FilledTonalButton(onClick = { onSearch("") }, shape = CircleShape) { Text("清除") }
                Button(onClick = { onSearch(query) }, shape = CircleShape, modifier = Modifier.weight(1f).height(50.dp)) { Text("搜索") }
            }
        }
    }
}

@Composable
private fun ReviewFilterChoice(label: String, selected: String, choices: List<Pair<String, String>>,
    onSelect: (String) -> Unit, tag: String) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { expanded = true }
            .heightIn(min = 52.dp).padding(horizontal = 4.dp).testTag("review-choose-$tag"), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(20.dp))
            Text(choices.firstOrNull { it.first == selected }?.second ?: "请选择", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(20.dp))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 340.dp)) {
            choices.forEach { (id, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(id); expanded = false },
                    modifier = Modifier.testTag("review-$tag-$id"), trailingIcon = {
                        if (id == selected) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                    })
            }
        }
    }
}
