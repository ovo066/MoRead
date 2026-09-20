package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.datastore.PageTurnAnimation

/**
 * 一页提交后，旧三页窗口里有两张位图仍然对应新窗口，可直接换引用；离窗的那张供新邻页
 * 原地重绘。把映射独立出来，避免连续翻页每次重画前/中/后三个整屏位图。
 */
internal data class RotatedPageWindow<T>(
    val previous: T?,
    val current: T?,
    val next: T?,
    val reusable: T?
)

internal fun <T> rotatePageWindow(
    previous: T?,
    current: T?,
    next: T?,
    direction: PageTurnDirection
): RotatedPageWindow<T> = when (direction) {
    PageTurnDirection.NEXT -> RotatedPageWindow(
        previous = current,
        current = next,
        next = null,
        reusable = previous
    )
    PageTurnDirection.PREVIOUS -> RotatedPageWindow(
        previous = null,
        current = previous,
        next = current,
        reusable = next
    )
}

/**
 * 普通重绘在翻页期间必须延迟；已登记的提交刷新必须同步轮换位图窗口，
 * 否则下一次快速翻页会读到上一轮的页面快照。
 */
internal fun shouldRefreshPageWindowImmediately(
    turnRunning: Boolean,
    relativePosition: Int,
    hasPreparedTurn: Boolean
): Boolean = !turnRunning || (relativePosition == 0 && hasPreparedTurn)

/** 合并手势期间的正文/批注更新；落定后按最终窗口刷新一次，不重放过期的相对页号。 */
internal class PageWindowRefreshQueue {
    private var pending = false

    fun request(relativePosition: Int, turnRunning: Boolean, hasPreparedTurn: Boolean): Int? {
        if (!shouldRefreshPageWindowImmediately(turnRunning, relativePosition, hasPreparedTurn)) {
            pending = true
            return null
        }
        // 提交过程必须同步轮换三页缓冲；积累的更新仍由落定回调统一刷新。
        if (turnRunning) return relativePosition
        val result = if (pending) 0 else relativePosition
        pending = false
        return result
    }

    fun finishTurn(): Int? {
        if (!pending) return null
        pending = false
        return 0
    }
}

/** 只有卷曲几何需要一张包含纸面的完整快照；其余模式复用静态背景层。 */
internal fun PageTurnAnimation.usesEmbeddedPageBackground(): Boolean =
    this == PageTurnAnimation.SIMULATION || this == PageTurnAnimation.MODERN_SIMULATION
