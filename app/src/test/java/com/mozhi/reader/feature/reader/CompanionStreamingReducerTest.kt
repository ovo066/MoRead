package com.mozhi.reader.feature.reader

import com.mozhi.reader.ai.agent.AgentEvent
import com.mozhi.reader.core.database.entity.MessageEntity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CompanionStreamingReducerTest {
    private fun committed(round: String) = AgentEvent.RoundCommitted(MessageEntity(
        id = 9, conversationId = 1, role = "assistant", content = "answer",
        createdAt = 1, clientRoundId = round
    ))

    @Test fun lateFrameAfterCommitCannotRestoreClearedBuffer() {
        val reducer = CompanionStreamingReducer()
        reducer.reduce(AgentEvent.RoundStarted("one"))
        reducer.reduce(AgentEvent.Text("a".repeat(300)))
        val previousFrame = reducer.frame()
        assertTrue(previousFrame.text.isNotEmpty())
        reducer.reduce(committed("one"))
        assertEquals("", reducer.frame().text)
        assertEquals("", reducer.text)
    }

    @Test fun oldCommitDoesNotEraseNextRoundAndReasoning() {
        val reducer = CompanionStreamingReducer()
        reducer.reduce(AgentEvent.RoundStarted("one"))
        reducer.reduce(AgentEvent.Text("old"))
        reducer.reduce(AgentEvent.RoundStarted("two"))
        reducer.reduce(AgentEvent.Text("new"))
        reducer.reduce(AgentEvent.Reasoning("thinking"))
        reducer.reduce(committed("one"))
        assertEquals("new", reducer.text)
        assertEquals("thinking", reducer.reasoning)
        assertEquals("two", reducer.roundId)
    }

    @Test fun bufferedProducerNeverMutatesMainOwnedState() = runTest {
        val reducer = CompanionStreamingReducer()
        val events = Channel<AgentEvent>(Channel.BUFFERED)
        launch {
            events.send(AgentEvent.RoundStarted("one"))
            events.send(AgentEvent.Text("first"))
            events.send(committed("one"))
            events.send(AgentEvent.RoundStarted("two"))
            events.send(AgentEvent.Text("second"))
            events.close()
        }
        runCurrent()
        assertEquals("", reducer.text)
        for (event in events) reducer.reduce(event)
        assertEquals("second", reducer.text)
        assertEquals("two", reducer.roundId)
    }

    @Test fun clearingStopOrErrorDoesNotLeakOldTextIntoNextRound() {
        val reducer = CompanionStreamingReducer()
        reducer.reduce(AgentEvent.RoundStarted("stopped"))
        reducer.reduce(AgentEvent.Text("partial"))
        assertEquals("partial", reducer.text)
        reducer.clear()
        reducer.reduce(AgentEvent.RoundStarted("retry"))
        reducer.reduce(AgentEvent.Text("retry text"))
        assertEquals("retry text", reducer.text)
        assertTrue("retry text".startsWith(reducer.frame().text))
    }
}
