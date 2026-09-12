package com.mozhi.reader.ai.knowledge

import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.core.database.entity.ChapterKnowledgeEntity
import com.mozhi.reader.core.retrieval.ReadingScope
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class KnowledgeFact(val text: String, val quote: String, val start: Int, val end: Int)

@Serializable
data class KnowledgeCharacter(val name: String, val facts: List<KnowledgeFact>)

@Serializable
data class ChapterKnowledge(val summary: List<KnowledgeFact>, val characters: List<KnowledgeCharacter> = emptyList(), val outline: String = "") {
    val readableOutline: String get() = outline.ifBlank { summary.joinToString("\n\n") { it.text } }
}

data class VisibleChapterKnowledge(val entry: ChapterKnowledgeEntity, val content: ChapterKnowledge)

internal data class KnowledgePart(val start: Int, val text: String)

internal object ChapterKnowledgeCodec {
    const val PROMPT_VERSION = 2
    const val MAX_SOURCE_CHARS = 60_000
    const val PART_CHARS = 10_000

    @Serializable private data class DraftFact(val text: String, val quote: String)
    @Serializable private data class DraftCharacter(val name: String, val facts: List<DraftFact>)
    @Serializable private data class Draft(val outline: String, val summary: List<DraftFact>, val characters: List<DraftCharacter> = emptyList())
    @Serializable private data class CharacterDraft(val characters: List<DraftCharacter>)

    fun parts(source: String, enforceChapterLimit: Boolean = true): List<KnowledgePart> {
        require(source.isNotBlank() && (!enforceChapterLimit || source.length <= MAX_SOURCE_CHARS)) { "每章最多整理 60000 字已读内容" }
        val parts = mutableListOf<KnowledgePart>()
        var start = 0
        while (start < source.length) {
            var end = minOf(start + PART_CHARS, source.length)
            if (end < source.length && source[end - 1].isHighSurrogate() && source[end].isLowSurrogate()) end--
            if (end < source.length) {
                val boundary = (end - 1 downTo start + PART_CHARS / 2).firstOrNull { source[it] in "\n。！？.!?" }
                if (boundary != null) end = boundary + 1
            }
            parts += KnowledgePart(start, source.substring(start, end))
            start = end
        }
        return parts
    }

    /** All offsets are derived locally; fabricated, ambiguous and out-of-range quotes fail closed. */
    fun parse(raw: String, part: KnowledgePart, maxOutlineChars: Int = 2400): ChapterKnowledge {
        require(raw.length <= 64_000) { "整理结果过长" }
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val draft = try { AiJson.decodeFromString<Draft>(clean) }
            catch (_: Exception) { throw IllegalArgumentException("整理结果格式不完整，请重试") }
        require(draft.summary.size in 1..8 && draft.characters.size <= 16) { "整理结果条目过多或缺少摘要" }
        return ChapterKnowledge(draft.summary.map { verify(it, part) }, verifyCharacters(draft.characters, part), validateOutline(draft.outline, maxOutlineChars))
    }

    fun parseCharacters(raw: String, part: KnowledgePart): List<KnowledgeCharacter> {
        require(raw.length <= 64_000) { "人物结果过长" }
        val draft = AiJson.decodeFromString<CharacterDraft>(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        return verifyCharacters(draft.characters, part)
    }

    fun validateOutline(value: String, limit: Int = 2400): String {
        val clean = value.trim()
        require(clean.length in 1..limit) { "请按要求生成长度适中的章节梗概" }
        require(!Regex("(?m)^\\s*(?:[-*•]|[0-9]+[.)、])\\s+").containsMatchIn(clean)) { "请写成连贯自然段，不要罗列分散要点" }
        return clean
    }

    private fun verify(fact: DraftFact, part: KnowledgePart): KnowledgeFact {
        val text = fact.text.trim()
        val quote = fact.quote.trim()
        require(text.length in 1..600 && quote.length in 4..300) { "整理结果缺少简短描述或原文依据" }
        val start = part.text.indexOf(quote)
        require(start >= 0 && part.text.indexOf(quote, start + 1) == -1) { "部分引文无法唯一核对，请重新整理" }
        return KnowledgeFact(text, quote, part.start + start, part.start + start + quote.length)
    }

    private fun verifyCharacters(characters: List<DraftCharacter>, part: KnowledgePart): List<KnowledgeCharacter> {
        require(characters.size <= 24) { "单段人物过多，请保留有依据的主要人物" }
        return characters.map { character ->
            val name = character.name.trim()
            require(name.length in 1..60 && character.facts.size in 1..4) { "人物资料格式无效" }
            require(name !in setOf("他", "她", "我", "你", "他们", "她们", "旁白")) { "请使用人名或稳定称呼" }
            require(part.text.contains(name)) { "人物名称未出现在提供的原文中" }
            KnowledgeCharacter(name, character.facts.map { verify(it, part) })
        }
    }

    fun merge(parts: List<ChapterKnowledge>): ChapterKnowledge = ChapterKnowledge(
        parts.flatMap { it.summary }.distinct(),
        parts.flatMap { it.characters }.groupBy { it.name }.map { (name, rows) ->
            KnowledgeCharacter(name, rows.flatMap { it.facts }.distinct())
        }, parts.joinToString("\n\n") { it.readableOutline }
    )

    fun encode(value: ChapterKnowledge): String = AiJson.encodeToString(value)

    fun visible(entry: ChapterKnowledgeEntity, scope: ReadingScope, revision: String): VisibleChapterKnowledge? {
        if (entry.sourceRevision != revision || entry.promptVersion !in 1..PROMPT_VERSION || entry.sourceEnd <= 0 ||
            !scope.allowsChunk(entry.chapterIndex, 0, entry.sourceEnd)) return null
        val content = runCatching { AiJson.decodeFromString<ChapterKnowledge>(entry.contentJson) }.getOrNull() ?: return null
        val facts = content.summary + content.characters.flatMap { it.facts }
        if (facts.isEmpty() || facts.any { it.start < 0 || it.end <= it.start || it.end > entry.sourceEnd || it.end - it.start != it.quote.length }) return null
        return VisibleChapterKnowledge(entry, content)
    }

    fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
