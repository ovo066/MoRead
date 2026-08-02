package com.mozhi.reader.ai.client

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImageExtractorTest {

    @Test
    fun openRouterStyleImagesArray() {
        val body = """
            {"choices":[{"message":{"role":"assistant","content":"给你画好了",
            "images":[{"type":"image_url","image_url":{"url":"data:image/png;base64,QUJD"}}]}}]}
        """.trimIndent()
        assertEquals(listOf("data:image/png;base64,QUJD"), ChatImageExtractor.extract(body))
    }

    @Test
    fun markdownImageInContentText() {
        val body = """
            {"choices":[{"message":{"content":"完成 ![生成图](https://cdn.example.test/a/b.png?sig=1)"}}]}
        """.trimIndent()
        assertEquals(
            listOf("https://cdn.example.test/a/b.png?sig=1"),
            ChatImageExtractor.extract(body)
        )
    }

    @Test
    fun dataUriInsidePlainText() {
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val body = """{"choices":[{"message":{"content":"data:image/webp;base64,$encoded 已生成"}}]}"""
        assertEquals(listOf("data:image/webp;base64,$encoded"), ChatImageExtractor.extract(body))
    }

    @Test
    fun multimodalContentParts() {
        val body = """
            {"choices":[{"message":{"content":[
              {"type":"text","text":"如下"},
              {"type":"image_url","image_url":{"url":"https://files.example.test/x.jpg"}}
            ]}}]}
        """.trimIndent()
        assertEquals(listOf("https://files.example.test/x.jpg"), ChatImageExtractor.extract(body))
    }

    @Test
    fun imagesEndpointStyleDataArrayOnChatPath() {
        val body = """{"data":[{"b64_json":"QUJD"},{"url":"https://img.example.test/y.png"}]}"""
        assertEquals(
            listOf("data:image/png;base64,QUJD", "https://img.example.test/y.png"),
            ChatImageExtractor.extract(body)
        )
    }

    @Test
    fun duplicatesCollapseAndGarbageYieldsEmpty() {
        val body = """
            {"choices":[{"message":{"content":"![a](https://e.test/z.png) ![b](https://e.test/z.png)"}}]}
        """.trimIndent()
        assertEquals(listOf("https://e.test/z.png"), ChatImageExtractor.extract(body))
        assertTrue(ChatImageExtractor.extract("not json").isEmpty())
        assertTrue(ChatImageExtractor.extract("""{"choices":[]}""").isEmpty())
    }
}
