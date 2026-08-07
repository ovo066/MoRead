package com.mozhi.reader.ai.prompt

import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatPart
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.core.datastore.GlobalPromptInjectionPosition
import com.mozhi.reader.core.datastore.GlobalPromptPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalPromptInjectorTest {
    @Test
    fun `injects system presets around existing system`() {
        val result = GlobalPromptInjector.inject(
            listOf(ChatMessage(ChatRole.SYSTEM, "base"), ChatMessage(ChatRole.USER, "hello")),
            listOf(preset("before", GlobalPromptInjectionPosition.BEFORE_SYSTEM), preset("after", GlobalPromptInjectionPosition.AFTER_SYSTEM))
        )

        assertTrue(result.first().content.indexOf("before") < result.first().content.indexOf("base"))
        assertTrue(result.first().content.indexOf("after") > result.first().content.indexOf("base"))
    }

    @Test
    fun `injects latest multimodal user without changing older user`() {
        val history = listOf(
            ChatMessage(ChatRole.USER, "old"),
            ChatMessage(ChatRole.ASSISTANT, "answer"),
            ChatMessage(ChatRole.USER, "photo", parts = listOf(ChatPart.Image("data", "image/jpeg")))
        )
        val result = GlobalPromptInjector.inject(
            history,
            listOf(preset("rule", GlobalPromptInjectionPosition.AFTER_LAST_USER))
        )

        assertEquals("old", result.first().content)
        assertTrue(result.last().content.endsWith("rule"))
        assertTrue(result.last().parts.last() is ChatPart.Text)
    }

    private fun preset(prompt: String, position: GlobalPromptInjectionPosition) =
        GlobalPromptPreset("$prompt-$position", prompt, prompt, enabled = true, position = position)
}
