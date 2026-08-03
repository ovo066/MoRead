package com.mozhi.reader.ai.agent

import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.database.entity.PersonaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 段评讨论串的上下文组装：锚点邻域防剧透截断、转写命名、系统提示词要素。 */
class AnnotationDiscussionContextTest {

    @Test
    fun neighborhoodExpandsAroundAnchorWithEllipses() {
        val text = "甲".repeat(100) + "锚点" + "乙".repeat(100)
        val result = extractAnchorNeighborhood(
            chapterText = text,
            start = 100,
            end = 102,
            readLimit = null,
            radius = 10
        )
        assertEquals("……" + "甲".repeat(10) + "锚点" + "乙".repeat(10) + "……", result)
    }

    @Test
    fun neighborhoodAtChapterHeadHasNoLeadingEllipsis() {
        val result = extractAnchorNeighborhood(
            chapterText = "开头锚点后文后文",
            start = 2,
            end = 4,
            readLimit = null,
            radius = 100
        )
        assertEquals("开头锚点后文后文", result)
    }

    @Test
    fun neighborhoodNeverCrossesReadLimitInCurrentChapter() {
        val text = "已读部分锚点未读的后半章不该出现"
        val anchorStart = text.indexOf("锚点")
        val result = extractAnchorNeighborhood(
            chapterText = text,
            start = anchorStart,
            end = anchorStart + 2,
            readLimit = anchorStart + 2,
            radius = 50
        )
        assertTrue(result.contains("已读部分锚点"))
        assertFalse(result.contains("未读"))
        // 截断在阅读位置上，不再补后省略号误导模型以为原文就到这
        assertFalse(result.endsWith("……"))
    }

    @Test
    fun transcriptNamesUserPersonaAndDeletedPersona() {
        val annotation = annotation(personaId = 7, note = "这里的雪是伏笔")
        val replies = listOf(
            reply(id = 1, personaId = null, content = "我觉得也是"),
            reply(id = 2, personaId = 99, content = "被删角色的话")
        )
        val transcript = buildDiscussionTranscript(
            annotation = annotation,
            replies = replies,
            personaNames = mapOf(7L to "观澜")
        )
        assertEquals(
            "观澜：这里的雪是伏笔\n用户：我觉得也是\n已删除角色：被删角色的话",
            transcript
        )
    }

    @Test
    fun transcriptShowsPlaceholderForPureHighlightOpener() {
        val transcript = buildDiscussionTranscript(
            annotation = annotation(personaId = null, note = ""),
            replies = emptyList(),
            personaNames = emptyMap()
        )
        assertEquals("用户：（只划了这段原文，没有写想法）", transcript)
    }

    @Test
    fun systemPromptCarriesPersonaSpoilerLineQuoteAndTranscript() {
        val prompt = buildDiscussionSystemPrompt(
            persona = persona(name = "观澜", personality = "冷静的文本细读者", isRoleplay = true),
            book = book(lastReadChapterIndex = 11),
            chapterTitle = "雪夜",
            chapterNumber = 3,
            quote = "窗外的雪下了一整夜",
            neighborhood = "……前文……窗外的雪下了一整夜……后文……",
            transcript = "用户：这里写得真好"
        )
        assertTrue(prompt.contains("「观澜」"))
        assertTrue(prompt.contains("冷静的文本细读者"))
        assertTrue(prompt.contains("《测试书》"))
        assertTrue(prompt.contains("第 12 章")) // 防剧透：进度线
        assertTrue(prompt.contains("第 3 章「雪夜」"))
        assertTrue(prompt.contains("窗外的雪下了一整夜"))
        assertTrue(prompt.contains("用户：这里写得真好"))
        assertTrue(prompt.contains("search_book"))
        // 扮演型角色不追加工具助手约束
        assertFalse(prompt.contains("阅读助手"))
    }

    @Test
    fun systemPromptAddsAssistantConstraintForToolPersona() {
        val prompt = buildDiscussionSystemPrompt(
            persona = persona(name = "书僮", personality = "", isRoleplay = false),
            book = book(lastReadChapterIndex = 0),
            chapterTitle = null,
            chapterNumber = 1,
            quote = "引文",
            neighborhood = "",
            transcript = "用户：？"
        )
        assertTrue(prompt.contains("阅读助手"))
    }

    private fun annotation(personaId: Long?, note: String) = AnnotationEntity(
        id = 1,
        bookId = 1,
        personaId = personaId,
        chapterIndex = 2,
        startCharOffset = 0,
        endCharOffset = 4,
        selectedText = "引文片段",
        note = note,
        createdAt = 0
    )

    private fun reply(id: Long, personaId: Long?, content: String) = AnnotationReplyEntity(
        id = id,
        annotationId = 1,
        personaId = personaId,
        contentMarkdown = content,
        createdAt = id
    )

    private fun persona(name: String, personality: String, isRoleplay: Boolean) = PersonaEntity(
        id = 7,
        name = name,
        personality = personality,
        isRoleplay = isRoleplay,
        createdAt = 0
    )

    private fun book(lastReadChapterIndex: Int) = BookEntity(
        id = 1,
        title = "测试书",
        author = "作者",
        coverPath = null,
        epubPath = "/tmp/a.epub",
        sourceType = BookSourceType.TXT,
        importedAt = 0,
        totalChapters = 20,
        lastReadChapterIndex = lastReadChapterIndex,
        lastReadCharOffset = 0
    )
}
