package com.mozhi.reader.ai.search

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
    fun `firecrawl image payload requests image source`() {
        val payload = buildImageSearchPayload(WebSearchProvider.FIRECRAWL, "book cover", 12)

        assertEquals(12, payload["limit"]?.jsonPrimitive?.int)
        assertEquals(
            "images",
            payload["sources"]?.jsonArray?.single()?.jsonObject?.get("type")?.jsonPrimitive?.content
        )
    }

    @Test
    fun `parses firecrawl image results with dimensions`() {
        val results = parseImageSearchResponse(
            WebSearchProvider.FIRECRAWL,
            """{"data":{"images":[{"title":"Cover","imageUrl":"https://img.example/cover.jpg","imageWidth":1200,"imageHeight":1800,"url":"https://example.com/book"}]}}"""
        )

        assertEquals(1, results.size)
        assertEquals("https://img.example/cover.jpg", results.single().imageUrl)
        assertEquals("https://example.com/book", results.single().pageUrl)
        assertEquals(1200, results.single().width)
        assertEquals(1800, results.single().height)
    }

    @Test
    fun `tavily image payload and response include descriptions`() {
        val payload = buildImageSearchPayload(WebSearchProvider.TAVILY, "book cover", 10)
        val results = parseImageSearchResponse(
            WebSearchProvider.TAVILY,
            """{"images":[{"url":"https://img.example/a.jpg","description":"Main cover"}],"results":[{"title":"Book page","url":"https://example.com/book","images":[{"url":"https://img.example/b.jpg","description":"Alternate cover"}]}]}"""
        )

        assertEquals(true, payload["include_images"]?.jsonPrimitive?.boolean)
        assertEquals(true, payload["include_image_descriptions"]?.jsonPrimitive?.boolean)
        assertEquals(2, results.size)
        assertEquals("Main cover", results.first().title)
        assertEquals("https://example.com/book", results.last().pageUrl)
    }

    @Test
    fun `exa image response reads associated and extra image links`() {
        val results = parseImageSearchResponse(
            WebSearchProvider.EXA,
            """{"results":[{"title":"Book","url":"https://example.com/book","image":"https://img.example/main.jpg","extras":{"imageLinks":["https://img.example/extra.jpg"]}}]}"""
        )

        assertEquals(2, results.size)
        assertEquals(listOf("Exa", "Exa"), results.map { it.source })
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
