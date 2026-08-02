package com.mozhi.reader.feature.bookdetail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mikepenz.markdown.m3.Markdown
import com.mozhi.reader.core.database.entity.BookEntity
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.mozhi.reader.ui.components.blockSheetDrag
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.BookmarkEntity
import com.mozhi.reader.core.database.entity.IllustrationEntity
import com.mozhi.reader.core.database.entity.NoteEntity
import com.mozhi.reader.core.database.entity.tagList
import com.mozhi.reader.core.library.NoteRepository
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.ReadingHeatmap
import com.mozhi.reader.ui.components.RingGauge
import com.mozhi.reader.ui.components.SectionLabel
import com.mozhi.reader.ui.components.StatCell
import com.mozhi.reader.ui.components.MoReadBackdrop
import com.mozhi.reader.ui.theme.MoReadTokens
import com.mozhi.reader.ui.theme.sealColor
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 书籍详情页（design/ui-adaptation-plan.md §3），独立 destination。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookDetailScreen(
    bookId: Long,
    onBack: () -> Unit,
    onContinueReading: (Long) -> Unit,
    viewModel: BookDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showBookmarks by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var selectedNote by remember { mutableStateOf<NoteEntity?>(null) }
    var showAllNotes by remember { mutableStateOf(false) }
    var showAnnotations by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }
    var showNoteEditor by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<NoteEntity?>(null) }
    var selectedIllustration by remember { mutableStateOf<IllustrationEntity?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BookDetailEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
                is BookDetailEvent.LaunchIntent -> runCatching {
                    context.startActivity(
                        android.content.Intent.createChooser(event.intent, "分享读书笔记")
                    )
                }
            }
        }
    }

    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(viewModel::replaceCover) }

    MoReadBackdrop {
        val book = state.book
        if (state.isLoading || book == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.isLoading) CircularProgressIndicator()
                else Text("书籍不存在", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@MoReadBackdrop
        }

        val chapterTitle = state.chapters
            .getOrNull(book.lastReadChapterIndex)
            ?.title
            .orEmpty()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                DetailTopBar(
                    onBack = onBack,
                    onEdit = { showEditor = true },
                    onBookmarks = { showBookmarks = true }
                )
            }
            item {
                DetailHero(book = book)
            }
            item {
                RingRow(
                    book = book,
                    streakDays = state.streakDays,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatCell(formatDuration(state.totalDurationMs), "阅读时长", Modifier.weight(1f))
                    StatCell("${state.readingDays}", "阅读天数", Modifier.weight(1f))
                    StatCell("${state.bookmarks.size}", "书签", Modifier.weight(1f))
                    StatCell("${state.notes.size}", "笔记", Modifier.weight(1f))
                }
            }
            item {
                ReadingAssetsEntry(
                    noteCount = state.notes.size,
                    annotationCount = state.annotations.size,
                    illustrationCount = state.illustrations.size,
                    onNotes = { showAllNotes = true },
                    onAnnotations = { showAnnotations = true },
                    onGallery = { showGallery = true },
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            item {
                FrostedSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(24.dp),
                    shadowElevation = 6.dp
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "阅读热力",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        ReadingHeatmap(
                            durationsByEpochDay = state.durationsByEpochDay,
                            todayEpochDay = LocalDate.now().toEpochDay(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        )
                    }
                }
            }
            item {
                NotesSection(
                    notes = state.notes,
                    onNoteClick = { selectedNote = it },
                    onShowAll = { showAllNotes = true },
                    onCreate = {
                        editingNote = null
                        showNoteEditor = true
                    },
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            item {
                BookmarkEntryRow(
                    count = state.bookmarks.size,
                    onClick = { showBookmarks = true },
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            item {
                Button(
                    onClick = { onContinueReading(book.id) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = MoReadTokens.CapsuleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .navigationBarsPadding()
                        .height(54.dp)
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (book.lastReadAt == 0L) {
                            "开始阅读"
                        } else {
                            "继续阅读 · ${chapterTitle.ifBlank { "第 ${book.lastReadChapterIndex + 1} 章" }}"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 76.dp)
        )

        if (showEditor) {
            BookMetadataEditorDialog(
                book = book,
                isWorking = state.isWorking,
                onPickCover = {
                    coverPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onDismiss = { showEditor = false },
                onSave = { title, author, tags ->
                    viewModel.saveMetadata(title, author, tags)
                    showEditor = false
                }
            )
        }
    }

    if (showBookmarks) {
        ModalBottomSheet(onDismissRequest = { showBookmarks = false }) {
            Text(
                text = "书签",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
            if (state.bookmarks.isEmpty()) {
                Text(
                    text = "还没有书签，阅读时点右上角即可添加。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp)
                )
            } else {
                val bookmarkListState = androidx.compose.foundation.lazy.rememberLazyListState()
                LazyColumn(
                    state = bookmarkListState,
                    modifier = Modifier
                        .height(480.dp)
                        .blockSheetDrag(bookmarkListState)
                ) {
                    items(
                        state.bookmarks,
                        key = BookmarkEntity::id
                    ) { bookmark ->
                        ListItem(
                            headlineContent = { Text(bookmark.label) },
                            supportingContent = {
                                Text(bookmark.excerpt.ifBlank { "第 ${bookmark.chapterIndex + 1} 章" })
                            },
                            trailingContent = {
                                IconButton(onClick = { viewModel.deleteBookmark(bookmark.id) }) {
                                    Icon(
                                        Icons.Outlined.Bookmarks,
                                        contentDescription = "删除书签"
                                    )
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showAllNotes) {
        ModalBottomSheet(
            onDismissRequest = { showAllNotes = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            val orderedNotes = remember(state.notes) { state.notes.sortedForReview() }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.86f)
                    .padding(horizontal = 20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "剧情梗概与读书笔记",
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Serif,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = viewModel::exportNotes) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = "导出到文档")
                    }
                    IconButton(onClick = viewModel::shareNotes) {
                        Icon(Icons.Outlined.Share, contentDescription = "分享笔记")
                    }
                    TextButton(onClick = {
                        editingNote = null
                        showNoteEditor = true
                    }) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Text("新建")
                    }
                }
                val noteListState = androidx.compose.foundation.lazy.rememberLazyListState()
                LazyColumn(
                    state = noteListState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .blockSheetDrag(noteListState),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (orderedNotes.isEmpty()) {
                        item {
                            Text(
                                "还没有读书笔记。点击右上角“新建”即可记录，伴读保存的剧情梗概也会显示在这里。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    }
                    items(orderedNotes, key = NoteEntity::id) { note ->
                        NoteCard(
                            note = note,
                            onClick = {
                                showAllNotes = false
                                selectedNote = note
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAnnotations) {
        ModalBottomSheet(
            onDismissRequest = { showAnnotations = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.86f)
                    .padding(horizontal = 20.dp)
            ) {
                Text("段落批注与评论", style = MaterialTheme.typography.titleLarge)
                Text(
                    "阅读正文时点击段落旁的“评”标记可参与评论。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                val threads = remember(state.annotations) {
                    state.annotations.groupBy {
                        Triple(it.chapterIndex, it.startCharOffset, it.endCharOffset)
                    }.values.sortedByDescending { group -> group.maxOf(AnnotationEntity::createdAt) }
                }
                if (threads.isEmpty()) {
                    Text("还没有批注。阅读时长按原文即可添加，伴读 Agent 也能调用 add_annotation。")
                } else {
                    val annotationListState = androidx.compose.foundation.lazy.rememberLazyListState()
                    LazyColumn(
                        state = annotationListState,
                        modifier = Modifier
                            .weight(1f)
                            .blockSheetDrag(annotationListState),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(threads, key = { group -> group.first().id }) { comments ->
                            AnnotationReviewCard(
                                comments = comments,
                                onDelete = viewModel::deleteAnnotation
                            )
                        }
                    }
                }
            }
        }
    }

    if (showGallery) {
        ModalBottomSheet(
            onDismissRequest = { showGallery = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .padding(horizontal = 20.dp)
            ) {
                Text("书籍插图廊", style = MaterialTheme.typography.titleLarge)
                Text(
                    "选段和伴读 Agent 生成的插图都会保存在这里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                if (state.illustrations.isEmpty()) {
                    Text("还没有插图，可在阅读页划线后选择“生图”。")
                } else {
                    val galleryListState = androidx.compose.foundation.lazy.rememberLazyListState()
                    LazyColumn(
                        state = galleryListState,
                        modifier = Modifier
                            .weight(1f)
                            .blockSheetDrag(galleryListState),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.illustrations, key = IllustrationEntity::id) { illustration ->
                            IllustrationGalleryCard(
                                illustration = illustration,
                                onOpen = { selectedIllustration = illustration },
                                onDelete = { viewModel.deleteIllustration(illustration) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNoteEditor) {
        NoteEditorDialog(
            note = editingNote,
            onDismiss = { showNoteEditor = false },
            onSave = { title, content ->
                val existing = editingNote
                if (existing == null) viewModel.createNote(title, content)
                else viewModel.updateNote(existing.id, title, content)
                showNoteEditor = false
                editingNote = null
            }
        )
    }

    selectedIllustration?.let { illustration ->
        AlertDialog(
            onDismissRequest = { selectedIllustration = null },
            title = { Text("书籍插图") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxHeight(0.72f)) {
                    item {
                        AsyncImage(
                            model = File(illustration.imagePath),
                            contentDescription = "生成插图",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Text(
                            illustration.prompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedIllustration = null }) { Text("关闭") } },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.deleteIllustration(illustration)
                    selectedIllustration = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        )
    }

    selectedNote?.let { note ->
        AlertDialog(
            onDismissRequest = { selectedNote = null },
            title = {
                Column {
                    Text(note.title.ifBlank { "读书笔记" })
                    Text(
                        if (note.kind == NoteRepository.KIND_PLOT_SUMMARY) "剧情梗概" else "读书笔记",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            text = {
                LazyColumn(modifier = Modifier.fillMaxHeight(0.68f)) {
                    item { Markdown(content = note.contentMarkdown) }
                    item {
                        Text(
                            buildString {
                                note.relatedChapterIndex?.let { append("内容范围：截至第 ${it + 1} 章\n") }
                                append("保存于 ${formatDate(note.updatedAt)}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedNote = null }) { Text("关闭") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        editingNote = note
                        showNoteEditor = true
                        selectedNote = null
                    }) { Text("编辑") }
                    TextButton(onClick = {
                        viewModel.deleteNote(note.id)
                        selectedNote = null
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            }
        )
    }
}

@Composable
private fun NoteEditorDialog(
    note: NoteEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var title by remember(note?.id) { mutableStateOf(note?.title.orEmpty()) }
    var content by remember(note?.id) { mutableStateOf(note?.contentMarkdown.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (note == null) "新建读书笔记" else "编辑读书笔记") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxHeight(0.68f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("标题") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("笔记正文（支持 Markdown）") },
                        minLines = 9,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title.trim(), content.trim()) },
                enabled = title.isNotBlank() || content.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun AnnotationReviewCard(
    comments: List<AnnotationEntity>,
    onDelete: (Long) -> Unit
) {
    val first = comments.first()
    FrostedSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 3.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "第 ${first.chapterIndex + 1} 章 · “${first.selectedText.take(180)}${if (first.selectedText.length > 180) "…" else ""}”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            comments.forEach { annotation ->
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (annotation.personaId == null) "我的批注" else "角色批注",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(annotation.note.ifBlank { "仅标记原文" })
                    }
                    IconButton(onClick = { onDelete(annotation.id) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "删除批注", modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun IllustrationGalleryCard(
    illustration: IllustrationEntity,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    FrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 4.dp
    ) {
        Column {
            AsyncImage(
                model = File(illustration.imagePath),
                contentDescription = "书籍插图",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
            )
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    illustration.chapterIndex?.let { "第 ${it + 1} 章" } ?: "全书插图",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Close, contentDescription = "删除插图")
                }
            }
        }
    }
}

/**
 * 书籍信息编辑：书名/作者/标签/封面。
 *
 * EPUB 的元数据经常是垃圾（WPS 导出会写 Unknown / WPS_1532705572），自动清洗只能猜到
 * 文件名一层，所以最终一定要留一个手改的口子。
 */
@Composable
private fun BookMetadataEditorDialog(
    book: BookEntity,
    isWorking: Boolean,
    onPickCover: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, List<String>) -> Unit
) {
    var title by remember(book.id) { mutableStateOf(book.title) }
    var author by remember(book.id) { mutableStateOf(book.author) }
    var tags by remember(book.id, book.tags) { mutableStateOf(book.tagList()) }
    var tagDraft by remember(book.id) { mutableStateOf("") }

    fun commitTag() {
        // 逗号是标签的存储分隔符，输入里出现就当多个标签处理。
        val added = tagDraft.split(',', '，')
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (added.isNotEmpty()) {
            tags = (tags + added).distinct().take(MAX_TAGS)
        }
        tagDraft = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑书籍信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("书名") },
                    singleLine = true,
                    isError = title.isBlank(),
                    supportingText = if (title.isBlank()) {
                        { Text("书名不能为空") }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("作者") },
                    placeholder = { Text("留空则显示「未知作者」") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("标签", style = MaterialTheme.typography.titleSmall)
                if (tags.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        tags.forEach { tag ->
                            InputChip(
                                selected = false,
                                onClick = { tags = tags - tag },
                                shape = MoReadTokens.CapsuleShape,
                                label = { Text(tag) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "删除标签 $tag",
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = tagDraft,
                    onValueChange = { tagDraft = it },
                    label = { Text("添加标签") },
                    singleLine = true,
                    enabled = tags.size < MAX_TAGS,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitTag() }),
                    trailingIcon = {
                        IconButton(
                            onClick = { commitTag() },
                            enabled = tagDraft.isNotBlank() && tags.size < MAX_TAGS
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = "添加标签")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("封面", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = if (book.coverPath.isNullOrBlank()) "未设置" else "已设置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = onPickCover,
                        shape = MoReadTokens.CapsuleShape,
                        enabled = !isWorking
                    ) {
                        Text(if (isWorking) "处理中…" else "从相册选择")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 用户可能在输入框里留了没提交的标签，别静默丢掉。
                    val pending = tagDraft.split(',', '，')
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                    onSave(title, author, (tags + pending).distinct().take(MAX_TAGS))
                },
                enabled = title.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private const val MAX_TAGS = 12

@Composable
private fun DetailTopBar(onBack: () -> Unit, onEdit: () -> Unit, onBookmarks: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FrostedSurface(shape = CircleShape, shadowElevation = 6.dp) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
        }
        Text(
            text = "书籍详情",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FrostedSurface(shape = CircleShape, shadowElevation = 6.dp) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = "编辑书籍信息")
                }
            }
            FrostedSurface(shape = CircleShape, shadowElevation = 6.dp) {
                IconButton(onClick = onBookmarks) {
                    Icon(Icons.Outlined.Bookmarks, contentDescription = "书签")
                }
            }
        }
    }
}

@Composable
private fun DetailHero(book: BookEntity) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HeroCover(
            book = book,
            modifier = Modifier.size(width = 128.dp, height = 182.dp)
        )
        Text(
            text = book.title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 16.dp, start = 32.dp, end = 32.dp)
        )
        Text(
            text = "${book.author.ifBlank { "未知作者" }} · ${book.totalChapters} 章",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
        val tags = remember(book.tags) { book.tagList() }
        if (tags.isNotEmpty()) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, start = 24.dp, end = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tags.forEach { tag -> TagChip(tag) }
            }
        }
    }
}

/** 只读标签胶囊（编辑在弹窗里做）。 */
@Composable
private fun TagChip(tag: String) {
    Surface(
        shape = MoReadTokens.CapsuleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            text = tag,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun HeroCover(book: BookEntity, modifier: Modifier = Modifier) {
    val coverFile = remember(book.coverPath) {
        book.coverPath?.let(::File)?.takeIf(File::isFile)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 18.dp,
        color = com.mozhi.reader.feature.bookshelf.coverColor(book.title)
    ) {
        Box(Modifier.fillMaxSize()) {
            if (coverFile != null) {
                AsyncImage(
                    model = coverFile,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    com.mozhi.reader.feature.bookshelf.coverColor(book.title),
                                    Color.Black.copy(alpha = 0.45f)
                                )
                            )
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(5.dp)
                            .padding(start = 2.dp)
                            .background(Color.White.copy(alpha = 0.30f))
                    )
                    // 直排书名：超过一列就从右往左折列，与书架 fallback 封面同规则。
                    val display = if (book.title.length > 23) book.title.take(22) + "…" else book.title
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 14.dp, end = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        display.chunked(8).asReversed().forEach { column ->
                            Text(
                                text = column.toCharArray().joinToString("\n"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.95f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 双环卡（§3.3）：全书进度环（moss）+ 连续阅读环（seal）。 */
@Composable
private fun RingRow(
    book: BookEntity,
    streakDays: Int,
    modifier: Modifier = Modifier
) {
    val progress = if (book.totalChapters <= 0 || book.lastReadAt == 0L) {
        0f
    } else {
        ((book.lastReadChapterIndex + 1f) / book.totalChapters).coerceIn(0f, 1f)
    }
    val seal = sealColor()
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FrostedSurface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RingGauge(
                    progress = progress,
                    ringColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(96.dp)
                ) {
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = "全书进度",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Text(
                    text = "第 ${book.lastReadChapterIndex + 1} / ${book.totalChapters} 章",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        FrostedSurface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RingGauge(
                    progress = (streakDays / 30f).coerceIn(0f, 1f),
                    ringColor = seal,
                    modifier = Modifier.size(96.dp)
                ) {
                    Text(
                        text = "$streakDays",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = "连续阅读",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Text(
                    text = "天",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ReadingAssetsEntry(
    noteCount: Int,
    annotationCount: Int,
    illustrationCount: Int,
    onNotes: () -> Unit,
    onAnnotations: () -> Unit,
    onGallery: () -> Unit,
    modifier: Modifier = Modifier
) {
    FrostedSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        shadowElevation = 5.dp
    ) {
        Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AssetEntryCell(Icons.Outlined.EditNote, "读书笔记", "$noteCount 条", onNotes, Modifier.weight(1f))
            AssetEntryCell(
                Icons.Outlined.ChatBubbleOutline,
                "段落批注",
                "$annotationCount 条",
                onAnnotations,
                Modifier.weight(1f)
            )
            AssetEntryCell(Icons.Outlined.Image, "插图廊", "$illustrationCount 张", onGallery, Modifier.weight(1f))
        }
    }
}

@Composable
private fun AssetEntryCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 5.dp))
        Text(count, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 剧情梗概排在普通笔记前；点卡片直接展开 Markdown 全文回顾。 */
@Composable
private fun NotesSection(
    notes: List<NoteEntity>,
    onNoteClick: (NoteEntity) -> Unit,
    onShowAll: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ordered = notes.sortedForReview()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(
                title = "剧情梗概与读书笔记",
                trailing = "全部 ${notes.size} 条",
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCreate) {
                Icon(Icons.Outlined.EditNote, contentDescription = null, modifier = Modifier.size(17.dp))
                Text("写笔记", modifier = Modifier.padding(start = 4.dp))
            }
        }
        if (ordered.isEmpty()) {
            Text(
                text = "点击“写笔记”记录想法，也可让伴读角色保存剧情梗概。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            ordered.take(5).forEach { note ->
                NoteCard(note = note, onClick = { onNoteClick(note) })
            }
            if (ordered.size > 5) {
                TextButton(onClick = onShowAll, modifier = Modifier.align(Alignment.End)) {
                    Text("查看全部 ${ordered.size} 条")
                }
            }
        }
    }
}

private fun List<NoteEntity>.sortedForReview(): List<NoteEntity> = sortedWith(
    compareByDescending<NoteEntity> { it.kind == NoteRepository.KIND_PLOT_SUMMARY }
        .thenByDescending(NoteEntity::updatedAt)
)

@Composable
private fun NoteCard(note: NoteEntity, onClick: () -> Unit) {
    FrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 4.dp
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = note.title.ifBlank {
                        if (note.kind == NoteRepository.KIND_PLOT_SUMMARY) "剧情梗概" else "读书笔记"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Serif,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = note.contentMarkdown
                        .replace(Regex("[#*_`>\\[\\]]"), "")
                        .replace('\n', ' ')
                        .trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
                Text(
                    text = buildString {
                        note.relatedChapterIndex?.let { append("截至第 ${it + 1} 章 · ") }
                        append(formatDate(note.updatedAt))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun BookmarkEntryRow(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FrostedSurface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MoReadTokens.CapsuleShape,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Bookmarks,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "书签 $count 处",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            )
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

internal fun formatDuration(durationMs: Long): String {
    val totalMinutes = durationMs / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours}h${minutes.toString().padStart(2, '0')}m"
        totalMinutes > 0 -> "${totalMinutes}m"
        durationMs > 0 -> "<1m"
        else -> "0m"
    }
}

private fun formatDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(DateTimeFormatter.ofPattern("M月d日"))
