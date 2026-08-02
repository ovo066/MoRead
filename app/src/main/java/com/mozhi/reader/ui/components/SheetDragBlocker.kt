package com.mozhi.reader.ui.components

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * ModalBottomSheet 内嵌可滚动内容的手势防抖（CLAUDE.md「UI 手感」条目）：
 * 弹层的嵌套滚动会把列表消费不掉的滚动量（列表到顶后的下拉、内容不满一屏时的
 * 任何下拉、到底后的上推）拿去拖动整个弹层，松手再弹回——列表一滑整层上下弹跳。
 * 这里把剩余滚动量全部吃掉：内容区手势只滚列表，关闭弹层走返回/蒙层/拖把手。
 *
 * 用法：给弹层内的 LazyColumn/verticalScroll 容器（或其父层）挂
 * `Modifier.blockSheetDrag(state)`，state 是该滚动容器自己的状态。
 */
@Composable
fun Modifier.blockSheetDrag(state: ScrollableState): Modifier {
    val connection = remember(state) { SheetDragBlockerConnection(state) }
    return nestedScroll(connection)
}

private class SheetDragBlockerConnection(
    private val state: ScrollableState
) : NestedScrollConnection {

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // 列表已到顶时，向下拖拽本来会先交给弹层；在父层接管之前截断它。
        return if (available.y > 0f && !state.canScrollBackward) {
            Offset(0f, available.y)
        } else {
            Offset.Zero
        }
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset = Offset(0f, available.y)

    override suspend fun onPreFling(available: Velocity): Velocity {
        return if (available.y > 0f && !state.canScrollBackward) {
            Velocity(0f, available.y)
        } else {
            Velocity.Zero
        }
    }

    override suspend fun onPostFling(
        consumed: Velocity,
        available: Velocity
    ): Velocity = Velocity(0f, available.y)
}
