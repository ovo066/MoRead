package com.mozhi.reader.feature.reader

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import com.mozhi.reader.core.database.entity.AnnotationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One source-coordinate request from book details, notes or a companion citation. */
data class ReaderLocateRequest(
    val chapterIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val sourceAnchorJson: String = ""
)

internal fun AnnotationEntity.readerLocateRequest() = ReaderLocateRequest(
    chapterIndex, startCharOffset, endCharOffset, textAnchorJson
)

private const val REQUEST_KEY = "reader-locate-request"
private const val CHAPTER_KEY = "locate-chapter"
private const val START_KEY = "locate-start"
private const val END_KEY = "locate-end"
private const val ANCHOR_KEY = "locate-anchor"

/** Publish all coordinates together; separate collectors can otherwise observe mixed requests. */
internal fun SavedStateHandle.requestReaderLocate(request: ReaderLocateRequest) {
    this[REQUEST_KEY] = Bundle().apply {
        putInt(CHAPTER_KEY, request.chapterIndex)
        putInt(START_KEY, request.startCharOffset)
        putInt(END_KEY, request.endCharOffset)
        putString(ANCHOR_KEY, request.sourceAnchorJson)
    }
}

internal fun SavedStateHandle.readerLocateRequests(): Flow<ReaderLocateRequest?> {
    // Preserve a pending request restored from a version that stored four separate values.
    if (!contains(REQUEST_KEY)) get<Int>(CHAPTER_KEY)?.let { chapter ->
        requestReaderLocate(ReaderLocateRequest(chapter, get<Int>(START_KEY) ?: 0,
            get<Int>(END_KEY) ?: 0, get<String>(ANCHOR_KEY).orEmpty()))
    }
    return getStateFlow<Bundle?>(REQUEST_KEY, null).map { it?.toLocateRequest() }
}

internal fun SavedStateHandle.consumeReaderLocate(request: ReaderLocateRequest) {
    // Completing an older suspended resolution must not erase a newer click.
    if (get<Bundle>(REQUEST_KEY)?.toLocateRequest() != request) return
    this[REQUEST_KEY] = null
    listOf(CHAPTER_KEY, START_KEY, END_KEY, ANCHOR_KEY).forEach { remove<Any>(it) }
}

private fun Bundle.toLocateRequest() = ReaderLocateRequest(
    getInt(CHAPTER_KEY), getInt(START_KEY), getInt(END_KEY), getString(ANCHOR_KEY).orEmpty()
)
