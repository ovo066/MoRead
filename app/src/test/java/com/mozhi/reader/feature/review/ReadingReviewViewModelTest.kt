package com.mozhi.reader.feature.review

import com.mozhi.reader.ai.client.*
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.library.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ReadingReviewViewModelTest {
    private val library = mockk<LibraryRepository>()
    private val annotations = mockk<AnnotationRepository>()
    private val notes = mockk<NoteRepository>()
    private val personas = mockk<PersonaRepository>()
    private val settings = mockk<ReaderSettingsRepository>()
    private val clients = mockk<AiClientFactory>()
    private val exporter = mockk<ReviewExporter>()
    private val client = mockk<ChatApiClient>()
    private val book = reviewTestBook()
    private val annotation = reviewTestAnnotation()
    private val selected = listOf(ReviewEntry(book, "我的", annotation = annotation))
    private val protect = MutableStateFlow(true)
    private val history = slot<List<ChatMessage>>()

    @Before fun prepare() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { library.observeBooksIncludingRemoved() } returns flowOf(listOf(book))
        every { annotations.observeAll() } returns flowOf(listOf(annotation))
        every { notes.observeAll() } returns flowOf(emptyList())
        every { personas.observePersonas() } returns flowOf(listOf(reviewTestPersona()))
        every { settings.companionSpoilerProtectionEnabled } returns protect
        every { settings.cachedSettings } returns MutableStateFlow(com.mozhi.reader.core.datastore.ReaderSettings())
        every { settings.settings } returns flowOf(com.mozhi.reader.core.datastore.ReaderSettings())
        coEvery { library.getBook(1) } returns book
        coEvery { annotations.getAnnotation(1) } returns annotation
        coEvery { personas.getPersona(7) } returns reviewTestPersona()
        coEvery { clients.forRole(ModelRole.CHAT) } returns ResolvedChatClient(client, ChatOptions.Default, mockk(relaxed = true), "test")
        every { client.chatStream(capture(history), any(), any()) } returns flowOf(ChatDelta.Text("我的新视角 [1]"))
        coEvery { notes.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 42
    }
    @After fun clean() { Dispatchers.resetMain() }
    private fun model() = ReadingReviewViewModel(library, annotations, notes, personas, settings, clients, exporter)

    @Test fun openingNeverCallsAiAndGenerationNeverAutosaves() = runTest {
        val vm = model()
        coVerify(exactly = 0) { clients.forRole(any()) }
        vm.generate(selected, 7, "谈谈记忆", false)
        advanceUntilIdle()
        assertEquals("我的新视角 [1]", vm.draft.value?.content)
        assertTrue(history.captured.last().content.contains(annotation.selectedText))
        coVerify(exactly = 0) { notes.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        vm.updateDraft("我改过的标题", "我修改的内容 [1]")
        vm.saveDraft()
        advanceUntilIdle()
        coVerify(exactly = 1) { notes.create(1, 7, "我改过的标题", match { it.startsWith("我修改的内容") && it.contains("素材出处") },
            any(), any(), any(), any(), 60, 500) }
        assertNull(vm.draft.value)
    }

    @Test fun changingSourceWhileGeneratingPreventsSave() = runTest {
        val vm = model()
        vm.generate(selected, 7, "", false)
        advanceUntilIdle()
        coEvery { annotations.getAnnotation(1) } returns annotation.copy(note = "用户的新想法")
        vm.saveDraft()
        advanceUntilIdle()
        assertTrue(vm.draft.value?.error.orEmpty().contains("修改或删除"))
        coVerify(exactly = 0) { notes.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun tighteningSpoilerProtectionPreventsSavingBroaderDraft() = runTest {
        protect.value = false
        val vm = model()
        vm.generate(selected, 7, "", false)
        advanceUntilIdle()
        protect.value = true
        vm.saveDraft()
        advanceUntilIdle()
        assertTrue(vm.draft.value?.error.orEmpty().contains("可读范围"))
        coVerify(exactly = 0) { notes.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun stoppingKeepsPartialDraftWithoutSavingAndDiscardClearsIt() = runTest {
        every { client.chatStream(any(), any(), any()) } returns flow { emit(ChatDelta.Text("尚未完成")); awaitCancellation() }
        val vm = model()
        vm.generate(selected, 7, "", false)
        assertTrue(vm.draft.value?.running == true)
        vm.stopGeneration()
        advanceUntilIdle()
        assertEquals("尚未完成", vm.draft.value?.content)
        assertFalse(requireNotNull(vm.draft.value).running)
        vm.discardDraft()
        assertNull(vm.draft.value)
        coVerify(exactly = 0) { notes.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}
