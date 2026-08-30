package com.mozhi.reader.feature.reader

import android.view.Choreographer
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 流式 UI 节拍：SSE 增量在 ViewModel 里先进 StringBuilder，每个显示帧最多发布一次快照。
 * 逐 token 发布会让整个聊天页以网络速率重组；固定 delay 又会与 60/120Hz 屏幕错拍，
 * 因此统一挂到 Choreographer，在布局与滚动补偿前提供同帧数据。
 */
internal fun CoroutineScope.launchStreamingTicker(publish: () -> Unit): Job = launch {
    while (isActive) {
        awaitUiFrame()
        publish()
    }
}

/** 把突发到达的大段 SSE 文本摊到后续帧，避免单帧灌入几十个字符造成高度跳变。 */
internal fun nextStreamingFrame(current: String, target: String): String {
    if (current == target) return target
    if (!target.startsWith(current)) return target
    val pending = target.length - current.length
    val emitted = maxOf(1, pending / 8)
    return target.take(current.length + emitted)
}
private suspend fun awaitUiFrame() = suspendCancellableCoroutine { continuation ->
    val choreographer = Choreographer.getInstance()
    val callback = Choreographer.FrameCallback {
        if (continuation.isActive) continuation.resume(Unit)
    }
    choreographer.postFrameCallback(callback)
    continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
}