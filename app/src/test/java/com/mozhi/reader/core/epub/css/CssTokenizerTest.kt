package com.mozhi.reader.core.epub.css

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CssTokenizerTest {
    @Test
    fun `tokenizer preserves dimensions percentages signs and decimals`() {
        val tokens = CssTokenizer.tokenize("-2px 15% +.5em 1.25 /* ignored */")
            .filter { it.type !in setOf(CssTokenType.WHITESPACE, CssTokenType.EOF) }

        assertEquals(listOf(CssTokenType.DIMENSION, CssTokenType.PERCENTAGE, CssTokenType.DIMENSION, CssTokenType.NUMBER), tokens.map { it.type })
        assertEquals(-2f, tokens[0].number)
        assertEquals("px", tokens[0].unit)
        assertEquals(15f, tokens[1].number)
        assertEquals(.5f, tokens[2].number)
        assertFalse(tokens.any { it.text.contains("ignored") })
    }

    @Test
    fun `strings escapes hashes and quoted or unquoted urls are tokenized`() {
        val tokens = CssTokenizer.tokenize("\"line\\a break\" #abc url(icon.png) url('two space.png')")
            .filter { it.type !in setOf(CssTokenType.WHITESPACE, CssTokenType.EOF) }

        assertEquals(CssTokenType.STRING, tokens[0].type)
        assertTrue(tokens[0].text.startsWith("line"))
        assertEquals(CssToken(CssTokenType.HASH, "abc"), tokens[1])
        assertEquals(listOf("icon.png", "two space.png"), tokens.drop(2).map { it.text })
    }
}
