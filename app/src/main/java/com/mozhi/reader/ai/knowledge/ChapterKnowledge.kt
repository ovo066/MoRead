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
data class KnowledgeCharacter(val name: String, val facts: List<KnowledgeFact>,
    val attributes: List<KnowledgeCharacterAttribute> = emptyList(),
    val relationships: List<KnowledgeCharacterRelationship> = emptyList())

@Serializable
enum class CharacterAttributeKind { ALIAS, AGE, GENDER, IDENTITY, APPEARANCE }
@Serializable
data class KnowledgeCharacterAttribute(val kind: CharacterAttributeKind, val value: String, val fact: KnowledgeFact)
@Serializable
data class KnowledgeCharacterRelationship(val target: String, val relation: String, val fact: KnowledgeFact)

@Serializable
data class ChapterKnowledge(val summary: List<KnowledgeFact>, val characters: List<KnowledgeCharacter> = emptyList(), val outline: String = "") {
    val readableOutline: String get() = outline.ifBlank { summary.joinToString("\n\n") { it.text } }
}

data class VisibleChapterKnowledge(val entry: ChapterKnowledgeEntity, val content: ChapterKnowledge)

internal data class KnowledgePart(val start: Int, val text: String)

/** 从某章 [start] 处截出的一段已读正文，供子 agent 整理单个人物。 */
internal data class SourcePassage(val chapterIndex: Int, val start: Int, val text: String)

internal object ChapterKnowledgeCodec {
    const val PROMPT_VERSION = 2
    const val MAX_SOURCE_CHARS = 60_000
    const val PART_CHARS = 10_000

    @Serializable private data class DraftFact(val text: String, val quote: String)
    @Serializable private data class DraftAttribute(val kind: CharacterAttributeKind, val value: String, val quote: String)
    @Serializable private data class DraftRelationship(val target: String, val relation: String, val quote: String)
    @Serializable private data class DraftCharacter(val name: String, val facts: List<DraftFact>,
        val attributes: List<DraftAttribute> = emptyList(), val relationships: List<DraftRelationship> = emptyList())
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
        val clean = KnowledgeResponseJson.clean(raw)
        val draft = try { AiJson.decodeFromString<Draft>(clean) }
            catch (_: Exception) { throw IllegalArgumentException("整理结果格式不完整，请重试") }
        require(draft.summary.size in 1..8 && draft.characters.size <= 16) { "整理结果条目过多或缺少摘要" }
        return ChapterKnowledge(draft.summary.map { verify(it, part) }, verifyCharacters(draft.characters, part), validateOutline(draft.outline, maxOutlineChars))
    }

    fun parseCharacters(raw: String, part: KnowledgePart): List<KnowledgeCharacter> {
        require(raw.length <= 64_000) { "人物结果过长" }
        val draft = try { AiJson.decodeFromString<CharacterDraft>(KnowledgeResponseJson.characters(raw)) }
            catch (_: IllegalArgumentException) { throw IllegalArgumentException("人物结果格式不完整，请重试或提高模型输出上限") }
        return verifyCharacters(draft.characters, part)
    }

    fun validateOutline(value: String, limit: Int = 2400): String {
        val clean = value.trim()
        require(clean.length in 1..limit) { "请按要求生成长度适中的章节梗概" }
        require(!Regex("(?m)^\\s*(?:[-*•]|[0-9]+[.)、])\\s+").containsMatchIn(clean)) { "请写成连贯自然段，不要罗列分散要点" }
        return clean
    }

    /** 检索段落之间的分隔；引文跨过它就说明模型把两段拼在了一起。 */
    private const val PASSAGE_SEPARATOR = "\n\n〔……〕\n\n"

    /**
     * 子 agent 读的是从全书检索来的若干段落，不是一段连续正文。引文必须完整落在某一段里且全文唯一，
     * 再换算回（章节, 章内字符偏移），这样人物资料仍能逐条跳回原文。
     */
    fun parseCharactersAcross(raw: String, passages: List<SourcePassage>): List<Pair<Int, KnowledgeCharacter>> {
        require(passages.isNotEmpty()) { "没有可供核对的原文段落" }
        val joined = StringBuilder()
        val segments = ArrayList<IntRange>(passages.size)
        passages.forEach { passage ->
            if (joined.isNotEmpty()) joined.append(PASSAGE_SEPARATOR)
            val start = joined.length
            joined.append(passage.text)
            segments += start until joined.length
        }
        val verified = parseCharacters(raw, KnowledgePart(0, joined.toString()))
        fun locate(fact: KnowledgeFact): Pair<Int, KnowledgeFact> {
            val index = segments.indexOfFirst { fact.start >= it.first && fact.end <= it.last + 1 }
            require(index >= 0) { "引文跨越了两段原文，请只引用其中一段里的连续文字" }
            val passage = passages[index]
            val delta = passage.start - segments[index].first
            return passage.chapterIndex to fact.copy(start = fact.start + delta, end = fact.end + delta)
        }
        return verified.flatMap { person ->
            val facts = person.facts.map(::locate)
            val attributes = person.attributes.map { attribute -> locate(attribute.fact).let { (chapter, fact) -> chapter to attribute.copy(fact = fact) } }
            val relationships = person.relationships.map { relation -> locate(relation.fact).let { (chapter, fact) -> chapter to relation.copy(fact = fact) } }
            (facts.map { it.first } + attributes.map { it.first } + relationships.map { it.first }).distinct().sorted().map { chapter ->
                chapter to KnowledgeCharacter(
                    person.name,
                    facts.filter { it.first == chapter }.map { it.second },
                    attributes.filter { it.first == chapter }.map { it.second },
                    relationships.filter { it.first == chapter }.map { it.second }
                )
            }
        }
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
            require(character.attributes.size <= 12 && character.relationships.size <= 12) { "单个人物属性或关系过多" }
            val attributes = character.attributes.map { attribute ->
                val value = attribute.value.trim()
                require(value.length in 1..80) { "人物属性格式无效" }
                require(attribute.quote.contains(name)) { "属性引文需包含人物称呼" }
                if (attribute.kind == CharacterAttributeKind.ALIAS) require(attribute.quote.contains(value) && value != name) { "别名需在同一段原文中明确关联" }
                KnowledgeCharacterAttribute(attribute.kind, value, verify(DraftFact(value, attribute.quote), part))
            }.distinctBy { it.kind to it.value }
            val relationships = character.relationships.map { relationship ->
                val target = relationship.target.trim()
                val relation = relationship.relation.trim()
                require(target.length in 1..60 && relation.length in 1..80 && target != name) { "人物关系格式无效" }
                require(relationship.quote.contains(name) && relationship.quote.contains(target)) { "关系引文需包含双方姓名或稳定称呼" }
                KnowledgeCharacterRelationship(target, relation, verify(DraftFact("$name → $target：$relation", relationship.quote), part))
            }.distinctBy { it.target to it.relation }
            KnowledgeCharacter(name, character.facts.map { verify(it, part) }, attributes, relationships)
        }
    }

    fun merge(parts: List<ChapterKnowledge>): ChapterKnowledge = ChapterKnowledge(
        parts.flatMap { it.summary }.distinct(),
        parts.flatMap { it.characters }.groupBy { it.name }.map { (name, rows) ->
            KnowledgeCharacter(name, rows.flatMap { it.facts }.distinct(), rows.flatMap { it.attributes }.distinct(), rows.flatMap { it.relationships }.distinct())
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
