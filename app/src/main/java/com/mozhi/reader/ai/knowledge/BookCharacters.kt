package com.mozhi.reader.ai.knowledge

import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.core.database.entity.BookCharacterGuideEntity
import kotlinx.serialization.Serializable

@Serializable
data class CharacterEvidence(val chapterIndex: Int, val fact: KnowledgeFact)
@Serializable
data class BookCharacter(val name: String, val evidence: List<CharacterEvidence>)
@Serializable
data class BookCharacterGuide(
    val characters: List<BookCharacter>,
    val scannedChapters: Int,
    val sourceCharacters: Long,
    /** true = 只扫到阅读进度为止，资料不含未读情节；旧存档解码成 false（全书）。 */
    val progressBounded: Boolean = false
)
data class VisibleBookCharacters(val entry: BookCharacterGuideEntity, val guide: BookCharacterGuide)
data class BookCharactersSnapshot(val saved: VisibleBookCharacters? = null, val outdated: Boolean = false, val checkpointParts: Int = 0)

internal object BookCharactersCodec {
    const val PROMPT_VERSION = 1
    fun fromChapters(
        chapters: List<Pair<Int, List<KnowledgeCharacter>>>,
        scannedChapters: Int,
        sourceCharacters: Long,
        progressBounded: Boolean = false
    ): BookCharacterGuide {
        val accumulator = Accumulator()
        chapters.forEach { (index, people) -> accumulator.add(index, people) }
        return accumulator.guide(scannedChapters, sourceCharacters, progressBounded)
    }

    /** Process one source part at a time, retaining a bounded introduction and recent facts per person. */
    class Accumulator {
        private val people = linkedMapOf<String, List<CharacterEvidence>>()
        fun add(chapterIndex: Int, characters: List<KnowledgeCharacter>) {
            characters.forEach { person ->
                val distinct = (people[person.name].orEmpty() + person.facts.map { CharacterEvidence(chapterIndex, it) })
                    .distinctBy { it.fact.text }.sortedWith(compareBy({ it.chapterIndex }, { it.fact.start }))
                people[person.name] = if (distinct.size <= 16) distinct else distinct.take(4) + distinct.takeLast(12)
            }
        }
        fun guide(scannedChapters: Int, sourceCharacters: Long, progressBounded: Boolean = false) =
            BookCharacterGuide(people.map { (name, facts) -> BookCharacter(name, facts) }, scannedChapters,
                sourceCharacters, progressBounded)
    }

    fun visible(entry: BookCharacterGuideEntity?, revision: String): VisibleBookCharacters? {
        entry ?: return null
        if (entry.sourceRevision != revision || entry.promptVersion != PROMPT_VERSION) return null
        val guide = runCatching { AiJson.decodeFromString<BookCharacterGuide>(entry.contentJson) }.getOrNull() ?: return null
        if (guide.characters.any { it.name.isBlank() || it.evidence.any { e -> e.chapterIndex < 0 || e.fact.start < 0 ||
                e.fact.end <= e.fact.start || e.fact.end - e.fact.start != e.fact.quote.length } }) return null
        return VisibleBookCharacters(entry, guide)
    }
}
