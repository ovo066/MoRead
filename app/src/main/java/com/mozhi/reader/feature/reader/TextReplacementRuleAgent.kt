package com.mozhi.reader.feature.reader

import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.datastore.ReaderTextReplacementRule
import com.mozhi.reader.core.datastore.validationError
import com.mozhi.reader.core.library.LibraryRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Uses representative excerpts from every chapter to draft one safe, user-editable cleanup rule. */
@Singleton
class TextReplacementRuleAgent @Inject constructor(
    private val clientFactory: AiClientFactory,
    private val libraryRepository: LibraryRepository
) {
    suspend fun propose(bookId: Long, requirement: String): ReaderTextReplacementRule {
        val cleanRequirement = requirement.trim()
        require(cleanRequirement.isNotBlank()) { "请先说明要清理什么文本" }
        val excerpts = collectExcerpts(bookId)
        require(excerpts.isNotBlank()) { "这本书没有可分析的正文" }
        val resolved = clientFactory.forRole(ModelRole.CHAT)
        val raw = resolved.client.chat(
            messages = listOf(
                ChatMessage(ChatRole.SYSTEM, SYSTEM_PROMPT),
                ChatMessage(
                    ChatRole.USER,
                    buildString {
                        append("【用户需求】\n").append(cleanRequirement)
                        append("\n\n【全书分章取样】\n").append(excerpts)
                    }
                )
            ),
            options = resolved.options.copy(reasoning = null)
        )
        val draft = parseDraft(raw)
        val rule = ReaderTextReplacementRule(
            id = 0L,
            name = draft.name.trim().ifBlank { "AI 清洗规则" }.take(48),
            pattern = draft.pattern.trim(),
            replacement = draft.replacement.take(MAX_REPLACEMENT_CHARS),
            ignoreCase = draft.ignoreCase
        )
        rule.validationError()?.let { error("AI 生成的规则无效：$it") }
        return rule
    }

    private suspend fun collectExcerpts(bookId: Long): String {
        val chapters = libraryRepository.getChapters(bookId)
        if (chapters.isEmpty()) return ""
        val perChapter = (MAX_BOOK_SAMPLE_CHARS / chapters.size)
            .coerceIn(1, MAX_CHAPTER_SAMPLE_CHARS)
        return buildString {
            chapters.forEach { chapter ->
                val body = libraryRepository.readChapterText(bookId, chapter)
                if (body.isBlank()) return@forEach
                append("\n\n【第 ").append(chapter.chapterIndex + 1).append(" 章：")
                    .append(chapter.title).append("】\n")
                append(body.sampleForRuleAgent(perChapter))
            }
        }.take(MAX_BOOK_SAMPLE_CHARS)
    }

    private fun String.sampleForRuleAgent(limit: Int): String {
        if (length <= limit) return this
        val first = (limit * 0.7f).toInt()
        val last = (limit - first).coerceAtLeast(1)
        return take(first) + "\n…（本章中段略）…\n" + takeLast(last)
    }

    private fun parseDraft(raw: String): RuleDraft {
        val candidate = raw.trim().removeCodeFence()
        val jsonText = candidate.substring(
            candidate.indexOf('{').takeIf { it >= 0 } ?: 0,
            (candidate.lastIndexOf('}').takeIf { it >= 0 } ?: candidate.lastIndex) + 1
        )
        return runCatching { JSON.decodeFromString(RuleDraft.serializer(), jsonText) }
            .getOrElse { error("AI 没有返回可用的规则 JSON，请重试或手动填写") }
    }

    private fun String.removeCodeFence(): String = trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

    @Serializable
    private data class RuleDraft(
        val name: String = "AI 清洗规则",
        val pattern: String = "",
        val replacement: String = "",
        val ignoreCase: Boolean = false
    )

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        const val MAX_BOOK_SAMPLE_CHARS = 72_000
        const val MAX_CHAPTER_SAMPLE_CHARS = 4_000
        const val MAX_REPLACEMENT_CHARS = 4_000
        val SYSTEM_PROMPT = """
            You create safe Kotlin/Java regex cleanup rules for a local ebook reader.
            Inspect the supplied excerpts and satisfy the user's cleanup request without removing normal story text.
            Return exactly one JSON object and nothing else:
            {"name":"short Chinese name","pattern":"Kotlin regex","replacement":"replacement text, empty to delete","ignoreCase":false}
            The regex will be applied independently to each chapter with MULTILINE enabled. Prefer narrow anchors and explicit
            ad/contact/QQ/group patterns over broad wildcards. Do not use destructive rules unless the excerpts demonstrate them.
        """.trimIndent()
    }
}
