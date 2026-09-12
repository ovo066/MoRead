package com.mozhi.reader.core.datastore

/** Preferences only. A running session is never restored after navigation or process death. */
data class AutoReadSettings(
    val mode: PageMode = PageMode.SCROLL,
    val scrollDpPerSecond: Float = 24f,
    val pageIntervalSeconds: Int = 15,
    val showGuide: Boolean = false
) {
    fun normalized() = copy(
        scrollDpPerSecond = if (scrollDpPerSecond.isFinite()) scrollDpPerSecond.coerceIn(8f, 96f) else 24f,
        pageIntervalSeconds = pageIntervalSeconds.coerceIn(3, 120)
    )
}
