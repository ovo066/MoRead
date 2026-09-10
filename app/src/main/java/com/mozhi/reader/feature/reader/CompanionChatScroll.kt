package com.mozhi.reader.feature.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 聊天只有一个滚动所有者：底部跟随 / 自由浏览 / 主动返回。正向列表保持消息顶部的
 * 稳定 key 锚点，翻看超长流式气泡时也不会被尾部增长带走；只在已完成测量且用户没有
 * 操作时补齐底部，不轮询、不添加动态留白、不把新问题强塞到视口顶部。
 */
@Stable
internal class CompanionChatScrollState(initialLastIndex: Int = -1) {
    // Seed the very first measure at the tail, including bubbles taller than the viewport.
    // Measuring the oldest messages and then issuing two scrolls causes visible jumps during
    // Navigation's lookahead/enter transition, even when a later frame hides the list.
    val listState = LazyListState(
        firstVisibleItemIndex = initialLastIndex.coerceAtLeast(0),
        firstVisibleItemScrollOffset = if (initialLastIndex >= 0) CHAT_TAIL_SCROLL_OFFSET else 0
    )
    private var initialAnchorKey: String? = null

    fun prepareInitialPosition(lastKey: String, lastIndex: Int) {
        if (initiallyPositioned || initialAnchorKey == lastKey) return
        initialAnchorKey = lastKey
        listState.requestScrollToItem(lastIndex, CHAT_TAIL_SCROLL_OFFSET)
    }
    var initiallyPositioned by mutableStateOf(false)
        internal set
    var followingLatest by mutableStateOf(true)
        private set
    var returningToLatest by mutableStateOf(false)
        private set
    var followRequest by mutableIntStateOf(0)
        private set
    private var gestureTowardLatest = false

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y != 0f) {
                // 手指一动就交还控制权；向历史拖动时不能因仍在贴底容差内而重新跟随。
                followingLatest = false
                gestureTowardLatest = available.y < 0f
            }
            return Offset.Zero
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource
        ): Offset {
            // 也覆盖向下翻回最新消息的惯性滚动，但布局变化本身不能恢复自动跟随。
            if (gestureTowardLatest && (consumed.y < 0f || available.y < 0f) && listState.isAtLatest()) {
                followingLatest = true
            }
            return Offset.Zero
        }
    }

    fun pauseFollowing() {
        followingLatest = false
        gestureTowardLatest = false
    }

    fun requestFollowLatest() {
        followingLatest = true
        followRequest++
    }

    suspend fun returnToLatest() {
        if (returningToLatest) return
        returningToLatest = true
        followingLatest = true
        try {
            listState.animateToLatest()
        } finally {
            // 用户手势可取消动画；此处不能擅自重新打开 followingLatest。
            returningToLatest = false
        }
    }
}

@Composable
internal fun rememberCompanionChatScrollState(
    sessionKey: String,
    entries: List<ChatEntry>,
    isLoadingMessages: Boolean
): CompanionChatScrollState {
    val state = remember(sessionKey) {
        CompanionChatScrollState(if (isLoadingMessages) -1 else entries.lastIndex)
    }
    // A deferred Room result must also be anchored BEFORE LazyColumn measures that result.
    SideEffect {
        if (!isLoadingMessages && entries.isNotEmpty()) {
            state.prepareInitialPosition(entries.last().key, entries.lastIndex)
        }
    }
    val latestEntries by rememberUpdatedState(entries)
    val loading by rememberUpdatedState(isLoadingMessages)
    LaunchedEffect(state) {
        snapshotFlow {
            // 同时观察数据与布局；不能拿尚未测量的新数据去索引上一帧的列表。
            ChatFollowSnapshot(
                ready = !loading,
                entryCount = latestEntries.size,
                lastEntryKey = latestEntries.lastOrNull()?.key,
                layout = state.listState.layoutInfo,
                following = state.followingLatest,
                returning = state.returningToLatest,
                scrolling = state.listState.isScrollInProgress,
                request = state.followRequest
            )
        }.collect { snapshot ->
            if (!snapshot.ready || snapshot.scrolling || snapshot.returning ||
                snapshot.layout.totalItemsCount != snapshot.entryCount
            ) return@collect
            if (snapshot.entryCount == 0) {
                state.initiallyPositioned = true
                return@collect
            }
            if (state.initiallyPositioned && !state.followingLatest) return@collect
            val last = snapshot.layout.visibleItemsInfo.lastOrNull() ?: return@collect
            // 总数相同的会话替换/删除也要等新 key 真正进入布局，避免按旧高度滚动。
            if (last.index == snapshot.entryCount - 1 && last.key != snapshot.lastEntryKey) return@collect
            try {
                if (last.index != snapshot.entryCount - 1) {
                    state.listState.snapToLatest()
                } else {
                    val overflow = last.offset + last.size -
                        (snapshot.layout.viewportEndOffset - snapshot.layout.afterContentPadding)
                    if (overflow > 0) state.listState.scrollBy(overflow.toFloat())
                }
                // Reveal an aligned measurement, not merely a completed scroll request.
                // Never hide an already-visible list after a subsequent height change.
                val measured = state.listState.layoutInfo
                val measuredLast = measured.visibleItemsInfo.lastOrNull()
                if (!state.initiallyPositioned && measured.totalItemsCount == snapshot.entryCount &&
                    measuredLast != null && measuredLast.index == snapshot.entryCount - 1 &&
                    measuredLast.key == snapshot.lastEntryKey &&
                    measuredLast.offset + measuredLast.size <=
                        measured.viewportEndOffset - measured.afterContentPadding + 1
                ) state.initiallyPositioned = true
            } catch (_: CancellationException) {
                // 手势的优先级高于自动滚动；仅本次滚动被抢占时继续观察，不能杀掉整个跟随器。
                currentCoroutineContext().ensureActive()
            }
        }
    }
    return state
}

/** 首次贴底后列表淡入的时长；与二级页 shared-axis 转场同期完成，不做第二段可感知的动画。 */
private const val CHAT_REVEAL_FADE_MS = 160

private data class ChatFollowSnapshot(
    val ready: Boolean,
    val entryCount: Int,
    val lastEntryKey: String?,
    val layout: androidx.compose.foundation.lazy.LazyListLayoutInfo,
    val following: Boolean,
    val returning: Boolean,
    val scrolling: Boolean,
    val request: Int
)

@Composable
internal fun CompanionChatMessageList(
    entries: List<ChatEntry>,
    scrollState: CompanionChatScrollState,
    modifier: Modifier = Modifier,
    content: @Composable (ChatEntry) -> Unit
) {
    // 首次定位完成后短淡入，而不是硬切出现：页面还在转场滑动时整屏列表突然「弹」出来，
    // 就是用户感知到的「闪」。已可见后不再隐藏，此后的高度变化只由跟随逻辑处理。
    val revealAlpha by animateFloatAsState(
        targetValue = if (scrollState.initiallyPositioned) 1f else 0f,
        animationSpec = tween(durationMillis = if (scrollState.initiallyPositioned) CHAT_REVEAL_FADE_MS else 0),
        label = "chatReveal"
    )
    LazyColumn(
        state = scrollState.listState,
        userScrollEnabled = scrollState.initiallyPositioned,
        modifier = modifier
            .nestedScroll(scrollState.nestedScrollConnection)
            .graphicsLayer { alpha = revealAlpha },
        contentPadding = PaddingValues(top = 8.dp, bottom = 14.dp)
    ) {
        itemsIndexed(
            items = entries,
            key = { _, entry -> entry.key },
            contentType = { _, entry -> entry.contentType }
        ) { index, entry ->
            Box(
                Modifier.fillMaxWidth().padding(
                    top = chatEntryTopSpacing(entries.getOrNull(index - 1), entry).dp
                )
            ) {
                content(entry)
            }
        }
    }
}
