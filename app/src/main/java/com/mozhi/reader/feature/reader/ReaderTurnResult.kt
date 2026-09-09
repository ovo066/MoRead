package com.mozhi.reader.feature.reader

/** RETRYABLE means layout changed; CANCELLED means a newer navigation/source owns the position. */
enum class ReaderTurnResult { COMMITTED, RETRYABLE, CANCELLED }

internal fun readerFollowTurnResult(
    committed: Boolean,
    expectedNavigation: Int?,
    actualNavigation: Int,
    expectedSource: Int?,
    actualSource: Int
): ReaderTurnResult = when {
    expectedNavigation != null && expectedNavigation != actualNavigation -> ReaderTurnResult.CANCELLED
    expectedSource != null && expectedSource != actualSource -> ReaderTurnResult.CANCELLED
    committed -> ReaderTurnResult.COMMITTED
    else -> ReaderTurnResult.RETRYABLE
}
