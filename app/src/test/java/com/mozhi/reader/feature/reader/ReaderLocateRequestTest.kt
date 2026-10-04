package com.mozhi.reader.feature.reader

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReaderLocateRequestTest {
    @Test fun aNewRequestAlwaysCarriesItsOwnRangeAndAnchorAndSurvivesRestoration() = runBlocking {
        val handle = SavedStateHandle()
        val requests = handle.readerLocateRequests()
        val first = ReaderLocateRequest(0, 1, 8, "first-anchor")
        val second = ReaderLocateRequest(10, 800, 840, "chapter-eleven-anchor")
        handle.requestReaderLocate(first)
        assertEquals(first, requests.first())
        handle.requestReaderLocate(second)
        assertEquals(second, requests.first())
        handle.consumeReaderLocate(first)
        assertEquals(second, requests.first())
        val restored = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        assertEquals(second, restored.readerLocateRequests().first())
        restored.consumeReaderLocate(second)
        assertNull(restored.readerLocateRequests().first())
        restored.requestReaderLocate(second)
        assertEquals(second, restored.readerLocateRequests().first())
    }

    @Test fun olderSavedCoordinatesMigrateAndAreNotReplayedAfterConsumption() = runBlocking {
        val handle = SavedStateHandle(mapOf("locate-chapter" to 10, "locate-start" to 80,
            "locate-end" to 90, "locate-anchor" to "saved-anchor"))
        val expected = ReaderLocateRequest(10, 80, 90, "saved-anchor")
        assertEquals(expected, handle.readerLocateRequests().first())
        handle.consumeReaderLocate(expected)
        assertNull(handle.readerLocateRequests().first())
        assertFalse(handle.contains("locate-chapter"))
    }
}
