package com.mozhi.reader.ai.prompt

import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatPart
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.core.datastore.GlobalPromptInjectionPosition
import com.mozhi.reader.core.datastore.GlobalPromptPreset

/** 将启用的全局预设注入请求副本；持久化会话与用户原文保持不变。 */
object GlobalPromptInjector {
    fun inject(
        messages: List<ChatMessage>,
        presets: List<GlobalPromptPreset>
    ): List<ChatMessage> {
        val enabled = presets.filter { it.enabled && it.prompt.isNotBlank() }
        if (enabled.isEmpty()) return messages
        val output = messages.toMutableList()

        val beforeSystem = enabled.block(GlobalPromptInjectionPosition.BEFORE_SYSTEM)
        val afterSystem = enabled.block(GlobalPromptInjectionPosition.AFTER_SYSTEM)
        if (beforeSystem.isNotBlank() || afterSystem.isNotBlank()) {
            val systemIndex = output.indexOfFirst { it.role == ChatRole.SYSTEM }
            if (systemIndex >= 0) {
                val original = output[systemIndex]
                output[systemIndex] = original.copy(
                    content = listOf(beforeSystem, original.content, afterSystem)
                        .filter(String::isNotBlank)
                        .joinToString("\n\n")
                )
            } else {
                output.add(
                    0,
                    ChatMessage(
                        ChatRole.SYSTEM,
                        listOf(beforeSystem, afterSystem)
                            .filter(String::isNotBlank)
                            .joinToString("\n\n")
                    )
                )
            }
        }

        val beforeUser = enabled.block(GlobalPromptInjectionPosition.BEFORE_LAST_USER)
        val afterUser = enabled.block(GlobalPromptInjectionPosition.AFTER_LAST_USER)
        val userIndex = output.indexOfLast { it.role == ChatRole.USER }
        if (userIndex >= 0 && (beforeUser.isNotBlank() || afterUser.isNotBlank())) {
            output[userIndex] = output[userIndex].withUserInjection(beforeUser, afterUser)
        }
        return output
    }

    private fun List<GlobalPromptPreset>.block(position: GlobalPromptInjectionPosition): String =
        filter { it.position == position }
            .joinToString("\n") { "【全局预设·${it.name}】\n${it.prompt.trim()}" }

    private fun ChatMessage.withUserInjection(before: String, after: String): ChatMessage {
        val content = listOf(before, content, after).filter(String::isNotBlank).joinToString("\n\n")
        if (parts.isEmpty()) return copy(content = content)
        val nextParts = buildList {
            if (before.isNotBlank()) add(ChatPart.Text(before + "\n\n"))
            addAll(parts)
            if (after.isNotBlank()) add(ChatPart.Text("\n\n" + after))
        }
        return copy(content = content, parts = nextParts)
    }
}
