package com.mozhi.reader.feature.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Context builders may synchronously query ObjectBox/files; never run them on the UI owner. */
internal suspend fun <T> prepareCompanionContext(block: suspend () -> T): T =
    withContext(Dispatchers.IO) { block() }
