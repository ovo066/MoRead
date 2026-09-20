package com.mozhi.reader.core.dictionary

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EnglishLearningPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun vocabularyAndBookSwitchesSurviveARealStoreReopen() = runTest {
        val file = File(temporary.root, "english.preferences_pb")
        fun create(scope: CoroutineScope) = ReaderSettingsRepository(PreferenceDataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer, producePath = { file.absolutePath.toPath() }), scope = scope))
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val settings = create(scope)
            assertFalse(settings.companionTokenUsageEnabled.first())
            settings.setCompanionTokenUsageEnabled(true)
            assertEquals(WordAnnotationMode.INLINE, settings.settings.first().wordAnnotationMode)
            settings.setEnglishLearning(true)
            settings.setWordAnnotationMode(WordAnnotationMode.POPUP)
            settings.setBilingual(7, true); settings.setBilingual(9, true); settings.setBilingual(7, false)
            settings.saveVocabulary(VocabularyWord("BOOK", gloss = "书", phonetic = "/bʊk/", context = "A book."))
            settings.saveVocabulary(VocabularyWord("book", gloss = "书本", learned = true))
            scope.coroutineContext.job.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val reopened = create(scope)
            assertTrue(reopened.companionTokenUsageEnabled.first())
            val loaded = reopened.settings.first()
            assertTrue(loaded.englishLearningEnabled)
            assertEquals(WordAnnotationMode.POPUP, loaded.wordAnnotationMode)
            assertEquals(setOf(9L), loaded.bilingualBooks)
            assertEquals(1, loaded.vocabulary.size)
            assertTrue(loaded.vocabulary.single().learned)
            assertEquals("书本", loaded.vocabulary.single().gloss)
            reopened.saveVocabulary(loaded.vocabulary.single(), remove = true)
            assertTrue(reopened.settings.first().vocabulary.isEmpty())
            reopened.saveVocabulary(VocabularyWord("故", definition = "旧的", context = "温故而知新"))
            scope.coroutineContext.job.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            assertEquals("故", create(scope).settings.first().vocabulary.single().word)
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }
}
