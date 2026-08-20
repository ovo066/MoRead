package com.mozhi.reader.feature.reader

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ToolCall
import com.mozhi.reader.ai.embedding.BookEmbeddingProgress
import com.mozhi.reader.ai.embedding.EmbeddingIndexStage
import com.mozhi.reader.ai.media.AgentMediaResult
import androidx.compose.ui.text.font.FontFamily
import com.mozhi.reader.ui.components.ChatTextStyling
import com.mozhi.reader.ui.components.rememberChatBubbleStyle
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.core.database.entity.PersonaChatAppearance
import com.mozhi.reader.core.library.MessageAttachment
import kotlinx.serialization.builtins.ListSerializer

/**
 * 伴读聊天的共享组件与时间线模型：气泡、执行过程卡、插图/语音卡、索引进度卡。
 * 聊天界面本体在 CompanionChatScreen（全屏页，正向列表），列表条目模型在
 * CompanionChatList.kt；旧的阅读页弹层版聊天已随全屏页上线移除。
 */
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
                // 固定高度：图片异步解码完成时卡片尺寸不变，滚动经过时不会跳一下。
                AsyncImage(
                    model = result.path,
                    contentDescription = "Agent 生成的书籍插图",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
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
    "web_search" -> "搜索互联网"
    "web_scrape" -> "抓取网页正文"
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
    onBranch: () -> Unit,
    appearance: PersonaChatAppearance = PersonaChatAppearance.DEFAULT,
    fontFamily: FontFamily? = null,
    onLocateCitation: (CompanionCitation) -> Unit = {}
) {
    val fromUser = message.role == "user"
    var showActions by remember(message.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val style = chatBubbleStyleFor(appearance, fromUser, palette)
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
                color = style.container,
                contentColor = style.content,
                border = style.border,
                shape = style.shape(fromUser)
            ) {
                ChatTextStyling(appearance, fontFamily) {
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
                        // 引用标记是给程序看的，渲染前摘掉；引文本身留在正文里保持句子通顺。
                        val parsed = remember(message.content) {
                            CompanionCitationParser.parse(message.content)
                        }
                        AiRichText(content = parsed.displayText, palette = palette)
                        if (parsed.citations.isNotEmpty()) {
                            CitationChips(
                                citations = parsed.citations,
                                palette = palette,
                                onClick = onLocateCitation
                            )
                        }
                    }
                    if (message.editedAt != null) {
                        Text(
                            "已编辑",
                            style = MaterialTheme.typography.labelSmall,
                            color = style.content.copy(alpha = 0.65f),
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
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

/** [streaming] 为 true 时按块级分段渲染，稳定段落不随新 token 重排。 */
@Composable
internal fun CompanionBubble(
    text: String,
    fromUser: Boolean,
    palette: ReaderPalette,
    streaming: Boolean = false,
    appearance: PersonaChatAppearance = PersonaChatAppearance.DEFAULT,
    fontFamily: FontFamily? = null
) {
    val style = chatBubbleStyleFor(appearance, fromUser, palette)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f),
            color = style.container,
            contentColor = style.content,
            border = style.border,
            shape = style.shape(fromUser)
        ) {
            ChatTextStyling(appearance, fontFamily) {
            when {
                fromUser -> Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
                streaming -> StreamingAiRichText(
                    content = text,
                    palette = palette,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
                else -> AiRichText(
                    content = text,
                    palette = palette,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
            }
        }
    }
}

/** 「跳到原文」胶囊：一条引用一枚，点了退回阅读页并高亮那句话。 */
@Composable
private fun CitationChips(
    citations: List<CompanionCitation>,
    palette: ReaderPalette,
    onClick: (CompanionCitation) -> Unit
) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        citations.forEach { citation ->
            Surface(
                onClick = { onClick(citation) },
                shape = RoundedCornerShape(9.dp),
                color = palette.glass,
                contentColor = palette.onBackground,
                border = BorderStroke(1.dp, palette.glassBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.MenuBook,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = CompanionCitationParser.label(citation),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 5.dp)
                    )
                }
            }
        }
    }
}

/**
 * 默认外观下必须与改动前逐像素一致，因此把阅读页调色板作为「未自定义」时的取值传进去，
 * 而不是让共享实现回落到 MaterialTheme。
 */
@Composable
private fun chatBubbleStyleFor(
    appearance: PersonaChatAppearance,
    fromUser: Boolean,
    palette: ReaderPalette
) = rememberChatBubbleStyle(
    appearance = appearance,
    fromUser = fromUser,
    defaultUserContainer = palette.accent,
    defaultUserContent = palette.onAccent,
    defaultAssistantContainer = palette.accentContainer.copy(alpha = 0.48f),
    defaultAssistantContent = palette.onBackground,
    surfaceContent = palette.onBackground
)
