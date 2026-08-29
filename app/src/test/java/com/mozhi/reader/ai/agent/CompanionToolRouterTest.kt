package com.mozhi.reader.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionToolRouterTest {
    private val allTools = setOf(
        "get_reading_progress",
        "search_book",
        "read_book_section",
        "recall_memory",
        "add_annotation",
        "write_note",
        "save_plot_summary",
        "generate_image",
        "synthesize_speech"
    )

    @Test
    fun `main companion keeps persona tools without keyword match`() {
        val tools = CompanionToolRouter.available(
            personaEnabledTools = allTools,
            webSearchEnabled = false,
            longTermMemoryEnabled = true
        )

        assertEquals(allTools, tools)
    }

    @Test
    fun `main companion applies runtime capability switches`() {
        val tools = CompanionToolRouter.available(
            personaEnabledTools = allTools,
            requiredTools = setOf("generate_image"),
            webSearchEnabled = true,
            longTermMemoryEnabled = false
        )

        assertFalse("recall_memory" in tools)
        assertTrue("web_search" in tools)
        assertTrue("web_scrape" in tools)
        assertTrue("generate_image" in tools)
    }

    @Test
    fun `scene question does not attach unrelated tools`() {
        val tools = CompanionToolRouter.select(
            userText = "这句话是什么意思？",
            sceneAvailable = true,
            personaEnabledTools = allTools,
            webSearchEnabled = true,
            longTermMemoryEnabled = true
        )

        assertTrue(tools.isEmpty())
    }

    @Test
    fun `image request only attaches image and needed book lookup`() {
        val tools = CompanionToolRouter.select(
            userText = "根据书里这个人物画一张插图",
            sceneAvailable = false,
            personaEnabledTools = allTools,
            webSearchEnabled = true,
            longTermMemoryEnabled = true
        )

        assertEquals(setOf("search_book", "generate_image"), tools)
        assertFalse("synthesize_speech" in tools)
        assertFalse("web_search" in tools)
    }

    @Test
    fun `required tools bypass persona selection`() {
        val tools = CompanionToolRouter.select(
            userText = "生成剧情梗概",
            sceneAvailable = false,
            personaEnabledTools = emptySet(),
            requiredTools = setOf("get_reading_progress", "read_book_section", "save_plot_summary"),
            webSearchEnabled = false,
            longTermMemoryEnabled = false
        )

        assertEquals(setOf("get_reading_progress", "read_book_section", "save_plot_summary"), tools)
    }
}