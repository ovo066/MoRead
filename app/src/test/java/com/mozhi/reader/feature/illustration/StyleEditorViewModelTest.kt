package com.mozhi.reader.feature.illustration

import androidx.lifecycle.ViewModelStore
import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.media.*
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.dao.ImageConsistencyDao
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.core.library.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class StyleEditorViewModelTest {
    private val repository = mockk<ImageConsistencyRepository>()
    private val library = mockk<LibraryRepository>()
    private val dao = mockk<ImageConsistencyDao>()
    private val illustrations = mockk<IllustrationRepository>()
    private val clients = mockk<AiClientFactory>()
    private val database = mockk<MoReadDatabase>()
    private val queue = mockk<IllustrationQueue>()
    private val settings = mockk<ReaderSettingsRepository>()
    private val store = ViewModelStore()
    private val original = StyleSpec(natural = "existing watercolor", referenceIds = listOf("old-reference"), seed = 42)
    private val templates = MutableStateFlow(emptyList<ImageStyleTemplateEntity>())
    private val book = BookEntity(7, "Book", "", null, "", BookSourceType.TXT, 1, totalChapters = 1, maxReachedCharOffset = 100)
    private lateinit var model: ImageStudioViewModel

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { repository.bookContext(7, null) } returns ImageBookContext(book, emptyList(), emptyList(), original, 0)
        every { repository.observeStyle(7) } returns flowOf(original)
        every { repository.observeLooks(7) } returns flowOf(emptyList())
        every { repository.dao } returns dao
        every { dao.observeTemplates() } returns templates
        every { dao.observeQueue(7) } returns flowOf(emptyList())
        every { illustrations.observeForBook(7) } returns flowOf(emptyList())
        every { settings.settings } returns flowOf(ReaderSettings())
        every { queue.running } returns MutableStateFlow(emptySet())
        coEvery { clients.imageGeneration() } throws IllegalStateException("unconfigured")
        coEvery { database.personaDao().getPersonas() } returns emptyList()
        coEvery { repository.saveStyle(any(), any()) } just Runs
        coEvery { repository.saveTemplate(any(), any(), any()) } answers {
            val id = arg<String>(2)
            templates.value = templates.value.filterNot { it.id == id } + ImageStyleTemplateEntity(id, arg(0),
                ImageRecipeCodec.json.encodeToString(StyleSpec.serializer(), arg(1)), 1)
        }
        model = ImageStudioViewModel(repository, library, illustrations, mockk(), settings, clients, mockk(), queue, database)
        store.put("studio", model)
        model.enter(7, StudioEntry(StudioPage.STYLE))
    }
    @After fun teardown() { store.clear(); Dispatchers.resetMain() }

    @Test fun newDiyIsBlankAndCancelDoesNotChangeTheSelectedStyle() = runTest {
        model.newStyle()
        assertEquals(StudioPage.STYLE_EDITOR, model.state.value.page)
        assertEquals("", model.state.value.styleEditorName)
        assertEquals("", model.state.value.styleEditorDraft.natural)
        assertEquals("", model.state.value.styleEditorDraft.tags)
        assertTrue(model.state.value.styleEditorDraft.referenceIds.isEmpty())
        assertNull(model.state.value.styleEditorDraft.seed)
        model.styleEditor(StyleSpec(presetId = "custom", natural = "new graphite"))
        model.back()
        assertEquals(StudioPage.STYLE, model.state.value.page)
        assertEquals(original, model.state.value.styleDraft)
        coVerify(exactly = 0) { repository.saveStyle(any(), any()) }
        coVerify(exactly = 0) { repository.saveTemplate(any(), any(), any()) }
        model.copyStyle()
        assertEquals(original.referenceIds, model.state.value.styleEditorDraft.referenceIds)
    }

    @Test fun savingNewTemplateCreatesItsOwnSnapshotAndBookChangesOnlyWhenApplied() = runTest {
        model.newStyle()
        val draft = StyleSpec(presetId = "custom", natural = "graphite", tags = "pencil", referenceIds = listOf("new-reference"))
        model.styleEditorName("My pencil")
        model.styleEditor(draft)
        model.saveStyleEditor()
        assertEquals("My pencil", templates.value.single().name)
        assertEquals(draft, model.state.value.styleDraft)
        assertEquals(original, model.state.value.style)
        assertEquals(StudioPage.STYLE, model.state.value.page)
        coVerify(exactly = 0) { repository.saveStyle(any(), any()) }
        model.saveStyle()
        coVerify(exactly = 1) { repository.saveStyle(7, draft) }
    }

    @Test fun editingExistingTemplateUsesItsOwnValuesAndKeepsItsId() = runTest {
        val templateStyle = StyleSpec(presetId = "custom", natural = "old ink", referenceIds = listOf("template-reference"))
        val template = ImageStyleTemplateEntity("template", "Ink", ImageRecipeCodec.json.encodeToString(StyleSpec.serializer(), templateStyle), 1)
        templates.value = listOf(template)
        model.page(StudioPage.STYLE)
        model.editStyle(template)
        assertEquals(templateStyle, model.state.value.styleEditorDraft)
        assertEquals("Ink", model.state.value.styleEditorName)
        model.styleEditorName("Evening ink")
        model.styleEditor(templateStyle.copy(natural = "evening ink"))
        model.saveStyleEditor()
        assertEquals("template", templates.value.single().id)
        assertEquals("Evening ink", templates.value.single().name)
        assertEquals(original, model.state.value.style)
        coVerify(exactly = 0) { repository.saveStyle(any(), any()) }
    }

    @Test fun trialUsesTheDiyDraftWithoutSavingAndReturnsToTheEditor() = runTest {
        val chapter = ChapterEntity(bookId = 7, chapterIndex = 0, title = "Chapter", href = "", charCount = 5)
        coEvery { library.getBook(7) } returns book
        coEvery { library.getChapter(7, 0) } returns chapter
        coEvery { library.readChapterText(7, chapter) } returns "scene"
        coEvery { repository.plan(any(), any(), any(), any(), any(), any(), any()) } answers {
            ImageRecipe(style = arg(6), shot = ShotSpec(action = "scene"))
        }
        model.newStyle()
        model.styleEditorName("Trial")
        val draft = StyleSpec(presetId = "custom", natural = "dry ink")
        model.styleEditor(draft)
        model.sample()
        assertEquals(StudioPage.GENERATE, model.state.value.page)
        assertEquals(draft, model.state.value.recipe!!.style)
        coVerify(exactly = 0) { repository.saveStyle(any(), any()) }
        coVerify(exactly = 0) { repository.saveTemplate(any(), any(), any()) }
        model.leaveStylePreview()
        assertEquals(StudioPage.STYLE_EDITOR, model.state.value.page)
        assertEquals("Trial", model.state.value.styleEditorName)
        assertEquals(draft, model.state.value.styleEditorDraft)
    }
}
