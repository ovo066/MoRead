package com.mozhi.reader.core.library

import android.content.Context
import androidx.room.withTransaction
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.dao.BookDao
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookReadState
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.database.entity.BookmarkEntity
import com.mozhi.reader.core.database.entity.BookTocEntryEntity
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.database.entity.ReadingDailyEntity
import com.mozhi.reader.core.datastore.ReaderTextReplacementRule
import com.mozhi.reader.core.datastore.compileRegex
import com.mozhi.reader.core.datastore.validationError
import com.mozhi.reader.core.vector.VectorQueries
import dagger.hilt.android.qualifiers.ApplicationContext
import io.objectbox.BoxStore
import java.io.File
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

data class ChapterDraft(
    val index: Int,
    val title: String,
    val href: String,
    val charCount: Int
)

data class BookTocEntryDraft(
    val orderIndex: Int,
    val title: String,
    val href: String,
    val depth: Int,
    val parentOrderIndex: Int?,
    val chapterIndex: Int?,
    val hasChildren: Boolean
)

/** A complete chapter replacement used by reader-side editing and chapter re-recognition. */
data class EditableChapterDraft(
    val index: Int,
    val title: String,
    val href: String,
    val body: String
)

@Singleton
class LibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MoReadDatabase,
    private val bookDao: BookDao,
    private val textStore: BookTextStore,
    private val textWriter: BookTextWriter,
    private val mediaStore: BookMediaStore,
    private val layoutStore: BookLayoutStore,
    private val vectorStore: dagger.Lazy<BoxStore>
) {
    fun observeBooks(): Flow<List<BookEntity>> = bookDao.observeBooks()

    suspend fun getBooks(): List<BookEntity> = bookDao.getBooks()

    fun observeBook(bookId: Long): Flow<BookEntity?> = bookDao.observeBook(bookId)

    fun observeChapters(bookId: Long): Flow<List<ChapterEntity>> =
        bookDao.observeChapters(bookId)

    fun observeTocEntries(bookId: Long): Flow<List<BookTocEntryEntity>> =
        bookDao.observeTocEntries(bookId)

    fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>> =
        bookDao.observeBookmarks(bookId)

    fun observeReadingDays(bookId: Long): Flow<List<ReadingDailyEntity>> =
        bookDao.observeReadingDays(bookId)

    fun observeAllReadingDays(): Flow<List<ReadingDailyEntity>> =
        bookDao.observeAllReadingDays()

    suspend fun getBook(bookId: Long): BookEntity? = bookDao.getBook(bookId)

    suspend fun getEpubBooksMissingCovers(): List<BookEntity> =
        bookDao.getBooksMissingCovers(BookSourceType.EPUB)

    suspend fun getEpubBooksMissingToc(): List<BookEntity> =
        bookDao.getBooksMissingToc(BookSourceType.EPUB)

    suspend fun updateBookCover(bookId: Long, coverPath: String) {
        bookDao.updateBookCover(bookId, coverPath)
    }

    /** 用户在详情页手改书名/作者/标签。 */
    suspend fun updateBookMetadata(
        bookId: Long,
        title: String,
        author: String,
        tags: List<String>
    ) {
        bookDao.updateBookMetadata(
            bookId = bookId,
            title = title.trim(),
            author = author.trim(),
            tags = tags.joinToString(TAG_SEPARATOR) { it.trim() }.trim()
        )
    }

    /**
     * 手动标记阅读状态；传 null 表示交还给按进度自动推导。
     * 标为已读完/搁置只写这一列，不动 lastRead*，回顾旧章不会把标记冲掉。
     */
    suspend fun setReadState(bookId: Long, state: BookReadState?) {
        bookDao.updateReadState(bookId, state?.name)
    }

    suspend fun setPinned(bookId: Long, pinned: Boolean) {
        bookDao.updatePinnedAt(bookId, if (pinned) System.currentTimeMillis() else 0L)
    }

    /**
     * 换封面。旧文件在新文件落盘后才删，中途失败最坏是留一个孤儿文件而不是丢封面；
     * 且只删应用私有目录内的，绝不碰用户相册里的原图。
     */
    suspend fun replaceBookCover(bookId: Long, coverPath: String?) {
        val previous = bookDao.getBook(bookId)?.coverPath
        bookDao.replaceBookCover(bookId, coverPath)
        if (previous != null && previous != coverPath) {
            File(previous).takeIf {
                it.isFile && it.isInsideAppStorage() && !it.isImageLibraryAsset()
            }?.delete()
        }
    }

    /**
     * 清理可重新抽取的 EPUB 封面：删文件并置空 coverPath，这样封面补齐任务下次能重建。
     * 用户手选的封面与 TXT 书的封面不在范围内。
     *
     * @return 释放的字节数
     */
    suspend fun clearReExtractableCovers(): Long {
        val books = bookDao.getBooksWithReExtractableCovers()
        if (books.isEmpty()) return 0L
        var freed = 0L
        val cleared = mutableListOf<Long>()
        books.forEach { book ->
            val file = book.coverPath?.let(::File)
            if (file != null && file.isFile && file.isInsideAppStorage()) {
                val size = file.length()
                if (file.delete()) freed += size
            }
            cleared += book.id
        }
        bookDao.clearCoverPaths(cleared)
        return freed
    }

    suspend fun getChapters(bookId: Long): List<ChapterEntity> = bookDao.getChapters(bookId)

    suspend fun getChapter(bookId: Long, chapterIndex: Int): ChapterEntity? =
        bookDao.getChapter(bookId, chapterIndex)

    suspend fun getTotalCharacterCount(bookId: Long): Long =
        bookDao.getTotalCharacterCount(bookId)

    suspend fun getCharacterCountBefore(bookId: Long, chapterIndex: Int): Long =
        bookDao.getCharacterCountBefore(bookId, chapterIndex)

    suspend fun getTagNames(bookId: Long): List<String> = bookDao.getTagNames(bookId)

    suspend fun getReadingDays(bookId: Long): List<ReadingDailyEntity> =
        bookDao.getReadingDays(bookId)

    suspend fun getTocEntries(bookId: Long): List<BookTocEntryEntity> =
        bookDao.getTocEntries(bookId)

    suspend fun getChapterTitle(bookId: Long, chapterIndex: Int): String? =
        bookDao.getChapterTitle(bookId, chapterIndex)

    suspend fun insertBook(
        book: BookEntity,
        chapters: List<ChapterDraft>,
        tocEntries: List<BookTocEntryDraft> = chapters.toFlatTocEntries()
    ): Long =
        database.withTransaction {
            val bookId = bookDao.insertBook(book)
            bookDao.insertChapters(
                chapters.map { chapter ->
                    ChapterEntity(
                        bookId = bookId,
                        chapterIndex = chapter.index,
                        title = chapter.title,
                        href = chapter.href,
                        charCount = chapter.charCount
                    )
                }
            )
            if (tocEntries.isNotEmpty()) {
                bookDao.insertTocEntries(tocEntries.map { it.toEntity(bookId) })
            }
            bookId
        }

    suspend fun replaceBookToc(bookId: Long, entries: List<BookTocEntryDraft>) {
        database.withTransaction {
            bookDao.deleteTocEntriesForBook(bookId)
            if (entries.isNotEmpty()) {
                bookDao.insertTocEntries(entries.map { it.toEntity(bookId) })
            }
        }
    }

    suspend fun saveProgress(
        bookId: Long,
        locatorJson: String,
        chapterIndex: Int,
        charOffset: Int = 0
    ) {
        bookDao.updateProgress(
            bookId = bookId,
            locatorJson = locatorJson,
            chapterIndex = chapterIndex,
            charOffset = charOffset,
            readAt = System.currentTimeMillis()
        )
    }

    suspend fun booksNeedingText(): List<BookEntity> =
        bookDao.getBooksBelowTextVersion(CURRENT_TEXT_VERSION)

    /**
     * Writes the book's chapter bodies to disk and records the byte ranges.
     *
     * The blob is written before the transaction so a crash leaves `textVersion` untouched and the
     * next run redoes the whole book; a half-written index is never observable.
     */
    /**
     * Writes the text blob and byte ranges. [markReady] flips `textVersion` in the same
     * transaction; legacy migration passes false so the version only flips after the stored
     * positions were converted — readers gate on `textVersion`, so they must never observe the
     * window between the two.
     */
    suspend fun materializeBookText(
        bookId: Long,
        chapters: List<ChapterTextInput>,
        markReady: Boolean = true
    ) {
        val ranges = textWriter.write(textStore.textFile(bookId), chapters)
        textStore.invalidate(bookId)
        database.withTransaction {
            ranges.forEach { range ->
                bookDao.updateChapterTextRange(
                    bookId = bookId,
                    chapterIndex = range.index,
                    byteOffset = range.byteOffset,
                    byteLength = range.byteLength,
                    charCount = range.charCount
                )
            }
            if (markReady) bookDao.updateTextVersion(bookId, CURRENT_TEXT_VERSION)
        }
    }

    /** Replaces exactly one selected range and returns the new cursor position after the edit. */
    suspend fun replaceChapterText(
        bookId: Long,
        chapterIndex: Int,
        range: IntRange,
        replacement: String
    ): Int {
        val chapters = bookDao.getChapters(bookId)
        val current = chapters.firstOrNull { it.chapterIndex == chapterIndex }
            ?: error("章节不存在")
        val body = readChapterText(bookId, current)
        val start = range.first.coerceIn(0, body.length)
        val endExclusive = (range.last + 1).coerceIn(start, body.length)
        val updated = body.replaceRange(start, endExclusive, replacement)
        if (updated == body) return start + replacement.length
        val inputs = chapters.map { chapter ->
            ChapterTextInput(
                index = chapter.chapterIndex,
                body = if (chapter.chapterIndex == chapterIndex) updated else {
                    readChapterText(bookId, chapter)
                }
            )
        }
        materializeBookText(bookId, inputs)
        return (start + replacement.length).coerceIn(0, updated.length)
    }

    /** Applies enabled regex rules in list order. The text is only rewritten when a match exists. */
    suspend fun applyTextReplacementRules(
        bookId: Long,
        rules: List<ReaderTextReplacementRule>
    ): Int {
        val activeRules = rules.filter(ReaderTextReplacementRule::enabled)
        activeRules.forEach { rule ->
            require(rule.validationError() == null) { "规则「${rule.name}」无效" }
        }
        if (activeRules.isEmpty()) return 0
        val compiled = activeRules.map { it to it.compileRegex() }
        val chapters = bookDao.getChapters(bookId)
        var matches = 0
        val inputs = chapters.map { chapter ->
            var body = readChapterText(bookId, chapter)
            compiled.forEach { (rule, regex) ->
                val count = regex.findAll(body).count()
                if (count > 0) {
                    matches += count
                    body = regex.replace(body, rule.replacement)
                }
            }
            ChapterTextInput(index = chapter.chapterIndex, body = body)
        }
        if (matches > 0) materializeBookText(bookId, inputs)
        return matches
    }

    /** Replaces the chapter table and text blob together after TXT chapter recognition changes. */
    suspend fun replaceBookChapters(bookId: Long, chapters: List<EditableChapterDraft>) {
        require(chapters.isNotEmpty()) { "没有可写入的章节" }
        val book = requireNotNull(bookDao.getBook(bookId)) { "书籍不存在" }
        val normalized = chapters.sortedBy(EditableChapterDraft::index).mapIndexed { index, chapter ->
            chapter.copy(index = index)
        }
        val ranges = textWriter.write(
            textStore.textFile(bookId),
            normalized.map { chapter -> ChapterTextInput(chapter.index, chapter.body) }
        )
        textStore.invalidate(bookId)
        database.withTransaction {
            bookDao.deleteChaptersForBook(bookId)
            val rangeByIndex = ranges.associateBy(ChapterTextRange::index)
            bookDao.insertChapters(
                normalized.map { chapter ->
                    val range = requireNotNull(rangeByIndex[chapter.index])
                    ChapterEntity(
                        bookId = bookId,
                        chapterIndex = chapter.index,
                        title = chapter.title,
                        href = chapter.href,
                        charCount = range.charCount,
                        textByteOffset = range.byteOffset,
                        textByteLength = range.byteLength
                    )
                }
            )
            bookDao.deleteTocEntriesForBook(bookId)
            bookDao.insertTocEntries(
                normalized.map { chapter ->
                    BookTocEntryDraft(
                        orderIndex = chapter.index,
                        title = chapter.title,
                        href = chapter.href,
                        depth = 0,
                        parentOrderIndex = null,
                        chapterIndex = chapter.index,
                        hasChildren = false
                    ).toEntity(bookId)
                }
            )
            bookDao.updateBook(
                book.copy(
                    totalChapters = normalized.size,
                    lastReadLocator = null,
                    lastReadChapterIndex = 0,
                    lastReadCharOffset = 0,
                    textVersion = CURRENT_TEXT_VERSION
                )
            )
        }
    }

    suspend fun markTextReady(bookId: Long) {
        bookDao.updateTextVersion(bookId, CURRENT_TEXT_VERSION)
    }

    suspend fun readChapterText(bookId: Long, chapter: ChapterEntity): String =
        textStore.readChapter(bookId, chapter.textByteOffset, chapter.textByteLength)

    suspend fun getBookmarks(bookId: Long): List<BookmarkEntity> = bookDao.getBookmarks(bookId)

    suspend fun updateReadPosition(bookId: Long, chapterIndex: Int, charOffset: Int) {
        bookDao.updateReadPosition(bookId, chapterIndex, charOffset)
    }

    suspend fun updateBookmarkPosition(bookmarkId: Long, chapterIndex: Int, charOffset: Int) {
        bookDao.updateBookmarkPosition(bookmarkId, chapterIndex, charOffset)
    }

    suspend fun addBookmark(bookId: Long, locatorJson: String, label: String): Long =
        bookDao.insertBookmark(
            BookmarkEntity(
                bookId = bookId,
                locatorJson = locatorJson,
                label = label,
                createdAt = System.currentTimeMillis()
            )
        )

    /** New-track bookmark: (chapterIndex, charOffset) is the position, locatorJson stays empty. */
    suspend fun addBookmark(
        bookId: Long,
        chapterIndex: Int,
        charOffset: Int,
        excerpt: String,
        label: String
    ): Long = bookDao.insertBookmark(
        BookmarkEntity(
            bookId = bookId,
            locatorJson = "",
            chapterIndex = chapterIndex,
            charOffset = charOffset,
            excerpt = excerpt,
            label = label,
            createdAt = System.currentTimeMillis()
        )
    )

    suspend fun deleteBookmark(bookmarkId: Long) {
        bookDao.deleteBookmark(bookmarkId)
    }

    suspend fun recordReadingDuration(
        bookId: Long,
        durationMs: Long,
        recordedAt: Long = System.currentTimeMillis()
    ) {
        if (durationMs < MIN_READING_DURATION_MS) return
        val zoneId = ZoneId.systemDefault()
        val segments = buildList {
            var segmentStartAt = (recordedAt - durationMs).coerceAtLeast(0L)
            while (segmentStartAt < recordedAt) {
                val localDate = Instant.ofEpochMilli(segmentStartAt)
                    .atZone(zoneId)
                    .toLocalDate()
                val nextDayAt = localDate
                    .plusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
                val segmentEndAt = minOf(recordedAt, nextDayAt)
                add(
                    ReadingSegment(
                        epochDay = localDate.toEpochDay(),
                        durationMs = segmentEndAt - segmentStartAt,
                        lastReadAt = segmentEndAt
                    )
                )
                segmentStartAt = segmentEndAt
            }
        }
        database.withTransaction {
            segments.forEach { segment ->
                bookDao.insertReadingDay(
                    ReadingDailyEntity(
                        bookId = bookId,
                        epochDay = segment.epochDay,
                        durationMs = 0,
                        lastReadAt = segment.lastReadAt
                    )
                )
                bookDao.addReadingDuration(
                    bookId = bookId,
                    epochDay = segment.epochDay,
                    durationMs = segment.durationMs,
                    lastReadAt = segment.lastReadAt
                )
            }
        }
    }

    suspend fun deleteBook(book: BookEntity) {
        database.withTransaction {
            bookDao.deleteBook(book.id)
        }
        // 向量清理失败不阻塞删书（孤儿切片按 bookId 隔离，检索不到）。
        runCatching { VectorQueries.removeChunksForBook(vectorStore.get(), book.id) }
        textStore.delete(book.id)
        mediaStore.delete(book.id)
        layoutStore.delete(book.id)
        File(context.filesDir, "illustrations/${book.id}").deleteRecursively()
        File(context.cacheDir, "agent-speech/${book.id}").deleteRecursively()
        File(book.epubPath).takeIf { it.isFile && it.isInsideAppStorage() }?.delete()
        book.coverPath
            ?.let(::File)
            ?.takeIf { it.isFile && it.isInsideAppStorage() && !it.isImageLibraryAsset() }
            ?.delete()
    }

    private fun File.isInsideAppStorage(): Boolean {
        val appRoot = context.filesDir.canonicalFile
        return canonicalFile.toPath().startsWith(appRoot.toPath())
    }

    /** 图片库文件可被多本书和阅读背景复用，书籍生命周期不得删除它。 */
    private fun File.isImageLibraryAsset(): Boolean {
        val imageRoot = File(
            context.filesDir,
            com.mozhi.reader.core.datastore.ReaderImageImporter.IMAGE_LIBRARY_DIRECTORY
        ).canonicalFile
        return canonicalFile.parentFile == imageRoot
    }

    companion object {
        const val CURRENT_TEXT_VERSION = 4
        const val TAG_SEPARATOR = ","
        private const val MIN_READING_DURATION_MS = 1_000L
    }

    private data class ReadingSegment(
        val epochDay: Long,
        val durationMs: Long,
        val lastReadAt: Long
    )
}

private fun List<ChapterDraft>.toFlatTocEntries(): List<BookTocEntryDraft> = map { chapter ->
    BookTocEntryDraft(
        orderIndex = chapter.index,
        title = chapter.title,
        href = chapter.href,
        depth = 0,
        parentOrderIndex = null,
        chapterIndex = chapter.index,
        hasChildren = false
    )
}

private fun BookTocEntryDraft.toEntity(bookId: Long): BookTocEntryEntity = BookTocEntryEntity(
    bookId = bookId,
    orderIndex = orderIndex,
    title = title,
    href = href,
    depth = depth,
    parentOrderIndex = parentOrderIndex,
    chapterIndex = chapterIndex,
    hasChildren = hasChildren
)
