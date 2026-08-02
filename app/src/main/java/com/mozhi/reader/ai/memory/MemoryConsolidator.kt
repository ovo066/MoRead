package com.mozhi.reader.ai.memory

import com.mozhi.reader.ai.client.AiClientException
import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.core.database.dao.ChatDao
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.vector.Embeddings
import com.mozhi.reader.core.vector.MemoryEntry
import com.mozhi.reader.core.vector.VectorQueries
import io.objectbox.BoxStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

sealed interface MemoryConsolidationOutcome {
    data class Completed(val batches: Int, val memories: Int) : MemoryConsolidationOutcome
    data object NotReady : MemoryConsolidationOutcome
    data class Skipped(val reason: String) : MemoryConsolidationOutcome
    data class Failed(val error: Throwable) : MemoryConsolidationOutcome
}

internal data class MemoryBatch(
    val messages: List<MessageEntity>,
    val throughMessageId: Long
)

/** §4.6：常规每 30 条；会话关闭时剩余至少 10 条也固化。 */
internal object MemoryBatchPlanner {
    fun plan(
        messages: List<MessageEntity>,
        consolidatedThrough: Long,
        forceOnClose: Boolean
    ): MemoryBatch? {
        val candidates = messages.filter {
            it.id > consolidatedThrough &&
                it.content.isNotBlank() &&
                (it.role == ChatRole.USER.wire || it.role == ChatRole.ASSISTANT.wire)
        }
        val threshold = if (forceOnClose) CLOSE_THRESHOLD else BATCH_SIZE
        if (candidates.size < threshold) return null
        val selected = candidates.take(BATCH_SIZE)
        return MemoryBatch(selected, selected.last().id)
    }

    fun transcript(batch: MemoryBatch): String = buildString {
        batch.messages.forEach { message ->
            val label = if (message.role == ChatRole.USER.wire) "用户" else "我"
            append(label)
            append("：")
            append(message.content.take(MAX_MESSAGE_CHARS))
            append('\n')
            if (length >= MAX_TRANSCRIPT_CHARS) return@buildString
        }
    }.take(MAX_TRANSCRIPT_CHARS)

    const val BATCH_SIZE = 30
    const val CLOSE_THRESHOLD = 10
    private const val MAX_MESSAGE_CHARS = 2_000
    private const val MAX_TRANSCRIPT_CHARS = 30_000
}

internal object MemorySummaryParser {
    fun parse(raw: String): List<String> {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val direct = decode(trimmed)
        if (direct.isNotEmpty() || trimmed == "[]") return direct
        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        return if (start >= 0 && end > start) decode(trimmed.substring(start, end + 1)) else emptyList()
    }

    private fun decode(json: String): List<String> = runCatching {
        when (val root = AiJson.parseToJsonElement(json)) {
            is JsonArray -> root
            is JsonObject -> root["memories"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }.mapNotNull { it.jsonPrimitive.contentOrNull }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(5)
            .map { it.take(500) }
    }.getOrDefault(emptyList())
}

/**
 * CHEAP 总结 → EMBEDDING 向量化 → MemoryEntry。ObjectBox 先写、Room 水位后推进；
 * 若两步之间进程退出，重跑用 (conversationId, sourceMessageId) 检测已写批次，避免重复。
 */
@Singleton
class MemoryConsolidator @Inject constructor(
    private val chatDao: ChatDao,
    private val clientFactory: AiClientFactory,
    private val vectorStore: BoxStore
) {
    suspend fun consolidateAvailable(
        conversationId: Long,
        forceOnClose: Boolean = false
    ): MemoryConsolidationOutcome {
        val conversation = chatDao.getConversation(conversationId)
            ?: return MemoryConsolidationOutcome.Skipped("会话不存在")
        val personaId = conversation.personaId
            ?: return MemoryConsolidationOutcome.Skipped("无角色会话不产生长期记忆")
        var watermark = conversation.memoryConsolidatedThroughMessageId
        var batch = MemoryBatchPlanner.plan(
            chatDao.getMessages(conversationId),
            watermark,
            forceOnClose
        ) ?: return MemoryConsolidationOutcome.NotReady

        val cheap = try {
            clientFactory.forRole(ModelRole.CHEAP)
        } catch (error: Throwable) {
            if (error.isConfigurationIssue()) {
                return MemoryConsolidationOutcome.Skipped(error.message.orEmpty())
            }
            return MemoryConsolidationOutcome.Failed(error)
        }
        val embedding = try {
            clientFactory.forRole(ModelRole.EMBEDDING)
        } catch (error: Throwable) {
            if (error.isConfigurationIssue()) {
                return MemoryConsolidationOutcome.Skipped(error.message.orEmpty())
            }
            return MemoryConsolidationOutcome.Failed(error)
        }

        var batches = 0
        var memories = 0
        return try {
            while (true) {
                if (!VectorQueries.hasMemoryBatch(vectorStore, conversationId, batch.throughMessageId)) {
                    val summaries = summarize(batch, cheap.client, cheap.options)
                    if (summaries.isNotEmpty()) {
                        val vectors = embedding.client.embed(summaries)
                        check(vectors.size == summaries.size) { "记忆 embedding 数量与条目数不一致" }
                        val now = System.currentTimeMillis()
                        val entries = summaries.mapIndexed { index, summary ->
                            MemoryEntry().also { entry ->
                                entry.personaId = personaId
                                entry.bookId = conversation.bookId
                                entry.conversationId = conversationId
                                entry.sourceMessageId = batch.throughMessageId
                                entry.summary = summary
                                entry.sourceType = SOURCE_TYPE
                                entry.createdAt = now + index
                                entry.embedding = Embeddings.conformToIndex(vectors[index])
                            }
                        }
                        vectorStore.runInTx { vectorStore.boxFor(MemoryEntry::class.java).put(entries) }
                        memories += entries.size
                    }
                }
                chatDao.advanceMemoryConsolidationWatermark(conversationId, batch.throughMessageId)
                watermark = batch.throughMessageId
                batches++
                batch = MemoryBatchPlanner.plan(
                    chatDao.getMessages(conversationId),
                    watermark,
                    forceOnClose
                ) ?: break
            }
            MemoryConsolidationOutcome.Completed(batches, memories)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            MemoryConsolidationOutcome.Failed(error)
        }
    }

    private suspend fun summarize(
        batch: MemoryBatch,
        client: com.mozhi.reader.ai.client.ChatApiClient,
        options: com.mozhi.reader.ai.client.ChatOptions
    ): List<String> {
        val response = client.chat(
            messages = listOf(
                ChatMessage(
                    ChatRole.SYSTEM,
                    """
                    你负责把伴读对话固化为长期记忆。只提取未来交流仍有用的用户偏好、事实、约定与共同经历；
                    不记录临时寒暄，不猜测，不补充对话外信息。用角色第一人称表述，例如“用户告诉我……”。
                    只输出 JSON 字符串数组，0 到 5 条，每条独立且简洁，不要 Markdown。
                    """.trimIndent()
                ),
                ChatMessage(ChatRole.USER, MemoryBatchPlanner.transcript(batch))
            ),
            options = options
        )
        return MemorySummaryParser.parse(response)
    }

    private fun Throwable.isConfigurationIssue(): Boolean =
        this is AiClientException.NotConfigured ||
            this is AiClientException.MissingKey ||
            this is AiClientException.InvalidKey ||
            this is AiClientException.Unsupported ||
            this is IllegalArgumentException

    private companion object {
        const val SOURCE_TYPE = "CHAT_SUMMARY"
    }
}
