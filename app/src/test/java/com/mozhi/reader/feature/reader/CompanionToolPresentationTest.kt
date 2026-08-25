package com.mozhi.reader.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionToolPresentationTest {

    @Test
    fun `book tools have distinct icons and explanations`() {
        val search = companionToolPresentation("search_book")
        val read = companionToolPresentation("read_book_section")
        val memory = companionToolPresentation("recall_memory")

        assertEquals(CompanionToolIcon.SEARCH, search.icon)
        assertEquals("在已读范围内查找相关片段", search.description)
        assertEquals(CompanionToolIcon.BOOK, read.icon)
        assertEquals("读取指定章节或连续段落", read.description)
        assertEquals(CompanionToolIcon.MEMORY, memory.icon)
        assertEquals("回忆长期记忆", memory.action)
    }

    @Test
    fun `creation tools use media specific presentations`() {
        assertEquals(
            CompanionToolIcon.ANNOTATION,
            companionToolPresentation("add_annotation").icon
        )
        assertEquals(CompanionToolIcon.NOTE, companionToolPresentation("write_note").icon)
        assertEquals(CompanionToolIcon.IMAGE, companionToolPresentation("generate_image").icon)
        assertEquals(CompanionToolIcon.AUDIO, companionToolPresentation("synthesize_speech").icon)
    }

    @Test
    fun `unknown tools keep the runtime display name`() {
        val presentation = companionToolPresentation("future_tool", "处理新能力")

        assertEquals("处理新能力", presentation.title)
        assertEquals("执行 future_tool", presentation.description)
        assertEquals(CompanionToolIcon.GENERIC, presentation.icon)
    }
}
