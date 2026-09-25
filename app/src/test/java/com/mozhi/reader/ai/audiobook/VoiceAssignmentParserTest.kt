package com.mozhi.reader.ai.audiobook

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceAssignmentParserTest {
    @Test
    fun `只接受候选音色 id`() {
        val result = VoiceAssignmentParser.parse(
            """{"voiceAssignments":{"林渊":"voice-a","苏晚":"unknown"}}""",
            setOf("voice-a", "voice-b")
        )
        assertEquals(mapOf("林渊" to "voice-a"), result)
    }
    @Test fun malformedNestedValuesAreIgnoredWithoutLosingValidAssignments() {
        assertEquals(mapOf("苏晚" to "voice-a"), VoiceAssignmentParser.parse(
            """{"voiceAssignments":{"林渊":{"id":"voice-a"},"苏晚":"voice-a"}}""", setOf("voice-a")))
        assertEquals(emptyMap<String, String>(), VoiceAssignmentParser.parse("""{"roles":[]}""", setOf("voice-a")))
    }
}
