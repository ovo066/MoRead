package com.mozhi.reader.feature.reader

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import com.mozhi.reader.ai.embedding.BookEmbeddingProgress

/**
 * 全屏伴读聊天的扁平列表模型（正向 LazyColumn，业内 AI 聊天通用形态）。
 *
 * 列表自上而下：场景头 → 索引进度 → 开场白 → 历史时间线 → 本轮工具卡 →
 * 流式正文 → 状态行 → 错误行。流式增长永远发生在列表尾部（视口下方），
 * 生成期间不做任何程序化滚动，用户怎么滑都不会被内容顶动——这是
 * ChatGPT/Claude 移动端「问题置顶、答案向下生长」模式的结构性保证。
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

    data class Greeting(val text: String) : ChatEntry {
        override val key: String = "chat-greeting"
        override val contentType: String = "assistant-bubble"
    }

    data class History(val item: CompanionTimelineItem) : ChatEntry {
        override val key: String = item.key
        override val contentType: String = when (item) {
            is CompanionTimelineItem.Bubble -> "message-bubble"
            is CompanionTimelineItem.Tools -> "tools-card"
            is CompanionTimelineItem.Media -> "media-card"
        }
    }

    data class LiveTools(val steps: List<AgentExecutionStep>) : ChatEntry {
        override val key: String = "chat-live-tools"
        override val contentType: String = "tools-card"
    }

    data class LiveText(val text: String) : ChatEntry {
        override val key: String = "chat-live-text"
        override val contentType: String = "assistant-bubble"
    }

    data class LiveStatus(val text: String) : ChatEntry {
        override val key: String = "chat-live-status"
        override val contentType: String = "status"
    }

    data class LiveError(val text: String) : ChatEntry {
        override val key: String = "chat-live-error"
        override val contentType: String = "error"
    }
}

internal fun buildCompanionChatEntries(
    timeline: List<CompanionTimelineItem>,
    liveSteps: List<AgentExecutionStep>,
    streamingText: String?,
    isStreaming: Boolean,
    toolStatus: String?,
    thinkingLabel: String,
    error: String?,
    greeting: String?,
    embeddingProgress: BookEmbeddingProgress?,
    sceneQuote: String
): List<ChatEntry> = buildList {
    add(ChatEntry.Scene(sceneQuote))
    embeddingProgress?.let { add(ChatEntry.Embedding(it)) }
    if (timeline.isEmpty() && !greeting.isNullOrBlank()) add(ChatEntry.Greeting(greeting))
    timeline.forEach { add(ChatEntry.History(it)) }
    if (liveSteps.isNotEmpty()) add(ChatEntry.LiveTools(liveSteps))
    streamingText?.takeIf(String::isNotBlank)?.let { add(ChatEntry.LiveText(it)) }
    if (isStreaming || toolStatus != null) {
        add(ChatEntry.LiveStatus(toolStatus ?: thinkingLabel))
    }
    error?.let { add(ChatEntry.LiveError(it)) }
}

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
