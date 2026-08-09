package com.mozhi.reader.feature.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 流式 UI 节拍：SSE 增量在 ViewModel 里先进 StringBuilder，每拍向 UI 发布一次快照。
 * 逐 token 发布会让整个聊天页以网络速率重组（长回复时每帧全文重排），
 * 20fps 的打字机观感与逐 token 无差别，但把重组频率钉死在可预算的常数上。
 */
internal const val STREAM_UI_TICK_MS = 50L

internal fun CoroutineScope.launchStreamingTicker(publish: () -> Unit): Job = launch {
    while (isActive) {
        delay(STREAM_UI_TICK_MS)
        publish()
    }
}
