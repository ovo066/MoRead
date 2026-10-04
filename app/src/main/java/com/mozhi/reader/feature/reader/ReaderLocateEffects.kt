package com.mozhi.reader.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.mozhi.reader.core.datastore.ChineseConversionMode
import com.mozhi.reader.core.library.ResolvedTextAnchor
import com.mozhi.reader.feature.reader.engine.TransientHighlightSpan
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Location feedback is temporary; saved annotation ink belongs to the independent mark stream. */
@Composable
internal fun ReaderLocateEffects(
    request: ReaderLocateRequest?,
    readerReady: Boolean,
    contentReady: Boolean,
    contentRevision: Int,
    conversionMode: ChineseConversionMode,
    screenState: ReaderScreenState,
    resolve: suspend (ReaderLocateRequest) -> ResolvedTextAnchor?,
    jump: (Int, Int) -> Unit,
    onConsumed: (ReaderLocateRequest) -> Unit,
    highlightIsVisible: (TransientHighlightSpan) -> Boolean
) {
    LaunchedEffect(request, readerReady, contentReady, contentRevision, conversionMode) {
        if (request == null || !readerReady || !contentReady) return@LaunchedEffect
        val range = resolve(request) ?: return@LaunchedEffect
        jump(request.chapterIndex, range.start)
        screenState.locateHighlight = TransientHighlightSpan(request.chapterIndex, range.start, range.end)
        onConsumed(request)
    }

    val highlight = screenState.locateHighlight
    val visible = highlight != null && contentReady && highlightIsVisible(highlight)
    val latestVisible by rememberUpdatedState(visible)
    // Loading a distant chapter can take longer than the feedback duration. Start its timer only
    // once the target is displayed, and never clear saved annotations when this hint disappears.
    LaunchedEffect(highlight) {
        if (highlight == null) return@LaunchedEffect
        snapshotFlow { latestVisible }.first { it }
        delay(LOCATE_HIGHLIGHT_MS)
        screenState.locateHighlight = null
    }
}

internal const val LOCATE_HIGHLIGHT_MS = 2_600L
