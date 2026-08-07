package com.mozhi.reader.ai.client

import com.mozhi.reader.ai.prompt.GlobalPromptInjector
import com.mozhi.reader.core.datastore.GlobalPromptPreset
import kotlinx.coroutines.flow.Flow

/** CHAT 角色的轻量装饰器：让选词、伴读和段评共享同一套全局预设。 */
internal class GlobalPresetChatApiClient(
    private val delegate: ChatApiClient,
    private val presets: List<GlobalPromptPreset>
) : ChatApiClient {
    override fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        options: ChatOptions
    ): Flow<ChatDelta> = delegate.chatStream(GlobalPromptInjector.inject(messages, presets), tools, options)

    override suspend fun chat(messages: List<ChatMessage>, options: ChatOptions): String =
        delegate.chat(GlobalPromptInjector.inject(messages, presets), options)

    override suspend fun embed(texts: List<String>): List<FloatArray> = delegate.embed(texts)
}
