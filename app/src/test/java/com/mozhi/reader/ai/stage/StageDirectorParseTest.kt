package com.mozhi.reader.ai.stage

import com.mozhi.reader.core.stage.*
import android.content.Context
import com.mozhi.reader.ai.client.*
import com.mozhi.reader.core.database.entity.ModelRole
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.*
import org.junit.Test

class StageDirectorParseTest {
    private val paragraphs = StageDirections.paragraphs("木门缓缓打开。\n\n永不回头\n霓虹在积水里晃。")

    @Test fun `代码块废话未知字段和枚举逐条容错`() {
        val raw = "说明\n```json\n" + """{"scenes":[
            {"p":0,"setting":"INTERIOR","time":"UNKNOWN","extra":1},
            {"p":1,"weather":"RAIN","mood":"EERIE"},
            {"p":2,"light":"future-value"},
            {"p":-1,"setting":"CITY"},{"p":3,"setting":"SEA"},
            {"p":"bad","setting":"CITY"},null],"key":[]}""" + "\n```\n结束"
        val result = requireNotNull(StageDirectorParser.parse(raw, 0..1, paragraphs))
        assertEquals(listOf(StageDirection(0, setting = StageSetting.INTERIOR), StageDirection(1, weather = StageWeatherCue.RAIN, mood = StageMood.EERIE)), result.directions)
        assertTrue(result.keyLines.isEmpty())
    }

    @Test fun `同段场景取最后一条而非拼接`() {
        val result = requireNotNull(StageDirectorParser.parse("""{"scenes":[
            {"p":0,"setting":"CITY","time":"NIGHT"},
            {"p":0,"setting":"INTERIOR"}],"key":[]}""", 0..2, paragraphs))
        assertEquals(listOf(StageDirection(0, setting = StageSetting.INTERIOR)), result.directions)
        val empty = requireNotNull(StageDirectorParser.parse("""{"scenes":[{"p":0,"setting":"CITY"},{"p":0,"setting":"INVALID"}]}""", 0..2, paragraphs))
        assertTrue(empty.directions.isEmpty())
    }

    @Test fun `金句只接受本段逐字摘录并限制长度与范围`() {
        val raw = """{"scenes":[],"key":[
            {"p":1,"quote":" 永不回头 "},{"p":0,"quote":"永不回头"},
            {"p":2,"quote":"霓虹映在积水里"},{"p":2,"quote":123},
            {"p":9,"quote":"永不回头"},{"p":1,"quote":""}]}"""
        val result = requireNotNull(StageDirectorParser.parse(raw, 0..2, paragraphs))
        assertEquals(listOf(StageKeyLine(1, "永不回头")), result.keyLines)
        val long = StageDirections.paragraphs("一".repeat(15))
        assertTrue(requireNotNull(StageDirectorParser.parse("""{"key":[{"p":0,"quote":"${long[0].text}"}]}""", 0..0, long)).keyLines.isEmpty())
        assertTrue(requireNotNull(StageDirectorParser.parse(raw, 2..2, paragraphs)).keyLines.isEmpty())
    }

    @Test fun `损坏包装和错误数组不抛异常`() {
        for (raw in listOf("nothing", "{broken}", "[]", "{}")) assertNull(StageDirectorParser.parse(raw, 0..2, paragraphs))
        assertNotNull(StageDirectorParser.parse("""{"scenes":[false,{}, {"p":0,"setting":{}}],"key":[null]}""", 0..2, paragraphs))
    }

    @Test fun `五千字分窗保留段落边界且承接只来自上一窗`() {
        val lines = (0 until 25).map { "段$it：" + "甲".repeat(195) }
        val body = lines.joinToString("\n\n")
        assertTrue(body.length >= 5000)
        val windows = StageDirectorWindows.split(body)
        assertTrue(windows.size > 2)
        assertEquals(lines, windows.flatMap { it.paragraphs }.map { it.text })
        for ((i, window) in windows.withIndex()) {
            val current = window.paragraphs.joinToString("\n") { it.text }
            assertTrue(current.length <= StageDirectorWindows.MAX_CHARS)
            assertEquals(if (i == 0) "" else windows[i - 1].paragraphs.joinToString("\n") { it.text }.takeLast(400), window.continuation)
            for (paragraph in window.paragraphs) {
                assertEquals(paragraph.text, body.substring(paragraph.start, paragraph.end))
                assertTrue(windows.drop(i + 1).all { next -> next.paragraphs.none { it.index == paragraph.index } })
            }
        }
    }

    @Test fun `空白段不编号超长单段不硬切也不越上限`() {
        val body = "\n  第一段  \r\n\n" + "长".repeat(2000) + "\n第三段\n"
        val paragraphs = StageDirections.paragraphs(body)
        assertEquals(listOf(0, 1, 2), paragraphs.map { it.index })
        assertEquals("第一段", paragraphs[0].text)
        val windows = StageDirectorWindows.split(body)
        assertEquals(listOf(0, 2), windows.flatMap { it.paragraphs }.map { it.index })
        assertEquals("", windows.last().continuation)
        assertTrue(StageDirectorWindows.split("\n  \n").isEmpty())
    }
}

/** 验证费用边界、缓存正文身份以及静默降级，不访问真实网络。 */
class StageDirectorCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(): Triple<StageDirector, AiClientFactory, ChatApiClient> {
        val context = mockk<Context>()
        every { context.filesDir } returns temporary.root
        val factory = mockk<AiClientFactory>()
        val client = mockk<ChatApiClient>()
        coEvery { factory.forRole(ModelRole.CHEAP) } returns ResolvedChatClient(client, ChatOptions.Default, mockk(), "cheap")
        coEvery { client.chat(any(), options = ChatOptions.Default) } returns """{"scenes":[{"p":0,"setting":"INTERIOR"}],"key":[]}"""
        return Triple(StageDirector(dagger.Lazy { factory }, context), factory, client)
    }

    @Test fun `逐章缓存命中不解析模型且同正文哈希碰撞仍重新调用`() = runBlocking {
        val (director, factory, client) = fixture()
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val result = director.direct(7, 2, "", "Aa", null)
        assertEquals(result, director.cached(7, 2, "Aa"))
        assertEquals(result, director.direct(7, 2, "", "Aa", null))
        coVerify(exactly = 1) { factory.forRole(ModelRole.CHEAP) }
        coVerify(exactly = 1) { client.chat(any(), options = ChatOptions.Default) }
        assertNull(director.cached(7, 2, "BB"))
        assertNotNull(director.direct(7, 2, "", "BB", null))
        assertNotNull(director.cached(7, 2, "BB"))
        coVerify(exactly = 2) { client.chat(any(), options = ChatOptions.Default) }
        assertNull(director.cached(8, 2, "BB"))
        assertNull(director.cached(7, 3, "BB"))
        assertFalse(temporary.root.walkTopDown().any { it.extension == "tmp" })
    }

    @Test fun `配置缺失与网络失败静默返回且不写失败缓存`() = runBlocking {
        val (director, factory, client) = fixture()
        coEvery { factory.forRole(ModelRole.CHEAP) } throws AiClientException.MissingKey("provider")
        assertNull(director.direct(1, 0, "", "正文", null))
        coVerify(exactly = 0) { client.chat(any(), any()) }
        coEvery { factory.forRole(ModelRole.CHEAP) } returns ResolvedChatClient(client, ChatOptions.Default, mockk(), "cheap")
        coEvery { client.chat(any(), any()) } throws java.io.IOException("offline")
        assertNull(director.direct(1, 0, "", "正文", null))
        assertNull(director.cached(1, 0, "正文"))
    }

    @Test fun `每次请求只含本窗与上一窗承接并使用同一模型选项`() = runBlocking {
        val (director, _, client) = fixture()
        val body = (0 until 25).joinToString("\n") { "第$it 段：" + "甲".repeat(195) }
        val windows = StageDirectorWindows.split(body)
        val requests = mutableListOf<List<ChatMessage>>()
        coEvery { client.chat(capture(requests), options = ChatOptions.Default) } returns """{"scenes":[],"key":[]}"""
        assertNotNull(director.direct(1, 0, "标题", body, StageEra.ANCIENT))
        assertEquals(windows.size, requests.size)
        for ((i, request) in requests.withIndex()) {
            assertEquals(StageDirector.SYSTEM_PROMPT, request.first().content)
            val user = request.last().content
            assertEquals(director.userPrompt("标题", StageEra.ANCIENT, windows[i]), user)
            for (paragraph in windows.drop(i + 1).flatMap { it.paragraphs }) assertFalse(user.contains("[${paragraph.index}] "))
        }
        val file = temporary.root.resolve("stage-director/1/0-${body.hashCode().toUInt().toString(16)}.json")
        file.writeText("broken")
        assertNull(director.cached(1, 0, body))
    }
}
