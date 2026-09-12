package com.mozhi.reader.core.library

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Background repair and removal must not recreate each other's files. Fixed stripes stay bounded. */
internal object BookContentMutation {
    private val locks = Array(64) { Mutex() }
    suspend fun <T> withBook(bookId: Long, block: suspend () -> T): T =
        locks[(bookId and 63L).toInt()].withLock { block() }
}
