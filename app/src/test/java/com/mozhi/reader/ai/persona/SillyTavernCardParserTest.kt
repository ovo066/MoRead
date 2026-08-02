package com.mozhi.reader.ai.persona

import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SillyTavernCardParserTest {

    @Test
    fun parsesV2PngCardWithSeparatedWorldBook() {
        val json = """
            {
              "spec": "chara_card_v2",
              "data": {
                "name": "白鹭",
                "description": "{{char}}是一名旅居书店的猫。",
                "personality": "慵懒、敏锐",
                "scenario": "{{user}}走进书店。",
                "first_mes": "欢迎来到白鹭书屋，{{user}}。",
                "mes_example": "<START>\n{{user}}: 你是谁？\n{{char}}: 我是白鹭。",
                "tags": ["猫", "书店", "治愈", "多余标签"],
                "creator": "someone",
                "character_book": {
                  "entries": [
                    {"content": "白鹭其实是店主的化身。", "enabled": true, "comment": "真身"},
                    {"content": "禁用条目保留但不绑定。", "enabled": false, "keys": ["秘密"]},
                    {"content": "  "}
                  ]
                }
              }
            }
        """.trimIndent()
        val png = pngWith("chara" to base64(json))

        val card = SillyTavernCardParser.parse(png)

        assertNotNull(card)
        card!!
        assertEquals("白鹭", card.name)
        assertEquals("猫 · 书店 · 治愈", card.subtitle)
        assertTrue(card.personality.contains("白鹭是一名旅居书店的猫。"))
        assertTrue(card.personality.contains("性格特质：慵懒、敏锐"))
        assertTrue(card.personality.contains("场景设定：用户走进书店。"))
        // 世界书与人设分离：人设描述里不再有设定集。
        assertTrue(!card.personality.contains("【设定集】"))
        assertTrue(!card.personality.contains("店主的化身"))
        assertEquals(2, card.worldBook.size)
        assertEquals("真身", card.worldBook[0].name)
        assertEquals("白鹭其实是店主的化身。", card.worldBook[0].content)
        assertTrue(card.worldBook[0].enabled)
        // 无触发词的条目只能常驻。
        assertTrue(card.worldBook[0].constant)
        // 禁用条目保留、enabled = false、注入方式与触发词保真、条目名回落到首个触发词。
        assertEquals("秘密", card.worldBook[1].name)
        assertTrue(!card.worldBook[1].enabled)
        assertTrue(!card.worldBook[1].constant)
        assertEquals(listOf("秘密"), card.worldBook[1].keys)
        assertEquals("欢迎来到白鹭书屋，用户。", card.greeting)
        assertEquals(1, card.exampleDialogs.size)
        assertEquals("你是谁？", card.exampleDialogs[0].user)
        assertEquals("我是白鹭。", card.exampleDialogs[0].assistant)
        // PNG 卡本身就是立绘。
        assertTrue(card.avatarPng.contentEquals(png))
    }

    @Test
    fun prefersCcv3ChunkOverChara() {
        val v2 = """{"data":{"name":"旧版"}}"""
        val v3 = """{"spec":"chara_card_v3","data":{"name":"新版"}}"""
        val png = pngWith("chara" to base64(v2), "ccv3" to base64(v3))

        assertEquals("新版", SillyTavernCardParser.parse(png)?.name)
    }

    @Test
    fun parsesPlainJsonV1WithRootLevelFields() {
        val json = """
            {"name":"简卡","description":"一段人设。","first_mes":"你好。"}
        """.trimIndent()

        val card = SillyTavernCardParser.parse(json.toByteArray())

        assertNotNull(card)
        assertEquals("简卡", card!!.name)
        assertEquals("一段人设。", card.personality)
        assertEquals("你好。", card.greeting)
        assertNull(card.avatarPng)
    }

    @Test
    fun exampleDialogsHandleMultipleBlocksAndContinuationLines() {
        val mes = """
            <START>
            {{user}}: 第一问
            {{char}}: 第一答
            续行也算我的话
            <START>
            {{user}}: 第二问
            {{char}}: 第二答
        """.trimIndent()

        val dialogs = SillyTavernCardParser.parseExampleDialogs(mes, "角色")

        assertEquals(2, dialogs.size)
        assertEquals("第一问", dialogs[0].user)
        assertEquals("第一答\n续行也算我的话", dialogs[0].assistant)
        assertEquals("第二问", dialogs[1].user)
        assertEquals("第二答", dialogs[1].assistant)
    }

    @Test
    fun garbageInputsReturnNull() {
        assertNull(SillyTavernCardParser.parse("不是 JSON".toByteArray()))
        assertNull(SillyTavernCardParser.parse(pngWith())) // 无 tEXt 卡块的 PNG
        assertNull(SillyTavernCardParser.parse("""{"description":"没有名字"}""".toByteArray()))
    }

    // ---- PNG 构造 ----

    private fun base64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray())

    private fun pngWith(vararg textChunks: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        writeChunk(out, "IHDR", ByteArray(13))
        textChunks.forEach { (keyword, text) ->
            writeChunk(
                out,
                "tEXt",
                keyword.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
                    text.toByteArray(Charsets.ISO_8859_1)
            )
        }
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val length = data.size
        out.write(byteArrayOf(
            (length ushr 24).toByte(),
            (length ushr 16).toByte(),
            (length ushr 8).toByte(),
            length.toByte()
        ))
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(data)
        out.write(ByteArray(4)) // CRC 填零：解析器按设计不校验
    }
}
