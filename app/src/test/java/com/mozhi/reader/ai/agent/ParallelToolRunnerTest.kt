package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ParallelToolRunnerTest {

    private fun call(id: String, name: String = "search_book") = ToolCall(id, name, "{}")
    private val safe: (ToolCall) -> Boolean = { it.name in ConcurrencySafeTools.NAMES }

    @Test fun `adjacent read-only calls form one batch and writes stay alone`() {
        val calls = listOf(call("a"), call("b", "grep_book"), call("c", "write_note"), call("d"), call("e", "add_annotation"), call("f", "add_annotation"))
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3), listOf(4), listOf(5)), ParallelToolRunner.batches(calls, safe))
    }

    @Test fun `read-only calls overlap and results keep the model's order`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var running = 0
        var maxRunning = 0
        val order = mutableListOf<String>()
        val calls = listOf(call("slow"), call("fast"), call("mid"))
        val results = ParallelToolRunner().run(calls, safe, execute = { c ->
            running++
            maxRunning = maxOf(maxRunning, running)
            if (c.id == "slow") gate.await()
            yield()
            running--
            ToolResult.Success("result-${c.id}")
        }) { progress ->
            if (progress is ParallelToolRunner.Progress.Finished) {
                order += calls[progress.index].id
                if (calls[progress.index].id == "fast") gate.complete(Unit)
            }
        }
        assertEquals(3, maxRunning)
        assertEquals(listOf("result-slow", "result-fast", "result-mid"), results.map { it.content })
        assertEquals("slow", order.last())
    }

    @Test fun `all starts of a batch are reported before any finish`() = runTest {
        val events = mutableListOf<String>()
        ParallelToolRunner().run(listOf(call("a"), call("b")), safe, execute = { ToolResult.Success(it.id) }) { progress ->
            events += when (progress) {
                is ParallelToolRunner.Progress.Started -> "start-${progress.index}"
                is ParallelToolRunner.Progress.Finished -> "end-${progress.index}"
            }
        }
        assertEquals(listOf("start-0", "start-1"), events.take(2))
    }

    @Test fun `write calls never overlap another call`() = runTest {
        var running = 0
        val calls = listOf(call("r1"), call("w1", "write_note"), call("r2"), call("w2", "save_plot_summary"))
        ParallelToolRunner().run(calls, safe, execute = { c ->
            running++
            if (!safe(c)) assertEquals(1, running)
            yield()
            running--
            ToolResult.Success(c.id)
        }) { }
    }

    @Test fun `parallelism is bounded`() = runTest {
        var running = 0
        var maxRunning = 0
        val calls = (1..9).map { call("c$it") }
        ParallelToolRunner(maxParallel = 2).run(calls, safe, execute = { c ->
            running++
            maxRunning = maxOf(maxRunning, running)
            yield(); yield()
            running--
            ToolResult.Success(c.id)
        }) { }
        assertEquals(2, maxRunning)
    }

    @Test fun `one failure does not affect sibling results`() = runTest {
        val results = ParallelToolRunner().run(listOf(call("ok"), call("bad"), call("ok2")), safe, execute = { c ->
            if (c.id == "bad") ToolResult.Failure("X", "坏了") else ToolResult.Success(c.id)
        }) { }
        assertTrue(results[0] is ToolResult.Success)
        assertTrue(results[1] is ToolResult.Failure)
        assertTrue(results[2] is ToolResult.Success)
    }

    @Test fun `cancellation of the batch propagates`() = runTest {
        val job = async {
            ParallelToolRunner().run(listOf(call("a"), call("b")), safe, execute = { awaitCancellation() }) { }
        }
        yield()
        job.cancel()
        try {
            job.await()
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
    }
}
