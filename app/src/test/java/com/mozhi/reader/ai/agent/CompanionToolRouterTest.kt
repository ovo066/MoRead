package com.mozhi.reader.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionToolRouterTest {
    private val allTools = setOf(
        "get_reading_progress",
        "search_book",
        "grep_book",
        "read_book_section",
        "list_chapters",
        "list_annotations",
        "list_notes",
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
    fun `read tools are always available even with empty persona whitelist`() {
        val tools = CompanionToolRouter.available(
            personaEnabledTools = emptySet(),
            webSearchEnabled = false,
            longTermMemoryEnabled = true
        )

        assertTrue("list_chapters" in tools)
        assertTrue("list_annotations" in tools)
        assertTrue("list_notes" in tools)
        assertTrue("search_book" in tools)
    }

    @Test
    fun `discussion always exposes all read tools even without intent keywords`() {
        val tools = CompanionToolRouter.forDiscussion(longTermMemoryEnabled = true)
        assertEquals(setOf("get_reading_progress", "search_book", "grep_book", "read_book_section",
            "list_chapters", "list_annotations", "list_notes", "recall_memory"), tools)
        assertFalse("add_annotation" in tools)
        assertFalse("generate_image" in tools)
        assertFalse("web_search" in tools)
    }

    @Test
    fun `discussion respects disabled long term memory without losing book retrieval`() {
        val tools = CompanionToolRouter.forDiscussion(longTermMemoryEnabled = false)
        assertFalse("recall_memory" in tools)
        assertTrue("search_book" in tools)
        assertTrue("read_book_section" in tools)
    }

    @Test
    fun `required tools bypass persona selection`() {
        val tools = CompanionToolRouter.available(
            personaEnabledTools = emptySet(),
            requiredTools = setOf("get_reading_progress", "read_book_section", "save_plot_summary"),
            webSearchEnabled = false, longTermMemoryEnabled = false)
        assertTrue("save_plot_summary" in tools)
        assertFalse("generate_image" in tools)
    }
}
