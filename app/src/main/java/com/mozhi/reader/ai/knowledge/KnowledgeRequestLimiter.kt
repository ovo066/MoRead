package com.mozhi.reader.ai.knowledge

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Different chapters are independent; only model requests share a bounded, fair queue. */
@Singleton
class KnowledgeRequestLimiter @Inject constructor() {
    private val permits = Semaphore(2)
    suspend fun <T> request(block: suspend () -> T): T = permits.withPermit { block() }
}
