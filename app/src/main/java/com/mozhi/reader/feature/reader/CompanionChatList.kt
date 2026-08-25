package com.mozhi.reader.feature.reader

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import com.mozhi.reader.ai.embedding.BookEmbeddingProgress
import com.mozhi.reader.ai.media.AgentMediaResult
import com.mozhi.reader.core.database.entity.MessageEntity

/**
 * 全屏伴读聊天的扁平列表模型（正向 LazyColumn，业内 AI 聊天通用形态）。
 *
 * 列表自上而下：场景头 → 索引胶囊 → 开场白 → 历史（过程卡 / 气泡 / 媒体）→
 * 本轮过程卡 → 流式气泡 → 状态行 → 错误行。流式增长永远发生在列表尾部（视口下方），
 * 生成期间不做任何程序化滚动，用户怎么滑都不会被内容顶动——这是
 * ChatGPT/Claude 移动端「问题置顶、答案向下生长」模式的结构性保证。
 *
 * 一条 AI 消息可以拆成多个 [ChatEntry.Bubble]（多气泡 / 语音行），
 * 气泡组的尖角、头像与时间戳在 [buildCompanionChatEntries] 里一次算清，
 * 渲染层只管照着画——这样规则可单测，不散落在 Composable 里。
 */
internal sealed interface ChatEntry {
    val key: String
    val contentType: String

    data class Scene(val text: String) : ChatEntry {
        override val key: String = "chat-scene"
        override val contentType: String = "scene"
    }

    data class Embedding(val progress: BookEmbeddingProgress) : ChatEntry {
        override val key: String = "chat-embedding"
        override val contentType: String = "embedding"
    }

    /** 一轮的思维链 + 工具步骤，挂在该轮气泡上方。 */
    data class Process(
        val id: String,
        val steps: List<AgentExecutionStep>,
        val reasoning: String?,
        val isLive: Boolean
    ) : ChatEntry {
        override val key: String = "process-$id"
        override val contentType: String = "process"
    }

    /**
     * 一个可见气泡。[message] 为 null 表示它没有对应的库内消息
     * （开场白、流式副本），此时不提供复制/编辑等操作。
     */
    data class Bubble(
        val id: String,
        val part: CompanionBubblePart,
        val fromUser: Boolean,
        val message: MessageEntity? = null,
        /** 气泡组最后一条：只有它带尖角（iMessage 规则）。 */
        val isTail: Boolean = true,
        /** 气泡组第一条：只有它显示头像，组内其余条目留出等宽占位。 */
        val showAvatar: Boolean = true,
        /** 组尾才显示时间；null = 不显示。 */
        val timestamp: Long? = null,
        val streaming: Boolean = false,
        val canReroll: Boolean = false
    ) : ChatEntry {
        override val key: String = "bubble-$id"
        override val contentType: String =
            if (part is CompanionBubblePart.Voice) "voice-bubble" else "text-bubble"
    }

    data class Media(val callId: String, val result: AgentMediaResult) : ChatEntry {
        override val key: String = "media-$callId"
        override val contentType: String = "media"
    }

    data class Status(val text: String) : ChatEntry {
        override val key: String = "chat-live-status"
        override val contentType: String = "status"
    }

    data class ErrorLine(val text: String) : ChatEntry {
        override val key: String = "chat-live-error"
        override val contentType: String = "error"
    }
}

internal fun buildCompanionChatEntries(
    timeline: List<CompanionTimelineItem>,
    liveSteps: List<AgentExecutionStep>,
    liveReasoning: String?,
    streamingText: String?,
    isStreaming: Boolean,
    toolStatus: String?,
    thinkingLabel: String,
    error: String?,
    greeting: String?,
    embeddingProgress: BookEmbeddingProgress?,
    sceneQuote: String,
    multiBubble: Boolean,
    lastAssistantMessageId: Long? = null
): List<ChatEntry> {
    val entries = buildList {
        add(ChatEntry.Scene(sceneQuote))
        embeddingProgress?.let { add(ChatEntry.Embedding(it)) }
        if (timeline.isEmpty() && !greeting.isNullOrBlank()) {
            // 开场白按同一套协议解析：角色卡里写了语音标记也该生效。
            parseCompanionParts(greeting, multiBubble).forEachIndexed { index, part ->
                add(ChatEntry.Bubble(id = "greeting-$index", part = part, fromUser = false))
            }
        }
        timeline.forEach { item ->
            when (item) {
                // 过程卡在它所属消息的气泡之前：先看到「想了什么、查了什么」，再看到答案。
                is CompanionTimelineItem.Process -> add(
                    ChatEntry.Process(
                        id = item.sourceMessageId.toString(),
                        steps = item.steps,
                        reasoning = item.reasoning,
                        isLive = false
                    )
                )
                is CompanionTimelineItem.Bubble -> {
                    val fromUser = item.message.role == "user"
                    val parts = if (fromUser) {
                        // 用户消息原样一条，不参与多气泡与语音标记解析。
                        listOf(CompanionBubblePart.Text(item.message.content))
                    } else {
                        parseCompanionParts(item.message.content, multiBubble)
                    }
                    parts.forEachIndexed { index, part ->
                        add(
                            ChatEntry.Bubble(
                                id = "${item.message.id}-$index",
                                part = part,
                                fromUser = fromUser,
                                message = item.message,
                                timestamp = item.message.createdAt,
                                canReroll = !fromUser &&
                                    item.message.id == lastAssistantMessageId &&
                                    index == parts.lastIndex
                            )
                        )
                    }
                }
                is CompanionTimelineItem.Media -> add(ChatEntry.Media(item.callId, item.result))
            }
        }
        if (liveSteps.isNotEmpty() || !liveReasoning.isNullOrBlank()) {
            add(
                ChatEntry.Process(
                    id = "live",
                    steps = liveSteps,
                    reasoning = liveReasoning,
                    isLive = true
                )
            )
        }
        streamingText
            ?.takeIf(String::isNotBlank)
            ?.takeUnless { text -> timeline.endsWithAssistantText(text) }
            ?.let { text ->
                // 流式期间只把「已经换行落定」的段落切成稳定气泡，最后一段跟着 token 长；
                // 否则每来一个字都会重排整条消息的气泡结构。
                val settled = text.substringBeforeLast('\n', missingDelimiterValue = "")
                val tailText = text.removePrefix(settled).removePrefix("\n")
                parseCompanionParts(settled, multiBubble).forEachIndexed { index, part ->
                    add(ChatEntry.Bubble(id = "live-$index", part = part, fromUser = false))
                }
                tailText.takeIf(String::isNotBlank)?.let { tail ->
                    add(
                        ChatEntry.Bubble(
                            id = "live-tail",
                            part = CompanionBubblePart.Text(tail),
                            fromUser = false,
                            streaming = true
                        )
                    )
                }
            }
        if (isStreaming || toolStatus != null) {
            add(ChatEntry.Status(toolStatus ?: thinkingLabel))
        }
        error?.let { add(ChatEntry.ErrorLine(it)) }
    }
    return entries.withBubbleGrouping()
}

/**
 * 气泡组语义：连续同一方向的气泡算一组，中间夹进过程卡/媒体卡即断组。
 * 只有组首显示头像、只有组尾带尖角与时间——这是 iMessage 的规则，
 * 也是让连着几条短消息看起来像「一个人连发」而不是「几个人各说一句」的关键。
 */
private fun List<ChatEntry>.withBubbleGrouping(): List<ChatEntry> {
    val result = toMutableList()
    var index = 0
    while (index < result.size) {
        val start = result[index] as? ChatEntry.Bubble
        if (start == null) {
            index++
            continue
        }
        var end = index
        while (end + 1 < result.size) {
            val next = result[end + 1] as? ChatEntry.Bubble ?: break
            if (next.fromUser != start.fromUser) break
            end++
        }
        for (position in index..end) {
            val bubble = result[position] as ChatEntry.Bubble
            result[position] = bubble.copy(
                showAvatar = position == index,
                isTail = position == end,
                timestamp = bubble.timestamp.takeIf { position == end }
            )
        }
        index = end + 1
    }
    return result
}

/** 数据库 Flow 可能先于 RoundCommitted 抵达 UI；此时隐藏已被历史接管的流式副本。 */
private fun List<CompanionTimelineItem>.endsWithAssistantText(text: String): Boolean =
    asReversed()
        .filterIsInstance<CompanionTimelineItem.Bubble>()
        .firstOrNull()
        ?.message
        ?.let { it.role == "assistant" && it.content == text }
        ?: false

/** 正向列表的贴底判定容差；「回到底部」浮钮与建议条都以它为界。 */
internal const val CHAT_BOTTOM_SLACK_PX = 32
private const val CHAT_ANIMATED_TAIL_ITEMS = 2

/** 最后一项完全露出（含容差）才算在底部；空列表视为在底部。 */
internal fun isChatListAtBottom(
    totalItems: Int,
    lastVisibleIndex: Int,
    lastVisibleBottom: Int,
    viewportBottom: Int,
    slackPx: Int = CHAT_BOTTOM_SLACK_PX
): Boolean {
    if (totalItems == 0) return true
    if (lastVisibleIndex != totalItems - 1) return false
    return lastVisibleBottom - viewportBottom <= slackPx
}

internal fun LazyListState.isAtLatest(): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()
    return isChatListAtBottom(
        totalItems = info.totalItemsCount,
        lastVisibleIndex = last?.index ?: -1,
        lastVisibleBottom = last?.let { it.offset + it.size } ?: 0,
        viewportBottom = info.viewportEndOffset - info.afterContentPadding
    )
}

/** 立即定位到列表真实底部（最后一项可能比视口还高，先进视口再补余量）。 */
internal suspend fun LazyListState.snapToLatest() {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    scrollToItem(lastIndex)
    remainingToBottom()?.let { scrollBy(it) }
}

/** 平滑滚动到列表真实底部（「回到底部」浮钮用）。 */
internal suspend fun LazyListState.animateToLatest() {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
    if (lastVisibleIndex < lastIndex - CHAT_ANIMATED_TAIL_ITEMS) {
        scrollToItem((lastIndex - CHAT_ANIMATED_TAIL_ITEMS).coerceAtLeast(0))
    }
    animateScrollToItem(lastIndex)
    remainingToBottom()?.let { animateScrollBy(it) }
}

private fun LazyListState.remainingToBottom(): Float? {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull { it.index == info.totalItemsCount - 1 } ?: return null
    val remaining = (last.offset + last.size) - (info.viewportEndOffset - info.afterContentPadding)
    return remaining.toFloat().takeIf { it > 0f }
}
