package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.datastore.ChineseConversionMode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Offsets belong to both a conversion mode and a particular version of the source window. */
data class ReaderPresentationSnapshot(val mode: ChineseConversionMode, val sourceGeneration: Int)

/** Bounded retries apply to stale work only; cancellation and real failures keep their meaning. */
internal class ReaderPresentationResolver(private val capture: () -> ReaderPresentationSnapshot) {
    fun isCurrent(snapshot: ReaderPresentationSnapshot): Boolean = snapshot == capture()

    suspend fun <T> resolve(compute: suspend (ReaderPresentationSnapshot) -> T?): T? {
        repeat(2) {
            currentCoroutineContext().ensureActive()
            val snapshot = capture()
            val result = compute(snapshot)
            currentCoroutineContext().ensureActive()
            if (isCurrent(snapshot)) return result
        }
        return null
    }
}
