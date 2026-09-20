package com.mozhi.reader.ai.client

import org.junit.Assert.*
import org.junit.Test

class ChatUsageTest {
    @Test fun cumulativeClaudeUsageKeepsCachedInputAndNeverAddsOutputTwice() {
        var now = 0L
        val usage = ChatUsageAccumulator { now }
        usage.accept(parseChatUsage("""{"message":{"usage":{"input_tokens":25,"cache_read_input_tokens":100,"cache_creation_input_tokens":50,"output_tokens":1}}}""", ApiDialect.CLAUDE)!!)
        usage.accept(parseChatUsage("""{"usage":{"output_tokens":42}}""", ApiDialect.CLAUDE)!!)
        usage.accept(parseChatUsage("""{"usage":{"output_tokens":42}}""", ApiDialect.CLAUDE)!!)
        now = 2_000_000_000L
        assertEquals(175L, usage.inputTokens)
        assertEquals(42L, usage.outputTokens)
        assertEquals(217, usage.totalTokens)
        assertEquals(2000L, usage.elapsedMillis())
    }
    @Test fun usageOnlyOpenAiAndResponsesEventsAreRead() {
        assertEquals(ChatDelta.Usage(100, 20, 120), parseChatUsage("""{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120}}""", ApiDialect.OPENAI))
        assertEquals(ChatDelta.Usage(100, 20, 120), parseChatUsage("""{"response":{"usage":{"input_tokens":100,"output_tokens":20,"total_tokens":120}}}""", ApiDialect.OPENAI_RESPONSES))
    }
    @Test fun geminiIncludesReportedThinkingTokensAndMissingUsageStaysUnknown() {
        assertEquals(ChatDelta.Usage(100, 70, 170), parseChatUsage("""{"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":20,"thoughtsTokenCount":50,"totalTokenCount":170}}""", ApiDialect.GEMINI))
        assertNull(parseChatUsage("""{"choices":[],"usage":{}}""", ApiDialect.OPENAI))
        assertNull(parseChatUsage("""{"usage":{"prompt_tokens":-1}}""", ApiDialect.OPENAI))
        assertNull(ChatUsageAccumulator().totalTokens)
    }
}
