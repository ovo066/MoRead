package com.mozhi.reader.feature.reader

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** No catch-up: the next interval starts only after a committed turn and ready source. */
internal suspend fun runAutoReadPaging(
    session: AutoReadSession,
    isReady: () -> Boolean,
    hasNext: () -> Boolean,
    nextIsReady: () -> Boolean,
    turn: suspend () -> ReaderTurnResult
) {
    val token = session.generation
    suspend fun awaitReady(predicate: () -> Boolean): Boolean {
        val ready = withTimeoutOrNull(30_000) {
            while (session.owns(token) && !predicate()) delay(100)
            session.owns(token)
        } ?: false
        if (!ready && session.owns(token)) session.pause(AutoReadPauseReason.ERROR)
        return ready
    }
    while (session.owns(token)) {
        if (!awaitReady(isReady)) break
        delay(session.settings.pageIntervalSeconds * 1_000L)
        if (!session.owns(token)) break
        if (!isReady()) continue
        if (!hasNext()) {
            session.pause(AutoReadPauseReason.END)
            break
        }
        if (!awaitReady(nextIsReady)) break
        if (turn() == ReaderTurnResult.CANCELLED) session.pause(AutoReadPauseReason.NAVIGATION)
    }
}
