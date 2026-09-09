package com.mozhi.reader.feature.reader

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import com.mozhi.reader.ai.embedding.BookEmbeddingProgress
import com.mozhi.reader.ai.media.AgentMediaResult
import com.mozhi.reader.core.database.entity.MessageEntity

/**
 * 全屏伴读聊天的扁平列表模型（时间顺序；界面以稳定 key 保持浏览锚点）。
 *
 * 列表自上而下：场景头 → 索引胶囊 → 开场白 → 历史（过程卡 / 气泡 / 媒体）→
 * 本轮过程卡 → 流式气泡 → 状态行 → 错误行。用户翻看历史时保持稳定 key 和滚动位置，
 * 只有停在底部或主动发送/返回最新消息时才自动跟随。
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
        /** 多气泡流式组尚可能继续长出新条目，期间冻结所有尖角避免旧气泡改形。 */
        val freezeGroupTail: Boolean = false,
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
    lastAssistantMessageId: Long? = null,
    liveEntryId: String = "live"
): List<ChatEntry> {
    // Room and the stream collector may arrive in either order. Identity, never text,
    // decides whether persistence has taken over this round.
    val persistedRoundIds = timeline.mapNotNull {
        when (it) {
            is CompanionTimelineItem.Bubble -> it.message.clientRoundId
            is CompanionTimelineItem.Process -> it.clientRoundId
            is CompanionTimelineItem.Media -> null
        }
    }.toSet()
    val liveRoundCommitted = liveEntryId in persistedRoundIds
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
                        id = item.clientRoundId ?: item.sourceMessageId.toString(),
                        steps = item.steps,
                        reasoning = item.reasoning,
                        isLive = false
                    )
                )
                is CompanionTimelineItem.Bubble -> {
                    val fromUser = item.message.role == "user"
                    val messageUiId = item.message.clientRoundId ?: item.message.id.toString()
                    val parts = if (fromUser) {
                        // 用户消息原样一条，不参与多气泡与语音标记解析。
                        listOf(CompanionBubblePart.Text(item.message.content))
                    } else {
                        parseCompanionParts(item.message.content, multiBubble)
                    }
                    parts.forEachIndexed { index, part ->
                        add(
                            ChatEntry.Bubble(
                                id = "$messageUiId-$index",
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
        if (!liveRoundCommitted && (liveSteps.isNotEmpty() || !liveReasoning.isNullOrBlank())) {
            add(
                ChatEntry.Process(
                    id = liveEntryId,
                    steps = liveSteps,
                    reasoning = liveReasoning,
                    isLive = true
                )
            )
        }
        streamingText
            ?.takeIf(String::isNotBlank)
            ?.takeUnless { liveRoundCommitted }
            ?.let { text ->
                // Keep the same parser during streaming and persistence. Cutting at
                // the last newline loses fenced/list block context and merges rows at commit.
                val parts = parseCompanionParts(text, multiBubble)
                parts.forEachIndexed { index, part ->
                    val unfinishedVoice = isStreaming && index == parts.lastIndex &&
                        part is CompanionBubblePart.Voice
                    add(
                        ChatEntry.Bubble(
                            id = "$liveEntryId-$index",
                            // Do not synthesize audio for every growing voice token.
                            part = if (unfinishedVoice) CompanionBubblePart.Text(part.text) else part,
                            fromUser = false,
                            streaming = isStreaming && index == parts.lastIndex,
                            freezeGroupTail = multiBubble && isStreaming
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
 *
 * 时间戳还要再降一次噪：与上一枚**已显示**的时间戳相隔不到 [TIMESTAMP_GAP_MS] 就不再显示。
 * 一来一回的连续对话里每组都盖一个「14:03」纯属噪声，只有真的隔了一段时间才值得标出来。
 */
private fun List<ChatEntry>.withBubbleGrouping(): List<ChatEntry> {
    val result = toMutableList()
    var index = 0
    var lastShownTimestamp: Long? = null
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
        val freezeGroupTail = (index..end).any { position ->
            (result[position] as ChatEntry.Bubble).freezeGroupTail
        }
        for (position in index..end) {
            val bubble = result[position] as ChatEntry.Bubble
            val isTail = !freezeGroupTail && position == end
            val timestamp = bubble.timestamp
                ?.takeIf { isTail }
                ?.takeIf { shouldShowTimestamp(lastShownTimestamp, it) }
            if (timestamp != null) lastShownTimestamp = timestamp
            result[position] = bubble.copy(
                showAvatar = position == index,
                // 流式期间冻结组形状：新换行不会让上一条气泡反复长/丢尖角。
                isTail = isTail,
                timestamp = timestamp
            )
        }
        index = end + 1
    }
    return result
}

/** 相邻两枚时间戳的最小间隔；小于它就不再重复标注。 */
internal const val TIMESTAMP_GAP_MS = 5 * 60 * 1000L

internal fun shouldShowTimestamp(lastShown: Long?, candidate: Long): Boolean =
    lastShown == null || candidate - lastShown >= TIMESTAMP_GAP_MS

/**
 * 条目之间的纵向间距。
 *
 * 改造前整条列表统一 `spacedBy(8.dp)`：同一个人连发的三条和「你问 / 它答」之间一样疏，
 * 于是看不出哪几条是一组。这里把组内压到 [SPACING_WITHIN_GROUP]、组间与异类条目
 * 拉到 [SPACING_BETWEEN_GROUPS]，分组关系靠留白自己说清楚。
 */
internal fun chatEntryTopSpacing(previous: ChatEntry?, current: ChatEntry): Int {
    if (previous == null) return 0
    val before = previous as? ChatEntry.Bubble ?: return SPACING_BETWEEN_GROUPS
    val now = current as? ChatEntry.Bubble ?: return SPACING_BETWEEN_GROUPS
    // showAvatar 就是「组首」标记（用户侧没有头像，但同样按组首置位）。
    if (now.showAvatar) return SPACING_BETWEEN_GROUPS
    return if (before.fromUser == now.fromUser) SPACING_WITHIN_GROUP else SPACING_BETWEEN_GROUPS
}

internal const val SPACING_WITHIN_GROUP = 3
internal const val SPACING_BETWEEN_GROUPS = 12

/** 正向列表的贴底判定容差；「回到底部」浮钮与建议条都以它为界。 */
internal const val CHAT_BOTTOM_SLACK_PX = 32
private const val CHAT_ANIMATED_TAIL_ITEMS = 2
// Large but overflow-safe; LazyColumn clamps to the actual content end in a single measure.
internal const val CHAT_TAIL_SCROLL_OFFSET = Int.MAX_VALUE / 2

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

/** 距离真实列表底部的像素数；最后一项未进入视口时视为无限远。 */
internal fun LazyListState.distanceFromLatest(): Int {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return 0
    val last = info.visibleItemsInfo.lastOrNull()
    if (last?.index != info.totalItemsCount - 1) return Int.MAX_VALUE
    val viewportBottom = info.viewportEndOffset - info.afterContentPadding
    return (last.offset + last.size - viewportBottom).coerceAtLeast(0)
}

/** 一次测量定位到列表真实底部，最后一项高于视口时也无需先露出顶部。 */
internal suspend fun LazyListState.snapToLatest() {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    scrollToItem(lastIndex, CHAT_TAIL_SCROLL_OFFSET)
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
