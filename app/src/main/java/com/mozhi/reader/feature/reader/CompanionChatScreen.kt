package com.mozhi.reader.feature.reader

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.ui.components.MoReadBackdrop
import com.mozhi.reader.ui.components.PersonaAvatarImage
import com.mozhi.reader.ui.components.blockSheetDrag
import com.mozhi.reader.ui.components.rememberChatFontFamily
import com.mozhi.reader.ui.components.safeTopPadding
import com.mozhi.reader.ui.theme.isDarkTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 独立全屏伴读聊天页（路由 companion-chat/{bookId}）：顶栏角色切换 + 会话管理，
 * 消息区复用弹层时代的 timeline 组件，输入区支持图片/文本文件附件。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CompanionChatScreen(
    bookId: Long,
    onBack: () -> Unit,
    onLocateInBook: (chapterIndex: Int, startCharOffset: Int, endCharOffset: Int) -> Unit = { _, _, _ -> },
    companionViewModel: ReaderCompanionViewModel = hiltViewModel(),
    mediaViewModel: ReaderSelectionMediaViewModel = hiltViewModel()
) {
    val state by companionViewModel.uiState.collectAsStateWithLifecycle()
    val chatContext by companionViewModel.chatContext.collectAsStateWithLifecycle()
    LaunchedEffect(bookId) { companionViewModel.bind(bookId) }

    val palette = companionChatPalette()
    val persona = state.activePersona
    val sceneQuote = chatContext.sceneQuote

    var input by rememberSaveable(persona?.id) { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf(listOf<PendingAttachment>()) }
    var personaMenuExpanded by remember { mutableStateOf(false) }
    var showConversations by remember { mutableStateOf(false) }
    var renamingConversationId by remember { mutableStateOf<Long?>(null) }
    var conversationTitle by remember { mutableStateOf("") }
    var deletingConversationId by remember { mutableStateOf<Long?>(null) }
    var deletingConversationTitle by remember { mutableStateOf("") }
    var editingMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var editText by remember { mutableStateOf("") }
    var previewImagePath by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current

    LaunchedEffect(companionViewModel) {
        companionViewModel.events.collect { event ->
            when (event) {
                is CompanionChatEvent.LocateInBook -> onLocateInBook(
                    event.chapterIndex,
                    event.startCharOffset,
                    event.endCharOffset
                )
                // 定位失败要说出来：静默无反应会让人以为是点击没生效，反复戳。
                is CompanionChatEvent.Message ->
                    Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4)
    ) { uris ->
        pendingAttachments = pendingAttachments + uris.map { uri ->
            PendingAttachment(uri = uri, isImage = true, name = "图片")
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            } ?: "文件"
            pendingAttachments = pendingAttachments +
                PendingAttachment(uri = uri, isImage = false, name = name)
        }
    }

    val messageListState = rememberLazyListState()
    var autoFollowLatest by remember(state.conversationId) { mutableStateOf(true) }
    var questionAnchorInProgress by remember(state.conversationId) { mutableStateOf(false) }
    val followConnection = remember(messageListState, state.conversationId) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 只认真实手势。程序滚动也会令 isScrollInProgress=true，不能拿它判断用户意图。
                if (source == NestedScrollSource.UserInput && available.y > 0.5f) {
                    autoFollowLatest = false
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.UserInput && messageListState.isAtLatest()) {
                    autoFollowLatest = true
                }
                return Offset.Zero
            }
        }
    }
    val timeline = remember(state.messages) { buildCompanionTimeline(state.messages) }
    val lastAssistantMessageId = remember(timeline) {
        timeline.filterIsInstance<CompanionTimelineItem.Bubble>()
            .lastOrNull { it.message.role == "assistant" }
            ?.message?.id
    }
    val liveExecutionSteps = remember(timeline, state.executionSteps) {
        val historicalCallIds = timeline.filterIsInstance<CompanionTimelineItem.Process>()
            .flatMap { it.steps }
            .mapTo(hashSetOf()) { it.callId }
        state.executionSteps.filterNot { it.callId in historicalCallIds }
    }
    val entries = remember(
        timeline,
        liveExecutionSteps,
        state.streamingText,
        state.streamingReasoning,
        state.isStreaming,
        state.toolStatus,
        state.error,
        state.embeddingProgress,
        state.conversationId,
        state.multiBubbleEnabled,
        state.liveEntryId,
        state.messageUiKeys,
        lastAssistantMessageId,
        persona?.name,
        persona?.greeting,
        sceneQuote
    ) {
        buildCompanionChatEntries(
            timeline = timeline,
            liveSteps = liveExecutionSteps,
            liveReasoning = state.streamingReasoning,
            streamingText = state.streamingText,
            isStreaming = state.isStreaming,
            toolStatus = state.toolStatus,
            thinkingLabel = "${persona?.name.orEmpty()}正在思考…",
            error = state.error,
            greeting = persona?.greeting?.takeIf { state.conversationId == null },
            embeddingProgress = state.embeddingProgress,
            sceneQuote = sceneQuote,
            multiBubble = state.multiBubbleEnabled,
            lastAssistantMessageId = lastAssistantMessageId,
            liveEntryId = state.liveEntryId,
            messageKeyAliases = state.messageUiKeys
        )
    }
    val isAtBottom by remember(messageListState) {
        derivedStateOf { messageListState.isAtLatest() }
    }

    val density = LocalDensity.current
    val returnButtonShowPx = with(density) { 200.dp.roundToPx() }
    val returnButtonHidePx = with(density) { 48.dp.roundToPx() }
    var showReturnToBottom by remember(state.conversationId) { mutableStateOf(false) }
    LaunchedEffect(messageListState, state.conversationId, returnButtonShowPx, returnButtonHidePx) {
        snapshotFlow { messageListState.distanceFromLatest() }
            .distinctUntilChanged()
            .collect { distance ->
                showReturnToBottom = if (showReturnToBottom) {
                    distance > returnButtonHidePx
                } else {
                    distance > returnButtonShowPx
                }
            }
    }

    var wasStreaming by remember(state.conversationId) { mutableStateOf(state.isStreaming) }
    LaunchedEffect(state.isStreaming, state.conversationId) {
        if (wasStreaming && !state.isStreaming && autoFollowLatest) {
            withFrameNanos { }
            messageListState.snapToLatest()
        }
        wasStreaming = state.isStreaming
    }

    // 打开/切换会话，以及该会话的消息首次落地时，直接定位到最新消息。
    LaunchedEffect(persona?.id, state.conversationId, timeline.isEmpty()) {
        autoFollowLatest = true
        withFrameNanos { }
        messageListState.snapToLatest()
    }

    // 发送后把刚发的问题锚到视口顶部（业内 AI 聊天通用手感），回答从它下方展开。
    var questionAnchorTicket by remember(state.conversationId) { mutableIntStateOf(0) }
    var previousQuestionKey by remember(state.conversationId) { mutableStateOf<String?>(null) }
    val latestEntries by rememberUpdatedState(entries)
    val questionAnchorOffsetPx = with(LocalDensity.current) { -8.dp.roundToPx() }
    LaunchedEffect(questionAnchorTicket) {
        if (questionAnchorTicket == 0) return@LaunchedEffect
        questionAnchorInProgress = true
        try {
            // 等 Room 把本次 user 行送到列表；effect 只由 ticket 启动，不会被每个 token 取消。
            repeat(120) {
                withFrameNanos { }
                val currentEntries = latestEntries
                val index = currentEntries.indexOfLast { entry ->
                    entry is ChatEntry.Bubble && entry.fromUser && entry.key != previousQuestionKey
                }
                if (index >= 0) {
                    messageListState.scrollToItem(index, questionAnchorOffsetPx)
                    return@LaunchedEffect
                }
            }
        } finally {
            questionAnchorInProgress = false
        }
    }

    fun requestQuestionAnchor() {
        previousQuestionKey = entries.asReversed()
            .filterIsInstance<ChatEntry.Bubble>()
            .firstOrNull { it.fromUser }
            ?.key
        autoFollowLatest = true
        questionAnchorTicket++
    }

    fun send() {
        val clean = input.trim()
        if ((clean.isEmpty() && pendingAttachments.isEmpty()) || state.isStreaming) return
        requestQuestionAnchor()
        companionViewModel.send(clean, sceneQuote, pendingAttachments)
        input = ""
        pendingAttachments = emptyList()
    }

    val chatFont = rememberChatFontFamily(state.appearance.fontId, state.fontLibrary)
    val currentRoundKeys = remember(entries, state.isStreaming) {
        if (!state.isStreaming) {
            emptyList()
        } else {
            val questionIndex = entries.indexOfLast { entry ->
                entry is ChatEntry.Bubble && entry.fromUser
            }
            if (questionIndex >= 0) entries.drop(questionIndex).map(ChatEntry::key) else emptyList()
        }
    }
    val currentRoundKeySet = remember(currentRoundKeys) { currentRoundKeys.toSet() }
    val measuredEntryHeights = remember(state.conversationId) { mutableStateMapOf<String, Int>() }
    var listViewportHeightPx by remember(state.conversationId) { mutableIntStateOf(0) }
    val listVerticalPaddingPx = with(density) { 22.dp.roundToPx() }
    val listItemSpacingPx = with(density) { 8.dp.roundToPx() }
    val currentRoundContentHeightPx by remember(currentRoundKeys) {
        derivedStateOf { currentRoundKeys.sumOf { measuredEntryHeights[it] ?: 0 } }
    }
    val tailSpacerHeightPx by remember(
        state.isStreaming,
        currentRoundKeys,
        currentRoundContentHeightPx,
        listViewportHeightPx,
        listVerticalPaddingPx,
        listItemSpacingPx
    ) {
        derivedStateOf {
            if (!state.isStreaming || currentRoundKeys.isEmpty() || listViewportHeightPx <= 0) {
                0
            } else {
                val gaps = listItemSpacingPx * currentRoundKeys.size
                (listViewportHeightPx - listVerticalPaddingPx - currentRoundContentHeightPx - gaps)
                    .coerceAtLeast(0)
            }
        }
    }
    val tailSpacerHeight = with(density) { tailSpacerHeightPx.toDp() }

    // 只在当前回合的实际高度变化时补偿滚动，不再每个显示帧都读布局并发起 scrollBy。
    // 这样普通 token（尚未触发行换行）完全不碰滚动状态；用户展开过程卡时又会先关闭
    // autoFollowLatest，避免展开动画与贴底逻辑互相拉扯。
    LaunchedEffect(
        state.isStreaming,
        state.conversationId,
        currentRoundKeys,
        currentRoundContentHeightPx,
        tailSpacerHeightPx,
        autoFollowLatest,
        questionAnchorInProgress
    ) {
        if (!state.isStreaming || !autoFollowLatest || questionAnchorInProgress) {
            return@LaunchedEffect
        }
        withFrameNanos { }
        val info = messageListState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
        if (last.index == info.totalItemsCount - 1) {
            val overflow = (last.offset + last.size) -
                (info.viewportEndOffset - info.afterContentPadding)
            if (overflow > 0) messageListState.scrollBy(overflow.toFloat())
        } else if (tailSpacerHeightPx == 0) {
            // 预留空白耗尽后答案才进入真正的贴底阶段；此前保持问题锚点不动。
            messageListState.snapToLatest()
        }
    }

    val composerActions = companionComposerActions(
        isStreaming = state.isStreaming,
        spoilerProtectionEnabled = state.spoilerProtectionEnabled,
        multiBubbleEnabled = state.multiBubbleEnabled,
        onPickImage = {
            imagePicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        onPickTextFile = { filePicker.launch(arrayOf("text/*")) },
        onGeneratePlotSummary = {
            requestQuestionAnchor()
            companionViewModel.generatePlotSummary(sceneQuote)
        },
        onGenerateIllustration = {
            requestQuestionAnchor()
            companionViewModel.requestIllustration(sceneQuote)
        },
        onToggleSpoilerProtection = {
            companionViewModel.setSpoilerProtectionEnabled(!state.spoilerProtectionEnabled)
        },
        onToggleMultiBubble = {
            companionViewModel.setMultiBubbleEnabled(!state.multiBubbleEnabled)
        }
    )

    MoReadBackdrop {
        // 角色自定义的聊天背景：铺在最底，上面压一层主题底色做蒙版，
        // 蒙版强度由用户拉——图看得见和字看得清之间的取舍只有他自己知道。
        state.backgroundImagePath?.let { path ->
            AsyncImage(
                model = java.io.File(path),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(palette.background.copy(alpha = state.appearance.backgroundDim))
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 同书籍详情：沉浸阅读隐藏状态栏后不能让顶栏贴到屏幕上沿。
                .safeTopPadding()
                .imePadding()
        ) {
            CompanionChatHeader(
                persona = persona,
                personas = state.personas,
                bookTitle = chatContext.bookTitle,
                personaMenuExpanded = personaMenuExpanded,
                isStreaming = state.isStreaming,
                palette = palette,
                onBack = onBack,
                onOpenPersonaMenu = { personaMenuExpanded = true },
                onDismissPersonaMenu = { personaMenuExpanded = false },
                onSelectPersona = { personaId ->
                    personaMenuExpanded = false
                    companionViewModel.selectPersona(personaId)
                },
                onNewConversation = companionViewModel::newConversation,
                onShowConversations = { showConversations = true }
            )
            HorizontalDivider(color = palette.glassBorder)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    state = messageListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(followConnection)
                        .onSizeChanged { listViewportHeightPx = it.height }
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = entries,
                        key = ChatEntry::key,
                        contentType = ChatEntry::contentType
                    ) { entry ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (entry.key in currentRoundKeySet) {
                                        Modifier.onSizeChanged { measuredEntryHeights[entry.key] = it.height }
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
                            when (entry) {
                                is ChatEntry.Scene -> ChatSceneDivider(entry.text, palette)
                                is ChatEntry.Embedding -> EmbeddingProgressCapsule(
                                    progress = entry.progress,
                                    palette = palette,
                                    onRetry = companionViewModel::retryEmbedding
                                )
                                is ChatEntry.Process -> CompanionProcessCard(
                                    steps = entry.steps,
                                    reasoning = entry.reasoning,
                                    palette = palette,
                                    isLive = entry.isLive,
                                    stateKey = entry.key,
                                    onUserToggle = {
                                        // 用户主动查看过程时让视口归用户控制；否则贴底协程会在
                                        // 展开改变高度的同时反向滚动，造成明显抖动。
                                        if (state.isStreaming) autoFollowLatest = false
                                    }
                                )
                                is ChatEntry.Bubble -> CompanionChatBubble(
                                    entry = entry,
                                    palette = palette,
                                    personaName = persona?.name.orEmpty(),
                                    personaAvatarPath = persona?.avatarPath,
                                    appearance = state.appearance,
                                    fontFamily = chatFont,
                                    locatedCitations = entry.message
                                        ?.let { state.locatedCitations[it.id] }
                                        .orEmpty(),
                                    onLocateCitation = companionViewModel::locate,
                                    onEdit = {
                                        entry.message?.let { message ->
                                            editingMessage = message
                                            editText = message.content
                                        }
                                    },
                                    onDelete = {
                                        entry.message?.let { companionViewModel.deleteMessage(it.id) }
                                    },
                                    onReroll = {
                                        entry.message?.let {
                                            companionViewModel.reroll(it.id, sceneQuote)
                                        }
                                    },
                                    onBranch = {
                                        entry.message?.let { companionViewModel.branchFrom(it.id) }
                                    },
                                    onSpeak = { text ->
                                        companionViewModel.speak(text, mediaViewModel::playCachedSpeech)
                                    },
                                    voiceClip = state.voiceClips[entry.key],
                                    onPrepareVoice = {
                                        companionViewModel.prepareVoiceClip(entry.key, entry.part.text)
                                    },
                                    onRegenerateVoice = {
                                        companionViewModel.regenerateVoiceClip(entry.key, entry.part.text)
                                    },
                                    onPlayVoice = mediaViewModel::playCachedSpeech
                                )
                                is ChatEntry.Media -> CompanionMediaBubble(
                                    result = entry.result,
                                    palette = palette,
                                    onOpenImage = { path, _ -> previewImagePath = path },
                                    onPlayAudio = mediaViewModel::playCachedSpeech
                                )
                                is ChatEntry.Status -> Text(
                                    text = entry.text,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.muted,
                                    modifier = Modifier.padding(
                                        start = AVATAR_GUTTER,
                                        top = 2.dp,
                                        bottom = 2.dp
                                    )
                                )
                                is ChatEntry.ErrorLine -> Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = entry.text,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = { companionViewModel.retry(sceneQuote) }
                                    ) { Text("重试") }
                                }
                            }
                        }
                    }
                    if (state.isStreaming) {
                        item(
                            key = "chat-tail-spacer-${state.liveEntryId}",
                            contentType = "tail-spacer"
                        ) {
                            Spacer(
                                Modifier
                                    .fillMaxWidth()
                                    .height(tailSpacerHeight)
                            )
                        }
                    }
                }

                val followScope = rememberCoroutineScope()
                var isReturningToBottom by remember { mutableStateOf(false) }
                androidx.compose.animation.AnimatedVisibility(
                    visible = showReturnToBottom && entries.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                ) {
                    Surface(
                        color = palette.glassStrong,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, palette.glassBorder),
                        modifier = Modifier.clickable(enabled = !isReturningToBottom) {
                            autoFollowLatest = true
                            followScope.launch {
                                isReturningToBottom = true
                                try {
                                    messageListState.animateToLatest()
                                } finally {
                                    isReturningToBottom = false
                                }
                            }
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.ArrowDownward,
                                contentDescription = null,
                                tint = palette.accent,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                "回到底部",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.accent,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }

                // AI 建议回复：输入框上方横排悬浮胶囊，可左右滑动，点按即替用户发送。
                androidx.compose.animation.AnimatedVisibility(
                    visible = state.suggestions.isNotEmpty() &&
                        !state.isStreaming &&
                        isAtBottom,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                ) {
                    SuggestionStrip(
                        suggestions = state.suggestions,
                        palette = palette,
                        onPick = { text ->
                            requestQuestionAnchor()
                            companionViewModel.send(text, sceneQuote)
                        },
                        onDismiss = companionViewModel::dismissSuggestions
                    )
                }
            }

            CompanionComposer(
                input = input,
                onInputChange = { input = it },
                attachments = pendingAttachments,
                onRemoveAttachment = { index ->
                    pendingAttachments = pendingAttachments.filterIndexed { i, _ -> i != index }
                },
                actions = composerActions,
                isStreaming = state.isStreaming,
                palette = palette,
                onSend = { send() },
                onStop = companionViewModel::stop
            )
        }
    }

    if (showConversations) {
        CompanionConversationSheet(
            conversations = state.conversations,
            activeConversationId = state.conversationId,
            isStreaming = state.isStreaming,
            onDismiss = { showConversations = false },
            onNewConversation = {
                companionViewModel.newConversation()
                showConversations = false
            },
            onSelectConversation = { conversationId ->
                companionViewModel.selectConversation(conversationId)
                showConversations = false
            },
            onRenameConversation = { conversation ->
                renamingConversationId = conversation.id
                conversationTitle = conversation.title
            },
            onDeleteConversation = { conversation ->
                deletingConversationId = conversation.id
                deletingConversationTitle = conversation.title
            }
        )
    }

    RenameConversationDialog(
        conversationId = renamingConversationId,
        title = conversationTitle,
        onTitleChange = { conversationTitle = it },
        onDismiss = { renamingConversationId = null },
        onConfirm = { conversationId, title ->
            companionViewModel.renameConversation(conversationId, title)
            renamingConversationId = null
        }
    )

    DeleteConversationDialog(
        conversationId = deletingConversationId,
        title = deletingConversationTitle,
        onDismiss = { deletingConversationId = null },
        onConfirm = { conversationId ->
            companionViewModel.deleteConversation(conversationId)
            deletingConversationId = null
        }
    )

    EditCompanionMessageDialog(
        message = editingMessage,
        text = editText,
        onTextChange = { editText = it },
        onDismiss = { editingMessage = null },
        onConfirm = { message, text ->
            companionViewModel.editMessage(message.id, text, sceneQuote)
            editingMessage = null
        }
    )

    CompanionImagePreviewDialog(
        path = previewImagePath,
        onDismiss = { previewImagePath = null }
    )
}
