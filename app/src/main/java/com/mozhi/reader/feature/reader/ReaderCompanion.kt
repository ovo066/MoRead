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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ToolCall
import com.mozhi.reader.ai.media.AgentMediaResult
import androidx.compose.ui.text.font.FontFamily
import com.mozhi.reader.ui.components.ChatTextStyling
import com.mozhi.reader.ui.components.PersonaAvatarImage
import com.mozhi.reader.ui.components.rememberChatBubbleStyle
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.core.database.entity.PersonaChatAppearance
import com.mozhi.reader.core.library.MessageAttachment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.builtins.ListSerializer

/**
 * 伴读聊天的共享组件与时间线模型：气泡、媒体气泡、时间线构建。
 * 聊天界面本体在 CompanionChatScreen（全屏页，正向列表），列表条目模型在
 * CompanionChatList.kt，过程卡与场景头在 CompanionProcessCard.kt。
 */

/**
 * agent 生成的插图/语音，按 **气泡** 而不是全宽大卡呈现——它是角色「发来的一张图」，
 * 和它说的话是同一条消息流里的东西，不该长得像系统通知。
 */
@Composable
internal fun CompanionMediaBubble(
    result: AgentMediaResult,
    palette: ReaderPalette,
    onOpenImage: (String, String?) -> Unit,
    onPlayAudio: (String) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = palette.accentContainer.copy(alpha = 0.42f),
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 5.dp),
            border = BorderStroke(1.dp, palette.glassBorder),
            modifier = Modifier.fillMaxWidth(0.86f)
        ) {
            if (result.mediaKind == "image") {
                Column(
                    modifier = Modifier
                        .clickable { onOpenImage(result.path, null) }
                        .padding(5.dp)
                ) {
                    // 固定高度：图片异步解码完成时气泡尺寸不变，滚动经过时不会跳一下。
                    AsyncImage(
                        model = result.path,
                        contentDescription = "角色生成的书籍插图",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(14.dp))
                    )
                    result.message.takeIf(String::isNotBlank)?.let { caption ->
                        Text(
                            text = caption,
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.muted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
                    CompanionVoiceBubble(
                        text = result.message.ifBlank { "角色发来一段语音" },
                        clip = VoiceClipState(path = result.path),
                        palette = palette,
                        onPrepare = {},
                        onRegenerate = {},
                        onPlay = onPlayAudio
                    )
                }
            }
        }
    }
}

/**
 * 气泡最大宽度占行宽的比例。两侧共用同一个值，左右外边距才对称 ——
 * 改造前 AI 侧是 `fillMaxWidth(0.80f)` 套在被头像栏挤窄的 Row 里，比用户气泡窄一整栏。
 */
private const val BUBBLE_MAX_WIDTH_FRACTION = 0.86f

/** 组首头像直径。放在气泡上方，所以可以比并排时更小。 */
private val AVATAR_SIZE = 22.dp

internal sealed interface CompanionTimelineItem {
    val key: String

    data class Bubble(val message: MessageEntity) : CompanionTimelineItem {
        override val key: String = "message-${message.id}"
    }

    /** 一条 AI 消息的思维链与工具步骤；两者至少有一个非空时才产生。 */
    data class Process(
        val sourceMessageId: Long,
        val steps: List<AgentExecutionStep>,
        val reasoning: String?
    ) : CompanionTimelineItem {
        override val key: String = "process-$sourceMessageId"
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
                    val calls = message.toolCallsJson?.let { json ->
                        runCatching {
                            AiJson.decodeFromString(ListSerializer(ToolCall.serializer()), json)
                        }.getOrNull()
                    }.orEmpty()
                    val steps = calls.map { call ->
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
                            detail = if (result != null && !succeeded) result.take(120) else "",
                            arguments = call.arguments,
                            resultPreview = result?.take(MAX_HISTORY_RESULT_PREVIEW).orEmpty()
                        )
                    }
                    // 过程在前、发言在后：先「想了什么、查了什么」，再看它说了什么。
                    if (steps.isNotEmpty() || !message.reasoningContent.isNullOrBlank()) {
                        add(
                            CompanionTimelineItem.Process(
                                sourceMessageId = message.id,
                                steps = steps,
                                reasoning = message.reasoningContent
                            )
                        )
                    }
                    if (message.content.isNotBlank()) add(CompanionTimelineItem.Bubble(message))
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

/** 历史侧的结果预览与 AgentLoop 实时事件同一上限，展开后看到的内容前后一致。 */
private const val MAX_HISTORY_RESULT_PREVIEW = 600

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

/**
 * 一个可见气泡。历史、开场白与流式副本共用它——差别全在 [entry] 里算好了：
 * 组首显示头像、组尾带尖角与时间、只有落库消息才给操作行。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CompanionChatBubble(
    entry: ChatEntry.Bubble,
    palette: ReaderPalette,
    personaName: String,
    personaAvatarPath: String?,
    appearance: PersonaChatAppearance = PersonaChatAppearance.DEFAULT,
    fontFamily: FontFamily? = null,
    locatedCitations: List<LocatedCompanionCitation> = emptyList(),
    onLocateCitation: (LocatedCompanionCitation) -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onReroll: () -> Unit = {},
    onBranch: () -> Unit = {},
    onSpeak: (String) -> Unit = {},
    voiceClip: VoiceClipState? = null,
    onPrepareVoice: () -> Unit = {},
    onRegenerateVoice: () -> Unit = {},
    onPlayVoice: (String) -> Unit = {}
) {
    val fromUser = entry.fromUser
    val message = entry.message
    var showActions by remember(entry.key) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val style = chatBubbleStyleFor(appearance, fromUser, palette)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val bubbleMaxWidth = maxWidth * BUBBLE_MAX_WIDTH_FRACTION
        Column(modifier = Modifier.fillMaxWidth()) {
            // 头像与角色名摆在气泡**上方**：气泡因此能用满整行宽度，连发的几条也不再
            // 被一条空头像栏顶着缩进；一组只在开头署一次名，中间几条直接贴着往下长。
            if (!fromUser && entry.showAvatar) {
                Row(
                    modifier = Modifier.padding(start = 2.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PersonaAvatarImage(
                        name = personaName,
                        avatarPath = personaAvatarPath,
                        modifier = Modifier.size(AVATAR_SIZE)
                    )
                    if (personaName.isNotBlank()) {
                        Text(
                            text = personaName,
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 7.dp)
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start
            ) {
                Column(
                    modifier = Modifier.widthIn(max = bubbleMaxWidth),
                    horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start
                ) {
                    Surface(
                        modifier = Modifier.combinedClickable(
                            enabled = message != null,
                            onClick = { showActions = !showActions },
                            onLongClick = { showActions = true }
                        ),
                        color = style.container,
                        contentColor = style.content,
                        border = style.border,
                        shape = style.shape(fromUser, entry.isTail)
                    ) {
                        ChatTextStyling(appearance, fontFamily) {
                            Column(
                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp)
                            ) {
                                // 附件只挂在这条消息的第一个气泡上，多气泡时不会重复出现。
                                if ((message != null && entry.showAvatar) || fromUser) {
                                    message?.let { BubbleAttachments(it, palette, context) }
                                }
                                BubbleBody(
                                    entry = entry,
                                    palette = palette,
                                    locatedCitations = locatedCitations,
                                    onLocateCitation = onLocateCitation,
                                    voiceClip = voiceClip,
                                    onPrepareVoice = onPrepareVoice,
                                    onRegenerateVoice = onRegenerateVoice,
                                    onPlayVoice = onPlayVoice
                                )
                                if (message?.editedAt != null && entry.isTail) {
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
                        BubbleActionBar(
                            palette = palette,
                            fromUser = fromUser,
                            canReroll = entry.canReroll,
                            onDismiss = { showActions = false },
                            onCopy = {
                                clipboard.setText(AnnotatedString(entry.part.text))
                                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            },
                            onSpeak = { onSpeak(entry.part.text) },
                            onEdit = onEdit,
                            onReroll = onReroll,
                            onBranch = onBranch,
                            onDelete = onDelete
                        )
                    }
                    entry.timestamp?.takeIf { !showActions }?.let { timestamp ->
                        val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
                        Text(
                            text = formatter.format(Date(timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.muted.copy(alpha = 0.55f),
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BubbleBody(
    entry: ChatEntry.Bubble,
    palette: ReaderPalette,
    locatedCitations: List<LocatedCompanionCitation>,
    onLocateCitation: (LocatedCompanionCitation) -> Unit,
    voiceClip: VoiceClipState?,
    onPrepareVoice: () -> Unit,
    onRegenerateVoice: () -> Unit,
    onPlayVoice: (String) -> Unit
) {
    val text = entry.part.text
    when {
        entry.fromUser -> Text(text = text, style = MaterialTheme.typography.bodyMedium)
        entry.part is CompanionBubblePart.Voice -> if (voiceClip?.failed == true) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        } else {
            CompanionVoiceBubble(
                text = text,
                clip = voiceClip,
                palette = palette,
                onPrepare = onPrepareVoice,
                onRegenerate = onRegenerateVoice,
                onPlay = onPlayVoice
            )
        }
        else -> {
            // 引用标记是给程序看的，渲染前摘掉；引文本身留在正文里保持句子通顺。
            val parsed = remember(text) { CompanionCitationParser.parse(text) }
            if (entry.streaming) {
                StreamingAiRichText(content = parsed.displayText, palette = palette)
            } else {
                AiRichText(content = parsed.displayText, palette = palette)
            }
            if (locatedCitations.isNotEmpty() && entry.isTail) {
                CitationChips(
                    citations = locatedCitations,
                    palette = palette,
                    onClick = onLocateCitation
                )
            }
        }
    }
}

@Composable
private fun BubbleAttachments(
    message: MessageEntity,
    palette: ReaderPalette,
    context: android.content.Context
) {
    val attachments = remember(message.id) { MessageAttachment.decode(message.attachmentsJson) }
    if (attachments.isEmpty()) return
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
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.glass, RoundedCornerShape(10.dp))
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

/** 气泡操作行：一枚浮出的玻璃胶囊，而不是五个散在气泡下的方钮。 */
@Composable
private fun BubbleActionBar(
    palette: ReaderPalette,
    fromUser: Boolean,
    canReroll: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onEdit: () -> Unit,
    onReroll: () -> Unit,
    onBranch: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = palette.glassStrong,
        shape = CircleShape,
        border = BorderStroke(1.dp, palette.glassBorder),
        shadowElevation = 3.dp,
        modifier = Modifier.padding(top = 3.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BubbleActionIcon(Icons.Outlined.ContentCopy, "复制", palette) {
                onDismiss()
                onCopy()
            }
            // 「朗读」是 AI 自主发语音之外的手动兜底：想听哪句，点哪句。
            if (!fromUser) {
                BubbleActionIcon(Icons.Outlined.VolumeUp, "朗读", palette) {
                    onDismiss()
                    onSpeak()
                }
            }
            BubbleActionIcon(Icons.Outlined.Edit, "编辑", palette) {
                onDismiss()
                onEdit()
            }
            if (!fromUser && canReroll) {
                BubbleActionIcon(Icons.Outlined.Refresh, "重新生成", palette) {
                    onDismiss()
                    onReroll()
                }
            }
            BubbleActionIcon(Icons.Outlined.CallSplit, "从这里开分支", palette) {
                onDismiss()
                onBranch()
            }
            BubbleActionIcon(Icons.Outlined.Delete, "删除", palette) {
                onDismiss()
                onDelete()
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
            modifier = Modifier.size(16.dp)
        )
    }
}


/** 「跳到原文」胶囊：一条引用一枚，点了退回阅读页并高亮那句话。 */
@Composable
private fun CitationChips(
    citations: List<LocatedCompanionCitation>,
    palette: ReaderPalette,
    onClick: (LocatedCompanionCitation) -> Unit
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
                        text = CompanionCitationParser.label(citation.citation),
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
