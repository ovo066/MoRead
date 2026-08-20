package com.mozhi.reader.ai.audiobook

import org.junit.Assert.assertEquals
import org.junit.Test

class AudiobookScriptParserTest {
    @Test
    fun `解析代码块中的剧本数组`() {
        val result = AudiobookScriptParser.parse(
            """```json
            [{"start":0,"end":4,"role":"旁白","emotion":"中性"}]
            ```""".trimIndent(),
            textLength = 10
        )
        assertEquals(1, result.size)
        assertEquals("旁白", result.single().roleName)
    }

    @Test
    fun `越界坐标裁剪且丢弃空区间与重叠段`() {
        val result = AudiobookScriptParser.parse(
            """{"segments":[
              {"start":-4,"end":3,"role":"甲"},
              {"start":2,"end":6,"role":"乙"},
              {"start":8,"end":99,"role":"丙"},
              {"start":5,"end":5,"role":"空"}
            ]}""",
            textLength = 10
        )
        assertEquals(listOf(0 to 3, 8 to 10), result.map { it.startCharOffset to it.endCharOffset })
    }
    @Test
    fun `按稳定分段编号解析角色标注并忽略未知编号`() {
        val result = AudiobookScriptParser.parseAssignments(
            """{"assignments":[
              {"segment_id":3,"role":"苏晚","emotion":"悲伤"},
              {"segment_id":99,"role":"无效"},
              {"segment_id":3,"role":"重复"}
            ]}""",
            validIndices = setOf(1, 3, 5)
        )
        assertEquals(1, result.size)
        assertEquals(3, result.single().segmentIndex)
        assertEquals("苏晚", result.single().roleName)
    }

}
