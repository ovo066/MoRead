package com.mozhi.reader.ai.knowledge

import org.junit.Assert.*
import org.junit.Test

class CharacterProfileTest {
    private val source = "林舟又名阿舟，今年十八岁。林舟是小满的哥哥。"
    private val raw = """{"characters":[{"name":"林舟","facts":[{"text":"林舟登场","quote":"林舟又名阿舟，今年十八岁。"}],
        "attributes":[{"kind":"ALIAS","value":"阿舟","quote":"林舟又名阿舟，今年十八岁。"},{"kind":"AGE","value":"十八岁","quote":"林舟又名阿舟，今年十八岁。"}],
        "relationships":[{"target":"小满","relation":"哥哥","quote":"林舟是小满的哥哥。"}]}]}"""

    @Test fun structuredFieldsUseVerifiedOffsetsAndExplicitAliasesMergeAcrossChapters() {
        val people = ChapterKnowledgeCodec.parseCharacters(raw, KnowledgePart(50, source))
        assertEquals(50, people.single().attributes.first().fact.start)
        val aliasFact = KnowledgeFact("阿舟守夜", "阿舟独自守夜。", 0, 7)
        val guide = BookCharactersCodec.fromChapters(listOf(0 to people, 1 to listOf(KnowledgeCharacter("阿舟", listOf(aliasFact)))), 2, 80)
        val person = guide.characters.single()
        assertEquals("林舟", person.name)
        assertEquals(listOf("阿舟"), person.aliases)
        assertEquals("哥哥", person.relationships.single().relation)
        assertEquals(2, person.evidence.size)
        val renamed = BookCharactersCodec.edit(guide, person.identity, "林先生", "手动备注").characters.single()
        assertEquals(person.attributes, renamed.attributes)
        assertTrue(ExtractedCharacterCard.from(renamed).description.contains("十八岁"))
    }

    @Test fun unrelatedOrInventedProfileQuotesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ChapterKnowledgeCodec.parseCharacters(raw.replace("林舟是小满的哥哥。", "他们是兄弟。"), KnowledgePart(0, source))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterKnowledgeCodec.parseCharacters(raw.replace("\"value\":\"阿舟\"", "\"value\":\"小舟\""), KnowledgePart(0, source))
        }
    }
}
