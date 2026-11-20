package com.mozhi.reader.ai.companion

import org.junit.Assert.*
import org.junit.Test

class ProactiveReplyParserTest {
    @Test fun trimsReplyAndFlattensInternalLineBreaks() {
        assertEquals("但他还在等。 钟声并没有停。", ProactiveReplyParser.parse(
            """{"reply":"  但他还在等。\r\n钟声并没有停。  "}"""
        )?.reply)
    }

    @Test fun explicitSilenceIsDistinctFromParseFailure() {
        listOf("null", "\"null\"", "\" NULL \"", "\"无\"", "\"pass\"", "\"PaSs\"", "\" \\n \"").forEach { value ->
            val result = ProactiveReplyParser.parse("{\"reply\":$value}")
            assertNotNull(value, result)
            assertNull(result!!.reply)
        }
    }

    @Test fun cleansFencesAndThinkingLikeAnnotations() {
        assertEquals("也许还有别的原因。", ProactiveReplyParser.parse(
            "<think>思考</think>\n```json\n{\"reply\":\"也许还有别的原因。\"}\n```"
        )?.reply)
        assertEquals("再看看前文。", ProactiveReplyParser.parse(
            "<thinking>思考</thinking>{\"reply\":\"再看看前文。\"}"
        )?.reply)
    }

    @Test fun overlongReplyIsSilenceWithoutTruncatingASentence() {
        assertEquals(200, ProactiveReplyParser.parse("{\"reply\":\"${"字".repeat(200)}\"}")!!.reply!!.length)
        val result = ProactiveReplyParser.parse("{\"reply\":\"${"字".repeat(201)}\"}")
        assertNotNull(result)
        assertNull(result!!.reply)
    }

    @Test fun rejectsNonObjectsMalformedJsonAndWrongReplyTypes() {
        listOf("[]", "[{\"reply\":\"话\"}]", "null", "\"无\"", "不是 JSON", "{", "{}",
            "{\"reply\":true}", "{\"reply\":12}", "{\"reply\":[]}").forEach {
            assertNull(it, ProactiveReplyParser.parse(it))
        }
    }
}
