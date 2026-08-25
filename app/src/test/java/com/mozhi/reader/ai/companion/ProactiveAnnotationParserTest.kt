package com.mozhi.reader.ai.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveAnnotationParserTest {
    @Test
    fun acceptsEnvelopeAndCapsAtTwo() {
        val parsed = ProactiveAnnotationParser.parse(
            """{"annotations":[
                {"quote":"第一段原文","note":"第一条","style":"WAVY","voice":true},
                {"quote":"第二段原文","note":"第二条"},
                {"quote":"第三段原文","note":"第三条"}
            ]}"""
        )

        assertEquals(2, parsed.size)
        assertTrue(parsed.first().voice)
    }
}
