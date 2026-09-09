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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.ui.components.MoReadBackdrop
import com.mozhi.reader.ui.components.rememberChatFontFamily
import com.mozhi.reader.ui.components.safeTopPadding
import java.io.File
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
    onLocateInBook: (
        chapterIndex: Int,
        startCharOffset: Int,
        endCharOffset: Int,
        sourceAnchorJson: String
    ) -> Unit = { _, _, _, _ -> },
    companionViewModel: ReaderCompanionViewModel = hiltViewModel(),
    mediaViewModel: ReaderSelectionMediaViewModel = hiltViewModel()
) {
    CompanionChatPane(
        bookId = bookId,
        onClose = onBack,
        companionViewModel = companionViewModel,
        mediaViewModel = mediaViewModel,
        onLocateInBook = onLocateInBook,
        embedded = false
    )
}

/** Reuses the reader-owned VMs; embedding must never create a second chat session. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CompanionChatPane(
    bookId: Long,
    onClose: () -> Unit,
    companionViewModel: ReaderCompanionViewModel,
    mediaViewModel: ReaderSelectionMediaViewModel,
    onLocateInBook: (Int, Int, Int, String) -> Unit = { _, _, _, _ -> },
    embedded: Boolean = true
) {
    val onBack = onClose
    val state by companionViewModel.uiState.collectAsStateWithLifecycle()
    val chatContext by companionViewModel.chatContext.collectAsStateWithLifecycle()
    LaunchedEffect(bookId) { companionViewModel.bind(bookId) }

    val palette = companionChatPalette()
    val persona = state.activePersona
    val sceneQuote = chatContext.sceneQuote

    val input = state.composerDraft
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
                    event.endCharOffset,
                    event.sourceAnchorJson
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
        state.isLoadingMessages,
        state.multiBubbleEnabled,
        state.liveEntryId,
        lastAssistantMessageId,
        persona?.name,
        persona?.greeting,
        sceneQuote
    ) {
        if (state.isLoadingMessages) emptyList() else buildCompanionChatEntries(
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
            liveEntryId = state.liveEntryId
        )
    }
    val scrollState = rememberCompanionChatScrollState(
        sessionKey = "$bookId:${persona?.id}:${state.conversationId}",
        entries = entries,
        isLoadingMessages = state.isLoadingMessages
    )
    val messageListState = scrollState.listState
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

    fun send() {
        val clean = input.trim()
        if ((clean.isEmpty() && pendingAttachments.isEmpty()) || state.isStreaming || state.isLoadingMessages || state.stoppedReply != null) return
        scrollState.requestFollowLatest()
        companionViewModel.send(clean, sceneQuote, pendingAttachments)
        companionViewModel.updateComposerDraft("", persona?.id)
        pendingAttachments = emptyList()
    }

    val chatFont = rememberChatFontFamily(state.appearance.fontId, state.fontLibrary)

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
            scrollState.requestFollowLatest()
            companionViewModel.generatePlotSummary(sceneQuote)
        },
        onGenerateIllustration = {
            scrollState.requestFollowLatest()
            companionViewModel.requestIllustration(sceneQuote)
        },
        onToggleSpoilerProtection = {
            companionViewModel.setSpoilerProtectionEnabled(!state.spoilerProtectionEnabled)
        },
        onToggleMultiBubble = {
            companionViewModel.setMultiBubbleEnabled(!state.multiBubbleEnabled)
        }
    )

    if (state.isSavingStoppedReply) AlertDialog(
        onDismissRequest = {}, title = { Text("正在保存停止的回复") },
        text = { CircularProgressIndicator() }, confirmButton = {}
    )
    state.stoppedReply?.takeIf { !state.isSavingStoppedReply && state.error != null }?.let { draft ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("回复尚未保存") },
            text = { Text(state.error.orEmpty() + "\n\n" +
                (draft.text.ifBlank { draft.reasoning }).take(600)) },
            confirmButton = { TextButton(onClick = companionViewModel::retryStoppedReply) { Text("重试保存") } },
            dismissButton = { TextButton(onClick = companionViewModel::discardStoppedReply) { Text("舍弃残段") } }
        )
    }

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
                .then(if (embedded) Modifier else Modifier.safeTopPadding())
                .imePadding()
        ) {
            CompanionChatHeader(
                embedded = embedded,
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
                CompanionChatMessageList(
                    entries = entries,
                    scrollState = scrollState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
                ) { entry ->
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
                                    onUserToggle = scrollState::pauseFollowing
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
                                            scrollState.requestFollowLatest()
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
                                        start = 4.dp,
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
                                        onClick = {
                                            scrollState.requestFollowLatest()
                                            companionViewModel.retry(sceneQuote)
                                        }
                                    ) { Text("重试") }
                                }
                    }
                }

                val followScope = rememberCoroutineScope()
                androidx.compose.animation.AnimatedVisibility(
                    visible = scrollState.initiallyPositioned && showReturnToBottom && entries.isNotEmpty() && !state.isLoadingMessages,
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
                        modifier = Modifier.clickable(enabled = !scrollState.returningToLatest) {
                            followScope.launch { scrollState.returnToLatest() }
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
                            scrollState.requestFollowLatest()
                            companionViewModel.send(text, sceneQuote)
                        },
                        onDismiss = companionViewModel::dismissSuggestions
                    )
                }
            }

            CompanionComposer(
                input = input,
                onInputChange = { companionViewModel.updateComposerDraft(it, persona?.id) },
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
