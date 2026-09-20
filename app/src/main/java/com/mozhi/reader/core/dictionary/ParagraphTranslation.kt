package com.mozhi.reader.core.dictionary

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class EnglishParagraph(val start: Int, val end: Int, val text: String) {
    val key: String get() = paragraphKey(text)
}

fun englishParagraphs(body: String): List<EnglishParagraph> = Regex("[^\\r\\n]+").findAll(body)
    .filter { EnglishWords.pattern.containsMatchIn(it.value) }
    .map { EnglishParagraph(it.range.first, it.range.last + 1, it.value) }.toList()

fun paragraphKey(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

@Serializable
data class ParagraphTranslation(val start: Int, val end: Int, val sourceKey: String, val chinese: String, val hidden: Boolean = false) {
    fun matches(body: String): Boolean = start >= 0 && end in (start + 1)..body.length &&
        sourceKey == paragraphKey(body.substring(start, end))
}

/** Cached separately from book text; an edited paragraph never receives an old translation. */
@Singleton
class ParagraphTranslationRepository @Inject constructor(@ApplicationContext context: Context) {
    private val root = File(context.filesDir, "reader-custom/translations")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(bookId: Long, chapter: Int, body: String): List<ParagraphTranslation> = withContext(Dispatchers.IO) {
        mutex.withLock { read(file(bookId, chapter)).filter { it.matches(body) } }
    }

    suspend fun save(bookId: Long, chapter: Int, body: String, translation: ParagraphTranslation): List<ParagraphTranslation> = withContext(Dispatchers.IO) {
        require(translation.matches(body)) { "原文已变化，请重新翻译" }
        mutex.withLock {
            val destination = file(bookId, chapter)
            val records = read(destination).filter { it.matches(body) && it.start != translation.start } + translation
            write(destination, records)
            records
        }
    }

    suspend fun delete(bookId: Long, chapter: Int, body: String, translation: ParagraphTranslation): List<ParagraphTranslation> = withContext(Dispatchers.IO) {
        require(translation.matches(body)) { "原文已变化，请重新打开译文" }
        mutex.withLock {
            val destination = file(bookId, chapter)
            val records = read(destination).filter { it.matches(body) &&
                !(it.start == translation.start && it.sourceKey == translation.sourceKey) }
            write(destination, records)
            records
        }
    }

    private fun write(destination: File, records: List<ParagraphTranslation>) {
        destination.parentFile!!.mkdirs()
        val temporary = File.createTempFile("translation-", ".tmp", destination.parentFile)
        try {
            temporary.outputStream().use { stream ->
                stream.write(json.encodeToString(records).toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            // Same-directory atomic replacement, as used by BookLayoutStore. A failed replacement
            // must throw, rather than letting the UI publish a deletion/translation that was not saved.
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private fun file(bookId: Long, chapter: Int): File {
        require(bookId > 0 && chapter >= 0)
        return File(root, "$bookId/$chapter.json")
    }
    private fun read(file: File): List<ParagraphTranslation> {
        if (!file.exists()) return emptyList()
        require(file.length() <= 16 * 1024 * 1024) { "章节译文缓存过大" }
        return runCatching { json.decodeFromString<List<ParagraphTranslation>>(file.readText()) }.getOrDefault(emptyList())
    }
}
