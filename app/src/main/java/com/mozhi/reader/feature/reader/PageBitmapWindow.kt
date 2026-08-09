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

/** 只有卷曲几何需要一张包含纸面的完整快照；其余模式复用静态背景层。 */
internal fun PageTurnAnimation.usesEmbeddedPageBackground(): Boolean =
    this == PageTurnAnimation.SIMULATION
