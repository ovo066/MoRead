package com.mozhi.reader.feature.reader

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import com.mozhi.reader.R
import com.mozhi.reader.MainActivity
import com.mozhi.reader.ui.components.blockSheetDrag
import com.mozhi.reader.core.database.entity.AnnotationColors
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationStyle
import com.mozhi.reader.core.database.entity.BookmarkEntity
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.datastore.ChineseConversionMode
import com.mozhi.reader.core.datastore.ReaderFont
import com.mozhi.reader.core.datastore.PendingReaderFont
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderTextReplacementRule
import com.mozhi.reader.core.datastore.ReaderTheme
import com.mozhi.reader.core.datastore.activeThemeSlot
import com.mozhi.reader.core.datastore.chineseConversionModeFor
import com.mozhi.reader.core.datastore.resolveForBook
import com.mozhi.reader.core.datastore.withBookThemeSelection
import com.mozhi.reader.feature.reader.engine.ReaderAnnotationMark
import com.mozhi.reader.feature.reader.engine.ReaderIllustrationMark
import com.mozhi.reader.feature.reader.engine.InlineMarkerKind
import com.mozhi.reader.feature.reader.engine.InlineMarkerReservation
import com.mozhi.reader.core.library.ResolvedTextAnchor
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** 即划即改浮条：刚落的划线 id + 浮条位置（沿用选区工具栏的锚点位）。 */
private data class AnnotationInkFloater(
    val annotationId: Long,
    val topPx: Int
)

private data class AnnotationThreadKey(val annotationIds: Set<Long>)

private data class ReaderReturnPosition(
    val chapterIndex: Int,
    val charOffset: Int
)

private data class PresentedListenRange(
    val chapterIndex: Int,
    val sourceStart: Int,
    val sourceEnd: Int,
    val range: ResolvedTextAnchor
)

internal data class TextEditDraft(
    val chapterIndex: Int,
    val range: IntRange,
    val originalText: String
)

private enum class ReaderSheet {
    CONTENTS,
    BOOKMARKS,
    SETTINGS,
    SEARCH,
    REIDENTIFY_CHAPTERS,
    TEXT_REPLACEMENT_RULES
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: Long,
    onBack: () -> Unit,
    onOpenCompanionChat: (Long) -> Unit = {},
    onOpenListenPlayer: (Long) -> Unit = {},
    /** 从聊天页「跳到原文」带回来的位置；消费一次后由调用方清空。 */
    pendingLocate: ReaderLocateRequest? = null,
    onPendingLocateConsumed: () -> Unit = {},
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
    val sleepTimer by listenViewModel.sleepTimer.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var activeSheet by remember { mutableStateOf<ReaderSheet?>(null) }
    // 排版细调走屏幕中央的悬浮卡片，不是弹层里的又一张二级页：那一类设置每改一格都要看重排，
    // 半屏弹层会把正文下半屏压掉。
    var typographyCardVisible by remember { mutableStateOf(false) }
    var aiRequest by remember { mutableStateOf<ReaderAiRequest?>(null) }
    var inkFloater by remember { mutableStateOf<AnnotationInkFloater?>(null) }
    var annotationThread by remember { mutableStateOf<AnnotationThreadKey?>(null) }
    var linkPreview by remember { mutableStateOf<EpubLinkPreview?>(null) }
    var returnPosition by remember { mutableStateOf<ReaderReturnPosition?>(null) }
    var ttsDraft by remember { mutableStateOf<String?>(null) }
    var locateHighlight by remember {
        mutableStateOf<com.mozhi.reader.feature.reader.engine.TransientHighlightSpan?>(null)
    }
    var textEditDraft by remember { mutableStateOf<TextEditDraft?>(null) }
    var textRuleDraft by remember { mutableStateOf<ReaderTextReplacementRule?>(null) }
    var aiTextRuleDialogVisible by remember { mutableStateOf(false) }
    var hardwarePageTurnRequest by remember { mutableStateOf<ReaderPageTurnRequest?>(null) }
    var pendingFont by remember { mutableStateOf<PendingReaderFont?>(null) }
    var pendingFontName by remember { mutableStateOf("") }
    var chromeVisible by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    var detailsVisible by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var listenTimerVisible by remember { mutableStateOf(false) }
    val systemDark = com.mozhi.reader.ui.theme.isDarkTheme()
    val context = LocalContext.current
    val activity = context.findComponentActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    var readerStarted by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    // 日夜各存一套配色：先定此刻用哪一槽，再把它解析到顶层字段，调色板与渲染只认解析结果。
    val themeSlot = state.settings.activeThemeSlot(systemDark)
    val bookThemeSettings = state.settings.withBookThemeSelection(bookId)
    val readerSettings = state.settings.resolveForBook(bookId, themeSlot)
    val palette = readerPalette(readerSettings, systemDark)
    val listeningThisBook = listenState?.bookId == bookId
    val conversionMode = state.settings.chineseConversionModeFor(bookId)
    var presentedListenRange by remember { mutableStateOf<PresentedListenRange?>(null) }
    LaunchedEffect(
        listenState?.bookId,
        listenState?.chapterIndex,
        listenState?.sentenceStart,
        listenState?.sentenceEnd,
        conversionMode,
        state.isLoading
    ) {
        val listen = listenState
        presentedListenRange = null
        if (listen == null || listen.bookId != bookId) return@LaunchedEffect
        val range = viewModel.resolveSourceRange(
            listen.chapterIndex,
            listen.sentenceStart,
            listen.sentenceEnd
        ) ?: return@LaunchedEffect
        presentedListenRange = PresentedListenRange(
            listen.chapterIndex,
            listen.sentenceStart,
            listen.sentenceEnd,
            range
        )
    }
    val currentListenRange = presentedListenRange?.takeIf { presented ->
        val listen = listenState
        listen != null && listen.bookId == bookId &&
            presented.chapterIndex == listen.chapterIndex &&
            presented.sourceStart == listen.sentenceStart &&
            presented.sourceEnd == listen.sentenceEnd
    }
    // 滚动模式：听书「按页跳」与「翻页回写朗读位置」都不适用，跟读交给滚动面自己做。
    val scrollMode = state.settings.pageMode == com.mozhi.reader.core.datastore.PageMode.SCROLL
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    // 导入背景图落到哪一槽（日/夜），由发起入口指定；launcher 回调时已经离开那次点击。
    var backgroundImportSlot by remember {
        mutableStateOf(com.mozhi.reader.core.datastore.ReaderThemeSlot.DAY)
    }
    val backgroundImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importBackgroundImage(it, backgroundImportSlot) } }
    val customFontLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(viewModel::importCustomFont) }

    // 听书自动翻页：朗读句越过当前页边界时，直接跳到句首所在页（无翻页动画）。
    LaunchedEffect(listeningThisBook, scrollMode, currentListenRange, listenState?.isPlaying) {
        if (!listeningThisBook || scrollMode || listenState?.isPlaying != true) {
            return@LaunchedEffect
        }
        currentListenRange?.let { presented ->
            if (!viewModel.isShowingPosition(presented.chapterIndex, presented.range.start)) {
                viewModel.goToPosition(presented.chapterIndex, presented.range.start)
            }
        }
    }
    // 听书时手动翻页/跳章：把朗读位置同步到新页首（Legado 语义）。
    // 自动翻页落点恰是当前句起点，会命中 withinSentence 而不回环触发 seek。
    LaunchedEffect(listeningThisBook, scrollMode, conversionMode) {
        if (!listeningThisBook || scrollMode) return@LaunchedEffect
        snapshotFlow { state.currentChapterIndex to state.currentCharOffset }
            // 页面从沉浸播放页返回时会先发一次旧阅读位置；这不是用户 seek。
            .drop(1)
            .collect { (chapterIndex, charOffset) ->
                val listen = listenViewModel.state.value ?: return@collect
                if (listen.bookId != bookId) return@collect
                val sentence = viewModel.resolveSourceRange(
                    listen.chapterIndex,
                    listen.sentenceStart,
                    listen.sentenceEnd
                )
                val withinSentence = chapterIndex == listen.chapterIndex &&
                    sentence != null && charOffset >= sentence.start &&
                    charOffset < maxOf(sentence.end, sentence.start + 1)
                if (!withinSentence) {
                    listenViewModel.seekTo(
                        chapterIndex,
                        viewModel.sourceOffsetForDisplayed(chapterIndex, charOffset)
                    )
                }
            }
    }
    val chapterTitle = state.chapters
        .getOrNull(state.currentChapterIndex)
        ?.title
        .orEmpty()
    val contextQuote = chapterTitle.ifBlank { state.book?.title.orEmpty() }
    val readerReady = !state.isLoading && state.errorMessage == null

    LaunchedEffect(pendingLocate, readerReady) {
        val request = pendingLocate ?: return@LaunchedEffect
        if (!readerReady) return@LaunchedEffect
        val range = viewModel.resolveSourceRange(
            request.chapterIndex,
            request.startCharOffset,
            request.endCharOffset,
            request.sourceAnchorJson
        ) ?: return@LaunchedEffect
        viewModel.goToPosition(request.chapterIndex, range.start)
        locateHighlight = com.mozhi.reader.feature.reader.engine.TransientHighlightSpan(
            chapterIndex = request.chapterIndex,
            startCharOffset = range.start,
            endCharOffset = range.end
        )
        onPendingLocateConsumed()
    }

    // 退场计时必须独立成一个 effect：它若和消费请求写在一起，onPendingLocateConsumed()
    // 把 pendingLocate 置空会立刻改变 key、连同还没跑完的 delay 一起取消，
    // 高亮就再也不会熄灭——那就成了「永久划线」而不是「示意一下」。
    LaunchedEffect(locateHighlight) {
        if (locateHighlight == null) return@LaunchedEffect
        kotlinx.coroutines.delay(LOCATE_HIGHLIGHT_MS)
        locateHighlight = null
    }
    val contentVisible = readerReady && state.isContentReady
    val canTurnWithVolume by rememberUpdatedState(
        contentVisible && activeSheet == null && !detailsVisible && aiRequest == null &&
            inkFloater == null && annotationThread == null && linkPreview == null && ttsDraft == null &&
            !typographyCardVisible
    )
    DisposableEffect(activity, state.settings.volumeKeysPageTurn) {
        val host = activity as? MainActivity
        if (state.settings.volumeKeysPageTurn) {
            host?.setVolumeKeyPageTurnHandler { previous ->
                if (canTurnWithVolume) {
                    hardwarePageTurnRequest = ReaderPageTurnRequest(
                        sequence = (hardwarePageTurnRequest?.sequence ?: 0) + 1,
                        direction = if (previous) {
                            PageTurnDirection.PREVIOUS
                        } else {
                            PageTurnDirection.NEXT
                        }
                    )
                }
            }
        }
        onDispose { host?.setVolumeKeyPageTurnHandler(null) }
    }
    // 本地书秒开是常态，加载圈闪一下反而像卡顿；只有真慢（导入准备/超长章排版）才转圈。
    var showLoadingHint by remember { mutableStateOf(false) }
    LaunchedEffect(contentVisible) {
        showLoadingHint = false
        if (!contentVisible) {
            kotlinx.coroutines.delay(450)
            showLoadingHint = true
        }
    }
    // AI 批注开关只影响阅读页渲染；关闭时角色划线与标记不再出现（详情页仍可回顾）
    val visibleAnnotations = remember(state.annotations, state.showAiAnnotations) {
        if (state.showAiAnnotations) {
            state.annotations
        } else {
            state.annotations.filter { it.personaId == null }
        }
    }
    val markKey = conversionMode
    val annotationMarks = remember(
        visibleAnnotations,
        state.repliedAnnotationIds,
        state.contentRevision,
        markKey
    ) {
        visibleAnnotations.mapNotNull { annotation ->
            val range = viewModel.resolveAnnotationRange(annotation) ?: return@mapNotNull null
            ReaderAnnotationMark(
                id = annotation.id,
                chapterIndex = annotation.chapterIndex,
                startCharOffset = range.start,
                endCharOffset = range.end,
                hasComment = annotation.note.isNotBlank() ||
                    annotation.id in state.repliedAnnotationIds,
                style = annotation.style,
                colorTag = annotation.colorTag.ifBlank {
                    annotation.personaId?.let(AnnotationColors::forPersona).orEmpty()
                }
            )
        }
    }
    val illustrationMarks = remember(state.illustrations, state.contentRevision, markKey) {
        state.illustrations.mapNotNull { illustration ->
            val chapter = illustration.chapterIndex ?: return@mapNotNull null
            val range = viewModel.resolveIllustrationRange(illustration) ?: return@mapNotNull null
            ReaderIllustrationMark(
                id = illustration.id,
                chapterIndex = chapter,
                startCharOffset = range.start,
                endCharOffset = range.end
            )
        }
    }
    LaunchedEffect(annotationMarks, illustrationMarks) {
        val reservations = buildList {
            annotationMarks.filter { it.hasComment }.forEach {
                add(it.chapterIndex to InlineMarkerReservation(it.endCharOffset, InlineMarkerKind.ANNOTATION))
            }
            illustrationMarks.forEach {
                add(it.chapterIndex to InlineMarkerReservation(it.endCharOffset, InlineMarkerKind.ILLUSTRATION))
            }
        }.distinct().groupBy({ it.first }, { it.second })
        viewModel.contentController.setInlineMarkers(reservations)
    }
    val threadAnnotations = annotationThread?.let { key ->
        state.annotations.filter { it.id in key.annotationIds }
    }.orEmpty()

    LaunchedEffect(bookId) { companionViewModel.bind(bookId) }

    // 翻页/跳章即收起样式浮条
    LaunchedEffect(state.currentChapterIndex, state.pageIndex) { inkFloater = null }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ReaderEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
                is ReaderEvent.ConfirmFontImport -> {
                    pendingFont = event.pending
                    pendingFontName = event.pending.detectedName
                }
                is ReaderEvent.TextReplacementRuleSuggested -> textRuleDraft = event.rule
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
    // 阅读页窗口亮度。只改本窗口的 LayoutParams，不碰系统全局亮度（那需要写入设置的权限，
    // 而且退出应用后还得还原，任何一次崩溃都会把用户的手机留在一个奇怪的亮度上）。
    // onDispose 一律写回 BRIGHTNESS_OVERRIDE_NONE：退出阅读页立刻回到系统亮度。
    DisposableEffect(activity, state.settings.screenBrightness) {
        val window = activity?.window
        window?.let {
            it.attributes = it.attributes.apply {
                screenBrightness = state.settings.screenBrightness.let { value ->
                    if (value < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    else value.coerceIn(0f, 1f)
                }
            }
        }
        onDispose {
            window?.let {
                it.attributes = it.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }
    val hideStatusBar = state.settings.immersiveReading && !chromeVisible
    DisposableEffect(activity, hideStatusBar) {
        val statusBars = WindowInsetsCompat.Type.statusBars()
        val controller = activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView)
        }
        if (hideStatusBar) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(statusBars)
        } else {
            controller?.show(statusBars)
        }
        onDispose {
            controller?.show(statusBars)
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

    BackHandler(enabled = typographyCardVisible) { typographyCardVisible = false }

    // 半屏弹层与悬浮排版卡片共用同一份回调，所以提到两者之上装配一次。
    val typographyActions = ReaderTypographyActions(
        onFontScaleChange = viewModel::setFontScale,
        onFontChange = viewModel::setFont,
        onCustomFontSelect = viewModel::selectCustomFont,
        onImportFont = { customFontLauncher.launch("*/*") },
        onLineHeightChange = viewModel::setLineHeight,
        onPublisherStyleModeChange = viewModel::setPublisherStyleMode,
        onPageMarginLeftChange = viewModel::setPageMarginLeft,
        onPageMarginRightChange = viewModel::setPageMarginRight,
        onPageMarginTopChange = viewModel::setPageMarginTop,
        onPageMarginBottomChange = viewModel::setPageMarginBottom,
        onHeaderMarginTopChange = viewModel::setHeaderMarginTop,
        onFooterMarginBottomChange = viewModel::setFooterMarginBottom,
        onFontWeightChange = viewModel::setFontWeight,
        onLetterSpacingChange = viewModel::setLetterSpacing,
        onParagraphSpacingChange = viewModel::setParagraphSpacing,
        onFirstLineIndentChange = viewModel::setFirstLineIndent,
        onTitleScaleChange = viewModel::setTitleScale,
        onTitleTopSpacingChange = viewModel::setTitleTopSpacing,
        onTitleBottomSpacingChange = viewModel::setTitleBottomSpacing,
        onTextJustificationChange = viewModel::setTextJustification,
        onShowHeaderChange = viewModel::setShowHeader,
        onShowFooterChange = viewModel::setShowFooter,
        onThemeChange = viewModel::setTheme,
        onCustomThemeSelect = viewModel::selectCustomTheme,
        onSaveCustomTheme = viewModel::saveCustomTheme,
        onSaveBookCustomTheme = viewModel::saveBookCustomTheme,
        onDeleteCustomTheme = viewModel::deleteCustomTheme,
        onDayNightAutoChange = viewModel::setDayNightThemeAuto,
        onBookThemeEnabledChange = viewModel::setBookThemeEnabled,
        onBookThemeChange = viewModel::setBookTheme,
        onBookCustomThemeSelect = viewModel::selectBookCustomTheme,
        onImportBackground = { slot ->
            backgroundImportSlot = slot
            backgroundImageLauncher.launch(arrayOf("image/*"))
        },
        onBackgroundImageSelect = viewModel::selectBackgroundImage,
        onClearBackground = viewModel::clearBackgroundImage,
        onBackgroundOpacityChange = viewModel::setBackgroundImageOpacity,
        onSyntaxHighlightEnabledChange = viewModel::setSyntaxHighlightEnabled,
        onSaveSyntaxRule = viewModel::saveSyntaxHighlightRule,
        onDeleteSyntaxRule = viewModel::deleteSyntaxHighlightRule,
        onAnimationChange = viewModel::setPageTurnAnimation,
        onPageModeChange = viewModel::setPageMode,
        onKeepScreenOnChange = viewModel::setKeepScreenOn,
        onImmersiveReadingChange = viewModel::setImmersiveReading,
        onVolumeKeysPageTurnChange = viewModel::setVolumeKeysPageTurn,
        onChineseConversionModeChange = viewModel::setChineseConversionMode,
        onScreenBrightnessChange = viewModel::setScreenBrightness
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        when {
            state.errorMessage != null -> ReaderError(
                message = state.errorMessage.orEmpty(),
                onBack = onBack,
                modifier = Modifier.align(Alignment.Center)
            )
            readerReady -> {
                val paneEnabled = contentVisible && activeSheet == null && !detailsVisible &&
                    aiRequest == null && inkFloater == null && annotationThread == null &&
                    linkPreview == null && ttsDraft == null && textEditDraft == null && !typographyCardVisible
                // 听书当前句优先：正在朗读时它才是「此刻读到哪」，引文高亮已完成使命。
                val paneListenHighlight = currentListenRange?.let { listen ->
                    com.mozhi.reader.feature.reader.engine.TransientHighlightSpan(
                        chapterIndex = listen.chapterIndex,
                        startCharOffset = listen.range.start,
                        endCharOffset = listen.range.end
                    )
                } ?: locateHighlight
                val paneNotice: (String) -> Unit = { message ->
                    coroutineScope.launch { snackbarHostState.showSnackbar(message) }
                }
                val paneAiAction: (com.mozhi.reader.ai.prompt.SelectionAiAction, String, String) -> Unit =
                    { action, selectionText, contextText ->
                        aiRequest = ReaderAiRequest(
                            action = action,
                            selection = selectionText,
                            context = contextText,
                            bookId = bookId,
                            bookTitle = state.book?.title.orEmpty(),
                            chapterTitle = chapterTitle
                        )
                    }
                val paneAnnotationAction: (String, IntRange, Int) -> Unit =
                    { selectedText, range, anchorTopPx ->
                        // 即划即改：一击先落上次样式的划线，浮条随后浮出供实时微调
                        val chapterIndex = state.currentChapterIndex
                        coroutineScope.launch {
                            val id = viewModel.quickAnnotate(chapterIndex, selectedText, range)
                            if (id != null) {
                                inkFloater = AnnotationInkFloater(annotationId = id, topPx = anchorTopPx)
                            }
                        }
                    }
                val paneAnnotationClick: (List<Long>) -> Unit = { ids ->
                    annotationThread = AnnotationThreadKey(ids.toSet())
                }
                val paneIllustrationClick: (List<Long>) -> Unit = { ids ->
                    state.illustrations.firstOrNull { it.id in ids }?.let { illustration ->
                        selectionMediaViewModel.showSavedImage(
                            illustration.imagePath,
                            illustration.prompt
                        )
                    }
                }
                val paneLinkAction: (com.mozhi.reader.feature.reader.engine.ReaderPageLink) -> Unit = { link ->
                    coroutineScope.launch {
                        val preview = viewModel.previewEpubLink(link)
                        if (preview == null) snackbarHostState.showSnackbar("无法解析这个书内链接")
                        else linkPreview = preview
                    }
                }
                val paneTtsAction: (String) -> Unit = { selection -> ttsDraft = selection }
                val paneEditText: ((String, IntRange) -> Unit)? =
                    if (state.settings.chineseConversionModeFor(bookId) == ChineseConversionMode.OFF) {
                        { selection, range ->
                            textEditDraft = TextEditDraft(
                                chapterIndex = state.currentChapterIndex,
                                range = range,
                                originalText = selection
                            )
                        }
                    } else {
                        null
                    }
                val paneImageAction: (String, String, IntRange) -> Unit =
                    { selectionText, contextText, range ->
                        selectionMediaViewModel.generateImage(
                            bookId = bookId,
                            bookTitle = state.book?.title.orEmpty(),
                            chapterTitle = chapterTitle,
                            chapterIndex = state.currentChapterIndex,
                            charOffset = range.first,
                            textAnchorJson = viewModel.textAnchorJsonFor(
                                state.currentChapterIndex,
                                range
                            ),
                            selection = selectionText,
                            contextText = contextText
                        )
                    }
                if (scrollMode) {
                    ReaderScrollPane(
                        controller = viewModel.contentController,
                        settings = readerSettings,
                        palette = palette,
                        enabled = paneEnabled,
                        registerContentHook = viewModel::setContentHook,
                        onScrollSupersedesNavigation = viewModel::supersedePendingNavigation,
                        onCenterTap = { chromeVisible = !chromeVisible },
                        onBoundary = viewModel::onBoundaryHit,
                        onNotice = paneNotice,
                        annotations = annotationMarks,
                        illustrations = illustrationMarks,
                        transientHighlight = paneListenHighlight,
                        listenPlaying = listenState?.takeIf { it.bookId == bookId }?.isPlaying == true,
                        onAiAction = paneAiAction,
                        onAnnotationAction = paneAnnotationAction,
                        onAnnotationClick = paneAnnotationClick,
                        onIllustrationClick = paneIllustrationClick,
                        onLinkClick = paneLinkAction,
                        onTtsAction = paneTtsAction,
                        onImageAction = paneImageAction,
                        onEditText = paneEditText,
                        pageTurnRequest = hardwarePageTurnRequest,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    ReaderPane(
                        controller = viewModel.contentController,
                        settings = readerSettings,
                        palette = palette,
                        enabled = paneEnabled,
                        registerContentHook = viewModel::setContentHook,
                        onNotice = paneNotice,
                        annotations = annotationMarks,
                        illustrations = illustrationMarks,
                        transientHighlight = paneListenHighlight,
                        onAiAction = paneAiAction,
                        onAnnotationAction = paneAnnotationAction,
                        onAnnotationClick = paneAnnotationClick,
                        onIllustrationClick = paneIllustrationClick,
                        onLinkClick = paneLinkAction,
                        onTtsAction = paneTtsAction,
                        onImageAction = paneImageAction,
                        onEditText = paneEditText,
                        pageTurnRequest = hardwarePageTurnRequest,
                        onCenterTap = { chromeVisible = !chromeVisible },
                        onBoundary = viewModel::onBoundaryHit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            else -> Unit // 书籍数据还没到：只画纸色底，转圈交给下面的慢路径提示
        }

        // 慢路径才转圈：秒开时 contentVisible 早在这 450ms 之前就为真了。
        if (!contentVisible && (showLoadingHint || state.isPreparingText)) {
            Column(
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
        }

        if (!detailsVisible) {
            ReaderChrome(
                // 排版悬浮卡片浮出时收起上下工具栏：这一类设置就是要看正文，栏子占着两头没意义。
                visible = chromeVisible && !typographyCardVisible,
                bookTitle = state.book?.title ?: stringResource(R.string.app_name),
                chapterTitle = chapterTitle.ifBlank { "正在载入" },
                chapterProgress = state.chapterProgress,
                palette = palette,
                onBack = onBack,
                onOpenDetails = {
                    chromeVisible = false
                    detailsVisible = true
                },
                isCurrentPositionBookmarked = viewModel.isCurrentPositionBookmarked(),
                onToggleBookmark = viewModel::toggleBookmark,
                onPrevChapter = viewModel::goToPrevChapter,
                onNextChapter = viewModel::goToNextChapter,
                onSeekChapter = viewModel::seekWithinChapter,
                onContents = { activeSheet = ReaderSheet.CONTENTS },
                onBookmarks = { activeSheet = ReaderSheet.BOOKMARKS },
                onSettings = { activeSheet = ReaderSheet.SETTINGS },
                onTts = {
                    if (listeningThisBook) {
                        onOpenListenPlayer(bookId)
                    } else {
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            notificationPermissionLauncher.launch(
                                android.Manifest.permission.POST_NOTIFICATIONS
                            )
                        }
                        coroutineScope.launch {
                            listenViewModel.start(
                                bookId,
                                state.currentChapterIndex,
                                viewModel.sourceOffsetForDisplayed(
                                    state.currentChapterIndex,
                                    state.currentCharOffset
                                )
                            )
                        }
                    }
                },
                onCompanion = { onOpenCompanionChat(bookId) },
                onSearch = { activeSheet = ReaderSheet.SEARCH },
                onReidentifyChapters = { activeSheet = ReaderSheet.REIDENTIFY_CHAPTERS },
                onTextReplacementRules = { activeSheet = ReaderSheet.TEXT_REPLACEMENT_RULES }
            )
        }

        // 听书悬浮球独立于 chrome：沉浸阅读时正在播的东西也必须一直够得着。
        listenState?.takeIf { it.bookId == bookId && contentVisible && !detailsVisible }
            ?.let { current ->
                ReaderListenOrb(
                    state = current,
                    sleepTimer = sleepTimer,
                    palette = palette,
                    onOpenPlayer = { onOpenListenPlayer(bookId) },
                    onToggle = listenViewModel::toggle,
                    onPrevSentence = listenViewModel::prevSentence,
                    onNextSentence = listenViewModel::nextSentence,
                    onPrevChapter = listenViewModel::prevChapter,
                    onNextChapter = listenViewModel::nextChapter,
                    onSleepTimer = { listenTimerVisible = true },
                    onExit = listenViewModel::stop
                )
            }

        if (listenTimerVisible) {
            com.mozhi.reader.ui.components.SleepTimerSheet(
                current = sleepTimer,
                onDismiss = { listenTimerVisible = false },
                onSelect = listenViewModel::setSleepTimer
            )
        }

        DraggableCompanionOrb(
            persona = companionState.activePersona,
            palette = palette,
            visible = !detailsVisible && contentVisible,
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

        ReaderSelectionMediaStatus(
            state = selectionMediaState,
            palette = palette,
            bottomPadding = if (chromeVisible && !detailsVisible) 154.dp else 26.dp,
            onStop = selectionMediaViewModel::stopAudio,
            onCancel = selectionMediaViewModel::cancelOperation
        )

        val activeInkFloater = inkFloater
        ReaderAnnotationInkOverlay(
            annotation = activeInkFloater?.let { floater ->
                state.annotations.firstOrNull { it.id == floater.annotationId }
            },
            topPx = activeInkFloater?.topPx,
            palette = palette,
            onDismiss = { inkFloater = null },
            onChange = { style, color ->
                activeInkFloater?.let { floater ->
                    viewModel.updateAnnotationStyle(floater.annotationId, style, color)
                }
            }
        )

        ReaderTypographyCard(
            visible = typographyCardVisible,
            settings = state.settings,
            palette = palette,
            actions = typographyActions,
            onBack = {
                typographyCardVisible = false
                activeSheet = ReaderSheet.SETTINGS
            },
            onClose = { typographyCardVisible = false }
        )

        linkPreview?.let { preview ->
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .widthIn(max = 340.dp),
                shape = RoundedCornerShape(18.dp),
                color = palette.glassStrong.compositeOver(palette.background),
                contentColor = palette.onBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.glassBorder),
                shadowElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        preview.label.ifBlank { "书内链接" },
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.accent
                    )
                    if (preview.targetTitle.isNotBlank()) {
                        Text(
                            preview.targetTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.muted
                        )
                    }
                    Text(
                        preview.content,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 9
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { linkPreview = null }) { Text("关闭") }
                        TextButton(onClick = {
                            val externalUrl = preview.externalUrl
                            val targetChapter = preview.targetChapterIndex
                            linkPreview = null
                            if (externalUrl != null) {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(externalUrl))
                                    )
                                }
                            } else if (targetChapter != null) {
                                returnPosition = ReaderReturnPosition(
                                    state.currentChapterIndex,
                                    state.currentCharOffset
                                )
                                viewModel.goToEpubLink(preview)
                            }
                        }) { Text("跳转") }
                    }
                }
            }
        }

        returnPosition?.let { origin ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (chromeVisible && !detailsVisible) 150.dp else 20.dp)
                    .navigationBarsPadding(),
                shape = RoundedCornerShape(14.dp),
                color = palette.glassStrong.compositeOver(palette.background),
                contentColor = palette.onBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.glassBorder),
                shadowElevation = 8.dp
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        viewModel.goToPosition(origin.chapterIndex, origin.charOffset)
                        returnPosition = null
                    }) { Text("返回原进度") }
                    Box(
                        modifier = Modifier
                            .size(width = 1.dp, height = 22.dp)
                            .background(palette.glassBorder)
                    )
                    TextButton(onClick = { returnPosition = null }) { Text("关闭") }
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
                tocEntries = state.tocEntries,
                currentChapterIndex = state.currentChapterIndex,
                palette = palette,
                onChapterClick = { index, href ->
                    activeSheet = null
                    viewModel.goToTocEntry(index, href)
                }
            )
        }
        ReaderSheet.BOOKMARKS -> ModalBottomSheet(
            onDismissRequest = { activeSheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
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
            // 无遮罩＝正文透出来的只该是面板「外面」那部分；面板本体要不透明，
            // 否则 5% 的玻璃透明度会在深色纸上透出一层幽灵正文。
            containerColor = palette.glassStrong.compositeOver(palette.background),
            contentColor = palette.onBackground,
            // 不加遮罩：调字号/行距时正文必须看得见，否则「改完什么样」要关掉面板才知道。
            // 半高吸附让正文留在视野里，重排当场可见。
            scrimColor = Color.Transparent,
            sheetState = rememberModalBottomSheetState()
        ) {
            ReaderTypographySheet(
                settings = bookThemeSettings,
                bookId = bookId,
                slot = themeSlot,
                palette = palette,
                actions = typographyActions,
                onOpenTypographyCard = {
                    activeSheet = null
                    typographyCardVisible = true
                }
            )
        }
        ReaderSheet.REIDENTIFY_CHAPTERS -> ModalBottomSheet(
            onDismissRequest = { activeSheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = palette.glassStrong,
            contentColor = palette.onBackground,
            scrimColor = palette.scrim
        ) {
            ReidentifyChaptersSheet(
                palette = palette,
                onApply = { regex ->
                    activeSheet = null
                    viewModel.reidentifyChapters(regex)
                }
            )
        }
        ReaderSheet.TEXT_REPLACEMENT_RULES -> ModalBottomSheet(
            onDismissRequest = { activeSheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = palette.glassStrong,
            contentColor = palette.onBackground,
            scrimColor = palette.scrim
        ) {
            TextReplacementRulesSheet(
                rules = state.settings.textReplacementRules,
                palette = palette,
                onAdd = {
                    textRuleDraft = ReaderTextReplacementRule(
                        id = 0L,
                        name = "",
                        pattern = ""
                    )
                },
                onEdit = { rule -> textRuleDraft = rule },
                onDelete = { rule -> viewModel.deleteTextReplacementRule(rule.id) },
                onToggle = { rule, enabled ->
                    viewModel.saveTextReplacementRule(rule.copy(enabled = enabled))
                },
                onRequestAi = { aiTextRuleDialogVisible = true },
                onApply = viewModel::applyTextReplacementRules
            )
        }
        ReaderSheet.SEARCH -> {
            val searchViewModel: ReaderSearchViewModel = hiltViewModel()
            val searchState by searchViewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(bookId, conversionMode) {
                searchViewModel.bind(bookId, conversionMode)
            }
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
                        locateHighlight = com.mozhi.reader.feature.reader.engine.TransientHighlightSpan(
                            chapterIndex = hit.chapterIndex,
                            startCharOffset = hit.charOffset,
                            endCharOffset = hit.charOffset + hit.matchLength
                        )
                    }
                )
            }
        }
        null -> Unit
    }

    textRuleDraft?.let { rule ->
        TextReplacementRuleEditorDialog(
            initial = rule,
            onDismiss = { textRuleDraft = null },
            onSave = { updated ->
                viewModel.saveTextReplacementRule(updated)
                textRuleDraft = null
            }
        )
    }

    if (aiTextRuleDialogVisible) {
        AiTextReplacementRuleDialog(
            onDismiss = { aiTextRuleDialogVisible = false },
            onGenerate = { requirement ->
                aiTextRuleDialogVisible = false
                viewModel.generateTextReplacementRule(requirement)
            }
        )
    }

    ReaderSpeechConfirmDialog(
        text = ttsDraft,
        onDismiss = { ttsDraft = null },
        onConfirm = { text ->
            selectionMediaViewModel.speak(bookId = bookId, selection = text)
            ttsDraft = null
        }
    )

    if (annotationThread != null) {
        val discussionViewModel: AnnotationDiscussionViewModel = hiltViewModel()
        val discussionState by discussionViewModel.uiState.collectAsStateWithLifecycle()
        val threadIds = threadAnnotations.map { it.id }
        LaunchedEffect(threadIds) { discussionViewModel.open(threadIds) }
        ModalBottomSheet(
            onDismissRequest = {
                annotationThread = null
                discussionViewModel.close()
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = palette.glassStrong,
            contentColor = palette.onBackground,
            scrimColor = palette.scrim
        ) {
            AnnotationDiscussionSheet(
                annotations = threadAnnotations,
                replies = discussionState.replies,
                streaming = discussionState.streaming,
                error = discussionState.error,
                personas = companionState.personas,
                illustrations = state.illustrations,
                palette = palette,
                onPlayAudio = selectionMediaViewModel::playCachedSpeech,
                onSend = { target, text, respondPersonaId ->
                    discussionViewModel.sendUserReply(bookId, target, text, respondPersonaId)
                },
                onUpdateStyle = viewModel::updateAnnotationStyle,
                onDeleteAnnotation = viewModel::deleteAnnotation,
                onDeleteReply = discussionViewModel::deleteReply,
                onCancelStreaming = discussionViewModel::cancelStreaming,
                onDismiss = {
                    annotationThread = null
                    discussionViewModel.close()
                }
            )
        }
    }

    ReaderGeneratedImageDialog(
        state = selectionMediaState,
        palette = palette,
        onDismiss = selectionMediaViewModel::dismissImage,
        onReroll = selectionMediaViewModel::rerollImage
    )

    ReaderTextEditDialog(
        draft = textEditDraft,
        onDismiss = { textEditDraft = null },
        onSave = { draft, editedText ->
            viewModel.editSelectedText(draft.chapterIndex, draft.range, editedText)
            textEditDraft = null
        }
    )

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
            // 整高：面板自带输入框，半高吸附时键盘一弹就没有地方放输入行了
            // （内容列表已改成 weight，压缩列表保输入区，前提是 sheet 有整屏可用）。
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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

    ReaderFontImportDialog(
        font = pendingFont,
        name = pendingFontName,
        onNameChange = { pendingFontName = it },
        onDismiss = { font ->
            viewModel.cancelCustomFontImport(font)
            pendingFont = null
        },
        onConfirm = { font, name ->
            viewModel.confirmCustomFont(font, name)
            pendingFont = null
        }
    )
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

/** 聊天页「跳到原文」交回阅读页的一次性请求。 */
data class ReaderLocateRequest(
    val chapterIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val sourceAnchorJson: String = ""
)

/** 引文高亮只是「我把你带到这儿了」的提示，亮一下即可，不该长期占据视觉。 */
private const val LOCATE_HIGHLIGHT_MS = 2_600L
