package com.mozhi.reader.ai.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePromptComposerTest {
    @Test
    fun novelAiInstructionRequiresDanbooruTags() {
        val instruction = ImagePromptComposer.systemInstruction(ImagePromptFormat.NOVELAI_DANBOORU)
        assertTrue(instruction.contains("Danbooru"))
        assertTrue(instruction.contains("No Chinese"))
    }

    @Test
    fun normalizesModelOutputIntoCommaSeparatedTags() {
        val tags = ImagePromptComposer.normalizeDanbooruTags(
            "```\nTags: masterpiece, 1girl\nreading_book, warm light.\n```"
        )
        assertEquals("masterpiece, 1girl, reading_book, warm light", tags)
        assertFalse(tags.contains("```"))
    }
}
