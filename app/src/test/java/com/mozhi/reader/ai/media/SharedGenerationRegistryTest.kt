package com.mozhi.reader.ai.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedGenerationRegistryTest {
    @Test
    fun `等待者取消后生成继续且后来者复用结果`() = runTest {
        val registry = SharedGenerationRegistry<String, String>(backgroundScope)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var generationCount = 0

        val first = launch {
            registry.await("same-segment") {
                generationCount++
                started.complete(Unit)
                release.await()
                "cached-audio"
            }
        }
        started.await()
        first.cancelAndJoin()

        val second = async {
            registry.await("same-segment") {
                generationCount++
                "should-not-run"
            }
        }
        release.complete(Unit)

        assertEquals("cached-audio", second.await())
        assertEquals(1, generationCount)
        advanceUntilIdle()
        assertEquals(0, registry.activeCount())
    }

    @Test
    fun `并发等待同一键只执行一次`() = runTest {
        val registry = SharedGenerationRegistry<String, Int>(backgroundScope)
        val release = CompletableDeferred<Unit>()
        var generationCount = 0

        val first = async {
            registry.await("voice-key") {
                generationCount++
                release.await()
                7
            }
        }
        val second = async {
            registry.await("voice-key") {
                generationCount++
                9
            }
        }
        advanceUntilIdle()
        release.complete(Unit)

        assertEquals(7, first.await())
        assertEquals(7, second.await())
        assertEquals(1, generationCount)
    }
}
