package com.mozhi.reader.core.library

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

    /** Retrieval must distinguish missing/invalid text from a legitimate empty chapter. No stale cache. */
    suspend fun readChapterStrict(bookId: Long, byteOffset: Long, byteLength: Int): String =
        withContext(Dispatchers.IO) {
            val file = textFile(bookId)
            if (!file.isFile || byteOffset < 0 || byteLength < 0) {
                throw BookTextException("SOURCE_MISSING", "规范正文尚未就绪或已缺失")
            }
            if (byteLength > MAX_STRICT_CHAPTER_BYTES) {
                throw BookTextException("RESOURCE_LIMIT", "章节超过本地读取上限（8 MiB）")
            }
            currentCoroutineContext().ensureActive()
            val buffer = ByteArray(byteLength)
            RandomAccessFile(file, "r").use { handle ->
                if (byteOffset > handle.length() || byteLength > handle.length() - byteOffset) {
                    throw BookTextException("SOURCE_MISSING", "章节正文不完整")
                }
                handle.seek(byteOffset)
                handle.readFully(buffer)
            }
            currentCoroutineContext().ensureActive()
            try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(buffer)).toString()
            } catch (error: java.nio.charset.CharacterCodingException) {
                throw BookTextException("INVALID_TEXT", "章节 UTF-8 解析失败", error)
            }
        }

    /** Content-derived revision, not embedding progress or a coarse timestamp. Entirely local. */
    suspend fun contentRevision(bookId: Long): String = withContext(Dispatchers.IO) {
        val file = textFile(bookId)
        if (!file.isFile) throw BookTextException("SOURCE_MISSING", "规范正文文件缺失")
        val size = file.length()
        val modified = file.lastModified()
        if (size > MAX_REVISION_BYTES) {
            throw BookTextException("RESOURCE_LIMIT", "正文超过版本校验上限（128 MiB）")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            var readTotal = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                readTotal += count
                if (readTotal > MAX_REVISION_BYTES) throw BookTextException("RESOURCE_LIMIT", "正文超过版本校验上限")
                digest.update(buffer, 0, count)
            }
            if (readTotal != size || file.length() != size || file.lastModified() != modified) {
                throw BookTextException("CONTENT_CHANGED", "正文在读取过程中发生变化，请重新查询")
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
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
        const val MAX_STRICT_CHAPTER_BYTES = 8 * 1024 * 1024
        const val MAX_REVISION_BYTES = 128L * 1024 * 1024
    }
}

class BookTextException(val code: String, message: String, cause: Throwable? = null) : IOException(message, cause)
