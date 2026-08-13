package com.mozhi.reader.feature.reader

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.StopCircle
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
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
import com.mozhi.reader.ui.theme.isDarkTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    val timeline = remember(state.messages) { buildCompanionTimeline(state.messages) }
    val lastAssistantMessageId = remember(timeline) {
        timeline.filterIsInstance<CompanionTimelineItem.Bubble>()
            .lastOrNull { it.message.role == "assistant" }
            ?.message?.id
    }
    val liveExecutionSteps = remember(timeline, state.executionSteps) {
        val historicalCallIds = timeline.filterIsInstance<CompanionTimelineItem.Tools>()
            .flatMap { it.steps }
            .mapTo(hashSetOf()) { it.callId }
        state.executionSteps.filterNot { it.callId in historicalCallIds }
    }
    val entries = remember(state, timeline, liveExecutionSteps, sceneQuote) {
        buildCompanionChatEntries(
            timeline = timeline,
            liveSteps = liveExecutionSteps,
            streamingText = state.streamingText,
            isStreaming = state.isStreaming,
            toolStatus = state.toolStatus,
            thinkingLabel = "${persona?.name.orEmpty()}正在思考…",
            error = state.error,
            greeting = persona?.greeting?.takeIf { state.conversationId == null },
            embeddingProgress = state.embeddingProgress,
            sceneQuote = sceneQuote
        )
    }
    val isAtBottom by remember(messageListState) {
        derivedStateOf { messageListState.isAtLatest() }
    }

    // 打开/切换会话，以及该会话的消息首次落地时，直接定位到最新消息。
    // 之后生成期间没有任何自动滚动：回答只在视口下方生长，滑动永远不被顶动。
    LaunchedEffect(persona?.id, state.conversationId, timeline.isEmpty()) {
        withFrameNanos { }
        messageListState.snapToLatest()
    }

    // 发送后把刚发的问题锚到视口顶部（业内 AI 聊天通用手感），回答从它下方展开。
    var pendingQuestionAnchor by remember(state.conversationId) { mutableStateOf(false) }
    val questionAnchorOffsetPx = with(LocalDensity.current) { -8.dp.roundToPx() }
    LaunchedEffect(entries) {
        if (!pendingQuestionAnchor) return@LaunchedEffect
        val index = entries.indexOfLast { entry ->
            entry is ChatEntry.History &&
                (entry.item as? CompanionTimelineItem.Bubble)?.message?.role == "user"
        }
        if (index >= 0) {
            pendingQuestionAnchor = false
            messageListState.animateScrollToItem(index, questionAnchorOffsetPx)
        }
    }

    // 键盘弹出时视口变矮，正向列表默认锚在顶部；按 IME 每帧增量同步滚动，
    // 让输入条上方的内容跟着键盘上推（收起时由列表边界钳制自动回落）。
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    LaunchedEffect(messageListState, density) {
        var lastIme = imeInsets.getBottom(density)
        snapshotFlow { imeInsets.getBottom(density) }.collect { ime ->
            val delta = ime - lastIme
            lastIme = ime
            if (delta > 0) messageListState.scrollBy(delta.toFloat())
        }
    }

    fun send() {
        val clean = input.trim()
        if ((clean.isEmpty() && pendingAttachments.isEmpty()) || state.isStreaming) return
        pendingQuestionAnchor = true
        companionViewModel.send(clean, sceneQuote, pendingAttachments)
        input = ""
        pendingAttachments = emptyList()
    }

    MoReadBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
        ) {
            // 顶栏：返回｜角色 + 书名｜会话管理
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回",
                        tint = palette.onBackground
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { personaMenuExpanded = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PersonaAvatarImage(
                        name = persona?.name.orEmpty(),
                        avatarPath = persona?.avatarPath,
                        modifier = Modifier.size(38.dp)
                    )
                    Column(modifier = Modifier.padding(start = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = persona?.name ?: "伴读",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = palette.onBackground
                            )
                            Icon(
                                Icons.Outlined.ExpandMore,
                                contentDescription = "切换角色",
                                tint = palette.muted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = chatContext.bookTitle.ifBlank { "伴读中" },
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    DropdownMenu(
                        expanded = personaMenuExpanded,
                        onDismissRequest = { personaMenuExpanded = false }
                    ) {
                        state.personas.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate.name) },
                                leadingIcon = {
                                    PersonaAvatarImage(
                                        name = candidate.name,
                                        avatarPath = candidate.avatarPath,
                                        modifier = Modifier.size(26.dp)
                                    )
                                },
                                onClick = {
                                    personaMenuExpanded = false
                                    companionViewModel.selectPersona(candidate.id)
                                }
                            )
                        }
                    }
                }
                IconButton(
                    onClick = {
                        companionViewModel.newConversation()
                    },
                    enabled = !state.isStreaming
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "新会话", tint = palette.accent)
                }
                IconButton(onClick = { showConversations = true }) {
                    Icon(
                        Icons.Outlined.History,
                        contentDescription = "会话历史",
                        tint = palette.onBackground
                    )
                }
            }
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
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = entries,
                        key = ChatEntry::key,
                        contentType = ChatEntry::contentType
                    ) { entry ->
                        when (entry) {
                            is ChatEntry.Scene -> Surface(
                                color = palette.accentContainer.copy(alpha = 0.42f),
                                shape = RoundedCornerShape(0.dp, 14.dp, 14.dp, 0.dp),
                                border = BorderStroke(1.dp, palette.glassBorder)
                            ) {
                                Text(
                                    text = "当前章节：${entry.text}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.muted,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                            is ChatEntry.Embedding -> EmbeddingProgressCard(
                                progress = entry.progress,
                                palette = palette,
                                onRetry = companionViewModel::retryEmbedding
                            )
                            is ChatEntry.Greeting -> CompanionBubble(
                                text = entry.text,
                                fromUser = false,
                                palette = palette
                            )
                            is ChatEntry.History -> when (val item = entry.item) {
                                is CompanionTimelineItem.Bubble -> CompanionMessageBubble(
                                    message = item.message,
                                    palette = palette,
                                    canReroll = item.message.id == lastAssistantMessageId,
                                    onEdit = {
                                        editingMessage = item.message
                                        editText = item.message.content
                                    },
                                    onDelete = { companionViewModel.deleteMessage(item.message.id) },
                                    onReroll = {
                                        companionViewModel.reroll(item.message.id, sceneQuote)
                                    },
                                    onBranch = { companionViewModel.branchFrom(item.message.id) }
                                )
                                is CompanionTimelineItem.Tools -> AgentExecutionCard(
                                    steps = item.steps,
                                    palette = palette
                                )
                                is CompanionTimelineItem.Media -> AgentMediaCard(
                                    result = item.result,
                                    palette = palette,
                                    onOpenImage = { path, _ -> previewImagePath = path },
                                    onPlayAudio = mediaViewModel::playCachedSpeech
                                )
                            }
                            is ChatEntry.LiveTools -> AgentExecutionCard(
                                steps = entry.steps,
                                palette = palette
                            )
                            is ChatEntry.LiveText -> CompanionBubble(
                                text = entry.text,
                                fromUser = false,
                                palette = palette,
                                streaming = true
                            )
                            is ChatEntry.LiveStatus -> Text(
                                text = entry.text,
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.muted,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                            is ChatEntry.LiveError -> Row(verticalAlignment = Alignment.CenterVertically) {
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

                val followScope = rememberCoroutineScope()
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isAtBottom && entries.isNotEmpty(),
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
                        modifier = Modifier.clickable {
                            followScope.launch { messageListState.animateToLatest() }
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
                            pendingQuestionAnchor = true
                            companionViewModel.send(text, sceneQuote)
                        },
                        onDismiss = companionViewModel::dismissSuggestions
                    )
                }
            }

            // 输入区
            Surface(
                color = palette.glass,
                contentColor = palette.onBackground,
                border = BorderStroke(1.dp, palette.glassBorder),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 键盘弹出时 imePadding 已把整块内容抬到键盘上方，此时导航栏
                        // 的内边距必须归零，否则输入条和键盘之间会多出一条导航栏高的空白。
                        .windowInsetsPadding(WindowInsets.navigationBars.exclude(WindowInsets.ime))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    if (pendingAttachments.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            pendingAttachments.forEachIndexed { index, pending ->
                                Surface(
                                    color = palette.glassStrong,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, palette.glassBorder)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            if (pending.isImage) Icons.Outlined.Image else Icons.Outlined.Description,
                                            contentDescription = null,
                                            tint = palette.accent,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            pending.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .padding(start = 4.dp)
                                                .width(90.dp)
                                        )
                                        IconButton(
                                            onClick = {
                                                pendingAttachments = pendingAttachments
                                                    .filterIndexed { i, _ -> i != index }
                                            },
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(
                                                Icons.Outlined.Close,
                                                contentDescription = "移除附件",
                                                tint = palette.muted,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        var attachMenuExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(
                                onClick = { attachMenuExpanded = true },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.AttachFile,
                                    contentDescription = "更多输入",
                                    tint = palette.accent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = attachMenuExpanded,
                                onDismissRequest = { attachMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("图片") },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Image, contentDescription = null)
                                    },
                                    onClick = {
                                        attachMenuExpanded = false
                                        imagePicker.launch(
                                            PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly
                                            )
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("文本文件") },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Description, contentDescription = null)
                                    },
                                    onClick = {
                                        attachMenuExpanded = false
                                        filePicker.launch(arrayOf("text/*"))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("生成剧情梗概") },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Summarize, contentDescription = null)
                                    },
                                    onClick = {
                                        attachMenuExpanded = false
                                        if (!state.isStreaming) {
                                            pendingQuestionAnchor = true
                                            companionViewModel.generatePlotSummary(sceneQuote)
                                        }
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("防剧透模式")
                                            Text(
                                                if (state.spoilerProtectionEnabled) {
                                                    "仅检索已读内容（默认）"
                                                } else {
                                                    "允许伴读检索整本书"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = palette.muted
                                            )
                                        }
                                    },
                                    trailingIcon = {
                                        Switch(
                                            checked = state.spoilerProtectionEnabled,
                                            onCheckedChange = companionViewModel::setSpoilerProtectionEnabled
                                        )
                                    },
                                    onClick = {
                                        companionViewModel.setSpoilerProtectionEnabled(
                                            !state.spoilerProtectionEnabled
                                        )
                                    }
                                )
                            }
                        }
                        // 玻璃胶囊输入条：发送/停止钮嵌在胶囊内右下角，28dp 小圆钮随多行输入贴底。
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = palette.glassStrong,
                            contentColor = palette.onBackground,
                            border = BorderStroke(1.dp, palette.glassBorder),
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 40.dp)
                                        .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (input.isEmpty()) {
                                        Text(
                                            text = "问角色，也可以聊你的感受…",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = palette.muted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    BasicTextField(
                                        value = input,
                                        onValueChange = { input = it },
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                                            color = palette.onBackground
                                        ),
                                        cursorBrush = SolidColor(palette.accent),
                                        maxLines = 5,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                val canSend = input.isNotBlank() || pendingAttachments.isNotEmpty()
                                if (state.isStreaming) {
                                    IconButton(
                                        onClick = companionViewModel::stop,
                                        modifier = Modifier
                                            .padding(end = 6.dp, bottom = 6.dp)
                                            .size(28.dp)
                                            .background(palette.glass, CircleShape)
                                    ) {
                                        Icon(
                                            Icons.Outlined.StopCircle,
                                            contentDescription = "停止",
                                            tint = palette.accent,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else {
                                    IconButton(
                                        onClick = { send() },
                                        enabled = canSend,
                                        modifier = Modifier
                                            .padding(end = 6.dp, bottom = 6.dp)
                                            .size(28.dp)
                                            .background(
                                                if (canSend) palette.accent else palette.glass,
                                                CircleShape
                                            )
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Send,
                                            contentDescription = "发送",
                                            tint = if (canSend) palette.onAccent else palette.muted,
                                            modifier = Modifier
                                                .padding(start = 1.dp)
                                                .size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 会话历史面板
    if (showConversations) {
        ModalBottomSheet(
            onDismissRequest = { showConversations = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "会话历史",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            companionViewModel.newConversation()
                            showConversations = false
                        },
                        enabled = !state.isStreaming
                    ) { Text("新会话") }
                }
                val formatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
                val conversationListState = rememberLazyListState()
                LazyColumn(
                    state = conversationListState,
                    modifier = Modifier
                        .blockSheetDrag(conversationListState)
                        .padding(bottom = 24.dp)
                ) {
                    items(state.conversations, key = { it.id }) { conversation ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    companionViewModel.selectConversation(conversation.id)
                                    showConversations = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    conversation.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (conversation.id == state.conversationId) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Normal
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    formatter.format(Date(conversation.updatedAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = {
                                renamingConversationId = conversation.id
                                conversationTitle = conversation.title
                            }) {
                                Icon(
                                    Icons.Outlined.Edit,
                                    contentDescription = "重命名",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(onClick = {
                                deletingConversationId = conversation.id
                                deletingConversationTitle = conversation.title
                            }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "删除",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    renamingConversationId?.let { conversationId ->
        AlertDialog(
            onDismissRequest = { renamingConversationId = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = conversationTitle,
                    onValueChange = { conversationTitle = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        companionViewModel.renameConversation(conversationId, conversationTitle)
                        renamingConversationId = null
                    },
                    enabled = conversationTitle.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renamingConversationId = null }) { Text("取消") }
            }
        )
    }

    deletingConversationId?.let { conversationId ->
        AlertDialog(
            onDismissRequest = { deletingConversationId = null },
            title = { Text("删除会话？") },
            text = { Text("“$deletingConversationTitle”中的全部消息都会删除，此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        companionViewModel.deleteConversation(conversationId)
                        deletingConversationId = null
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingConversationId = null }) { Text("取消") }
            }
        )
    }

    editingMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { editingMessage = null },
            title = { Text(if (message.role == "user") "编辑并重新发送" else "编辑 AI 消息") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        minLines = 3,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (message.role == "user") {
                        Text(
                            "保存后会从这条消息重新生成，当前分支中它后面的内容会移除。需要保留时请先开分支。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        companionViewModel.editMessage(message.id, editText, sceneQuote)
                        editingMessage = null
                    },
                    enabled = editText.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingMessage = null }) { Text("取消") }
            }
        )
    }

    previewImagePath?.let { path ->
        Dialog(onDismissRequest = { previewImagePath = null }) {
            AsyncImage(
                model = File(path),
                contentDescription = "插图预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { previewImagePath = null }
            )
        }
    }
}

/** 全屏聊天页不在阅读纸色语境里，按应用主题构造一份 ReaderPalette 复用弹层组件。 */
@Composable
private fun companionChatPalette(): ReaderPalette {
    val scheme = MaterialTheme.colorScheme
    val dark = isDarkTheme()
    return ReaderPalette(
        background = scheme.background,
        onBackground = scheme.onBackground,
        muted = scheme.onSurfaceVariant,
        glass = scheme.surface.copy(alpha = if (dark) 0.72f else 0.85f),
        glassStrong = scheme.surface.copy(alpha = if (dark) 0.94f else 0.97f),
        glassBorder = scheme.outline.copy(alpha = 0.28f),
        accent = scheme.primary,
        accentContainer = scheme.primaryContainer,
        onAccent = scheme.onPrimary,
        scrim = Color.Black.copy(alpha = 0.42f),
        isDark = dark
    )
}

/** 输入框上方的横排建议胶囊：整行可横向滑动、逐条延迟浮入，点按即发送，尾部小叉收起。 */
@Composable
private fun SuggestionStrip(
    suggestions: List<String>,
    palette: ReaderPalette,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp)
    ) {
        suggestions.forEachIndexed { index, suggestion ->
            key(suggestion) {
                val appeared = remember {
                    MutableTransitionState(false).apply { targetState = true }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visibleState = appeared,
                    enter = fadeIn(tween(220, delayMillis = index * 90)) +
                        slideInVertically(tween(260, delayMillis = index * 90)) { it / 2 }
                ) {
                    Surface(
                        onClick = { onPick(suggestion) },
                        shape = RoundedCornerShape(17.dp),
                        color = palette.glass,
                        contentColor = palette.accent,
                        border = BorderStroke(1.dp, palette.glassBorder),
                        shadowElevation = 4.dp
                    ) {
                        Text(
                            text = suggestion,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .widthIn(max = 250.dp)
                                .padding(horizontal = 13.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
        Surface(
            onClick = onDismiss,
            shape = CircleShape,
            color = palette.glass,
            contentColor = palette.muted,
            border = BorderStroke(1.dp, palette.glassBorder)
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "收起建议",
                modifier = Modifier
                    .padding(5.dp)
                    .size(13.dp)
            )
        }
    }
}
