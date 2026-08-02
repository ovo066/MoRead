package com.mozhi.reader.feature.reader

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ToolCall
import com.mozhi.reader.ai.embedding.BookEmbeddingProgress
import com.mozhi.reader.ai.embedding.EmbeddingIndexStage
import com.mozhi.reader.ai.media.AgentMediaResult
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.database.entity.enabledTools
import com.mozhi.reader.core.database.entity.worldBook
import com.mozhi.reader.core.library.MessageAttachment
import coil3.compose.AsyncImage
import com.mozhi.reader.ui.components.PersonaAvatarImage
import com.mozhi.reader.ui.components.blockSheetDrag
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

private enum class CompanionTab {
    CHAT,
    CARD
}

/** 列表末项底边距视口底不超过该像素数即视为「在底部」（约半行容差）。 */
internal const val AUTO_FOLLOW_SLACK_PX = 24

/**
 * 流式跟随状态机：仅用户手势能改变跟随状态——拖动中实时取手指位置，手势结束瞬间
 * 采样一次落点；流式气泡长高等布局位移不得改变状态，否则上滑一停就被拉回底部（抽搐）。
 */
internal fun resolveAutoFollow(
    current: Boolean,
    wasScrolling: Boolean,
    scrolling: Boolean,
    atBottom: Boolean
): Boolean = when {
    scrolling -> atBottom
    wasScrolling -> atBottom
    else -> current
}

/**
 * 伴读弹层（design/ui-adaptation-plan.md §7）：角色来自 personas 表，
 * 当前角色与伴读页共用 activePersonaId；对话经 AgentLoop 真跑（流式 + 工具状态行）。
 */
@Composable
fun ReaderCompanionSheet(
    state: CompanionChatUiState,
    palette: ReaderPalette,
    contextQuote: String,
    onSelectPersona: (Long) -> Unit,
    onNewConversation: () -> Unit,
    onSelectConversation: (Long) -> Unit,
    onRenameConversation: (Long, String) -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onSend: (String) -> Unit,
    onGeneratePlotSummary: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onRetryEmbedding: () -> Unit,
    onEditMessage: (Long, String) -> Unit,
    onDeleteMessage: (Long) -> Unit,
    onReroll: (Long) -> Unit,
    onBranch: (Long) -> Unit,
    onOpenImage: (path: String, prompt: String?) -> Unit = { _, _ -> },
    onPlayAudio: (path: String) -> Unit = {},
    chatOnly: Boolean = false
) {
    val persona = state.activePersona
    var selectedTab by rememberSaveable { mutableStateOf(CompanionTab.CHAT) }
    var input by rememberSaveable(persona?.id) { mutableStateOf("") }
    var renamingConversationId by remember { mutableStateOf<Long?>(null) }
    var deletingConversationId by remember { mutableStateOf<Long?>(null) }
    var conversationTitle by remember { mutableStateOf("") }
    var deletingConversationTitle by remember { mutableStateOf("") }
    var editingMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var editText by remember { mutableStateOf("") }
    val messageListState = rememberLazyListState()
    var autoFollowLatest by remember(state.conversationId) { mutableStateOf(true) }
    val timeline = remember(state.messages) { buildCompanionTimeline(state.messages) }
    val lastAssistantMessageId = timeline.asReversed()
        .filterIsInstance<CompanionTimelineItem.Bubble>()
        .firstOrNull { it.message.role == "assistant" }
        ?.message?.id
    val currentConversation = state.conversations.firstOrNull { it.id == state.conversationId }
    val historicalCallIds = timeline.filterIsInstance<CompanionTimelineItem.Tools>()
        .flatMap { it.steps }
        .mapTo(hashSetOf()) { it.callId }
    val liveExecutionSteps = state.executionSteps.filterNot { it.callId in historicalCallIds }

    BackHandler(enabled = selectedTab == CompanionTab.CARD) {
        selectedTab = CompanionTab.CHAT
    }

    LaunchedEffect(messageListState) {
        var wasScrolling = false
        snapshotFlow {
            val layout = messageListState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            val atBottom = layout.totalItemsCount == 0 || (
                last != null && last.index == layout.totalItemsCount - 1 &&
                    last.offset + last.size <= layout.viewportEndOffset + AUTO_FOLLOW_SLACK_PX
                )
            messageListState.isScrollInProgress to atBottom
        }.distinctUntilChanged().collect { (scrolling, atBottom) ->
            autoFollowLatest = resolveAutoFollow(
                current = autoFollowLatest,
                wasScrolling = wasScrolling,
                scrolling = scrolling,
                atBottom = atBottom
            )
            wasScrolling = scrolling
        }
    }

    // 每次切换会话/角色首次进入时强制定位最新；之后只有用户没有主动上划看历史时，
    // 才随流式文本、工具步骤和新消息继续贴底。
    LaunchedEffect(persona?.id, state.conversationId, selectedTab) {
        if (selectedTab != CompanionTab.CHAT) return@LaunchedEffect
        autoFollowLatest = true
        withFrameNanos { }
        val count = messageListState.layoutInfo.totalItemsCount
        if (count > 0) messageListState.scrollToItem(count - 1, Int.MAX_VALUE)
    }
    LaunchedEffect(
        timeline.size,
        state.streamingText?.length,
        liveExecutionSteps.size,
        state.toolStatus,
        state.error,
        selectedTab
    ) {
        if (selectedTab != CompanionTab.CHAT || !autoFollowLatest || messageListState.isScrollInProgress) {
            return@LaunchedEffect
        }
        withFrameNanos { }
        val count = messageListState.layoutInfo.totalItemsCount
        if (count > 0 && !messageListState.isScrollInProgress) {
            messageListState.scrollToItem(count - 1, Int.MAX_VALUE)
        }
    }

    fun send(prompt: String) {
        val clean = prompt.trim()
        if (clean.isEmpty() || state.isStreaming) return
        autoFollowLatest = true
        onSend(clean)
        input = ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f)
            // 弹层的嵌套滚动会拿列表消费不掉的量拖动整个弹层再回弹（抽搐）；统一交给
            // blockSheetDrag 吃掉，内容区手势只滚列表，关闭走返回/蒙层/拖把手。
            .blockSheetDrag(messageListState)
            .imePadding()
            .padding(horizontal = 16.dp)
    ) {
        if (selectedTab == CompanionTab.CHAT) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PersonaAvatarImage(
                    name = persona?.name.orEmpty(),
                    avatarPath = persona?.avatarPath,
                    modifier = Modifier.size(40.dp)
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp)
                ) {
                    Text(
                        text = persona?.name ?: "伴读",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = currentConversation?.title ?: "尚未创建会话",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    color = palette.glass,
                    shape = RoundedCornerShape(13.dp),
                    border = BorderStroke(1.dp, palette.glassBorder),
                    modifier = Modifier.clickable { selectedTab = CompanionTab.CARD }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.History,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(17.dp)
                        )
                        Text(
                            "角色与会话",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.accent,
                            modifier = Modifier.padding(start = 5.dp)
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { selectedTab = CompanionTab.CHAT }) { Text("返回对话") }
                Text(
                    "角色、会话与角色卡",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        onNewConversation()
                        selectedTab = CompanionTab.CHAT
                    },
                    enabled = !state.isStreaming
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "新会话", tint = palette.accent)
                }
            }
        }

        if (selectedTab == CompanionTab.CHAT) {
            val followScope = rememberCoroutineScope()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
            LazyColumn(
                state = messageListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Surface(
                        color = palette.accentContainer.copy(alpha = 0.42f),
                        shape = RoundedCornerShape(0.dp, 14.dp, 14.dp, 0.dp),
                        border = BorderStroke(1.dp, palette.glassBorder)
                    ) {
                        Text(
                            text = "当前章节：$contextQuote",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.muted,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
                state.embeddingProgress?.let { progress ->
                    item {
                        EmbeddingProgressCard(
                            progress = progress,
                            palette = palette,
                            onRetry = onRetryEmbedding
                        )
                    }
                }
                // 尚未创建会话时用开场白垫场；创建后由真实 message 接管。
                if (timeline.isEmpty() && state.conversationId == null &&
                    !persona?.greeting.isNullOrBlank()
                ) {
                    item {
                        CompanionBubble(
                            text = persona?.greeting.orEmpty(),
                            fromUser = false,
                            palette = palette
                        )
                    }
                }
                items(timeline, key = CompanionTimelineItem::key) { item ->
                    when (item) {
                        is CompanionTimelineItem.Bubble -> CompanionMessageBubble(
                            message = item.message,
                            palette = palette,
                            canReroll = item.message.id == lastAssistantMessageId,
                            onEdit = {
                                editingMessage = item.message
                                editText = item.message.content
                            },
                            onDelete = { onDeleteMessage(item.message.id) },
                            onReroll = { onReroll(item.message.id) },
                            onBranch = { onBranch(item.message.id) }
                        )
                        is CompanionTimelineItem.Tools -> AgentExecutionCard(
                            steps = item.steps,
                            palette = palette
                        )
                        is CompanionTimelineItem.Media -> AgentMediaCard(
                            result = item.result,
                            palette = palette,
                            onOpenImage = onOpenImage,
                            onPlayAudio = onPlayAudio
                        )
                    }
                }
                state.streamingText?.takeIf(String::isNotBlank)?.let { streaming ->
                    item {
                        CompanionBubble(text = streaming, fromUser = false, palette = palette)
                    }
                }
                if (liveExecutionSteps.isNotEmpty()) {
                    item {
                        AgentExecutionCard(steps = liveExecutionSteps, palette = palette)
                    }
                }
                if (state.isStreaming || state.toolStatus != null) {
                    item {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Text(
                                text = state.toolStatus
                                    ?: "${persona?.name.orEmpty()}正在思考…",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.muted,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                state.error?.let { error ->
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onRetry) { Text("重试") }
                        }
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = !autoFollowLatest,
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
                        autoFollowLatest = true
                        followScope.launch {
                            val count = messageListState.layoutInfo.totalItemsCount
                            if (count > 0) messageListState.scrollToItem(count - 1, Int.MAX_VALUE)
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
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("解释这段", "梳理人物", "无剧透提示", "生成剧情梗概").forEach { prompt ->
                    AssistChip(
                        onClick = {
                            if (prompt == "生成剧情梗概") {
                                if (!state.isStreaming) {
                                    autoFollowLatest = true
                                    onGeneratePlotSummary()
                                }
                            } else {
                                send(prompt)
                            }
                        },
                        label = { Text(prompt) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = palette.glass,
                            labelColor = palette.accent
                        ),
                        border = BorderStroke(1.dp, palette.glassBorder)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("问角色，也可以聊你的感受…") },
                    singleLine = true,
                    shape = RoundedCornerShape(17.dp),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (state.isStreaming) {
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier
                            .size(48.dp)
                            .background(palette.glassStrong, RoundedCornerShape(16.dp))
                    ) {
                        Icon(
                            Icons.Outlined.StopCircle,
                            contentDescription = "停止",
                            tint = palette.accent
                        )
                    }
                } else {
                    IconButton(
                        onClick = { send(input) },
                        modifier = Modifier
                            .size(48.dp)
                            .background(palette.accent, RoundedCornerShape(16.dp))
                    ) {
                        Icon(
                            Icons.Outlined.ArrowUpward,
                            contentDescription = "发送",
                            tint = palette.onAccent
                        )
                    }
                }
            }
        } else {
            CompanionManagerPage(
                state = state,
                persona = persona,
                palette = palette,
                onSelectPersona = {
                    onSelectPersona(it)
                },
                onSelectConversation = {
                    onSelectConversation(it)
                    selectedTab = CompanionTab.CHAT
                },
                onRenameConversation = { id, title ->
                    renamingConversationId = id
                    conversationTitle = title
                },
                onDeleteConversation = { id, title ->
                    deletingConversationId = id
                    deletingConversationTitle = title
                },
                modifier = Modifier.weight(1f)
            )
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
                        onRenameConversation(conversationId, conversationTitle)
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
                        onDeleteConversation(conversationId)
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
                        onEditMessage(message.id, editText)
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
}

@Composable
private fun CompanionManagerPage(
    state: CompanionChatUiState,
    persona: PersonaEntity?,
    palette: ReaderPalette,
    onSelectPersona: (Long) -> Unit,
    onSelectConversation: (Long) -> Unit,
    onRenameConversation: (Long, String) -> Unit,
    onDeleteConversation: (Long, String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("切换角色", style = MaterialTheme.typography.titleSmall, color = palette.muted)
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.personas.forEach { item ->
                    AssistChip(
                        onClick = { onSelectPersona(item.id) },
                        label = { Text(item.name) },
                        leadingIcon = {
                            PersonaAvatarImage(
                                name = item.name,
                                avatarPath = item.avatarPath,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (item.id == persona?.id) {
                                palette.accentContainer
                            } else {
                                palette.glass
                            },
                            labelColor = palette.onBackground
                        ),
                        border = BorderStroke(1.dp, palette.glassBorder)
                    )
                }
            }
        }
        item {
            Text("会话", style = MaterialTheme.typography.titleSmall, color = palette.muted)
        }
        if (state.conversations.isEmpty()) {
            item {
                Text(
                    "还没有会话，点击右上角新增。",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted
                )
            }
        }
        items(state.conversations, key = { "manager-conversation-${it.id}" }) { conversation ->
            Surface(
                color = if (conversation.id == state.conversationId) {
                    palette.accentContainer.copy(alpha = 0.6f)
                } else {
                    palette.glass
                },
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, palette.glassBorder),
                modifier = Modifier.clickable { onSelectConversation(conversation.id) }
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (conversation.id == state.conversationId) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            Icons.Outlined.ChatBubbleOutline,
                            contentDescription = null,
                            tint = palette.muted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        conversation.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 9.dp)
                    )
                    IconButton(onClick = { onRenameConversation(conversation.id, conversation.title) }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "重命名会话", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { onDeleteConversation(conversation.id, conversation.title) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除会话", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        item {
            Text("当前角色卡", style = MaterialTheme.typography.titleSmall, color = palette.muted)
        }
        if (persona == null) {
            item { Text("尚未选择角色", color = palette.muted) }
        } else {
            item {
                Surface(
                    color = palette.glass,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, palette.glassBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PersonaAvatarImage(
                            name = persona.name,
                            avatarPath = persona.avatarPath,
                            modifier = Modifier.size(44.dp)
                        )
                        Column(modifier = Modifier.padding(start = 10.dp)) {
                            Text(persona.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                persona.subtitle.ifBlank { if (persona.isRoleplay) "角色扮演" else "工具助手" },
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.muted
                            )
                        }
                    }
                }
            }
            if (persona.personality.isNotBlank()) {
                item { CharacterField("人设", persona.personality, palette) }
            }
            if (persona.speakingStyle.isNotBlank()) {
                item { CharacterField("说话风格", persona.speakingStyle, palette) }
            }
            if (persona.greeting.isNotBlank()) {
                item { CharacterField("开场白", persona.greeting, palette) }
            }
            val lore = persona.worldBook().filter { it.enabled && it.content.isNotBlank() }
            if (lore.isNotEmpty()) {
                item {
                    CharacterField(
                        "世界书（${lore.size} 条）",
                        lore.joinToString("\n\n") { entry ->
                            if (entry.name.isBlank()) entry.content else "${entry.name}\n${entry.content}"
                        },
                        palette
                    )
                }
            }
            item {
                CharacterField(
                    "已开放工具",
                    persona.enabledTools().joinToString("、") { toolDisplayName(it) }.ifBlank { "无" },
                    palette
                )
            }
        }
        item { Spacer(Modifier.size(12.dp)) }
    }
}

@Composable
internal fun AgentExecutionCard(steps: List<AgentExecutionStep>, palette: ReaderPalette) {
    Surface(
        color = palette.glass,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, palette.glassBorder)
    ) {
        Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Text(
                text = "执行过程",
                style = MaterialTheme.typography.labelMedium,
                color = palette.muted,
                fontWeight = FontWeight.Medium
            )
            steps.forEach { step ->
                Row(
                    modifier = Modifier.padding(top = 7.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier.size(18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when (step.state) {
                            AgentStepState.RUNNING -> CircularProgressIndicator(
                                strokeWidth = 1.8.dp,
                                color = palette.accent,
                                modifier = Modifier.size(14.dp)
                            )
                            AgentStepState.SUCCEEDED -> Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                tint = palette.accent,
                                modifier = Modifier.size(16.dp)
                            )
                            AgentStepState.FAILED -> Icon(
                                Icons.Outlined.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.padding(start = 7.dp)) {
                        Text(
                            text = when (step.state) {
                                AgentStepState.RUNNING -> "正在${step.displayName}…"
                                AgentStepState.SUCCEEDED -> "${step.displayName} · 已完成"
                                AgentStepState.FAILED -> "${step.displayName} · 未完成"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (step.state == AgentStepState.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                palette.onBackground
                            }
                        )
                        step.detail.takeIf { it.isNotBlank() && it != "已完成" }?.let { detail ->
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.muted,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AgentMediaCard(
    result: AgentMediaResult,
    palette: ReaderPalette,
    onOpenImage: (String, String?) -> Unit,
    onPlayAudio: (String) -> Unit
) {
    Surface(
        color = palette.accentContainer.copy(alpha = 0.42f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, palette.glassBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (result.mediaKind == "image") {
            Column(
                modifier = Modifier
                    .clickable { onOpenImage(result.path, null) }
                    .padding(9.dp)
            ) {
                AsyncImage(
                    model = result.path,
                    contentDescription = "Agent 生成的书籍插图",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                )
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 6.dp)
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlayAudio(result.path) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = palette.accent,
                    contentColor = palette.onAccent,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = "播放生成语音",
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Column(modifier = Modifier.padding(start = 11.dp)) {
                    Text("播放生成语音", style = MaterialTheme.typography.labelLarge)
                    Text(result.message, style = MaterialTheme.typography.labelSmall, color = palette.muted)
                }
            }
        }
    }
}

@Composable
internal fun EmbeddingProgressCard(
    progress: BookEmbeddingProgress,
    palette: ReaderPalette,
    onRetry: () -> Unit
) {
    val isProblem = progress.stage == EmbeddingIndexStage.BLOCKED ||
        progress.stage == EmbeddingIndexStage.FAILED
    Surface(
        color = palette.glass,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            1.dp,
            if (isProblem) MaterialTheme.colorScheme.error.copy(alpha = 0.45f) else palette.glassBorder
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (progress.stage) {
                            EmbeddingIndexStage.NOT_CONFIGURED -> "全文检索未配置"
                            EmbeddingIndexStage.QUEUED -> "全文索引等待中"
                            EmbeddingIndexStage.INDEXING -> "正在建立全文索引"
                            EmbeddingIndexStage.READY -> "全文检索已就绪"
                            EmbeddingIndexStage.BLOCKED -> "全文索引需要处理"
                            EmbeddingIndexStage.FAILED -> "全文索引失败"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isProblem) MaterialTheme.colorScheme.error else palette.onBackground,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = buildString {
                            if (progress.totalChapters > 0) {
                                append("${progress.indexedChapters}/${progress.totalChapters} 章")
                                if (progress.message.isNotBlank()) append(" · ")
                            }
                            append(progress.message)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (progress.stage != EmbeddingIndexStage.READY &&
                    progress.stage != EmbeddingIndexStage.NOT_CONFIGURED
                ) {
                    TextButton(onClick = onRetry) { Text("重试", color = palette.accent) }
                }
            }
            if (progress.stage == EmbeddingIndexStage.INDEXING ||
                (progress.stage == EmbeddingIndexStage.QUEUED && progress.indexedChapters > 0)
            ) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    color = palette.accent,
                    trackColor = palette.glassBorder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun PersonaCardTab(persona: PersonaEntity?, palette: ReaderPalette) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (persona == null) {
            item {
                Text(
                    text = "还没有角色，去伴读页创建或导入一个。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.muted
                )
            }
            return@LazyColumn
        }
        item {
            Surface(
                color = palette.accentContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("${persona.name} · Character Card", fontWeight = FontWeight.Medium)
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val pills = listOf(
                            if (persona.isRoleplay) "扮演型" else "工具型",
                            "世界书 ${persona.worldBook().size} 条",
                            "工具 ${persona.enabledTools().size} 项"
                        )
                        pills.forEach { pill ->
                            Surface(color = palette.glass, shape = RoundedCornerShape(10.dp)) {
                                Text(
                                    text = pill,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.accent,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        item { CharacterField("人设", persona.personality, palette) }
        if (persona.speakingStyle.isNotBlank()) {
            item { CharacterField("说话风格", persona.speakingStyle, palette) }
        }
        if (persona.greeting.isNotBlank()) {
            item { CharacterField("开场白", persona.greeting, palette) }
        }
        val lore = persona.worldBook()
        if (lore.isNotEmpty()) {
            item {
                CharacterField(
                    title = "世界书 · ${lore.size} 条",
                    value = lore.joinToString("\n\n") { entry ->
                        buildString {
                            append(entry.name.ifBlank { "未命名条目" })
                            append("\n")
                            append(entry.content)
                        }
                    },
                    palette = palette
                )
            }
        }
    }
}

@Composable
private fun CompanionTabButton(
    selected: Boolean,
    label: String,
    icon: @Composable () -> Unit,
    palette: ReaderPalette,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) palette.glassStrong else Color.Transparent,
        shape = RoundedCornerShape(11.dp)
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) { icon() }
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = palette.onBackground)
        }
    }
}

internal sealed interface CompanionTimelineItem {
    val key: String

    data class Bubble(val message: MessageEntity) : CompanionTimelineItem {
        override val key: String = "message-${message.id}"
    }

    data class Tools(
        val sourceMessageId: Long,
        val steps: List<AgentExecutionStep>
    ) : CompanionTimelineItem {
        override val key: String = "tools-$sourceMessageId"
    }

    data class Media(
        val callId: String,
        val result: AgentMediaResult
    ) : CompanionTimelineItem {
        override val key: String = "media-$callId"
    }
}

internal fun buildCompanionTimeline(messages: List<MessageEntity>): List<CompanionTimelineItem> {
    val toolResults = messages.asSequence()
        .filter { it.role == "tool" && !it.toolCallId.isNullOrBlank() }
        .associateBy { it.toolCallId.orEmpty() }
    return buildList {
        messages.forEach { message ->
            when (message.role) {
                "user" -> if (message.content.isNotBlank()) add(CompanionTimelineItem.Bubble(message))
                "assistant" -> {
                    if (message.content.isNotBlank()) add(CompanionTimelineItem.Bubble(message))
                    val calls = message.toolCallsJson?.let { json ->
                        runCatching {
                            AiJson.decodeFromString(ListSerializer(ToolCall.serializer()), json)
                        }.getOrNull()
                    }.orEmpty()
                    if (calls.isNotEmpty()) {
                        add(
                            CompanionTimelineItem.Tools(
                                sourceMessageId = message.id,
                                steps = calls.map { call ->
                                    val result = toolResults[call.id]?.content
                                    val succeeded = result != null && result.isSuccessfulToolResult()
                                    AgentExecutionStep(
                                        callId = call.id,
                                        toolName = call.name,
                                        displayName = toolDisplayName(call.name),
                                        state = when {
                                            result == null -> AgentStepState.RUNNING
                                            succeeded -> AgentStepState.SUCCEEDED
                                            else -> AgentStepState.FAILED
                                        },
                                        detail = if (result != null && !succeeded) {
                                            result.take(120)
                                        } else {
                                            ""
                                        }
                                    )
                                }
                            )
                        )
                        calls.forEach { call ->
                            toolResults[call.id]?.content
                                ?.let(AgentMediaResult::decode)
                                ?.let { add(CompanionTimelineItem.Media(call.id, it)) }
                        }
                    }
                }
            }
        }
    }
}

private fun String.isSuccessfulToolResult(): Boolean {
    val value = trimStart()
    return !value.startsWith("工具执行失败") &&
        !value.startsWith("未知工具") &&
        !value.startsWith("缺少") &&
        !value.startsWith("超出") &&
        !value.startsWith("未找到") &&
        !value.startsWith("章节范围无效") &&
        !value.contains("无法确定") &&
        !value.contains("找不到这段 quote") &&
        !value.contains("检索不可用") &&
        !value.contains("尚未配置") &&
        !value.contains("还没有建成向量索引")
}

private fun toolDisplayName(name: String): String = when (name) {
    "get_reading_progress" -> "查询阅读进度"
    "search_book" -> "检索书中原文"
    "read_book_section" -> "读取指定已读章节"
    "recall_memory" -> "回忆过往交流"
    "add_annotation" -> "添加批注"
    "write_note" -> "写读书笔记"
    "save_plot_summary" -> "保存剧情梗概"
    "generate_image" -> "生成并保存插图"
    "synthesize_speech" -> "合成并缓存语音"
    else -> "调用 $name"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CompanionMessageBubble(
    message: MessageEntity,
    palette: ReaderPalette,
    canReroll: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReroll: () -> Unit,
    onBranch: () -> Unit
) {
    val fromUser = message.role == "user"
    var showActions by remember(message.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.86f),
            horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { showActions = !showActions },
                        onLongClick = { showActions = true }
                    ),
                color = if (fromUser) palette.accent else palette.accentContainer.copy(alpha = 0.48f),
                contentColor = if (fromUser) palette.onAccent else palette.onBackground,
                shape = if (fromUser) {
                    RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp)
                } else {
                    RoundedCornerShape(16.dp, 16.dp, 16.dp, 5.dp)
                }
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    val attachments = remember(message.id) {
                        MessageAttachment.decode(message.attachmentsJson)
                    }
                    if (attachments.isNotEmpty()) {
                        Row(
                            modifier = Modifier.padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            attachments.forEach { attachment ->
                                if (attachment.type == MessageAttachment.TYPE_IMAGE) {
                                    AsyncImage(
                                        model = java.io.File(context.filesDir, attachment.path),
                                        contentDescription = "图片附件",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(86.dp)
                                            .background(
                                                palette.glass,
                                                RoundedCornerShape(10.dp)
                                            )
                                    )
                                } else {
                                    Surface(
                                        color = palette.glass,
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, palette.glassBorder)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Outlined.Description,
                                                contentDescription = null,
                                                tint = palette.muted,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                attachment.name ?: "附件",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = palette.muted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(start = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (fromUser) {
                        Text(text = message.content, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        AiRichText(content = message.content, palette = palette)
                    }
                    if (message.editedAt != null) {
                        Text(
                            "已编辑",
                            style = MaterialTheme.typography.labelSmall,
                            color = (if (fromUser) palette.onAccent else palette.muted).copy(alpha = 0.65f),
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
            }
            AnimatedVisibility(visible = showActions, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    BubbleActionIcon(Icons.Outlined.ContentCopy, "复制", palette) {
                        showActions = false
                        clipboard.setText(AnnotatedString(message.content))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }
                    BubbleActionIcon(Icons.Outlined.Edit, "编辑", palette) {
                        showActions = false
                        onEdit()
                    }
                    if (!fromUser && canReroll) {
                        BubbleActionIcon(Icons.Outlined.Refresh, "重新生成", palette) {
                            showActions = false
                            onReroll()
                        }
                    }
                    BubbleActionIcon(Icons.Outlined.CallSplit, "从这里开分支", palette) {
                        showActions = false
                        onBranch()
                    }
                    BubbleActionIcon(Icons.Outlined.Delete, "删除", palette) {
                        showActions = false
                        onDelete()
                    }
                }
            }
        }
    }
}

@Composable
private fun BubbleActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Icon(
            icon,
            contentDescription = label,
            tint = palette.muted,
            modifier = Modifier.size(17.dp)
        )
    }
}

@Composable
internal fun CompanionBubble(text: String, fromUser: Boolean, palette: ReaderPalette) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.82f),
            color = if (fromUser) palette.accent else palette.accentContainer.copy(alpha = 0.48f),
            contentColor = if (fromUser) palette.onAccent else palette.onBackground,
            shape = if (fromUser) {
                RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp)
            } else {
                RoundedCornerShape(16.dp, 16.dp, 16.dp, 5.dp)
            }
        ) {
            if (fromUser) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            } else {
                AiRichText(
                    content = text,
                    palette = palette,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun CharacterField(title: String, value: String, palette: ReaderPalette) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Surface(
        color = palette.glass,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, palette.glassBorder),
        modifier = Modifier.clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.accent,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起$title" else "展开$title",
                    tint = palette.muted,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}
