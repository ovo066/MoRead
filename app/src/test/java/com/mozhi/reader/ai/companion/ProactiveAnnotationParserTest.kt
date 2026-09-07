package com.mozhi.reader.ai.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveAnnotationParserTest {

    private val threeDrafts = """{"annotations":[
        {"quote":"第一段原文","note":"第一条","style":"WAVY","voice":true},
        {"quote":"第二段原文","note":"第二条"},
        {"quote":"第三段原文","note":"第三条"}
    ]}"""

    @Test
    fun acceptsEnvelopeAndHonorsTheConfiguredLimit() {
        val parsed = ProactiveAnnotationParser.parse(threeDrafts, limit = 2)

        assertEquals(2, parsed.size)
        assertTrue(parsed.first().voice)
    }

    @Test
    fun unlimitedKeepsEverythingTheModelReturned() {
        assertEquals(3, ProactiveAnnotationParser.parse(threeDrafts, limit = Int.MAX_VALUE).size)
    }

    @Test
    fun bareArrayIsAccepted() {
        val parsed = ProactiveAnnotationParser.parse(
            """[{"quote":"原文","note":"评"}]""",
            limit = 5
        )
        assertEquals(1, parsed.size)
    }

    @Test
    fun blankFieldsAndGarbageAreDropped() {
        assertTrue(
            ProactiveAnnotationParser.parse(
                """{"annotations":[{"quote":"","note":"评"},{"quote":"原文","note":"  "}]}""",
                limit = 5
            ).isEmpty()
        )
        assertTrue(ProactiveAnnotationParser.parse("不是 JSON", limit = 5).isEmpty())
    }
}
