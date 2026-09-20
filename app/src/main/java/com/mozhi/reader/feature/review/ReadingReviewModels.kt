package com.mozhi.reader.feature.review

import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.NoteEntity
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.retrieval.AnnotationVisibility
import com.mozhi.reader.core.retrieval.ReadingScopeResolver

internal enum class ReviewSource(val label: String) { ALL("全部"), MINE("我的"), AI("AI 伴读") }
internal enum class ReviewKind(val label: String) { ALL("全部内容"), HIGHLIGHT("划线与批注"), NOTE("读书笔记") }

internal data class ReviewFilter(
    val query: String = "",
    val bookIds: Set<Long> = emptySet(),
    val source: ReviewSource = ReviewSource.ALL,
    val personaId: Long? = null,
    val kind: ReviewKind = ReviewKind.ALL,
    val oldestFirst: Boolean = false
)

internal data class ReviewEntry(
    val book: BookEntity,
    val author: String,
    val annotation: AnnotationEntity? = null,
    val note: NoteEntity? = null,
    val authorAvatarPath: String? = null
) {
    val key: String get() = annotation?.let { "highlight:${it.id}" } ?: "note:${requireNotNull(note).id}"
    val personaId: Long? get() = annotation?.personaId ?: note?.personaId
    val title: String get() = note?.title.orEmpty()
    val quote: String get() = annotation?.selectedText.orEmpty()
    val body: String get() = annotation?.note ?: note?.contentMarkdown.orEmpty()
    val timestamp: Long get() = annotation?.createdAt ?: requireNotNull(note).updatedAt
    val chapter: Int? get() = annotation?.chapterIndex ?: note?.relatedChapterIndex
    val offset: Int get() = annotation?.startCharOffset ?: note?.relatedCharOffset ?: 0
    val canLocate: Boolean get() = book.removedAt == 0L && chapter != null
    val kindLabel: String get() = if (annotation != null) "划线" else "笔记"
    val locationLabel: String get() = chapter?.let { "第 ${it + 1} 章" } ?: "全书笔记"
}

/** One visibility gate feeds the grid, search, counts, exports and AI source selection. */
internal fun reviewEntries(
    books: List<BookEntity>, annotations: List<AnnotationEntity>, notes: List<NoteEntity>,
    personas: List<PersonaEntity>, spoilerProtection: Boolean
): List<ReviewEntry> {
    val bookMap = books.associateBy { it.id }
    val names = personas.associate { it.id to it.name }
    val avatars = personas.associate { it.id to it.avatarPath }
    fun author(id: Long?) = id?.let { "AI · ${names[it] ?: "已删除角色"}" } ?: "我的"
    val highlights = annotations.mapNotNull { annotation ->
        val book = bookMap[annotation.bookId] ?: return@mapNotNull null
        val scope = ReadingScopeResolver.resolve(spoilerProtection, book)
        if (!AnnotationVisibility.isVisible(annotation, scope)) return@mapNotNull null
        ReviewEntry(book, author(annotation.personaId), annotation = annotation, authorAvatarPath = avatars[annotation.personaId])
    }
    val writings = notes.mapNotNull { note ->
        val book = bookMap[note.bookId] ?: return@mapNotNull null
        val scope = ReadingScopeResolver.resolve(spoilerProtection, book)
        if (note.personaId != null && !scope.isWholeBook) {
            val chapter = note.sourceScopeChapterIndex ?: note.relatedChapterIndex
            val offset = note.sourceScopeCharOffset ?: note.relatedCharOffset
            // A half-written provenance pair must not borrow an unrelated legacy position.
            val partial = (note.sourceScopeChapterIndex == null) != (note.sourceScopeCharOffset == null)
            if (partial || chapter == null || offset == null || chapter < 0 || offset < 0 ||
                !scope.allowsPosition(chapter, offset)) return@mapNotNull null
        }
        ReviewEntry(book, author(note.personaId), note = note, authorAvatarPath = avatars[note.personaId])
    }
    return (highlights + writings).sortedWith(compareByDescending<ReviewEntry> { it.timestamp }.thenBy { it.key })
}

internal fun filterReview(entries: List<ReviewEntry>, filter: ReviewFilter): List<ReviewEntry> {
    val words = filter.query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    val filtered = entries.filter { entry ->
        (filter.bookIds.isEmpty() || entry.book.id in filter.bookIds) &&
            (when (filter.source) {
                ReviewSource.ALL -> true
                ReviewSource.MINE -> entry.personaId == null
                ReviewSource.AI -> entry.personaId != null &&
                    (filter.personaId == null || entry.personaId == filter.personaId)
            }) && (when (filter.kind) {
                ReviewKind.ALL -> true
                ReviewKind.HIGHLIGHT -> entry.annotation != null
                ReviewKind.NOTE -> entry.note != null
            }) && words.all { word ->
                listOf(entry.book.title, entry.book.author, entry.author, entry.title, entry.quote, entry.body)
                    .any { it.contains(word, ignoreCase = true) }
            }
    }
    return if (filter.oldestFirst) filtered.reversed() else filtered
}

internal fun reviewMarkdown(entries: List<ReviewEntry>): String = buildString {
    append("# 划线与笔记\n\n")
    entries.groupBy { it.book.id }.values.forEach { group ->
        append("## ").append(group.first().book.title).append("\n\n")
        group.forEach { entry ->
            if (entry.title.isNotBlank()) append("### ").append(entry.title).append("\n\n")
            if (entry.quote.isNotBlank()) append("> ").append(entry.quote.replace("\n", "\n> ")).append("\n\n")
            if (entry.body.isNotBlank()) append(entry.body).append("\n\n")
            append("— ").append(entry.author).append(" · ").append(entry.locationLabel).append("\n\n")
        }
    }
}

internal const val MAX_REVIEW_SOURCES = 20
internal const val MAX_REVIEW_SOURCE_CHARS = 24000

/** Compact previews keep the words and paragraph breaks without exposing Markdown delimiters. */
internal fun reviewPlainText(markdown: String): String = markdown
    .replace(Regex("(?m)^#{1,6}\\s+"), "")
    .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
    .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
    .replace("**", "").replace("__", "").replace("~~", "").replace("`", "")

/** Whole records only. The preview and model always receive this same bounded snapshot. */
internal fun reviewSourceSelection(entries: List<ReviewEntry>, bookId: Long): List<ReviewEntry> {
    var remaining = MAX_REVIEW_SOURCE_CHARS
    return entries.filter { it.book.id == bookId }.take(MAX_REVIEW_SOURCES).takeWhile {
        remaining -= it.title.length + it.quote.length + it.body.length + it.author.length + 100
        remaining >= 0
    }
}

internal fun reviewSourceText(entries: List<ReviewEntry>): String = entries.mapIndexed { index, entry ->
    buildString {
        append("[").append(index + 1).append("] ").append(entry.locationLabel).append(" · ").append(entry.author)
        if (entry.title.isNotBlank()) append("\n标题：").append(entry.title)
        if (entry.quote.isNotBlank()) append("\n原文摘录：").append(entry.quote)
        if (entry.body.isNotBlank()) append("\n笔记 / 想法：").append(entry.body)
    }
}.joinToString("\n\n")
