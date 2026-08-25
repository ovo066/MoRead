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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.outlined.AutoAwesome
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
import androidx.compose.material.icons.outlined.Language
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
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
import com.mozhi.reader.core.datastore.PendingReaderImage
import com.mozhi.reader.ai.media.BookCoverGenerationProgress
import com.mozhi.reader.ai.media.OnlineBookCover
import com.mozhi.reader.ai.embedding.BookEmbeddingProgress
import com.mozhi.reader.ai.embedding.EmbeddingIndexStage
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
    val coverCandidates by viewModel.coverCandidates.collectAsStateWithLifecycle()
    val coverSearchQueries by viewModel.coverSearchQueries.collectAsStateWithLifecycle()
    val coverSearchAgentEnhanced by viewModel.coverSearchAgentEnhanced.collectAsStateWithLifecycle()
    val pendingCover by viewModel.pendingCover.collectAsStateWithLifecycle()
    val coverGenerationProgress by viewModel.coverGenerationProgress.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showBookmarks by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var showCoverPicker by remember { mutableStateOf(false) }
    var showCoverSearch by remember { mutableStateOf(false) }
    var showCoverGenerator by remember { mutableStateOf(false) }
    var showTagPicker by remember { mutableStateOf(false) }
    var showGroupPicker by remember { mutableStateOf(false) }
    var selectedNote by remember { mutableStateOf<NoteEntity?>(null) }
    var showAllNotes by remember { mutableStateOf(false) }
    var showAnnotations by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }
    var showMoreInfo by remember { mutableStateOf(false) }
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
    ) { uri -> uri?.let(viewModel::prepareCover) }

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
                    description = state.description,
                    onEditReadState = viewModel::setReadState,
                    onListen = { onListen(book.id) },
                    onContinueReading = { onContinueReading(book.id) }
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
                    bookmarkCount = state.bookmarks.size,
                    onNotes = { showAllNotes = true },
                    onAnnotations = { showAnnotations = true },
                    onGallery = { showGallery = true },
                    onBookmarks = { showBookmarks = true },
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            item {
                state.embeddingProgress?.let { progress ->
                    BookIndexCard(
                        progress = progress,
                        onEnabledChange = viewModel::setBookIndexEnabled,
                        onRetry = viewModel::retryBookIndex,
                        onRebuild = viewModel::rebuildBookIndex,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
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
                MoreBookDetailsEntry(
                    onClick = { showMoreInfo = true },
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }        }

        if (showMoreInfo) {
            ModalBottomSheet(
                onDismissRequest = { showMoreInfo = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxHeight(0.82f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Text(
                            "更多书籍信息",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
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
                            onPlay = { onPlayAudiobook(book.id) }
                        )
                    }
                    item {
                        FrostedSurface(
                            modifier = Modifier.fillMaxWidth(),
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
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                                )
                            }
                        }
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
                onSearch = {
                    showCoverPicker = false
                    showCoverSearch = true
                    viewModel.searchOnlineCovers()
                },
                onGenerate = {
                    showCoverPicker = false
                    showCoverGenerator = true
                },
                onDismiss = { showCoverPicker = false }
            )
        }

        if (showCoverSearch) {
            OnlineCoverPickerDialog(
                results = coverCandidates,
                queries = coverSearchQueries,
                agentEnhanced = coverSearchAgentEnhanced,
                isWorking = state.isWorking,
                onRefresh = viewModel::searchOnlineCovers,
                onSelect = { candidate ->
                    showCoverSearch = false
                    viewModel.prepareOnlineCover(candidate)
                },
                onDismiss = { showCoverSearch = false }
            )
        }

        if (showCoverGenerator) {
            AiCoverPromptDialog(
                isWorking = state.isWorking,
                onGenerate = { prompt ->
                    showCoverGenerator = false
                    viewModel.generateCover(prompt)
                },
                onDismiss = { showCoverGenerator = false }
            )
        }

        coverGenerationProgress?.takeIf { pendingCover == null }?.let { progress ->
            AiCoverGenerationProgressDialog(
                progress = progress,
                onCancel = viewModel::cancelCoverGeneration
            )
        }

        pendingCover?.let { pending ->
            CoverCropDialog(
                pending = pending,
                isWorking = state.isWorking,
                onConfirm = viewModel::confirmCoverCrop,
                onDismiss = viewModel::discardPendingCover
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
