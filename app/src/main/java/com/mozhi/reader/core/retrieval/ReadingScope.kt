package com.mozhi.reader.core.retrieval

import com.mozhi.reader.core.database.entity.BookEntity

/**
 * The only spoiler boundary understood by retrieval and memory code.
 *
 * Callers must resolve the UI setting once and pass a scope. Downstream code never branches on a
 * spoiler-protection Boolean, so every path always applies the same range check. Offsets use the
 * app-wide UTF-16 convention and are exclusive.
 */
class ReadingScope private constructor(
    val maxChapterIndex: Int,
    val maxCharOffset: Int
) {
    val isWholeBook: Boolean
        get() = maxChapterIndex == Int.MAX_VALUE && maxCharOffset == Int.MAX_VALUE

    fun allowsChapter(chapterIndex: Int): Boolean = chapterIndex <= maxChapterIndex

    fun allowsPosition(chapterIndex: Int, charOffset: Int): Boolean = when {
        chapterIndex < maxChapterIndex -> true
        chapterIndex > maxChapterIndex -> false
        else -> charOffset >= 0 && charOffset <= maxCharOffset
    }

    /** A chunk without trustworthy offsets is allowed only before the boundary chapter. */
    fun allowsChunk(chapterIndex: Int, startCharOffset: Int, endCharOffset: Int): Boolean = when {
        chapterIndex < maxChapterIndex -> true
        chapterIndex > maxChapterIndex -> false
        isWholeBook -> true
        startCharOffset < 0 || endCharOffset <= startCharOffset -> false
        else -> endCharOffset <= maxCharOffset
    }

    fun clampLastChapter(totalChapters: Int): Int =
        if (isWholeBook) (totalChapters - 1).coerceAtLeast(0)
        else maxChapterIndex.coerceIn(0, (totalChapters - 1).coerceAtLeast(0))

    override fun equals(other: Any?): Boolean =
        other is ReadingScope &&
            maxChapterIndex == other.maxChapterIndex &&
            maxCharOffset == other.maxCharOffset

    override fun hashCode(): Int = 31 * maxChapterIndex + maxCharOffset

    override fun toString(): String = if (isWholeBook) {
        "ReadingScope.WholeBook"
    } else {
        "ReadingScope(maxChapterIndex=$maxChapterIndex, maxCharOffset=$maxCharOffset)"
    }

    companion object {
        val WholeBook: ReadingScope = ReadingScope(Int.MAX_VALUE, Int.MAX_VALUE)

        fun uptoProgress(book: BookEntity): ReadingScope = ReadingScope(
            maxChapterIndex = book.maxReachedChapterIndex.coerceAtLeast(0),
            maxCharOffset = book.maxReachedCharOffset.coerceAtLeast(0)
        )

        fun upto(chapterIndex: Int, charOffset: Int): ReadingScope = ReadingScope(
            maxChapterIndex = chapterIndex.coerceAtLeast(0),
            maxCharOffset = charOffset.coerceAtLeast(0)
        )
    }
}

/** The sole conversion point from the legacy UI switch to a concrete boundary. */
object ReadingScopeResolver {
    fun resolve(spoilerProtectionEnabled: Boolean, book: BookEntity): ReadingScope =
        if (spoilerProtectionEnabled) ReadingScope.uptoProgress(book) else ReadingScope.WholeBook
}
