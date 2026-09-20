package com.mozhi.reader.feature.review

import com.mozhi.reader.core.database.entity.*
import org.junit.Assert.*
import org.junit.Test

internal fun reviewTestBook(id: Long = 1, title: String = "异世流放") = BookEntity(
    id = id, title = title, author = "易人北", coverPath = null, epubPath = "", sourceType = BookSourceType.TXT,
    importedAt = 0, totalChapters = 80, maxReachedChapterIndex = 60, maxReachedCharOffset = 500)

internal fun reviewTestAnnotation(id: Long = 1, bookId: Long = 1, personaId: Long? = null) = AnnotationEntity(
    id = id, bookId = bookId, personaId = personaId, chapterIndex = 60, startCharOffset = 0, endCharOffset = 25,
    selectedText = "他把书翻到有折角的那页，才发现折角是自己三年前留下的。旧物替人记事。",
    note = "忽然想起旧书摊那本《山海》，也有一个不是我折的角。", createdAt = 1790000000000L + id)

internal fun reviewTestNote(id: Long = 1, personaId: Long? = null) = NoteEntity(
    id = id, bookId = 1, personaId = personaId, title = "旧物替人记事", contentMarkdown = "时间留下的痕迹，也会替我们保存记忆。",
    createdAt = 1790000000000L, updatedAt = 1790000000000L + id)

internal fun reviewTestPersona() = PersonaEntity(id = 7, name = "阿翎", personality = "温和，细读文本", isRoleplay = true, createdAt = 0)

class ReadingReviewTest {
    @Test fun cardMotionIsContinuousSymmetricAndRestoresTheCenteredCard() {
        assertEquals(ReviewCardMotion(1f, 1f, 0f, 0f), reviewCardMotion(0f))
        val left = reviewCardMotion(-.5f)
        val right = reviewCardMotion(.5f)
        assertEquals(left.scale, right.scale)
        assertEquals(left.alpha, right.alpha)
        assertEquals(left.rotation, -right.rotation)
        assertTrue(right.alpha > .6f)
        assertEquals(reviewCardMotion(1f), reviewCardMotion(3f))
        assertEquals(reviewCardMotion(0f), reviewCardMotion(Float.NaN))
    }
    @Test fun sourceAndBookFiltersUseIdentityAndKeepDeletedPersonasSeparateFromMine() {
        val entries = reviewEntries(listOf(reviewTestBook(), reviewTestBook(2, "雨落书页")),
            listOf(reviewTestAnnotation(1), reviewTestAnnotation(2, personaId = 7), reviewTestAnnotation(3, 2, 99)),
            listOf(reviewTestNote()), listOf(reviewTestPersona()), true)
        assertEquals(2, filterReview(entries, ReviewFilter(source = ReviewSource.MINE)).size)
        assertEquals(2, filterReview(entries, ReviewFilter(source = ReviewSource.AI)).size)
        assertEquals("已删除角色", filterReview(entries, ReviewFilter(bookIds = setOf(2))).single().author.removePrefix("AI · "))
        assertEquals(entries, filterReview(entries, ReviewFilter(bookIds = setOf(1, 2))))
        assertEquals("highlight:2", filterReview(entries, ReviewFilter(source = ReviewSource.AI, personaId = 7)).single().key)
        assertEquals("note:1", filterReview(entries, ReviewFilter(kind = ReviewKind.NOTE)).single().key)
    }

    @Test fun unreadAiContentNeverEntersSearchCountsExportsOrCoCreation() {
        val entries = reviewEntries(listOf(reviewTestBook()),
            listOf(reviewTestAnnotation(1), reviewTestAnnotation(2, personaId = 7).copy(sourceScopeChapterIndex = 61, sourceScopeCharOffset = 0)),
            listOf(reviewTestNote(1, 7), reviewTestNote(2, 7).copy(sourceScopeChapterIndex = 60, sourceScopeCharOffset = 500),
                reviewTestNote(3, 7).copy(sourceScopeChapterIndex = 60, relatedCharOffset = 10), reviewTestNote(4)),
            listOf(reviewTestPersona()), true)
        assertEquals(setOf("highlight:1", "note:2", "note:4"), entries.map { it.key }.toSet())
        assertEquals(entries.size, reviewSourceSelection(entries, 1).size)
        assertFalse(reviewSourceText(entries).contains("[4]"))
    }

    @Test fun removingBookContentPreservesReviewButDisablesLocation() {
        val entry = reviewEntries(listOf(reviewTestBook().copy(removedAt = 9)), listOf(reviewTestAnnotation()), emptyList(), emptyList(), true).single()
        assertFalse(entry.canLocate)
        assertTrue(reviewMarkdown(listOf(entry)).contains(entry.quote))
    }

    @Test fun searchIncludesQuoteThoughtBookAndAuthorWithStableOrder() {
        val entries = reviewEntries(listOf(reviewTestBook()), listOf(reviewTestAnnotation(1), reviewTestAnnotation(2, personaId = 7)),
            listOf(reviewTestNote()), listOf(reviewTestPersona()), true)
        assertEquals("highlight:2", filterReview(entries, ReviewFilter(query = "阿翎 山海 异世")).single().key)
        assertEquals(entries.reversed(), filterReview(entries, ReviewFilter(oldestFirst = true)))
        assertTrue(filterReview(entries, ReviewFilter(query = "不存在")).isEmpty())
    }

    @Test fun coCreationUsesOnlyChosenBookAndWholeBoundedRecords() {
        val book = reviewTestBook()
        val entries = (1..30).map { ReviewEntry(book, "我的", annotation = reviewTestAnnotation(it.toLong())) } +
            ReviewEntry(reviewTestBook(2), "我的", annotation = reviewTestAnnotation(90, 2))
        assertEquals(20, reviewSourceSelection(entries, 1).size)
        assertTrue(reviewSourceSelection(entries, 1).all { it.book.id == 1L })
        assertTrue(reviewSourceSelection(listOf(entries.first().copy(annotation = reviewTestAnnotation().copy(selectedText = "长".repeat(24001)))), 1).isEmpty())
        val text = reviewSourceText(entries.take(2))
        assertTrue(text.contains("[1]")); assertTrue(text.contains("[2]")); assertTrue(text.contains("我的"))
    }

    @Test fun markdownSeparatesBooksOriginsAndMultilineQuotations() {
        val entry = ReviewEntry(reviewTestBook(), "AI · 阿翎", annotation = reviewTestAnnotation(personaId = 7).copy(selectedText = "第一句\n第二句"))
        val markdown = reviewMarkdown(listOf(entry))
        assertTrue(markdown.contains("> 第一句\n> 第二句"))
        assertTrue(markdown.contains("AI · 阿翎"))
        assertTrue(markdown.contains("第 61 章"))
        assertTrue(markdown.contains(entry.body))
    }
}
