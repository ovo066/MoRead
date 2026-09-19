package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.datastore.ChineseConversionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ReaderPresentationResolverTest {
    private var snapshot = ReaderPresentationSnapshot(ChineseConversionMode.OFF, 1)
    private val resolver = ReaderPresentationResolver { snapshot }

    @Test fun aStableSnapshotIsUsedAcrossSuspension() = runTest {
        var attempts = 0
        val result = resolver.resolve { captured ->
            attempts++
            yield()
            assertEquals(snapshot, captured)
            42
        }
        assertEquals(42, result)
        assertEquals(1, attempts)
    }

    @Test fun modeChangesDiscardTheOldResultAndRetryWithAFreshSnapshot() = runTest {
        val captures = mutableListOf<ReaderPresentationSnapshot>()
        val result = resolver.resolve { captured ->
            captures += captured
            if (captures.size == 1) snapshot = ReaderPresentationSnapshot(ChineseConversionMode.TW2SP, 2)
            yield()
            captured.mode
        }
        assertEquals(ChineseConversionMode.TW2SP, result)
        assertEquals(2, captures.size)
        assertNotEquals(captures[0], captures[1])
    }

    @Test fun sourceChangesInvalidateOffsetsEvenWhenTheModeHasReturnedToItsOriginalValue() = runTest {
        val original = snapshot
        var attempts = 0
        val result = resolver.resolve { captured ->
            if (attempts++ == 0) snapshot = snapshot.copy(sourceGeneration = 3)
            captured.sourceGeneration
        }
        assertEquals(3, result)
        assertFalse(resolver.isCurrent(original))
    }

    @Test fun continuousChangesAreBoundedAndNeverPublishStaleOffsets() = runTest {
        var attempts = 0
        val result = resolver.resolve {
            attempts++
            snapshot = snapshot.copy(sourceGeneration = snapshot.sourceGeneration + 1)
            42
        }
        assertNull(result)
        assertEquals(2, attempts)
    }

    @Test fun cancellationAndStableMissesAreNotRetried() = runTest {
        var attempts = 0
        val cancelled = runCatching { resolver.resolve<Int> { attempts++; throw CancellationException() } }
        assertTrue(cancelled.exceptionOrNull() is CancellationException)
        assertEquals(1, attempts)
        attempts = 0
        assertNull(resolver.resolve<Int> { attempts++; null })
        assertEquals(1, attempts)
    }
}
