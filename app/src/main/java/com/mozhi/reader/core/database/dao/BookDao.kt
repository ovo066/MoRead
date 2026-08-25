package com.mozhi.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.database.entity.BookmarkEntity
import com.mozhi.reader.core.database.entity.BookTocEntryEntity
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.database.entity.ReadingDailyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query(
        """
        SELECT * FROM books
        ORDER BY CASE WHEN lastReadAt = 0 THEN importedAt ELSE lastReadAt END DESC
        """
    )
    fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY importedAt ASC")
    suspend fun getBooks(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :bookId")
    fun observeBook(bookId: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBook(bookId: Long): BookEntity?

    @Insert
    suspend fun insertBook(book: BookEntity): Long

    @Update
    suspend fun updateBook(book: BookEntity)

    @Query(
        """
        SELECT * FROM books
        WHERE sourceType = :sourceType
          AND (coverPath IS NULL OR coverPath = '')
        """
    )
    suspend fun getBooksMissingCovers(sourceType: BookSourceType): List<BookEntity>

    @Query(
        """
        SELECT * FROM books
        WHERE sourceType = :sourceType
          AND NOT EXISTS (
              SELECT 1 FROM book_toc_entries WHERE book_toc_entries.bookId = books.id
          )
        """
    )
    suspend fun getBooksMissingToc(sourceType: BookSourceType): List<BookEntity>

    /**
     * 可重新抽取的封面：EPUB 书且用户没手动设过封面。清理封面缓存时只动这些，
     * 用户自选的封面（metadataEdited = 1）删了就找不回来，绝不能碰。
     */
    @Query(
        """
        SELECT * FROM books
        WHERE sourceType = 'EPUB'
          AND metadataEdited = 0
          AND coverPath IS NOT NULL
          AND coverPath != ''
        """
    )
    suspend fun getBooksWithReExtractableCovers(): List<BookEntity>

    @Query("UPDATE books SET coverPath = NULL WHERE id IN (:bookIds)")
    suspend fun clearCoverPaths(bookIds: List<Long>)

    @Query(
        """
        UPDATE books
        SET coverPath = :coverPath
        WHERE id = :bookId
          AND (coverPath IS NULL OR coverPath = '')
        """
    )
    suspend fun updateBookCover(bookId: Long, coverPath: String)

    /**
     * 用户手动编辑元数据。与 [updateBookCover] 不同，这里无条件覆盖并置 metadataEdited，
     * 之后任何自动回填（封面补齐、元数据清洗）都不再动这本书。
     */
    @Query(
        """
        UPDATE books
        SET title = :title,
            author = :author,
            tags = :tags,
            metadataEdited = 1
        WHERE id = :bookId
        """
    )
    suspend fun updateBookMetadata(bookId: Long, title: String, author: String, tags: String)

    @Query("UPDATE books SET coverPath = :coverPath, metadataEdited = 1 WHERE id = :bookId")
    suspend fun replaceBookCover(bookId: Long, coverPath: String?)

    @Query("UPDATE books SET manualReadState = :state WHERE id = :bookId")
    suspend fun updateReadState(bookId: Long, state: String?)

    @Query("UPDATE books SET pinnedAt = :pinnedAt WHERE id = :bookId")
    suspend fun updatePinnedAt(bookId: Long, pinnedAt: Long)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBook(bookId: Long)

    @Query(
        """
        UPDATE books
        SET lastReadLocator = :locatorJson,
            lastReadChapterIndex = :chapterIndex,
            lastReadCharOffset = :charOffset,
            lastReadAt = :readAt
        WHERE id = :bookId
        """
    )
    suspend fun updateProgress(
        bookId: Long,
        locatorJson: String,
        chapterIndex: Int,
        charOffset: Int,
        readAt: Long
    )

    @Query("SELECT * FROM books WHERE textVersion < :version")
    suspend fun getBooksBelowTextVersion(version: Int): List<BookEntity>

    @Query("UPDATE books SET textVersion = :version WHERE id = :bookId")
    suspend fun updateTextVersion(bookId: Long, version: Int)

    @Query(
        """
        UPDATE books
        SET lastReadChapterIndex = :chapterIndex,
            lastReadCharOffset = :charOffset
        WHERE id = :bookId
        """
    )
    suspend fun updateReadPosition(bookId: Long, chapterIndex: Int, charOffset: Int)

    @Query(
        """
        UPDATE chapters
        SET textByteOffset = :byteOffset,
            textByteLength = :byteLength,
            charCount = :charCount
        WHERE bookId = :bookId AND chapterIndex = :chapterIndex
        """
    )
    suspend fun updateChapterTextRange(
        bookId: Long,
        chapterIndex: Int,
        byteOffset: Long,
        byteLength: Int,
        charCount: Int
    )

    @Query(
        """
        UPDATE bookmarks
        SET chapterIndex = :chapterIndex,
            charOffset = :charOffset
        WHERE id = :bookmarkId
        """
    )
    suspend fun updateBookmarkPosition(bookmarkId: Long, chapterIndex: Int, charOffset: Int)

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    suspend fun getBookmarks(bookId: Long): List<BookmarkEntity>

    @Insert
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteChaptersForBook(bookId: Long)

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    fun observeChapters(bookId: Long): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    suspend fun getChapters(bookId: Long): List<ChapterEntity>

    @Insert
    suspend fun insertTocEntries(entries: List<BookTocEntryEntity>)

    @Query("DELETE FROM book_toc_entries WHERE bookId = :bookId")
    suspend fun deleteTocEntriesForBook(bookId: Long)

    @Query("SELECT * FROM book_toc_entries WHERE bookId = :bookId ORDER BY orderIndex")
    fun observeTocEntries(bookId: Long): Flow<List<BookTocEntryEntity>>

    @Query("SELECT * FROM book_toc_entries WHERE bookId = :bookId ORDER BY orderIndex")
    suspend fun getTocEntries(bookId: Long): List<BookTocEntryEntity>

    @Query("SELECT title FROM chapters WHERE bookId = :bookId AND chapterIndex = :chapterIndex")
    suspend fun getChapterTitle(bookId: Long, chapterIndex: Int): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>>

    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    suspend fun deleteBookmark(bookmarkId: Long)

    @Query("SELECT * FROM reading_daily WHERE bookId = :bookId ORDER BY epochDay")
    fun observeReadingDays(bookId: Long): Flow<List<ReadingDailyEntity>>

    @Query("SELECT * FROM reading_daily ORDER BY epochDay")
    fun observeAllReadingDays(): Flow<List<ReadingDailyEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReadingDay(day: ReadingDailyEntity)

    @Query(
        """
        UPDATE reading_daily
        SET durationMs = durationMs + :durationMs,
            lastReadAt = :lastReadAt
        WHERE bookId = :bookId AND epochDay = :epochDay
        """
    )
    suspend fun addReadingDuration(
        bookId: Long,
        epochDay: Long,
        durationMs: Long,
        lastReadAt: Long
    )
}
