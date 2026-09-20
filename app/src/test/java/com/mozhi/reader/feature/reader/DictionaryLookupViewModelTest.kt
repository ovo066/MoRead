package com.mozhi.reader.feature.reader

import androidx.lifecycle.ViewModelStore
import com.mozhi.reader.ai.client.*
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.core.dictionary.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DictionaryLookupViewModelTest {
    @Test fun changingDictionaryReplacesSavedGlossAndPhoneticsWhileKeepingLearningHistory() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val repository = mockk<LocalDictionaryRepository>()
            val settings = mockk<ReaderSettingsRepository>()
            val original = VocabularyWord("book", "old definition", "Original context.", 7, 2, 18,
                gloss = "旧标注", phonetic = "/old/", learned = true)
            val saved = MutableStateFlow(ReaderSettings(vocabulary = listOf(original)))
            every { settings.settings } returns saved
            coEvery { settings.saveVocabulary(any(), any()) } answers {
                saved.value = saved.value.copy(vocabulary = listOf(firstArg()))
            }
            coEvery { repository.list() } returns listOf(LocalDictionary("one", "词典一", 0), LocalDictionary("two", "词典二", 0))
            coEvery { repository.lookup("book") } returns listOf(
                DictionaryDefinition("one", "词典一", "<p>book /bʊk/ 书本</p>"),
                DictionaryDefinition("two", "词典二", "<p>book 预订</p>"))
            val model = EnglishLearningViewModel(repository, settings, mockk())
            store.put("dictionary", model)
            model.lookup(DictionaryLookupHit("book", "Please book a room.", 4, 22))
            runCurrent()
            model.saveWord(9, "one", false)
            runCurrent()
            assertEquals("书本", saved.value.vocabulary.single().gloss)
            assertEquals("/bʊk/", saved.value.vocabulary.single().phonetic)
            model.saveWord(9, "two", false)
            runCurrent()
            assertEquals(original.copy(definition = "book 预订", gloss = "预订", phonetic = ""), saved.value.vocabulary.single())
            assertEquals("已更新生词释义与词下标注", model.state.value.message)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun aiDictionaryUsesCheapAssignmentAndPreservesRichDefinition() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val repository = mockk<LocalDictionaryRepository>()
            val settings = mockk<ReaderSettingsRepository>()
            val clients = mockk<AiClientFactory>()
            val client = mockk<ChatApiClient>()
            every { settings.settings } returns MutableStateFlow(ReaderSettings(vocabulary = listOf(
                VocabularyWord("tugs", definition = "旧词典", gloss = "拉扯", phonetic = "/old/")
            )))
            coEvery { repository.list() } returns emptyList()
            coEvery { repository.lookup("tugs") } returns emptyList()
            coEvery { settings.saveVocabulary(any(), any()) } just Runs
            coEvery { clients.forRole(ModelRole.CHEAP) } returns ResolvedChatClient(client, ChatOptions(), mockk(), "dictionary-cheap")
            val definition = "## tugs\n\n原形：tug\n\n1. 用力拉；拽。"
            coEvery { client.chat(any(), any()) } returns buildJsonObject {
                put("gloss", "轻拽"); put("phonetic", "/tʌɡz/"); put("definition", definition)
            }.toString()
            val model = EnglishLearningViewModel(repository, settings, clients)
            store.put("dictionary", model)
            model.lookup(DictionaryLookupHit("tugs", "He tugs her sleeve.", 3, 12))
            runCurrent()
            model.aiLookup()
            runCurrent()
            assertEquals(definition, model.state.value.aiDefinition)
            assertFalse(model.state.value.aiBusy)
            assertNull(model.state.value.message)
            coVerify(exactly = 1) { clients.forRole(ModelRole.CHEAP) }
            coVerify(exactly = 0) { clients.forRole(ModelRole.CHAT) }
            coVerify { client.chat(match { messages -> messages.last().content.contains("He tugs her sleeve.") }, any()) }
            model.saveWord(8, null, true)
            runCurrent()
            coVerify { settings.saveVocabulary(match { it.word == "tugs" && it.definition == definition && it.gloss == "轻拽" && it.phonetic == "/tʌɡz/" }, false) }
            model.lookup(DictionaryLookupHit("故", "", 0, 0))
            assertNull(model.state.value.aiAnnotation)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun chineseSelectionWorksWithoutEnglishModeAndSavesTheChosenDictionaryDefinition() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val repository = mockk<LocalDictionaryRepository>()
            val settings = mockk<ReaderSettingsRepository>()
            every { settings.settings } returns MutableStateFlow(ReaderSettings(englishLearningEnabled = false))
            coEvery { repository.list() } returns listOf(LocalDictionary("first", "古汉语", 0), LocalDictionary("second", "汉语", 0))
            coEvery { repository.lookup("故") } returns listOf(DictionaryDefinition("first", "古汉语", "<b>故</b> 旧的"), DictionaryDefinition("second", "汉语", "<b>故</b> 缘故"))
            coEvery { settings.saveVocabulary(any(), any()) } just Runs
            val model = EnglishLearningViewModel(repository, settings, mockk())
            store.put("dictionary", model)
            model.lookup(DictionaryLookupHit(" 故 ", "温故而知新", 3, 12))
            runCurrent()
            assertEquals("故", model.state.value.hit!!.word)
            assertEquals(2, model.state.value.definitions.size)
            model.saveWord(8, "second", false)
            runCurrent()
            coVerify { settings.saveVocabulary(match { it.word == "故" && it.definition == "故 缘故" && it.context == "温故而知新" && it.chapterIndex == 3 && it.offset == 12 && it.bookId == 8L }, false) }
            model.lookup(DictionaryLookupHit("词".repeat(81), "", 0, 0))
            runCurrent()
            assertTrue(model.state.value.definitions.isEmpty())
            assertNotNull(model.state.value.message)
            coVerify(exactly = 1) { repository.lookup(any()) }
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
