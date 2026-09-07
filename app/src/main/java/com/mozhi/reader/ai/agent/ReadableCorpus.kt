package com.mozhi.reader.ai.agent

import com.mozhi.reader.core.retrieval.ReadingScope
import com.mozhi.reader.core.retrieval.RetrievalCandidate
import com.mozhi.reader.core.vector.BookChunk
import com.mozhi.reader.core.vector.ChapterChunk
import com.mozhi.reader.core.vector.ChapterChunker
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One request's canonical corpus. Embedding state is deliberately not an input. */
internal data class ReadableCorpus(
    val candidates: List<RetrievalCandidate>,
    val bodies: Map<Int, String>,
    val indexedText: Map<Pair<Int, Int>, ChapterChunk>,
    val failures: List<Int>,
    val failureCount: Int,
    val resourceLimited: Boolean
) {
    val complete: Boolean get() = failureCount == 0 && !resourceLimited

    /** An old/full embedding can only attach to the same complete, canonical, readable interval. */
    fun vectorCandidate(chunk: BookChunk): RetrievalCandidate? {
        val canonical = indexedText[chunk.chapterIndex to chunk.chunkIndex] ?: return null
        if (chunk.text != canonical.text) return null
        if (chunk.endCharOffset > chunk.startCharOffset &&
            (chunk.startCharOffset != canonical.startCharOffset || chunk.endCharOffset != canonical.endCharOffset)) return null
        val body = bodies[chunk.chapterIndex] ?: return null
        if (canonical.endCharOffset > body.length) return null
        return RetrievalCandidate(
            bookId = chunk.bookId, chapterIndex = chunk.chapterIndex, chunkIndex = chunk.chunkIndex,
            text = body.substring(canonical.startCharOffset, canonical.endCharOffset),
            startCharOffset = canonical.startCharOffset, endCharOffset = canonical.endCharOffset
        )
    }
}

internal const val MAX_LEXICAL_CHARS = 20_000_000
internal const val MAX_LEXICAL_CHAPTERS = 20_000

internal suspend fun loadReadableCorpus(
    bookId: Long,
    totalChapters: Int,
    scope: ReadingScope,
    loadChapter: suspend (Int) -> ChapterDocument?,
    loadChaptersThrough: suspend (Int) -> List<ChapterDocument>
): ReadableCorpus {
    val last = scope.clampLastChapter(totalChapters)
    val bulk = try {
        loadChaptersThrough(last).associateBy { it.chapterIndex }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyMap()
    }
    val bodies = LinkedHashMap<Int, String>()
    val indexedText = HashMap<Pair<Int, Int>, ChapterChunk>()
    val candidates = ArrayList<RetrievalCandidate>()
    val failures = ArrayList<Int>()
    var failureCount = 0
    var chars = 0
    var limited = false
    val indexes = if (totalChapters > 0) 0..last else IntRange.EMPTY
    for (index in indexes) {
        currentCoroutineContext().ensureActive()
        if (index >= MAX_LEXICAL_CHAPTERS) { limited = true; break }
        // A zero-length allowed prefix requires no body and must not create a false missing chapter.
        if (index == scope.maxChapterIndex && scope.maxCharOffset == 0) continue
        val document = bulk[index] ?: try {
            loadChapter(index)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (document == null || document.readError != null || document.chapterIndex != index) {
            failureCount++
            if (failures.size < 200) failures += index
            continue
        }
        val end = scope.readableEnd(index, document.body)
        if (end > MAX_LEXICAL_CHARS - chars) { limited = true; break }
        chars += end
        val readable = document.body.take(end)
        bodies[index] = readable
        // Keep original chunk numbers for RRF identity, but clip before BM25 tokenization/ranking.
        ChapterChunker.chunkWithOffsets(document.body).forEachIndexed { chunkIndex, chunk ->
            currentCoroutineContext().ensureActive()
            if (chunk.startCharOffset >= end) return@forEachIndexed
            val clippedEnd = minOf(end, chunk.endCharOffset)
            if (chunk.endCharOffset <= end) indexedText[index to chunkIndex] = chunk
            candidates += RetrievalCandidate(
                bookId, index, chunkIndex, readable.substring(chunk.startCharOffset, clippedEnd),
                chunk.startCharOffset, clippedEnd
            )
        }
    }
    if (totalChapters <= 0) failureCount++
    return ReadableCorpus(candidates, bodies, indexedText, failures, failureCount, limited)
}
