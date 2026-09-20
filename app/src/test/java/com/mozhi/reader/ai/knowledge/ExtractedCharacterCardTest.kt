package com.mozhi.reader.ai.knowledge

import com.mozhi.reader.ai.persona.SillyTavernCardParser
import org.junit.Assert.*
import org.junit.Test

class ExtractedCharacterCardTest {
    @Test fun exportedCardCanBeImportedWithoutInventedTraitsOrLosingEvidence() {
        val card = ExtractedCharacterCard("Alice", "读者，喜欢读书。\n（第 1 章依据：Alice opened the book.）")
        val imported = SillyTavernCardParser.parse(card.toJson().toByteArray())!!
        assertEquals(card.name, imported.name)
        assertTrue(imported.personality.contains(card.description))
        assertEquals("", imported.greeting)
        assertTrue(imported.worldBook.isEmpty())
    }
}
