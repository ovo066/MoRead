package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.datastore.ChineseConversionMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor

class ReaderSearchViewModelTest {

    @Test
    fun `canceled scan cannot complete a replacement or cleared query`() = runTest {
        val main = StandardTestDispatcher(testScheduler, "main")
        val worker = StandardTestDispatcher(testScheduler, "worker")
        Dispatchers.setMain(main)
        try {
            val started = List(2) { CompletableDeferred<Unit>() }
            var oldAttempt = 0
            val scannedModes = mutableListOf<ChineseConversionMode>()
            var publicationDispatcher: ContinuationInterceptor? = null
            val oldHit = BookSearchHit(0, "old", 0, "old", 0, 3)
            val newHit = BookSearchHit(0, "new", 0, "new", 0, 3)
            val viewModel = ReaderSearchViewModel { _, mode, query, publish ->
                scannedModes += mode
                if (query == "old") {
                    val attempt = oldAttempt++
                    searchChapterOrNull<Unit> {
                        withContext(worker) {
                            started[attempt].complete(Unit)
                            awaitCancellation()
                        }
                    }
                    publish(listOf(oldHit))
                } else {
                    publicationDispatcher = currentCoroutineContext()[ContinuationInterceptor]
                    publish(listOf(newHit))
                }
            }

            viewModel.bind(1, ChineseConversionMode.OFF)
            viewModel.search("old")
            runCurrent()
            assertTrue(started[0].isCompleted)

            viewModel.search("new")
            runCurrent()
            assertEquals(
                ReaderSearchUiState(
                    query = "new",
                    isSearching = false,
                    hits = listOf(newHit),
                    completed = true
                ),
                viewModel.uiState.value
            )
            assertSame(Dispatchers.Main, publicationDispatcher)

            viewModel.search("old")
            runCurrent()
            assertTrue(started[1].isCompleted)

            viewModel.bind(1, ChineseConversionMode.TW2SP)
            runCurrent()
            assertEquals(ReaderSearchUiState(), viewModel.uiState.value)

            viewModel.search("new")
            runCurrent()
            assertEquals(ChineseConversionMode.TW2SP, scannedModes.last())
        } finally {
            Dispatchers.resetMain()
        }
    }
}
