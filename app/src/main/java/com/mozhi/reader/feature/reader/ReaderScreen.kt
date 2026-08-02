package com.mozhi.reader.feature.reader

import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.mozhi.reader.R
import com.mozhi.reader.ui.components.blockSheetDrag
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.BookmarkEntity
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.datastore.ReaderFont
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderTheme
import com.mozhi.reader.feature.reader.engine.ReaderAnnotationMark
import kotlinx.coroutines.launch

private data class AnnotationDraft(
    val chapterIndex: Int,
    val selectedText: String,
    val range: IntRange
)

private data class AnnotationThreadKey(
    val chapterIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int
)

private enum class ReaderSheet {
    CONTENTS,
    BOOKMARKS,
    SETTINGS,
    SEARCH
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: Long,
    onBack: () -> Unit,
    onOpenCompanionChat: (Long) -> Unit = {},
    viewModel: ReaderViewModel = hiltViewModel(),
    aiViewModel: ReaderAiViewModel = hiltViewModel(),
    companionViewModel: ReaderCompanionViewModel = hiltViewModel(),
    selectionMediaViewModel: ReaderSelectionMediaViewModel = hiltViewModel(),
    listenViewModel: ReaderListenViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val aiState by aiViewModel.uiState.collectAsStateWithLifecycle()
    val companionState by companionViewModel.uiState.collectAsStateWithLifecycle()
    val selectionMediaState by selectionMediaViewModel.uiState.collectAsStateWithLifecycle()
    val listenState by listenViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var activeSheet by remember { mutableStateOf<ReaderSheet?>(null) }
    var aiRequest by remember { mutableStateOf<ReaderAiRequest?>(null) }
    var annotationDraft by remember { mutableStateOf<AnnotationDraft?>(null) }
    var annotationThread by remember { mutableStateOf<AnnotationThreadKey?>(null) }
    var ttsDraft by remember { mutableStateOf<String?>(null) }
    var chromeVisible by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    var detailsVisible by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val systemDark = com.mozhi.reader.ui.theme.isDarkTheme()
    val activity = LocalContext.current.findComponentActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    var readerStarted by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val palette = readerPalette(state.settings, systemDark)
    val listeningThisBook = listenState?.bookId == bookId
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // 听书自动翻页：朗读句越过当前页边界时，直接跳到句首所在页（无翻页动画）。
    LaunchedEffect(listeningThisBook) {
        if (!listeningThisBook) return@LaunchedEffect
        listenViewModel.state.collect { listen ->
            if (listen == null || listen.bookId != bookId) return@collect
            if (listen.isPlaying &&
                !viewModel.isShowingPosition(listen.chapterIndex, listen.sentenceStart)
            ) {
                viewModel.goToPosition(listen.chapterIndex, listen.sentenceStart)
            }
        }
    }
    // 听书时手动翻页/跳章：把朗读位置同步到新页首（Legado 语义）。
    // 自动翻页落点恰是当前句起点，会命中 withinSentence 而不回环触发 seek。
    LaunchedEffect(listeningThisBook) {
        if (!listeningThisBook) return@LaunchedEffect
        snapshotFlow { state.currentChapterIndex to state.currentCharOffset }
            .collect { (chapterIndex, charOffset) ->
                val listen = listenViewModel.state.value ?: return@collect
                if (listen.bookId != bookId) return@collect
                val withinSentence = chapterIndex == listen.chapterIndex &&
                    charOffset >= listen.sentenceStart &&
                    charOffset < maxOf(listen.sentenceEnd, listen.sentenceStart + 1)
                if (!withinSentence) listenViewModel.seekTo(chapterIndex, charOffset)
            }
    }
    val chapterTitle = state.chapters
        .getOrNull(state.currentChapterIndex)
        ?.title
        .orEmpty()
    val contextQuote = chapterTitle.ifBlank { state.book?.title.orEmpty() }
    val readerReady = !state.isLoading && state.errorMessage == null
    val annotationMarks = remember(state.annotations) {
        state.annotations.map { annotation ->
            ReaderAnnotationMark(
                id = annotation.id,
                chapterIndex = annotation.chapterIndex,
                startCharOffset = annotation.startCharOffset,
                endCharOffset = annotation.endCharOffset,
                hasComment = annotation.note.isNotBlank()
            )
        }
    }
    val threadAnnotations = annotationThread?.let { key ->
        state.annotations.filter {
            it.chapterIndex == key.chapterIndex &&
                it.startCharOffset == key.startCharOffset &&
                it.endCharOffset == key.endCharOffset
        }
    }.orEmpty()

    LaunchedEffect(bookId) { companionViewModel.bind(bookId) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ReaderEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }
    LaunchedEffect(selectionMediaViewModel) {
        selectionMediaViewModel.events.collect { event ->
            when (event) {
                is SelectionMediaEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }
    DisposableEffect(activity, state.settings.keepScreenOn) {
        if (state.settings.keepScreenOn) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (state.settings.keepScreenOn) {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> readerStarted = true
                Lifecycle.Event.ON_STOP -> {
                    readerStarted = false
                    viewModel.flushProgress()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onReaderPaused()
            viewModel.flushProgress()
        }
    }
    LaunchedEffect(readerStarted, detailsVisible, activeSheet, readerReady, aiRequest) {
        val shouldCountReading = readerStarted &&
            readerReady &&
            !detailsVisible &&
            activeSheet == null &&
            aiRequest == null
        if (shouldCountReading) {
            viewModel.onReaderResumed()
        } else {
            viewModel.onReaderPaused()
        }
    }

    BackHandler(enabled = detailsVisible && activeSheet == null) {
        detailsVisible = false
        chromeVisible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        when {
            state.isLoading -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(color = palette.accent)
                if (state.isPreparingText) {
                    Text(
                        text = "正在准备正文…",
                        color = palette.muted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            state.errorMessage != null -> ReaderError(
                message = state.errorMessage.orEmpty(),
                onBack = onBack,
                modifier = Modifier.align(Alignment.Center)
            )
            else -> ReaderPane(
                controller = viewModel.contentController,
                settings = state.settings,
                palette = palette,
                enabled = activeSheet == null && !detailsVisible && aiRequest == null &&
                    annotationDraft == null && annotationThread == null && ttsDraft == null,
                registerContentHook = viewModel::setContentHook,
                onNotice = { message ->
                    coroutineScope.launch { snackbarHostState.showSnackbar(message) }
                },
                annotations = annotationMarks,
                listenHighlight = listenState?.takeIf { it.bookId == bookId }?.let { listen ->
                    com.mozhi.reader.feature.reader.engine.ListenHighlightSpan(
                        chapterIndex = listen.chapterIndex,
                        startCharOffset = listen.sentenceStart,
                        endCharOffset = listen.sentenceEnd
                    )
                },
                onAiAction = { action, selectionText, contextText ->
                    aiRequest = ReaderAiRequest(
                        action = action,
                        selection = selectionText,
                        context = contextText,
                        bookId = bookId,
                        bookTitle = state.book?.title.orEmpty(),
                        chapterTitle = chapterTitle
                    )
                },
                onAnnotationAction = { selectedText, range ->
                    annotationDraft = AnnotationDraft(
                        chapterIndex = state.currentChapterIndex,
                        selectedText = selectedText,
                        range = range
                    )
                },
                onAnnotationClick = { ids ->
                    state.annotations.firstOrNull { it.id in ids }?.let { annotation ->
                        annotationThread = AnnotationThreadKey(
                            annotation.chapterIndex,
                            annotation.startCharOffset,
                            annotation.endCharOffset
                        )
                    }
                },
                onTtsAction = { selection -> ttsDraft = selection },
                onImageAction = { selectionText, contextText, range ->
                    selectionMediaViewModel.generateImage(
                        bookId = bookId,
                        bookTitle = state.book?.title.orEmpty(),
                        chapterTitle = chapterTitle,
                        chapterIndex = state.currentChapterIndex,
                        charOffset = range.first,
                        selection = selectionText,
                        contextText = contextText
                    )
                },
                onCenterTap = { chromeVisible = !chromeVisible },
                onBoundary = viewModel::onBoundaryHit,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (!detailsVisible) {
            ReaderChrome(
                visible = chromeVisible,
                bookTitle = state.book?.title ?: stringResource(R.string.app_name),
                chapterTitle = chapterTitle.ifBlank { "正在载入" },
                chapterProgress = state.chapterProgress,
                palette = palette,
                onBack = onBack,
                onOpenDetails = {
                    chromeVisible = false
                    detailsVisible = true
                },
                onAddBookmark = viewModel::addBookmark,
                onPrevChapter = viewModel::goToPrevChapter,
                onNextChapter = viewModel::goToNextChapter,
                onSeekChapter = viewModel::seekWithinChapter,
                onContents = { activeSheet = ReaderSheet.CONTENTS },
                onBookmarks = { activeSheet = ReaderSheet.BOOKMARKS },
                onSettings = { activeSheet = ReaderSheet.SETTINGS },
                onTts = {
                    if (listeningThisBook) {
                        // 已在听书：收起菜单露出悬浮控制舱。
                        chromeVisible = false
                    } else {
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            notificationPermissionLauncher.launch(
                                android.Manifest.permission.POST_NOTIFICATIONS
                            )
                        }
                        listenViewModel.start(
                            bookId,
                            state.currentChapterIndex,
                            state.currentCharOffset
                        )
                        chromeVisible = false
                    }
                },
                onCompanion = { onOpenCompanionChat(bookId) },
                onSearch = { activeSheet = ReaderSheet.SEARCH }
            )
        }

        listenState?.takeIf { it.bookId == bookId }?.let { listen ->
            ReaderListenBar(
                state = listen,
                palette = palette,
                visible = !chromeVisible && !detailsVisible,
                onToggle = listenViewModel::toggle,
                onPrevSentence = listenViewModel::prevSentence,
                onNextSentence = listenViewModel::nextSentence,
                onPrevChapter = listenViewModel::prevChapter,
                onNextChapter = listenViewModel::nextChapter,
                onExit = listenViewModel::stop,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        DraggableCompanionOrb(
            persona = companionState.activePersona,
            palette = palette,
            visible = !detailsVisible && readerReady,
            onClick = { onOpenCompanionChat(bookId) },
            interactionSignal = state.currentChapterIndex * 10_000 + state.pageIndex
        )

        ReaderBookDetailOverlay(
            visible = detailsVisible,
            book = state.book,
            chapterTitle = chapterTitle,
            progress = state.readingProgress,
            statistics = state.readingStats,
            bookmarkCount = state.bookmarks.size,
            palette = palette,
            onBack = {
                detailsVisible = false
                chromeVisible = true
            },
            onContinue = {
                detailsVisible = false
                chromeVisible = true
            }
        )

        selectionMediaState.status?.let { status ->
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = palette.glassStrong,
                contentColor = palette.onBackground,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 20.dp,
                        end = 20.dp,
                        bottom = if (chromeVisible && !detailsVisible) 154.dp else 26.dp
                    )
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectionMediaState.isWorking) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = palette.accent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = if (selectionMediaState.isWorking) 9.dp else 0.dp)
                    )
                    TextButton(
                        onClick = if (selectionMediaState.isPlaying) {
                            selectionMediaViewModel::stopAudio
                        } else {
                            selectionMediaViewModel::cancelOperation
                        }
                    ) {
                        Text(if (selectionMediaState.isPlaying) "停止" else "取消", color = palette.accent)
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (chromeVisible && !detailsVisible) 152.dp else 24.dp)
        )
    }

    when (activeSheet) {
        ReaderSheet.CONTENTS -> ModalBottomSheet(
            onDismissRequest = { activeSheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = palette.glassStrong,
            contentColor = palette.onBackground,
            scrimColor = palette.scrim
        ) {
            ContentsSheet(
                chapters = state.chapters,
                currentChapterIndex = state.currentChapterIndex,
                palette = palette,
                onChapterClick = { index ->
                    activeSheet = null
                    viewModel.goToChapter(index)
                }
            )
        }
        ReaderSheet.BOOKMARKS -> ModalBottomSheet(
            onDismissRequest = { activeSheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = palette.glassStrong,
            contentColor = palette.onBackground,
            scrimColor = palette.scrim
        ) {
            BookmarksSheet(
                bookmarks = state.bookmarks,
                palette = palette,
                onBookmarkClick = { bookmark ->
                    activeSheet = null
                    viewModel.goToBookmark(bookmark)
                },
                onDeleteBookmark = viewModel::deleteBookmark
            )
        }
        ReaderSheet.SETTINGS -> ModalBottomSheet(
            onDismissRequest = { activeSheet = null },
            containerColor = palette.glassStrong,
            contentColor = palette.onBackground
        ) {
            ReaderTypographySheet(
                settings = state.settings,
                palette = palette,
                onFontScaleChange = viewModel::setFontScale,
                onFontChange = viewModel::setFont,
                onLineHeightChange = viewModel::setLineHeight,
                onPageMarginChange = viewModel::setPageMargin,
                onThemeChange = viewModel::setTheme,
                onCustomThemeSelect = viewModel::selectCustomTheme,
                onSaveCustomTheme = viewModel::saveCustomTheme,
                onDeleteCustomTheme = viewModel::deleteCustomTheme,
                onAnimationChange = viewModel::setPageTurnAnimation,
                onKeepScreenOnChange = viewModel::setKeepScreenOn
            )
        }
        ReaderSheet.SEARCH -> {
            val searchViewModel: ReaderSearchViewModel = hiltViewModel()
            val searchState by searchViewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(bookId) { searchViewModel.bind(bookId) }
            ModalBottomSheet(
                onDismissRequest = {
                    activeSheet = null
                    searchViewModel.clear()
                },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = palette.glassStrong,
                contentColor = palette.onBackground,
                scrimColor = palette.scrim
            ) {
                ReaderSearchSheet(
                    state = searchState,
                    palette = palette,
                    onQueryChange = searchViewModel::search,
                    onHitClick = { hit ->
                        activeSheet = null
                        searchViewModel.clear()
                        viewModel.goToPosition(hit.chapterIndex, hit.charOffset)
                    }
                )
            }
        }
        null -> Unit
    }

    // 参数已迁到「设置 › 语音朗读」；这里只确认朗读范围，直接按配置开读
    ttsDraft?.let { text ->
        AlertDialog(
            onDismissRequest = { ttsDraft = null },
            title = { Text("朗读当前页") },
            text = {
                Text("将朗读当前页正文（约 ${text.length} 字）。引擎与音色可在「设置 › 语音朗读」调整。")
            },
            confirmButton = {
                TextButton(onClick = {
                    selectionMediaViewModel.speak(bookId = bookId, selection = text)
                    ttsDraft = null
                }) { Text("开始朗读") }
            },
            dismissButton = {
                TextButton(onClick = { ttsDraft = null }) { Text("取消") }
            }
        )
    }

    annotationDraft?.let { draft ->
        AnnotationEditorDialog(
            selectedText = draft.selectedText,
            onDismiss = { annotationDraft = null },
            onSave = { comment ->
                viewModel.addAnnotation(
                    chapterIndex = draft.chapterIndex,
                    selectedText = draft.selectedText,
                    range = draft.range,
                    comment = comment
                )
                annotationDraft = null
            }
        )
    }

    if (annotationThread != null) {
        ModalBottomSheet(
            onDismissRequest = { annotationThread = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = palette.glassStrong,
            contentColor = palette.onBackground
        ) {
            AnnotationThreadSheet(
                annotations = threadAnnotations,
                personas = companionState.personas,
                palette = palette,
                onReply = { comment ->
                    threadAnnotations.firstOrNull()?.let { viewModel.replyToAnnotation(it, comment) }
                },
                onDelete = viewModel::deleteAnnotation,
                onDismiss = { annotationThread = null }
            )
        }
    }

    selectionMediaState.imagePath?.let { imagePath ->
        AlertDialog(
            onDismissRequest = selectionMediaViewModel::dismissImage,
            title = { Text("选段插图") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AsyncImage(
                        model = imagePath,
                        contentDescription = "根据选段生成的插图",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 460.dp)
                    )
                    selectionMediaState.imagePrompt?.let { prompt ->
                        Text(
                            prompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        "图片已保存到本机应用目录。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = selectionMediaViewModel::dismissImage) { Text("完成") }
            }
        )
    }

    aiRequest?.let { request ->
        LaunchedEffect(request) { aiViewModel.start(request) }
        ModalBottomSheet(
            onDismissRequest = {
                aiViewModel.stop()
                aiViewModel.reset()
                aiRequest = null
            },
            containerColor = palette.glassStrong,
            contentColor = palette.onBackground,
            scrimColor = palette.scrim
        ) {
            ReaderAiSheet(
                state = aiState,
                palette = palette,
                onSend = aiViewModel::send,
                onStop = aiViewModel::stop,
                onRetry = aiViewModel::retry,
                onCopy = { text ->
                    clipboardManager.setText(
                        androidx.compose.ui.text.AnnotatedString(text)
                    )
                    coroutineScope.launch { snackbarHostState.showSnackbar("已复制回答") }
                }
            )
        }
    }
}

@Composable
private fun AnnotationEditorDialog(
    selectedText: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var comment by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加段落批注") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "“${selectedText.take(300)}${if (selectedText.length > 300) "…" else ""}”",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("你的批注") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "保存后正文旁会出现“评”标记，点击即可打开该段评论区。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(comment.trim()) }, enabled = comment.isNotBlank()) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun AnnotationThreadSheet(
    annotations: List<AnnotationEntity>,
    personas: List<com.mozhi.reader.core.database.entity.PersonaEntity>,
    palette: ReaderPalette,
    onReply: (String) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var reply by remember { mutableStateOf("") }
    val quote = annotations.firstOrNull()?.selectedText.orEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("段落评论区", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${annotations.size} 条批注",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted
                )
            }
            TextButton(onClick = onDismiss) { Text("完成") }
        }
        if (quote.isNotBlank()) {
            Surface(
                color = palette.accentContainer.copy(alpha = 0.45f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Text(
                    "“${quote.take(500)}${if (quote.length > 500) "…" else ""}”",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        val threadListState = androidx.compose.foundation.lazy.rememberLazyListState()
        LazyColumn(
            state = threadListState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .blockSheetDrag(threadListState),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (annotations.isEmpty()) {
                item { Text("这段批注已删除。", color = palette.muted, modifier = Modifier.padding(12.dp)) }
            }
            items(annotations, key = AnnotationEntity::id) { annotation ->
                val author = annotation.personaId?.let { id ->
                    personas.firstOrNull { it.id == id }?.name ?: "已删除角色"
                } ?: "我的批注"
                Surface(
                    color = palette.glass,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.glassBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(author, style = MaterialTheme.typography.labelMedium, color = palette.accent)
                            Text(
                                annotation.note.ifBlank { "仅标记了这段原文" },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        IconButton(onClick = { onDelete(annotation.id) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除这条批注", tint = palette.muted)
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = reply,
                onValueChange = { reply = it },
                placeholder = { Text("参与这段讨论…") },
                maxLines = 4,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    onReply(reply.trim())
                    reply = ""
                },
                enabled = reply.isNotBlank() && annotations.isNotEmpty()
            ) { Text("发表") }
        }
    }
}

@Composable
private fun ContentsSheet(
    chapters: List<ChapterEntity>,
    currentChapterIndex: Int,
    palette: ReaderPalette,
    onChapterClick: (Int) -> Unit
) {
    // 打开即定位到当前章附近；配合 skipPartiallyExpanded 与满高布局，无需再上滑。
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = (currentChapterIndex - 2).coerceAtLeast(0)
    )
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "目录",
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.onBackground
                )
                Text(
                    text = "共 ${chapters.size} 章 · 读到第 ${currentChapterIndex + 1} 章",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem((currentChapterIndex - 2).coerceAtLeast(0))
                    }
                },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                color = palette.glass,
                contentColor = palette.accent,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.glassBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.MyLocation,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "定位",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .navigationBarsPadding()
                .blockSheetDrag(listState),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 10.dp,
                end = 10.dp,
                bottom = 18.dp
            )
        ) {
            items(chapters, key = ChapterEntity::id) { chapter ->
                val current = chapter.chapterIndex == currentChapterIndex
                Surface(
                    onClick = { onChapterClick(chapter.chapterIndex) },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    color = if (current) {
                        palette.accentContainer.copy(alpha = 0.6f)
                    } else {
                        Color.Transparent
                    },
                    contentColor = palette.onBackground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = (chapter.chapterIndex + 1).toString().padStart(3, '0'),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (current) palette.accent else palette.muted,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Text(
                            text = chapter.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (current) palette.accent else palette.onBackground,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (current) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = "当前章节",
                                tint = palette.accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarksSheet(
    bookmarks: List<BookmarkEntity>,
    palette: ReaderPalette,
    onBookmarkClick: (BookmarkEntity) -> Unit,
    onDeleteBookmark: (Long) -> Unit
) {
    Column(modifier = Modifier.fillMaxHeight()) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp)) {
            Text(
                text = "书签",
                style = MaterialTheme.typography.headlineSmall,
                color = palette.onBackground
            )
            Text(
                text = "共 ${bookmarks.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = palette.muted,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (bookmarks.isEmpty()) {
            Text(
                text = "还没有书签，阅读页右上角菜单即可添加。",
                color = palette.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        } else {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .navigationBarsPadding()
                    .blockSheetDrag(listState),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 10.dp,
                    end = 10.dp,
                    bottom = 18.dp
                )
            ) {
                items(bookmarks, key = BookmarkEntity::id) { bookmark ->
                    Surface(
                        onClick = { onBookmarkClick(bookmark) },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        color = Color.Transparent,
                        contentColor = palette.onBackground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = bookmark.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.onBackground,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    text = bookmark.excerpt.ifBlank { "点击返回该位置" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.muted,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            IconButton(onClick = { onDeleteBookmark(bookmark.id) }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "删除书签",
                                    tint = palette.muted
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderError(message: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("无法打开书籍", style = MaterialTheme.typography.titleLarge)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onBack) { Text("返回书架") }
    }
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
