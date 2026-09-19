package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.core.library.AnnotationRepository
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.retrieval.ReadingScope
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AnnotationDiscussionServiceTest {
    private val loop = mockk<AgentLoop>()
    private val toolset = mockk<ReaderToolset>()
    private val library = mockk<LibraryRepository>()
    private val annotations = mockk<AnnotationRepository>()
    private val personas = mockk<PersonaRepository>()
    private val settings = mockk<ReaderSettingsRepository>()
    private val masks = mockk<UserMaskStore>()
    private val history = slot<List<ChatMessage>>()
    private val names = slot<Collection<String>>()
    private val scope = slot<ReadingScope>()
    private val memory = slot<MemoryScope>()
    private val book = BookEntity(id = 1, title = "测试书", author = "", coverPath = null,
        epubPath = "", sourceType = BookSourceType.TXT, importedAt = 0, totalChapters = 100,
        lastReadChapterIndex = 2, maxReachedChapterIndex = 98, maxReachedCharOffset = 350)

    private fun prepare(globalMemory: Boolean = true, personaMemory: Boolean = true, protect: Boolean = true): AnnotationDiscussionService {
        val persona = PersonaEntity(id = 7, name = "伴读", personality = "细读文本", isRoleplay = true, memoryEnabled = personaMemory, createdAt = 0)
        val annotation = AnnotationEntity(id = 3, bookId = 1, chapterIndex = 2,
            startCharOffset = 0, endCharOffset = 2, selectedText = "承诺", note = "他怎么又这样了？", createdAt = 0)
        coEvery { personas.getPersona(7) } returns persona
        coEvery { personas.getPersonas() } returns listOf(persona)
        coEvery { library.getBook(1) } returns book
        coEvery { library.getChapters(1) } returns emptyList()
        coEvery { library.getChapterTitle(1, 2) } returns "旧事"
        coEvery { annotations.getAnnotation(3) } returns annotation
        coEvery { annotations.getReplies(3) } returns emptyList()
        coEvery { annotations.addReply(3, 7, any(), any(), any()) } returns 11
        every { settings.companionSpoilerProtectionEnabled } returns flowOf(protect)
        every { settings.companionMemorySettings } returns flowOf(CompanionMemorySettings(
            longTermEnabled = globalMemory, crossBookChatSearchEnabled = false))
        coEvery { masks.activeMask() } returns UserMask(id = 42, name = "读者")
        every { toolset.forBook(bookId = 1, personaId = 7, enabledTools = capture(names),
            readingScope = capture(scope), memoryScope = capture(memory)) } returns emptyList()
        every { loop.runDetached(capture(history), any(), maxRounds = 5) } returns flowOf(AgentEvent.Text("他还记得那份承诺。"))
        return AnnotationDiscussionService(loop, toolset, library, annotations, personas, settings, masks)
    }

    @Test fun plainQuestionGetsRetrievalToolsAndUsesActualReadBoundary() = runTest {
        val events = prepare().respond(1, 3, 7).toList()
        assertTrue(events.last() is AnnotationDiscussionService.Event.Done)
        assertTrue(names.captured.containsAll(setOf("search_book", "grep_book", "read_book_section", "list_chapters", "recall_memory")))
        assertFalse(names.captured.contains("add_annotation"))
        assertEquals(ReadingScope.upto(98, 350), scope.captured)
        assertEquals(MemoryScope(true, false, 42), memory.captured)
        assertTrue(history.captured.first().content.orEmpty().contains("最远读到第 99 章"))
        assertTrue(history.captured.first().content.orEmpty().contains("章未读部分及之后"))
        coVerify(exactly = 1) { annotations.addReply(3, 7, "他还记得那份承诺。", any(), any()) }
    }

    @Test fun globalMemoryOffKeepsBookToolsButDisablesRecall() = runTest {
        prepare(globalMemory = false).respond(1, 3, 7).toList()
        assertFalse(names.captured.contains("recall_memory"))
        assertTrue(names.captured.contains("search_book"))
        assertFalse(memory.captured.longTermEnabled)
    }

    @Test fun personaMemoryOffAlsoDisablesRecall() = runTest {
        prepare(personaMemory = false).respond(1, 3, 7).toList()
        assertFalse(names.captured.contains("recall_memory"))
        assertFalse(memory.captured.longTermEnabled)
    }

    @Test fun spoilerOptOutIsConsistentInPromptAndToolScope() = runTest {
        prepare(protect = false).respond(1, 3, 7).toList()
        assertTrue(scope.captured.isWholeBook)
        assertTrue(history.captured.first().content.orEmpty().contains("已关闭防剧透限制"))
        assertFalse(history.captured.first().content.orEmpty().contains("绝不涉及第"))
    }
}
