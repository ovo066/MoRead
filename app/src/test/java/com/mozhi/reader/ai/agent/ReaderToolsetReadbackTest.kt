package com.mozhi.reader.ai.agent

import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.database.entity.BookTocEntryEntity
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.database.entity.NoteEntity
import com.mozhi.reader.core.database.entity.ReadingDailyEntity
import com.mozhi.reader.core.library.NoteRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderToolsetReadbackTest {
    @Test
    fun `progress overview hides next chapter with spoiler protection`() {
        val book = book(total = 3, current = 1, offset = 50, lastReadAt = 1_000)
        val text = formatProgressOverview(
            ProgressOverview(
                book = book,
                currentChapter = chapter(1, "当前章", 100),
                previousChapter = chapter(0, "上一章", 100),
                nextChapter = chapter(2, "剧透标题", 100),
                totalCharacters = 300,
                charactersBeforeCurrent = 100,
                tags = listOf("幻想"),
                readingDays = listOf(ReadingDailyEntity(1, 1, 3_600_000, 1_000)),
                noteCount = 2,
                plotSummaries = emptyList(),
                annotationCount = 3,
                currentChapterAnnotationCount = 1,
                bookmarkCount = 1
            ),
            spoilerProtectionEnabled = true,
            nowMillis = 1_000
        )

        assertTrue(text.contains("上一章"))
        assertTrue(text.contains("本章已读 50%"))
        assertTrue(text.contains("全书约 50%"))
        assertFalse(text.contains("剧透标题"))
        assertFalse(text.contains("NaN"))
    }

    @Test
    fun `chapter outline renders volumes and hides unread titles`() {
        val book = book(total = 4, current = 1, offset = 10, lastReadAt = 1)
        val chapters = (0..3).map { chapter(it, "章节${it + 1}", 1200) }
        val toc = listOf(
            toc(0, "第一卷", 0, null, null, true),
            toc(1, "章节1", 1, 0, 0, false),
            toc(2, "章节2", 1, 0, 1, false),
            toc(3, "第二卷剧透", 0, null, null, true),
            toc(4, "章节3剧透", 1, 3, 2, false),
            toc(5, "章节4剧透", 1, 3, 3, false)
        )

        val text = formatChapterOutline(book, chapters, toc, spoilerProtectionEnabled = true)

        assertTrue(text.contains("【第一卷】#1-#2"))
        assertTrue(text.contains("后面还有 2 章尚未读到"))
        assertFalse(text.contains("第二卷剧透"))
        assertFalse(text.contains("章节3剧透"))
    }

    @Test
    fun `chapter outline enforces character cap`() {
        val chapters = (0 until 300).map { chapter(it, "很长的章节标题-${"x".repeat(30)}-$it", 2000) }
        val text = formatChapterOutline(
            book(total = 300, current = 299, offset = 1, lastReadAt = 1),
            chapters,
            emptyList(),
            fromChapter = 1,
            toChapter = 300,
            spoilerProtectionEnabled = false
        )
        assertTrue(text.length <= 6_000)
        assertTrue(text.contains("目录内容未完"))
        val next = Regex("from_chapter=(\\d+)").find(text)?.groupValues?.get(1)?.toInt()
        assertTrue(next != null && next in 2..300)
    }

    @Test
    fun `annotation list filters authors and omits zero replies`() {
        val user = annotation(1, null, "用户划线", "用户想法")
        val ai = annotation(2, 9, "AI 划线", "")
        val text = formatAnnotationList("书", 1, 1, listOf(user, ai), mapOf(2L to 3), "user")
        assertTrue(text.contains("用户划线"))
        assertFalse(text.contains("AI 划线"))
        assertFalse(text.contains("讨论 0 条"))
    }

    @Test
    fun `note index marks handwritten note read only`() {
        val text = formatNoteIndex(listOf(note(id = 7, personaId = null)), nowMillis = 1000)
        assertTrue(text.contains("用户手写（只读，不可改写）"))
    }

    @Test
    fun `note write target updates own latest and rejects user note`() {
        val own = note(id = 8, personaId = 3)
        val user = note(id = 7, personaId = null)
        assertTrue(resolveNoteWriteTarget(null, own, 1, 3, NoteRepository.KIND_NOTE, false) is NoteWriteTarget.Update)
        assertTrue(resolveNoteWriteTarget(user, null, 1, 3, NoteRepository.KIND_NOTE, false) is NoteWriteTarget.Reject)
        assertTrue(resolveNoteWriteTarget(null, own, 1, 3, NoteRepository.KIND_NOTE, true) is NoteWriteTarget.Create)
    }

    private fun book(total: Int, current: Int, offset: Int, lastReadAt: Long) = BookEntity(
        id = 1,
        title = "测试书",
        author = "作者",
        coverPath = null,
        epubPath = "book.epub",
        sourceType = BookSourceType.EPUB,
        importedAt = 1,
        totalChapters = total,
        lastReadChapterIndex = current,
        lastReadCharOffset = offset,
        lastReadAt = lastReadAt
    )

    private fun chapter(index: Int, title: String, chars: Int) = ChapterEntity(
        id = index.toLong() + 1,
        bookId = 1,
        chapterIndex = index,
        title = title,
        href = "",
        charCount = chars
    )

    private fun toc(order: Int, title: String, depth: Int, parent: Int?, chapter: Int?, children: Boolean) =
        BookTocEntryEntity(
            id = order.toLong() + 1,
            bookId = 1,
            orderIndex = order,
            title = title,
            href = "",
            depth = depth,
            parentOrderIndex = parent,
            chapterIndex = chapter,
            hasChildren = children
        )

    private fun annotation(id: Long, personaId: Long?, quote: String, note: String) = AnnotationEntity(
        id = id,
        bookId = 1,
        personaId = personaId,
        chapterIndex = 0,
        startCharOffset = 0,
        endCharOffset = quote.length,
        selectedText = quote,
        note = note,
        createdAt = id
    )

    private fun note(id: Long, personaId: Long?) = NoteEntity(
        id = id,
        bookId = 1,
        personaId = personaId,
        title = "标题",
        contentMarkdown = "# 摘要\n正文",
        kind = NoteRepository.KIND_NOTE,
        createdAt = 1,
        updatedAt = 1
    )
}
