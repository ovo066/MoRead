package com.mozhi.reader.feature.bookdetail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.mozhi.reader.core.database.entity.BookReadState
import com.mozhi.reader.core.database.entity.BookTagEntity
import com.mozhi.reader.core.database.entity.BookTagRefEntity
import com.mozhi.reader.core.database.entity.BookmarkEntity
import com.mozhi.reader.core.database.entity.IllustrationEntity
import com.mozhi.reader.core.database.entity.NoteEntity
import com.mozhi.reader.core.database.entity.label
import com.mozhi.reader.core.database.entity.readState
import com.mozhi.reader.core.database.entity.ShelfGroupEntity
import com.mozhi.reader.core.datastore.ReaderImageAsset
import com.mozhi.reader.core.library.NoteRepository
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.ReadingHeatmap
import com.mozhi.reader.ui.components.RingGauge
import com.mozhi.reader.ui.components.SectionLabel
import com.mozhi.reader.ui.components.StatCell
import com.mozhi.reader.ui.components.MoReadBackdrop
import com.mozhi.reader.ui.components.MoReadDropdownMenu
import com.mozhi.reader.ui.components.MoReadMenuDivider
import com.mozhi.reader.ui.components.MoReadMenuItem
import com.mozhi.reader.ui.components.MoReadStableDropdownMenu
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
    onListen: (Long) -> Unit = {},
    onPlayAudiobook: (Long) -> Unit = {},
    onOpenAudiobookRoles: (Long) -> Unit = {},
    onOpenAudiobookProduction: (Long) -> Unit = {},
    /** 书架长按菜单的深链动作：edit = 直接开信息编辑，cover = 直接开封面选择。 */
    initialAction: String? = null,
    viewModel: BookDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showBookmarks by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var showCoverPicker by remember { mutableStateOf(false) }
    var showTagPicker by remember { mutableStateOf(false) }
    var showGroupPicker by remember { mutableStateOf(false) }
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

    LaunchedEffect(initialAction) {
        when (initialAction) {
            "edit" -> showEditor = true
            "cover" -> showCoverPicker = true
        }
    }

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
                    onEditInfo = { showEditor = true },
                    onChangeCover = { showCoverPicker = true },
                    onPickGroup = { showGroupPicker = true },
                    onPickTags = { showTagPicker = true },
                    groupName = state.selectedGroupName,
                    tagCount = state.selectedTags.size,
                    onBookmarks = { showBookmarks = true }
                )
            }
            item {
                DetailHero(
                    book = book,
                    tags = state.selectedTags.map(BookTagEntity::name),
                    onEditReadState = viewModel::setReadState
                )
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
                AudiobookEntryCard(
                    readyChapters = state.audiobookReadyChapters,
                    totalChapters = state.chapters.size,
                    totalMillis = state.audiobookTotalMillis,
                    roleNames = state.audiobookRoleNames,
                    onManage = { onOpenAudiobookRoles(book.id) },
                    onContinue = {
                        if (state.audiobookRoleNames.isEmpty()) onOpenAudiobookRoles(book.id)
                        else onOpenAudiobookProduction(book.id)
                    },
                    onPlay = { onPlayAudiobook(book.id) },
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .navigationBarsPadding()
                        .height(54.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { onListen(book.id) },
                        shape = MoReadTokens.CapsuleShape,
                        modifier = Modifier.weight(0.36f).fillMaxHeight()
                    ) {
                        Icon(Icons.Outlined.Headphones, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("听书")
                    }
                    Button(
                        onClick = { onContinueReading(book.id) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = MoReadTokens.CapsuleShape,
                        modifier = Modifier.weight(0.64f).fillMaxHeight()
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (book.lastReadAt == 0L) "开始阅读" else "继续阅读",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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
                onOpenCoverLibrary = { showCoverPicker = true },
                onDismiss = { showEditor = false },
                onSave = { title, author ->
                    viewModel.saveMetadata(title, author)
                    showEditor = false
                }
            )
        }

        if (showTagPicker) {
            BookTagPickerSheet(
                bookId = book.id,
                tags = state.shelfTags,
                refs = state.shelfTagRefs,
                onDismiss = { showTagPicker = false },
                onToggleTag = viewModel::setTag,
                onCreateTag = viewModel::createAndApplyTag
            )
        }

        if (showGroupPicker) {
            BookGroupPickerSheet(
                selectedGroupId = book.groupId,
                groups = state.shelfGroups,
                onDismiss = { showGroupPicker = false },
                onSelect = viewModel::setShelfGroup
            )
        }

        // 顶层持有：编辑对话框要用，书架长按的「修改封面」深链也直接开这一个。
        if (showCoverPicker) {
            ImageLibraryPickerDialog(
                images = state.imageLibrary,
                currentPath = book.coverPath,
                onSelect = { image ->
                    viewModel.selectCover(image.id)
                    showCoverPicker = false
                },
                onImport = {
                    showCoverPicker = false
                    coverPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onDismiss = { showCoverPicker = false }
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
                    "阅读正文时点击划线或“评”标记可参与讨论。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
                // 作者筛选：全部 / 我的 / 各角色（学习向用户复习时常只看自己的划线）
                var authorFilter by remember { mutableStateOf<Long?>(FILTER_ALL) }
                val annotationAuthors = remember(state.annotations) {
                    state.annotations.mapNotNull(AnnotationEntity::personaId).distinct()
                }
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = authorFilter == FILTER_ALL,
                        onClick = { authorFilter = FILTER_ALL },
                        label = { Text("全部") }
                    )
                    FilterChip(
                        selected = authorFilter == null,
                        onClick = { authorFilter = null },
                        label = { Text("我的") }
                    )
                    annotationAuthors.forEach { personaId ->
                        FilterChip(
                            selected = authorFilter == personaId,
                            onClick = { authorFilter = personaId },
                            label = {
                                Text(
                                    state.personaNames[personaId] ?: "已删除角色",
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }
                val threads = remember(state.annotations, authorFilter) {
                    state.annotations
                        .filter { annotation ->
                            authorFilter == FILTER_ALL || annotation.personaId == authorFilter
                        }
                        .groupBy {
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
                                personaNames = state.personaNames,
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

/** 批注作者筛选的「全部」哨兵值（null 已被「我的」占用）。 */
private const val FILTER_ALL = -1L

@Composable
private fun AnnotationReviewCard(
    comments: List<AnnotationEntity>,
    personaNames: Map<Long, String>,
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
                            annotation.personaId?.let { id ->
                                personaNames[id] ?: "已删除角色"
                            } ?: "我的批注",
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

@Composable
private fun AudiobookEntryCard(
    readyChapters: Int,
    totalChapters: Int,
    totalMillis: Long,
    roleNames: List<String>,
    onManage: () -> Unit,
    onContinue: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    FrostedSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 6.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Headphones,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        "AI 有声书",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (readyChapters > 0) {
                            "已制作 $readyChapters/$totalChapters 章 · ${formatDuration(totalMillis)}"
                        } else {
                            "分配角色音色，逐章合成多角色朗读"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (roleNames.isNotEmpty()) {
                Text(
                    text = roleNames.take(5).joinToString(" · ") +
                        if (roleNames.size > 5) " …" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onManage,
                    shape = MoReadTokens.CapsuleShape,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("角色与音色", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                if (readyChapters > 0) {
                    OutlinedButton(
                        onClick = onPlay,
                        shape = MoReadTokens.CapsuleShape,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("播放成品", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
                Button(
                    onClick = onContinue,
                    shape = MoReadTokens.CapsuleShape,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (roleNames.isEmpty()) "开始制作" else "继续制作",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookTagPickerSheet(
    bookId: Long,
    tags: List<BookTagEntity>,
    refs: List<BookTagRefEntity>,
    onDismiss: () -> Unit,
    onToggleTag: (Long, Boolean) -> Unit,
    onCreateTag: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val selectedIds = remember(bookId, refs) {
        refs.filter { it.bookId == bookId }.map(BookTagRefEntity::tagId).toSet()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("设置标签", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("搜索或新建标签…") }
            )
            val normalized = query.trim()
            val visible = tags.filter { it.name.contains(normalized, ignoreCase = true) }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(360.dp).blockSheetDrag(listState),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                if (normalized.isNotEmpty() && tags.none { it.name.equals(normalized, true) }) {
                    item {
                        ListItem(
                            headlineContent = { Text("新建“$normalized”") },
                            leadingContent = { Icon(Icons.Outlined.Add, contentDescription = null) },
                            modifier = Modifier.clickable {
                                onCreateTag(normalized)
                                query = ""
                            }
                        )
                    }
                }
                visible.groupBy { it.groupName.ifBlank { "未分组" } }.forEach { (group, groupTags) ->
                    item {
                        Text(
                            group,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(groupTags, key = BookTagEntity::id) { tag ->
                        val selected = tag.id in selectedIds
                        ListItem(
                            headlineContent = { Text(tag.name) },
                            leadingContent = {
                                Icon(
                                    if (selected) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                                    contentDescription = null
                                )
                            },
                            modifier = Modifier.clickable { onToggleTag(tag.id, !selected) }
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("完成") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookGroupPickerSheet(
    selectedGroupId: Long?,
    groups: List<ShelfGroupEntity>,
    onDismiss: () -> Unit,
    onSelect: (Long?) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("所属分组", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            ListItem(
                headlineContent = { Text("未分组") },
                trailingContent = {
                    if (selectedGroupId == null) Icon(Icons.Outlined.Check, contentDescription = null)
                },
                modifier = Modifier.clickable { onSelect(null); onDismiss() }
            )
            groups.filter { it.parentId == null }.forEach { parent ->
                ListItem(
                    headlineContent = { Text(parent.name) },
                    trailingContent = {
                        if (selectedGroupId == parent.id) Icon(Icons.Outlined.Check, contentDescription = null)
                    },
                    modifier = Modifier.clickable { onSelect(parent.id); onDismiss() }
                )
                groups.filter { it.parentId == parent.id }.forEach { child ->
                    ListItem(
                        headlineContent = { Text(child.name, modifier = Modifier.padding(start = 22.dp)) },
                        trailingContent = {
                            if (selectedGroupId == child.id) Icon(Icons.Outlined.Check, contentDescription = null)
                        },
                        modifier = Modifier.clickable { onSelect(child.id); onDismiss() }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * 书籍信息编辑：书名、作者与封面。分组和标签在详情页卡片中单独维护。
 *
 * EPUB 的元数据经常是垃圾（WPS 导出会写 Unknown / WPS_1532705572），自动清洗只能猜到
 * 文件名一层，所以最终一定要留一个手改的口子。
 */
@Composable
private fun BookMetadataEditorDialog(
    book: BookEntity,
    isWorking: Boolean,
    onPickCover: () -> Unit,
    onOpenCoverLibrary: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var title by remember(book.id) { mutableStateOf(book.title) }
    var author by remember(book.id) { mutableStateOf(book.author) }

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

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                        Text("封面", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = if (book.coverPath.isNullOrBlank()) "未设置" else "已设置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenCoverLibrary,
                            shape = MoReadTokens.CapsuleShape,
                            enabled = !isWorking
                        ) {
                            Text("从图片库选择")
                        }
                        TextButton(onClick = onPickCover, enabled = !isWorking) {
                            Text(if (isWorking) "处理中…" else "导入新图片")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(title, author)
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

@Composable
private fun ImageLibraryPickerDialog(
    images: List<ReaderImageAsset>,
    currentPath: String?,
    onSelect: (ReaderImageAsset) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从图片库选择封面") },
        text = {
            if (images.isEmpty()) {
                Text("图片库还是空的，可以先导入一张新图片。")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(images, key = ReaderImageAsset::id) { image ->
                        ListItem(
                            headlineContent = {
                                Text(image.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                if (image.width > 0 && image.height > 0) {
                                    Text("${image.width} × ${image.height}")
                                }
                            },
                            leadingContent = {
                                AsyncImage(
                                    model = File(image.filePath),
                                    contentDescription = image.displayName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                                )
                            },
                            trailingContent = if (currentPath == image.filePath) {
                                { Icon(Icons.Outlined.Check, contentDescription = "当前封面") }
                            } else {
                                null
                            },
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable {
                                onSelect(image)
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onImport) { Text("导入新图片") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private const val MAX_TAGS = 12

@Composable
private fun DetailTopBar(
    onBack: () -> Unit,
    onEditInfo: () -> Unit,
    onChangeCover: () -> Unit,
    onPickGroup: () -> Unit,
    onPickTags: () -> Unit,
    groupName: String,
    tagCount: Int,
    onBookmarks: () -> Unit
) {
    var editMenu by remember { mutableStateOf(false) }
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
            // 编辑收纳了信息/封面与书架整理（分组、标签），正文区不再为它留一整张卡。
            Box {
                FrostedSurface(shape = CircleShape, shadowElevation = 6.dp) {
                    IconButton(onClick = { editMenu = true }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "编辑")
                    }
                }
                MoReadStableDropdownMenu(
                    expanded = editMenu,
                    onDismissRequest = { editMenu = false },
                    width = 224.dp
                ) {
                    MoReadMenuItem(
                        text = "书名与作者",
                        icon = Icons.Outlined.Edit,
                        onClick = { editMenu = false; onEditInfo() }
                    )
                    MoReadMenuItem(
                        text = "更换封面",
                        icon = Icons.Outlined.Image,
                        onClick = { editMenu = false; onChangeCover() }
                    )
                    MoReadMenuDivider()
                    MoReadMenuItem(
                        text = "所属分组",
                        icon = Icons.Outlined.Folder,
                        trailingText = groupName,
                        onClick = { editMenu = false; onPickGroup() }
                    )
                    MoReadMenuItem(
                        text = "标签",
                        icon = Icons.Outlined.Sell,
                        trailingText = if (tagCount == 0) "未设置" else "$tagCount 个",
                        onClick = { editMenu = false; onPickTags() }
                    )
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
private fun DetailHero(
    book: BookEntity,
    tags: List<String>,
    onEditReadState: (BookReadState?) -> Unit
) {
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
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, start = 24.dp, end = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ReadStateChip(state = book.readState(), onSelect = onEditReadState)
            tags.forEach { tag -> TagChip(tag) }
        }
    }
}

/** 阅读状态胶囊：点开就能手动改四态，和书架长按菜单是同一份写入。 */
@Composable
private fun ReadStateChip(state: BookReadState, onSelect: (BookReadState?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            shape = MoReadTokens.CapsuleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Row(
                modifier = Modifier.padding(start = 10.dp, end = 7.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = state.label(), style = MaterialTheme.typography.labelSmall)
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = "修改阅读状态",
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(14.dp)
                )
            }
        }
        MoReadDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MoReadMenuItem(
                text = "按进度自动判断",
                selected = false,
                onClick = {
                    expanded = false
                    onSelect(null)
                }
            )
            BookReadState.entries.forEach { candidate ->
                MoReadMenuItem(
                    text = candidate.label(),
                    selected = candidate == state,
                    onClick = {
                        expanded = false
                        onSelect(candidate)
                    }
                )
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
