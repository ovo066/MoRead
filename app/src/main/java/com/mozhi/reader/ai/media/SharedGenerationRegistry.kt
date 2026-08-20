package com.mozhi.reader.ai.media

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * 让同一 key 的昂贵生成任务只跑一份，并把任务寿命挂到调用方之外的 scope。
 *
 * 某个界面或播放等待者取消时，只取消自己的 await；真正的生成继续完成，后续等待者
 * 直接接上同一份结果，避免切页、开弹层或 seek 后重新向供应商发起相同请求。
 */
internal class SharedGenerationRegistry<K : Any, V : Any>(
    private val scope: CoroutineScope
) {
    private val inFlight = ConcurrentHashMap<K, Deferred<V>>()

    suspend fun await(key: K, producer: suspend () -> V): V = acquire(key, producer).await()

    internal fun activeCount(): Int = inFlight.size

    private fun acquire(key: K, producer: suspend () -> V): Deferred<V> {
        while (true) {
            inFlight[key]?.let { return it }

            val created = scope.async(start = CoroutineStart.LAZY) { producer() }
            val existing = inFlight.putIfAbsent(key, created)
            if (existing != null) {
                created.cancel()
                return existing
            }

            created.invokeOnCompletion { inFlight.remove(key, created) }
            created.start()
            return created
        }
    }
}
