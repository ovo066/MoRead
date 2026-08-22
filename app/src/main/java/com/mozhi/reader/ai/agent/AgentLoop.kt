package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.AiClientException
import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ChatDelta
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatOptions
import com.mozhi.reader.ai.client.ChatPart
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.ai.client.ToolCall
import com.mozhi.reader.ai.client.ToolSpec
import com.mozhi.reader.ai.memory.RollingSummarizer
import com.mozhi.reader.core.database.dao.ChatDao
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.library.AttachmentStore
import com.mozhi.reader.core.library.MessageAttachment
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** What the UI sees while the agent works. */
sealed interface AgentEvent {
    /** Incremental assistant text of the current round. */
    data class Text(val text: String) : AgentEvent

    /**
     * 当前工具轮次的文字已持久化；UI 应在此刻将实时气泡交接给消息列表。
     * 显式轮次边界避免同一段文字同时以「已落库 + 流式」渲染两次。
     */
    data class RoundCommitted(val message: MessageEntity) : AgentEvent

    /** 工具开始执行；[callId] 让界面能把开始/完成更新到同一条时间线。 */
    data class ToolRun(
        val callId: String,
        val toolName: String,
        val displayName: String
    ) : AgentEvent

    /** 工具执行结束；只给界面安全的状态摘要，不暴露整段检索原文。 */
    data class ToolFinished(
        val callId: String,
        val toolName: String,
        val displayName: String,
        val succeeded: Boolean,
        val detail: String
    ) : AgentEvent
}

/**
 * The agent loop of DEVELOPMENT_PLAN §4.4: stream a turn, execute any requested tools locally,
 * append their results, and go again — at most [MAX_ROUNDS] rounds. Every assistant turn and tool
 * result is persisted immediately, so a cancelled loop leaves a consistent, resumable history.
 * With no tools this degenerates to plain streaming with persistence.
 */
@Singleton
class AgentLoop @Inject constructor(
    private val chatDao: ChatDao,
    private val clientFactory: dagger.Lazy<AiClientFactory>,
    private val attachmentStore: dagger.Lazy<AttachmentStore>,
    private val rollingSummarizer: dagger.Lazy<RollingSummarizer>
) {
    /**
     * Streams one agent turn for the conversation's current history.
     *
     * [systemPrompt] 非空时在本轮用它替换历史里的 system 消息（不落库）：
     * 伴读会话每轮由 ContextBuilder 按最新进度/记忆重建系统提示词，
     * 落库的那份只是会话创建时的快照。
     */
    fun run(
        conversationId: Long,
        tools: List<AgentTool>,
        systemPrompt: String? = null,
        modelRole: ModelRole = ModelRole.CHAT
    ): Flow<AgentEvent> = runWith(conversationId, tools, systemPrompt) {
        val resolved = clientFactory.get().forRole(modelRole)
        Streamer { messages, specs ->
            resolved.client.chatStream(messages, specs, resolved.options)
        }
    }

    /** One resolved model turn; injectable for tests. */
    fun interface Streamer {
        fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<ChatDelta>
    }

    /**
     * 段评讨论串等轻量场景的独立循环：历史由调用方内存组装，任何消息都不落
     * conversations/messages（讨论内容有自己的 annotation_replies 归宿，也不该
     * 污染统计口径与记忆固化）。工具执行与事件流和 [run] 同构。
     */
    fun runDetached(
        history: List<ChatMessage>,
        tools: List<AgentTool>,
        maxRounds: Int = DETACHED_MAX_ROUNDS,
        modelRole: ModelRole = ModelRole.CHAT
    ): Flow<AgentEvent> = runDetachedWith(history, tools, maxRounds) {
        val resolved = clientFactory.get().forRole(modelRole)
        Streamer { messages, specs ->
            resolved.client.chatStream(messages, specs, resolved.options)
        }
    }

    internal fun runDetachedWith(
        history: List<ChatMessage>,
        tools: List<AgentTool>,
        maxRounds: Int,
        resolve: suspend () -> Streamer
    ): Flow<AgentEvent> = flow {
        if (history.none { it.role == ChatRole.USER }) throw AiClientException.Empty()
        val working = history.toMutableList()
        val streamer = resolve()
        val specs = tools.map { it.spec }
        val byName = tools.associateBy { it.spec.name }
        var producedText = false

        repeat(maxRounds.coerceAtLeast(1)) { round ->
            val text = StringBuilder()
            var requested: List<ToolCall> = emptyList()
            streamer.stream(working, specs).collect { delta ->
                when (delta) {
                    is ChatDelta.Text -> {
                        text.append(delta.text)
                        emit(AgentEvent.Text(delta.text))
                    }
                    is ChatDelta.ToolCalls -> requested = delta.calls
                }
            }

            if (requested.isEmpty()) {
                if (text.isBlank() && !producedText) throw AiClientException.Empty()
                return@flow
            }

            if (text.isNotBlank()) producedText = true
            working.add(ChatMessage(ChatRole.ASSISTANT, text.toString(), toolCalls = requested))
            for (call in requested) {
                val tool = byName[call.name]
                val displayName = tool?.displayName ?: "调用 ${call.name}"
                emit(AgentEvent.ToolRun(call.id, call.name, displayName))
                val result = if (tool == null) {
                    "未知工具：${call.name}"
                } else {
                    try {
                        tool.execute(call.argumentsObject())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        "工具执行失败：${error.message ?: "未知错误"}"
                    }
                }
                val succeeded = result.isToolSuccess()
                emit(
                    AgentEvent.ToolFinished(
                        callId = call.id,
                        toolName = call.name,
                        displayName = displayName,
                        succeeded = succeeded,
                        detail = if (succeeded) "已完成" else result.take(MAX_TOOL_STATUS_CHARS)
                    )
                )
                working.add(ChatMessage(ChatRole.TOOL, result, toolCallId = call.id))
            }

            if (round == maxRounds - 1 && !producedText) {
                val notice = "（已达到工具调用上限，回复基于目前掌握的信息）"
                emit(AgentEvent.Text(notice))
            }
        }
    }

    internal fun runWith(
        conversationId: Long,
        tools: List<AgentTool>,
        systemPrompt: String? = null,
        resolve: suspend () -> Streamer
    ): Flow<AgentEvent> = flow {
        val entities = chatDao.getMessages(conversationId)
        val conversation = chatDao.getConversation(conversationId)
        val consolidatedThrough = conversation?.memoryConsolidatedThroughMessageId ?: 0L
        val persistedSystem = entities
            .takeWhile { it.role == ChatRole.SYSTEM.wire }
            .map(::toChatMessage)
        // 只有最新一条 user 消息重发真实图片/文件内容，历史轮降级为占位标记控成本
        val lastUserId = entities.lastOrNull { it.role == ChatRole.USER.wire }?.id
        val persisted = entities
            .filter { it.id > consolidatedThrough && it.role != ChatRole.SYSTEM.wire }
            .map { entity ->
                if (entity.id == lastUserId) {
                    toChatMessage(entity).copy(parts = hydrateParts(entity))
                } else {
                    toChatMessage(entity)
                }
            }
        val system = systemPrompt?.let { listOf(ChatMessage(ChatRole.SYSTEM, it)) }
            ?: persistedSystem
        // 前情提要补的是「已滑出 20 条窗口、又没攒够 30 条去固化」那段的裂缝。
        // 与 systemPrompt 覆写同一纪律：只在组装请求时注入，永不作为消息落库。
        val summary = rollingSummarizer.get()
            .block(conversation?.rollingSummary.orEmpty())
            ?.let { listOf(ChatMessage(ChatRole.SYSTEM, it)) }
            .orEmpty()
        val rest = persisted
        val history = (system + summary + rest.drop(windowStart(rest))).toMutableList()
        if (history.none { it.role == ChatRole.USER }) throw AiClientException.Empty()
        val streamer = resolve()
        val specs = tools.map { it.spec }
        val byName = tools.associateBy { it.spec.name }
        var producedText = false

        repeat(MAX_ROUNDS) { round ->
            val text = StringBuilder()
            var requested: List<ToolCall> = emptyList()
            streamer.stream(history, specs).collect { delta ->
                when (delta) {
                    is ChatDelta.Text -> {
                        text.append(delta.text)
                        emit(AgentEvent.Text(delta.text))
                    }
                    is ChatDelta.ToolCalls -> requested = delta.calls
                }
            }

            if (requested.isEmpty()) {
                val reply = text.toString()
                if (reply.isBlank() && !producedText) throw AiClientException.Empty()
                if (reply.isNotBlank()) persistAssistant(conversationId, reply, toolCalls = emptyList())
                return@flow
            }

            if (text.isNotBlank()) producedText = true
            val committedMessage = persistAssistant(conversationId, text.toString(), requested)
            if (committedMessage != null) {
                emit(AgentEvent.RoundCommitted(committedMessage))
            }
            history.add(ChatMessage(ChatRole.ASSISTANT, text.toString(), toolCalls = requested))

            for (call in requested) {
                val tool = byName[call.name]
                val displayName = tool?.displayName ?: "调用 ${call.name}"
                emit(
                    AgentEvent.ToolRun(
                        callId = call.id,
                        toolName = call.name,
                        displayName = displayName
                    )
                )
                val result = if (tool == null) {
                    "未知工具：${call.name}"
                } else {
                    try {
                        tool.execute(call.argumentsObject())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        "工具执行失败：${error.message ?: "未知错误"}"
                    }
                }
                val succeeded = result.isToolSuccess()
                emit(
                    AgentEvent.ToolFinished(
                        callId = call.id,
                        toolName = call.name,
                        displayName = displayName,
                        succeeded = succeeded,
                        detail = if (succeeded) "已完成" else result.take(MAX_TOOL_STATUS_CHARS)
                    )
                )
                val toolCompletedAt = System.currentTimeMillis()
                chatDao.insertMessage(
                    MessageEntity(
                        conversationId = conversationId,
                        role = ChatRole.TOOL.wire,
                        content = result,
                        toolCallId = call.id,
                        createdAt = toolCompletedAt
                    )
                )
                chatDao.touchConversation(conversationId, toolCompletedAt)
                history.add(ChatMessage(ChatRole.TOOL, result, toolCallId = call.id))
            }

            if (round == MAX_ROUNDS - 1) {
                val notice = "（已达到单轮工具调用上限，回复基于目前掌握的信息）"
                emit(AgentEvent.Text(notice))
                persistAssistant(conversationId, notice, emptyList())
            }
        }
    }

    private suspend fun persistAssistant(
        conversationId: Long,
        content: String,
        toolCalls: List<ToolCall>
    ): MessageEntity? {
        if (content.isBlank() && toolCalls.isEmpty()) return null
        val now = System.currentTimeMillis()
        val message = MessageEntity(
            conversationId = conversationId,
            role = ChatRole.ASSISTANT.wire,
            content = content,
            toolCallsJson = toolCalls.takeIf { it.isNotEmpty() }
                ?.let { AiJson.encodeToString(ListSerializer(ToolCall.serializer()), it) },
            createdAt = now
        )
        val messageId = chatDao.insertMessage(message)
        chatDao.touchConversation(conversationId, now)
        return message.copy(id = messageId)
    }

    /**
     * §4.5 的对话窗口：只带最近约 [WINDOW_MESSAGES] 条进请求。
     * 窗口起点必须落在 user 消息上——从中间切开会产生没有对应调用的 tool 结果，
     * 各方言都会拒绝。找不到合适起点时宁可整段带上。
     */
    private fun windowStart(rest: List<ChatMessage>): Int {
        if (rest.size <= WINDOW_MESSAGES) return 0
        val desired = rest.size - WINDOW_MESSAGES
        for (i in desired downTo 0) if (rest[i].role == ChatRole.USER) return i
        for (i in desired + 1 until rest.size) if (rest[i].role == ChatRole.USER) return i
        return 0
    }

    private fun toChatMessage(entity: MessageEntity): ChatMessage = ChatMessage(
        role = ChatRole.fromWire(entity.role),
        content = contentWithAttachmentMarks(entity),
        toolCalls = entity.toolCallsJson?.let { json ->
            runCatching {
                AiJson.decodeFromString(ListSerializer(ToolCall.serializer()), json)
            }.getOrNull()
        }.orEmpty(),
        toolCallId = entity.toolCallId
    )

    /** 历史消息的附件降级为占位标记，避免每轮重发 base64。 */
    private fun contentWithAttachmentMarks(entity: MessageEntity): String {
        val attachments = MessageAttachment.decode(entity.attachmentsJson)
        if (attachments.isEmpty()) return entity.content
        val marks = attachments.joinToString(" ") { attachment ->
            if (attachment.type == MessageAttachment.TYPE_IMAGE) {
                "[图片]"
            } else {
                "[文件：${attachment.name ?: "附件"}]"
            }
        }
        return listOf(marks, entity.content).filter(String::isNotBlank).joinToString("\n")
    }

    /** 最新 user 轮：图片转 base64 part，文本文件内容并入 text part。 */
    private suspend fun hydrateParts(entity: MessageEntity): List<ChatPart> {
        val attachments = MessageAttachment.decode(entity.attachmentsJson)
        if (attachments.isEmpty()) return emptyList()
        val parts = mutableListOf<ChatPart>()
        attachments.filter { it.type == MessageAttachment.TYPE_IMAGE }.forEach { attachment ->
            attachmentStore.get().loadImageBase64(attachment)?.let { base64 ->
                parts += ChatPart.Image(base64 = base64, mimeType = attachment.mime)
            }
        }
        val fileTexts = attachments
            .filter { it.type == MessageAttachment.TYPE_TEXT_FILE }
            .mapNotNull { attachment ->
                attachmentStore.get().loadTextContent(attachment)?.let { text ->
                    "【附件 ${attachment.name ?: "文件"}】\n$text"
                }
            }
        val text = (fileTexts + entity.content).filter(String::isNotBlank).joinToString("\n\n")
        parts += ChatPart.Text(text.ifBlank { entity.content })
        return parts
    }

    private companion object {
        const val MAX_ROUNDS = 8
        const val DETACHED_MAX_ROUNDS = 3
        const val WINDOW_MESSAGES = 20
        const val MAX_TOOL_STATUS_CHARS = 120
    }
}

private fun ToolCall.argumentsObject(): JsonObject =
    runCatching { AiJson.parseToJsonElement(arguments).jsonObject }.getOrNull()
        ?: JsonObject(emptyMap())

private fun String.isToolSuccess(): Boolean {
    val normalized = trimStart()
    return !normalized.startsWith("工具执行失败") &&
        !normalized.startsWith("未知工具") &&
        !normalized.startsWith("缺少") &&
        !normalized.startsWith("超出") &&
        !normalized.startsWith("未找到") &&
        !normalized.startsWith("章节范围无效") &&
        !normalized.contains("无法确定") &&
        !normalized.contains("找不到这段 quote") &&
        !normalized.contains("检索不可用") &&
        !normalized.contains("尚未配置") &&
        !normalized.contains("还没有建成向量索引")
}
