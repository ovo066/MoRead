package com.mozhi.reader.ai.search

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class WebSearchServiceTest {
    @Test
    fun `parses firecrawl v2 web results`() {
        val results = parseWebSearchResponse(
            WebSearchProvider.FIRECRAWL,
            """{"data":{"web":[{"title":"Firecrawl","url":"https://firecrawl.dev/","description":"Search API"}]}}"""
        )

        assertEquals(1, results.size)
        assertEquals("Firecrawl", results.single().title)
        assertEquals("Search API", results.single().snippet)
    }

    @Test
    fun `parses legacy firecrawl array results`() {
        val results = parseWebSearchResponse(
            WebSearchProvider.FIRECRAWL,
            """{"data":[{"title":"Docs","url":"https://docs.firecrawl.dev/","markdown":"  Firecrawl\n docs  "}]}"""
        )

        assertEquals("Firecrawl docs", results.single().snippet)
    }

    @Test
    fun `parses exa results and removes duplicate urls`() {
        val results = parseWebSearchResponse(
            WebSearchProvider.EXA,
            """{"results":[{"title":"Exa","url":"https://exa.ai/","text":"Neural search"},{"title":"Again","url":"https://exa.ai/","text":"duplicate"}]}"""
        )

        assertEquals(1, results.size)
        assertEquals("Neural search", results.single().snippet)
    }

    @Test
    fun `parses tavily content`() {
        val results = parseWebSearchResponse(
            WebSearchProvider.TAVILY,
            """{"results":[{"title":"Tavily","url":"https://tavily.com/","content":"Research result"}]}"""
        )

        assertEquals("Research result", results.single().snippet)
    }

    @Test
    fun `tavily search and extract payloads honor configured depth`() {
        val settings = WebSearchSettings(
            provider = WebSearchProvider.TAVILY,
            tavilySearchDepth = TavilyDepth.ADVANCED,
            tavilyExtractDepth = TavilyDepth.ADVANCED
        )

        val search = buildWebSearchPayload(WebSearchProvider.TAVILY, "news", 5, settings)
        val scrape = buildWebScrapePayload(
            WebSearchProvider.TAVILY,
            "https://example.com/article",
            settings
        )

        assertEquals("advanced", search["search_depth"]?.jsonPrimitive?.content)
        assertEquals("advanced", scrape["extract_depth"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parses firecrawl scraped markdown and metadata`() {
        val result = parseWebScrapeResponse(
            WebSearchProvider.FIRECRAWL,
            """{"data":{"markdown":"# Article\nBody","metadata":{"title":"Article","sourceURL":"https://example.com/a"}}}""",
            "https://example.com/a"
        )

        assertEquals("Article", result.title)
        assertEquals("https://example.com/a", result.url)
        assertEquals("# Article\nBody", result.content)
    }

    @Test
    fun `parses exa contents response`() {
        val result = parseWebScrapeResponse(
            WebSearchProvider.EXA,
            """{"results":[{"title":"Exa page","url":"https://exa.ai/docs","text":"Full text"}]}""",
            "https://exa.ai/docs"
        )

        assertEquals("Exa page", result.title)
        assertEquals("Full text", result.content)
    }

    @Test
    fun `parses tavily extract response`() {
        val result = parseWebScrapeResponse(
            WebSearchProvider.TAVILY,
            """{"results":[{"url":"https://tavily.com/docs","raw_content":"Extracted markdown"}]}""",
            "https://tavily.com/docs"
        )

        assertEquals("https://tavily.com/docs", result.title)
        assertEquals("Extracted markdown", result.content)
    }
}
