package com.mozhi.reader.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/** 「过程」卡的参数摘要：一行说清 agent 查了什么、写到了哪，坏数据一律降级为空。 */
class ToolCallSummaryTest {

    @Test
    fun `search_book shows query and requested count`() {
        assertEquals(
            "主角的身世 · 5段",
            ToolCallSummary.summarize("search_book", """{"query":"主角的身世","top_k":5}""")
        )
    }

    @Test
    fun `search_book without top_k does not invent a count`() {
        assertEquals(
            "主角的身世",
            ToolCallSummary.summarize("search_book", """{"query":"主角的身世"}""")
        )
    }

    @Test
    fun `read_book_section renders single chapter and ranges`() {
        assertEquals(
            "第 3 章",
            ToolCallSummary.summarize("read_book_section", """{"from_chapter":3}""")
        )
        assertEquals(
            "第 3-7 章",
            ToolCallSummary.summarize(
                "read_book_section",
                """{"from_chapter":3,"to_chapter":7}"""
            )
        )
        assertEquals(
            "第 3 章 · 从 12000 字续读",
            ToolCallSummary.summarize(
                "read_book_section",
                """{"from_chapter":3,"start_char":12000}"""
            )
        )
    }

    @Test
    fun `add_annotation surfaces chapter style and quote`() {
        assertEquals(
            "第 9 章 · 波浪 · 雪落在他肩上",
            ToolCallSummary.summarize(
                "add_annotation",
                """{"quote":"雪落在他肩上","comment":"伏笔","style":"wavy","chapter_number":9}"""
            )
        )
    }

    @Test
    fun `add_annotation without optional fields still shows the quote`() {
        assertEquals(
            "雪落在他肩上",
            ToolCallSummary.summarize(
                "add_annotation",
                """{"quote":"雪落在他肩上","comment":"金句"}"""
            )
        )
    }

    @Test
    fun `plot summary renders the chapter range`() {
        assertEquals(
            "第 1-12 章",
            ToolCallSummary.summarize(
                "save_plot_summary",
                """{"content_md":"…","from_chapter":1,"to_chapter":12}"""
            )
        )
    }

    @Test
    fun `multiline arguments collapse to a single line`() {
        assertEquals(
            "第一行 第二行",
            ToolCallSummary.summarize("write_note", """{"title":"第一行\n第二行"}""")
        )
    }

    @Test
    fun `long values are clipped with an ellipsis`() {
        val summary = ToolCallSummary.summarize(
            "generate_image",
            """{"prompt":"${"雪".repeat(80)}"}"""
        )
        assertEquals(49, summary.length)
        assertEquals('…', summary.last())
    }

    @Test
    fun `readback tools summarize scope`() {
        assertEquals(
            "第 3-8 章 · 卷部",
            ToolCallSummary.summarize("list_chapters", """{"from_chapter":3,"to_chapter":8,"level":"volume"}""")
        )
        assertEquals(
            "当前章 · 主角",
            ToolCallSummary.summarize("list_annotations", """{"query":"主角"}""")
        )
        assertEquals(
            "第 12 条",
            ToolCallSummary.summarize("list_notes", """{"note_id":12}""")
        )
    }

    @Test
    fun `no-argument and malformed calls degrade to an empty summary`() {
        assertEquals("", ToolCallSummary.summarize("get_reading_progress", "{}"))
        assertEquals("", ToolCallSummary.summarize("search_book", "not json"))
        assertEquals("", ToolCallSummary.summarize("search_book", null))
        // 参数在但缺了要用的键：不该编造，也不该抛。
        assertEquals("", ToolCallSummary.summarize("read_book_section", """{"max_chars":100}"""))
    }

    @Test
    fun `unknown tools fall back to the first textual argument`() {
        assertEquals(
            "某个值",
            ToolCallSummary.summarize("brand_new_tool", """{"count":3,"target":"某个值"}""")
        )
    }
}
