package com.mozhi.reader.feature.reader

/** Never expose an unbound/previous persona's history as a ready empty conversation. */
internal fun companionSessionMatches(
    requestedBookId: Long?,
    sessionBookId: Long?,
    activePersonaId: Long?,
    sessionPersonaId: Long?
): Boolean = requestedBookId != null && requestedBookId == sessionBookId && activePersonaId == sessionPersonaId
