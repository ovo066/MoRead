package com.mozhi.reader.feature.reader

import androidx.lifecycle.ViewModelStore
import com.mozhi.reader.ai.knowledge.*
import com.mozhi.reader.core.database.entity.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class ReaderKnowledgeViewModelTest {
    @Test fun sameBookRefreshKeepsVisibleSnapshotWhileWaitingForTheDatabase() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val outlines = mockk<ChapterKnowledgeRepository>()
            val characters = mockk<BookCharactersRepository>()
            val runner = mockk<KnowledgeGenerationRunner>()
            val results = MutableSharedFlow<KnowledgeSnapshot>()
            every { outlines.observe(1) } returns results
            every { characters.observe(1) } returns MutableStateFlow(BookCharactersSnapshot())
            every { runner.states } returns MutableStateFlow(emptyMap())
            val model = ReaderKnowledgeViewModel(outlines, characters, runner)
            store.put("knowledge", model)
            model.bind(1)
            runCurrent()
            val book = BookEntity(id = 1, title = "书", author = "", coverPath = null, epubPath = "", sourceType = BookSourceType.EPUB, importedAt = 1, totalChapters = 2)
            val snapshot = KnowledgeSnapshot(book)
            results.emit(snapshot)
            runCurrent()
            assertFalse(model.state.value.loading)
            assertEquals(snapshot, model.state.value.snapshot)
            model.bind(1)
            runCurrent() // The new subscription deliberately has not emitted a result yet.
            assertFalse(model.state.value.loading)
            assertEquals(snapshot, model.state.value.snapshot)
            verify(exactly = 0) { runner.stop(any(), any()) }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
