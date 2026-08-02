package com.mozhi.reader.ai.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplySuggestionParserTest {

    @Test
    fun `裸 JSON 数组直接解析`() {
        assertEquals(
            listOf("他后来怎么样了？", "这段好戳我", "换个话题吧"),
            ReplySuggestionParser.parse("""["他后来怎么样了？","这段好戳我","换个话题吧"]""")
        )
    }

    @Test
    fun `代码块围栏与前后杂讯都能剥掉`() {
        val raw = """
            好的，以下是建议：
            ```json
            ["为什么他要这么做？", "我有点难过"]
            ```
        """.trimIndent()
        assertEquals(
            listOf("为什么他要这么做？", "我有点难过"),
            ReplySuggestionParser.parse(raw)
        )
    }

    @Test
    fun `对象包裹 suggestions 键也认`() {
        assertEquals(
            listOf("继续讲", "你怎么看？"),
            ReplySuggestionParser.parse("""{"suggestions":["继续讲","你怎么看？"]}""")
        )
    }

    @Test
    fun `去重去空并截到三条`() {
        val raw = """["a","a","  ","b","c","d"]"""
        assertEquals(listOf("a", "b", "c"), ReplySuggestionParser.parse(raw))
    }

    @Test
    fun `超长条目截断到四十字`() {
        val long = "长".repeat(80)
        val parsed = ReplySuggestionParser.parse("""["$long"]""")
        assertEquals(1, parsed.size)
        assertEquals(40, parsed.first().length)
    }

    @Test
    fun `完全不是 JSON 时返回空而不是抛异常`() {
        assertTrue(ReplySuggestionParser.parse("抱歉，我无法生成建议。").isEmpty())
        assertTrue(ReplySuggestionParser.parse("").isEmpty())
        assertTrue(ReplySuggestionParser.parse("[]").isEmpty())
    }
}
