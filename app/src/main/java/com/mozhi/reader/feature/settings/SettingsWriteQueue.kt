package com.mozhi.reader.feature.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Main-owned FIFO. Failures remain actionable until that field is retried or discarded. */
internal class SettingsWriteQueue(
    private val scope: CoroutineScope,
    private val onFailure: (Throwable) -> Unit
) {
    private data class Write(val block: suspend () -> Unit, val result: CompletableDeferred<Boolean>)
    private var tail: Job? = null
    private val latest = linkedMapOf<String, Write>()

    fun enqueue(key: String = "default", write: suspend () -> Unit): Deferred<Boolean> {
        val previous = tail
        val request = Write(write, CompletableDeferred())
        latest[key] = request
        tail = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                previous?.join()
                write()
                request.result.complete(true)
            } catch (cancelled: CancellationException) {
                request.result.cancel(cancelled)
                throw cancelled
            } catch (error: Exception) {
                request.result.complete(false)
                onFailure(error)
            }
        }
        return request.result
    }

    suspend fun flush(): Boolean {
        do {
            val awaited = tail
            awaited?.join()
        } while (awaited !== tail)
        return latest.values.all { it.result.await() }
    }

    suspend fun retryFailed() {
        val failures = latest.toList().filter { (_, write) -> !write.result.await() }
        failures.forEach { (key, write) ->
            if (latest[key] === write) enqueue(key, write.block)
        }
    }

    /** Only called after a completed failed barrier and explicit user discard. */
    fun discardFailures() { latest.clear() }
}
