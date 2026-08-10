package com.mozhi.reader.feature.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiChapterRuleAgentTest {
    private val splitter = TxtChapterSplitter()

    @Test
    fun `parser accepts fenced json and preserves escaped regex`() {
        val draft = AiChapterRuleResponseParser.parse(
            """
            ```json
            {"name":"数字章节","regex":"^第\\d+章\\s+.*$","reason":"样本结构重复"}
            ```
            """.trimIndent()
        )

        requireNotNull(draft)
        assertEquals("数字章节", draft.name)
        assertEquals("^第\\d+章\\s+.*$", draft.regex)
    }

    @Test
    fun `sampler covers whole book and redacts semantic words`() {
        val text = buildString {
            repeat(120) { index ->
                appendLine("第${index + 1}章 秘密人物${index + 1}")
                appendLine("这里包含不应发送给模型的剧情秘密人物。")
            }
        }

        val sample = AiChapterRuleSampler.sample(text)

        assertTrue(sample.contains("位置 0%"))
        assertTrue(sample.contains("位置 50%"))
        assertTrue(sample.contains("位置 100%"))
        assertTrue(sample.contains("第0章"))
        assertFalse(sample.contains("秘密人物"))
        assertFalse(sample.contains("剧情"))
    }

    @Test
    fun `validator accepts chapter lines and rejects a rule matching every line`() {
        val text = buildString {
            repeat(8) { index ->
                appendLine("第${index + 1}章 标题")
                appendLine("这是足够长的章节正文，用于确保本地验证不会把正常章节误判为过短。".repeat(3))
            }
        }

        val valid = AiChapterRuleValidator.validate(text, "^第\\d+章\\s+.*$", splitter)
        val invalid = AiChapterRuleValidator.validate(text, "^.*$", splitter)

        assertTrue(valid is AiChapterRuleValidation.Valid)
        assertTrue(invalid is AiChapterRuleValidation.Invalid)
    }
}
