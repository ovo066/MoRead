package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.ToolSpec
import com.mozhi.reader.ai.companion.LibraryBookScope
import com.mozhi.reader.ai.companion.LibraryBookScopes
import com.mozhi.reader.ai.companion.LibraryCitation
import com.mozhi.reader.ai.companion.LibraryCitationParser
import com.mozhi.reader.ai.companion.LibraryScopeGuard
import com.mozhi.reader.ai.companion.LibraryConversationSources
import com.mozhi.reader.ai.chat.AiChatRepository
import com.mozhi.reader.ai.prompt.LibraryCompanionPrompt
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.database.entity.NoteEntity
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.retrieval.ReadingScope
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LibraryCompanionToolsetTest {
    private val scopes = listOf(LibraryBookScope(1, "书甲", "a".repeat(64), 2, 8), LibraryBookScope(2, "书乙", "b".repeat(64), 0, 2))
    private val template = object : AgentTool {
        override val displayName = "读取"
        override val spec = ToolSpec("read_book_section", "test", buildJsonObject { put("properties", JsonObject(emptyMap())) })
        override suspend fun execute(arguments: JsonObject) = error("wrapper must route explicitly")
    }

    @Test fun selectedBookRoutingCannotBroadenOtherBooksScope() = runTest {
        val read = mutableListOf<LibraryBookScope>()
        var checks = 0
        val tool = ScopedLibraryTool(template, scopes, { checks++ }) { book, _ -> read += book; "原文" }
        assertTrue(tool.execute(buildJsonObject { put("book_id", 99) }).contains("不在"))
        assertTrue(read.isEmpty())
        tool.execute(buildJsonObject { put("book_id", 2) })
        assertEquals(scopes[1], read.single())
        assertEquals(2, checks)
        assertEquals(2, read.single().readingScope.readableEnd(0, "乙书未读剧情"))
        assertEquals(listOf(JsonPrimitive("book_id")), tool.spec.parameters["required"]?.jsonArray)
    }

    @Test fun dynamicToolsDiscoverUnfocusedBooksButNeverExposeDestructiveOrNetworkCapabilities() = runTest {
        val library = mockk<LibraryRepository>()
        val reader = mockk<ReaderToolset>()
        val guard = mockk<LibraryScopeGuard>()
        val chats = mockk<AiChatRepository>()
        val book = BookEntity(id = 1, title = "甲", author = "", coverPath = null, epubPath = "", sourceType = BookSourceType.TXT, importedAt = 1, totalChapters = 10)
        coEvery { library.getBooks() } returns listOf(book)
        coEvery { guard.capture(listOf(2)) } returns listOf(scopes[1])
        coEvery { guard.validate(any<LibraryBookScope>()) } just Runs
        coEvery { chats.updateLibraryContext(any(), any(), any(), any()) } just Runs
        val reads = mutableListOf<Pair<Long, ReadingScope>>()
        every { reader.forBook(any(), any(), any(), any(), any(), any(), any()) } answers {
            val id = firstArg<Long>()
            val boundary = arg<ReadingScope>(4)
            assertFalse(arg<MemoryScope>(5).longTermEnabled)
            assertFalse(arg<Boolean>(6))
            listOf(object : AgentTool {
                override val displayName = "读取"
                override val spec = template.spec
                override suspend fun execute(arguments: JsonObject): String {
                    assertFalse("book_id" in arguments)
                    reads += id to boundary
                    return "原文"
                }
            }, object : AgentTool {
                override val displayName = "不应注册"
                override val spec = ToolSpec("delete_book", "forbidden", JsonObject(emptyMap()))
                override suspend fun execute(arguments: JsonObject): String = error("must never run")
            })
        }
        val sources = LibraryConversationSources(7, "u", emptyList(), guard, chats)
        val tools = LibraryCompanionToolset(library, mockk(), mockk(), guard, reader, mockk(), mockk()).forConversation(sources, null)
        assertEquals(setOf("find_books", "propose_library_organization", "read_book_section"), tools.map { it.spec.name }.toSet())
        val read = tools.single { it.spec.name == "read_book_section" }
        assertNull(read.spec.parameters["properties"]!!.jsonObject["book_id"]!!.jsonObject["enum"])
        read.execute(buildJsonObject { put("book_id", 2); put("from_chapter", 1) })
        assertEquals(listOf(2L to scopes[1].readingScope), reads)
        coVerify(exactly = 1) { guard.capture(listOf(2)) }
    }

    @Test fun wrappedFailuresRemainFailuresInsteadOfSuccessfulSourceReads() = runTest {
        val tool = ScopedLibraryTool(template, scopes, {}) { _, _ -> "缺少 from_chapter" }
        val result = tool.execute(buildJsonObject { put("book_id", 1) })
        assertTrue(result.startsWith("缺少"))
        assertFalse(result.isToolSuccess())
    }

    @Test fun corruptedOrDuplicateScopeMetadataNeverBecomesWholeLibraryAccess() {
        assertEquals(scopes, LibraryBookScopes.decode(LibraryBookScopes.encode(scopes)))
        listOf("", "{}", "[{\"bookId\":1}]").forEach { assertTrue(runCatching { LibraryBookScopes.decode(it) }.isFailure) }
        assertTrue(runCatching { LibraryBookScopes.encode(scopes + scopes[0]) }.isFailure)
        assertTrue(runCatching { LibraryBookScopes.encode(listOf(scopes[0].copy(maxCharOffset = Int.MAX_VALUE))) }.isFailure)
    }

    @Test fun sectionBudgetsAndExplicitRangeAreBounded() {
        val args = boundedLibraryArguments("read_book_section", buildJsonObject { put("from_chapter", 1); put("max_chars", 50_000) }, scopes[0])
        assertEquals(6_000, args["max_chars"]?.jsonPrimitive?.int)
        assertTrue(runCatching { boundedLibraryArguments("read_book_section", buildJsonObject { put("from_chapter", 1); put("to_chapter", Int.MAX_VALUE) }, scopes[0]) }.isFailure)
    }

    @Test fun citationsRequireExactTextInTheRightBookAndOriginalUtf16Range() {
        val body = "😀已经读到这里。后面是未读剧情。"
        val scope = scopes[0].copy(maxChapterIndex = 0, maxCharOffset = 10)
        val parsed = LibraryCitationParser.parse("〔书籍#1 第1章〕「已经读到这里。」 〔书籍#2 第1章〕「后面是未读剧情。」")
        assertEquals(2, parsed.citations.size)
        val hit = LibraryCitationParser.locate(parsed.citations[0], scope, body)!!
        assertEquals(2, hit.start)
        assertEquals("已经读到这里。", body.substring(hit.start, hit.end))
        assertNull(LibraryCitationParser.locate(parsed.citations[1], scope, body))
        assertNull(LibraryCitationParser.locate(LibraryCitation(1, 0, "后面是未读剧情。"), scope, body))
        assertNull(LibraryCitationParser.locate(LibraryCitation(1, 1, "已经读到这里。"), scope, body))
        assertTrue(LibraryCitationParser.parse("普通「已经读到这里。」不是引用").citations.isEmpty())
    }

    @Test fun scopeGuardRejectsRemovedRevisedOrNarrowedSources() = runTest {
        val library = mockk<LibraryRepository>()
        var book = BookEntity(id = 1, title = "甲", author = "", coverPath = null, epubPath = "", sourceType = BookSourceType.TXT,
            importedAt = 1, totalChapters = 10, maxReachedChapterIndex = 3, maxReachedCharOffset = 10)
        var revision = scopes[0].sourceRevision
        coEvery { library.getBook(1) } answers { book }
        coEvery { library.bookTextRevision(1) } answers { revision }
        val guard = LibraryScopeGuard(library)
        guard.validate(scopes[0])
        book = book.copy(maxReachedChapterIndex = 1)
        assertTrue(runCatching { guard.validate(scopes[0]) }.isFailure)
        book = book.copy(maxReachedChapterIndex = 3, removedAt = 9)
        assertTrue(runCatching { guard.validate(scopes[0]) }.isFailure)
        book = book.copy(removedAt = 0)
        revision = "c".repeat(64)
        assertTrue(runCatching { guard.validate(scopes[0]) }.isFailure)
    }

    @Test fun onlyANewTurnMayRefreshToTheActualReadProgress() = runTest {
        val library = mockk<LibraryRepository>()
        val book = BookEntity(id = 1, title = "甲", author = "", coverPath = null, epubPath = "", sourceType = BookSourceType.TXT,
            importedAt = 1, totalChapters = 10, maxReachedChapterIndex = 4, maxReachedCharOffset = 30)
        coEvery { library.getBook(1) } returns book
        coEvery { library.bookTextRevision(1) } returns scopes[0].sourceRevision
        val refreshed = LibraryScopeGuard(library).refresh(listOf(scopes[0])).single()
        assertEquals(ReadingScope.upto(4, 30), refreshed.readingScope)
        assertEquals(ReadingScope.upto(2, 8), scopes[0].readingScope)
        assertEquals(scopes[0].sourceRevision, refreshed.sourceRevision)
    }

    @Test fun notesAndPromptDoNotUseOtherBooksMemoryOrFutureProvenance() {
        val note = NoteEntity(bookId = 1, title = "想法", contentMarkdown = "笔记", createdAt = 0, updatedAt = 0)
        assertTrue(note.withinLibraryScope(ReadingScope.upto(0, 2)))
        assertFalse(note.copy(relatedChapterIndex = 2).withinLibraryScope(ReadingScope.upto(0, 2)))
        assertFalse(note.copy(personaId = 1).withinLibraryScope(ReadingScope.upto(0, 2)))
        assertFalse(note.copy(personaId = 1, sourceScopeChapterIndex = 0, sourceScopeCharOffset = 3).withinLibraryScope(ReadingScope.upto(0, 2)))
        val prompt = LibraryCompanionPrompt.build(null, null, scopes)
        assertTrue(prompt.contains("book_id=1")); assertTrue(prompt.contains("book_id=2"))
        assertTrue(prompt.contains("重点书籍只表示讨论偏好"))
        assertTrue(prompt.contains("propose_library_organization"))
        assertTrue(LibraryCompanionPrompt.build(null, null, emptyList()).contains("用户不需要先选书"))
    }
}
