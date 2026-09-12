package com.mozhi.reader.ai.companion

import com.mozhi.reader.core.retrieval.ReadingScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A book's source identity and spoiler boundary, frozen within each user turn. */
@Serializable
data class LibraryBookScope(
    val bookId: Long,
    val title: String,
    val sourceRevision: String,
    val maxChapterIndex: Int,
    val maxCharOffset: Int
) {
    val readingScope: ReadingScope get() = ReadingScope.upto(maxChapterIndex, maxCharOffset)
    val label: String get() = if (maxChapterIndex == 0 && maxCharOffset == 0) "尚未阅读" else "第 ${maxChapterIndex + 1} 章 · 已读 $maxCharOffset 字"
}

object LibraryBookScopes {
    const val MAX_FOCUS_BOOKS = 4
    const val MAX_READ_BOOKS = 4
    private const val MAX_TURN_BOOKS = MAX_FOCUS_BOOKS + MAX_READ_BOOKS
    const val MAX_BOOKS = 32
    const val CONVERSATION_TYPE = "LIBRARY_COMPANION"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(scopes: List<LibraryBookScope>): String {
        validate(scopes)
        return json.encodeToString(scopes)
    }

    /** Malformed metadata fails closed; it must never turn into an unrestricted book query. */
    fun decode(raw: String): List<LibraryBookScope> {
        require(raw.length <= 48_000) { "会话书籍范围数据过大" }
        return json.decodeFromString<List<LibraryBookScope>>(raw).also(::validate)
    }

    fun encodeTurnBooks(ids: List<Long>): String {
        require(ids.size <= MAX_TURN_BOOKS && ids.distinct().size == ids.size && ids.all { it > 0 })
        return json.encodeToString(ids)
    }

    fun decodeTurnBooks(raw: String): List<Long> {
        require(raw.length <= 512)
        return json.decodeFromString<List<Long>>(raw).also { encodeTurnBooks(it) }
    }

    /** Later turns must not make an earlier branch depend on books it never consulted. */
    fun retainedForHistory(raw: String, turnBooks: List<String?>): List<LibraryBookScope> {
        val scopes = decode(raw)
        // Legacy unknown metadata is kept conservatively; it is not evidence of an empty scope.
        if (turnBooks.any { it == null }) return scopes
        val retained = turnBooks.flatMap { decodeTurnBooks(requireNotNull(it)) }.toSet()
        return scopes.filter { it.bookId in retained }
    }

    private fun validate(scopes: List<LibraryBookScope>) {
        require(scopes.size <= MAX_BOOKS && scopes.distinctBy { it.bookId }.size == scopes.size) { "会话书籍范围无效" }
        require(scopes.all {
            it.bookId > 0 && it.title.length <= 500 && it.sourceRevision.matches(Regex("[a-f0-9]{64}")) &&
                it.maxChapterIndex in 0 until Int.MAX_VALUE && it.maxCharOffset in 0 until Int.MAX_VALUE
        }) { "会话书籍来源或阅读范围无效" }
    }
}
