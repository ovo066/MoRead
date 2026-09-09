package com.mozhi.reader.feature.reader

import com.mozhi.reader.ai.agent.AgentEvent

/**
 * Main-owner stream reducer. Producers send immutable events through the buffered flow;
 * only its collector and the Main frame callback can touch these mutable buffers.
 */
internal class CompanionStreamingReducer {
    private val textBuffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()
    var roundId: String? = null
        private set
    val text: String get() = textBuffer.toString()
    val reasoning: String get() = reasoningBuffer.toString()

    fun reduce(event: AgentEvent) {
        when (event) {
            is AgentEvent.RoundStarted -> {
                clear()
                roundId = event.roundId
            }
            is AgentEvent.Text -> textBuffer.append(event.text)
            is AgentEvent.Reasoning -> reasoningBuffer.append(event.text)
            is AgentEvent.RoundCommitted -> {
                // An old commit cannot erase the next round, even under replay/reconnect.
                if (event.message.clientRoundId == roundId) clear()
            }
            is AgentEvent.ToolRun, is AgentEvent.ToolFinished -> Unit
        }
    }

    // One immutable snapshot per display frame, without a fractional catch-up backlog.
    // Artificially withholding 7/8 of received text caused a large final flush at commit.
    fun frame(): StreamingFrame = StreamingFrame(
        text = text,
        reasoning = reasoning.takeIf(String::isNotBlank)
    )

    fun clear() {
        textBuffer.setLength(0)
        reasoningBuffer.setLength(0)
    }
}

internal data class StreamingFrame(val text: String, val reasoning: String?)
