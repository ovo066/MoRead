package com.mozhi.reader.core.library

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads chapter bodies out of the per-book `text.mz` blob.
 *
 * One file per book with byte ranges on the chapter rows means a chapter read is a single seek,
 * the index is already in memory from `LibraryRepository.getChapters`, and deleting a book is one
 * directory removal.
 */
@Singleton
class BookTextStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val decoded = object : LinkedHashMap<CacheKey, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, String>): Boolean =
            size > DECODED_CACHE_SIZE
    }

    fun textFile(bookId: Long): File = File(bookDirectory(bookId), TEXT_FILE_NAME)

    fun hasText(bookId: Long): Boolean = textFile(bookId).isFile

    suspend fun readChapter(bookId: Long, byteOffset: Long, byteLength: Int): String {
        if (byteOffset < 0 || byteLength <= 0) return ""
        val key = CacheKey(bookId, byteOffset, byteLength)
        synchronized(decoded) { decoded[key] }?.let { return it }
        val body = withContext(Dispatchers.IO) {
            val file = textFile(bookId)
            if (!file.isFile) return@withContext ""
            val buffer = ByteArray(byteLength)
            RandomAccessFile(file, "r").use { handle ->
                if (byteOffset + byteLength > handle.length()) return@withContext ""
                handle.seek(byteOffset)
                handle.readFully(buffer)
            }
            String(buffer, Charsets.UTF_8)
        }
        if (body.isNotEmpty()) synchronized(decoded) { decoded[key] = body }
        return body
    }

    suspend fun delete(bookId: Long) {
        withContext(Dispatchers.IO) { bookDirectory(bookId).deleteRecursively() }
        invalidate(bookId)
    }

    /** A rewrite changes byte ranges and content, so every decoded slice for this book is stale. */
    fun invalidate(bookId: Long) {
        synchronized(decoded) { decoded.keys.removeAll { it.bookId == bookId } }
    }

    fun bookDirectory(bookId: Long): File =
        File(File(context.filesDir, BOOKS_DIRECTORY), bookId.toString()).apply { mkdirs() }

    private data class CacheKey(val bookId: Long, val byteOffset: Long, val byteLength: Int)

    private companion object {
        const val BOOKS_DIRECTORY = "book-text"
        const val TEXT_FILE_NAME = "text.mz"
        const val DECODED_CACHE_SIZE = 3
    }
}
