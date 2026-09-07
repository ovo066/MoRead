package com.mozhi.reader.core.retrieval

import com.mozhi.reader.core.library.QuoteLocation
import java.util.UUID

/** Opaque, app-issued handles. Bounded to this process; eviction/restart explicitly invalidates them. */
class BookSourceRegistry(private val maxCursors: Int = 64, private val maxSources: Int = 2048) {
    data class Page(
        val bookId: Long,
        val revision: String,
        val scope: ReadingScope,
        val pattern: String,
        val normalize: Boolean,
        val contextChars: Int,
        val skip: Int,
        val coverageSignature: String
    )

    data class Source(
        val bookId: Long,
        val revision: String,
        val scope: ReadingScope,
        val chapterIndex: Int,
        val start: Int,
        val end: Int,
        val quote: String
    )

    private val cursors = LinkedHashMap<String, Page>()
    private val sources = LinkedHashMap<String, Source>()

    @Synchronized fun issueCursor(page: Page): String {
        val key = "g1." + UUID.randomUUID()
        cursors[key] = page
        while (cursors.size > maxCursors.coerceAtLeast(1)) cursors.remove(cursors.keys.first())
        return key
    }

    @Synchronized fun page(cursor: String): Page? = cursors[cursor]

    @Synchronized fun issueSource(source: Source): String {
        val key = "s1." + UUID.randomUUID()
        sources[key] = source
        while (sources.size > maxSources.coerceAtLeast(1)) sources.remove(sources.keys.first())
        return key
    }

    @Synchronized fun source(reference: String): Source? = sources[reference]

    /** Never locate the first matching quote: the app-issued exact range is the identity. */
    fun verify(
        source: Source,
        bookId: Long,
        revision: String,
        allowedScope: ReadingScope,
        originalBody: String,
        quote: String
    ): QuoteLocation? {
        if (source.bookId != bookId || source.revision != revision || source.quote != quote) return null
        if (source.start < 0 || source.end <= source.start || source.end > originalBody.length) return null
        if (!source.scope.allowsChunk(source.chapterIndex, source.start, source.end) ||
            !allowedScope.allowsChapter(source.chapterIndex) ||
            !allowedScope.allowsChunk(source.chapterIndex, source.start, source.end)) return null
        if (originalBody.substring(source.start, source.end) != quote) return null
        return QuoteLocation(source.chapterIndex, source.start, source.end)
    }
}
