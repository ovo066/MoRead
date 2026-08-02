package com.mozhi.reader.ai.client

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 四方言的多模态请求编码：带图消息的 JSON 结构 + 纯文本路径回归。 */
class MultimodalEncodingTest {

    private val imageMessage = ChatMessage(
        role = ChatRole.USER,
        content = "这张图里是什么？",
        parts = listOf(
            ChatPart.Image(base64 = "QUJD", mimeType = "image/jpeg"),
            ChatPart.Text("这张图里是什么？")
        )
    )
    private val plainMessage = ChatMessage(role = ChatRole.USER, content = "你好")

    @Test
    fun openAiPlainTextStaysStringContent() {
        assertEquals(JsonPrimitive("你好"), plainMessage.openAiContentElement())
    }

    @Test
    fun openAiImageBecomesDataUriContentArray() {
        val array = imageMessage.openAiContentElement().jsonArray
        assertEquals(2, array.size)
        val image = array[0].jsonObject
        assertEquals("image_url", image.getValue("type").jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,QUJD",
            image.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
        )
        val text = array[1].jsonObject
        assertEquals("text", text.getValue("type").jsonPrimitive.content)
        assertEquals("这张图里是什么？", text.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun responsesPlainTextStaysStringContent() {
        assertEquals(JsonPrimitive("你好"), responsesContentElement(plainMessage))
    }

    @Test
    fun responsesImageBecomesInputImageArray() {
        val array = responsesContentElement(imageMessage).jsonArray
        assertEquals("input_image", array[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,QUJD",
            array[0].jsonObject.getValue("image_url").jsonPrimitive.content
        )
        assertEquals("input_text", array[1].jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun claudeImageBecomesBase64SourceBlockBeforeText() {
        val client = ClaudeClient("https://api.anthropic.com", "key", "claude", OkHttpClient())
        val turns = client.encodeTurns(listOf(imageMessage))
        val content = turns.single().jsonObject.getValue("content").jsonArray
        val image = content[0].jsonObject
        assertEquals("image", image.getValue("type").jsonPrimitive.content)
        val source = image.getValue("source").jsonObject
        assertEquals("base64", source.getValue("type").jsonPrimitive.content)
        assertEquals("image/jpeg", source.getValue("media_type").jsonPrimitive.content)
        assertEquals("QUJD", source.getValue("data").jsonPrimitive.content)
        assertEquals("text", content[1].jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun claudePlainTextKeepsStringContent() {
        val client = ClaudeClient("https://api.anthropic.com", "key", "claude", OkHttpClient())
        val turns = client.encodeTurns(listOf(plainMessage))
        assertEquals(
            "你好",
            turns.single().jsonObject.getValue("content").jsonPrimitive.content
        )
    }

    @Test
    fun geminiImageBecomesInlineDataPart() {
        val parts = geminiUserParts(imageMessage)
        assertEquals(2, parts.size)
        assertEquals("image/jpeg", parts[0].inlineData?.mimeType)
        assertEquals("QUJD", parts[0].inlineData?.data)
        assertNull(parts[0].text)
        assertEquals("这张图里是什么？", parts[1].text)
    }

    @Test
    fun geminiPlainTextKeepsSingleTextPart() {
        val parts = geminiUserParts(plainMessage)
        assertEquals(1, parts.size)
        assertEquals("你好", parts.single().text)
        assertTrue(parts.single().inlineData == null)
    }
}
