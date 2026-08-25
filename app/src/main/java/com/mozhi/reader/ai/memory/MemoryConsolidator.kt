package com.mozhi.reader.ai.memory

import com.mozhi.reader.ai.client.AiClientException
import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ChatApiClient
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatOptions
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.core.database.dao.ChatDao
import com.mozhi.reader.core.database.dao.PersonaDao
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.datastore.CompanionMemorySettings
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.datastore.UserMaskStore
import com.mozhi.reader.core.vector.Embeddings
import com.mozhi.reader.core.vector.MemoryEntry
import com.mozhi.reader.core.vector.VectorQueries
import io.objectbox.BoxStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
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
) {
    /**
     * 这批消息发出时用户戴着哪个面具。同一批里混用面具很罕见（要在几十条消息中途切换），
     * 取用户消息里出现最多的那个即可；平局取 0（本人层）——归错到本人只是少一层隔离，
     * 归错到面具则会让本该常驻的偏好在摘下面具后消失。
     */
    val maskId: Long
        get() = messages
            .filter { it.role == ChatRole.USER.wire }
            .groupingBy(MessageEntity::maskId)
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()
            ?.key
            ?: 0L
}

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
 * CHEAP 提炼 → EMBEDDING 向量化 → MemoryEntry。ObjectBox 先写、Room 水位后推进；
 * 若两步之间进程退出，重跑用 (conversationId, sourceMessageId) 检测已写批次，避免重复。
 *
 * Memory 2.0 在此基础上加了三件事：
 * - **写入前检索**（批次 C）：把候选与最相似的旧记忆一起交给模型，产出
 *   ADD/UPDATE/DELETE/NOOP，解决「用户改口后新旧矛盾条目共存」；
 * - **用户画像**（批次 B）：同一次调用顺带整段改写角色的常驻画像；
 * - **面具归属**（第 6 节）：面具期间的经历打上 maskId，不污染本人偏好。
 */
@Singleton
class MemoryConsolidator @Inject constructor(
    private val chatDao: ChatDao,
    private val personaDao: PersonaDao,
    private val clientFactory: AiClientFactory,
    private val vectorStore: BoxStore,
    private val settingsRepository: ReaderSettingsRepository,
    private val userMaskStore: UserMaskStore
) {
    suspend fun consolidateAvailable(
        conversationId: Long,
        forceOnClose: Boolean = false
    ): MemoryConsolidationOutcome {
        val memorySettings = settingsRepository.companionMemorySettings.first()
        if (!memorySettings.longTermEnabled) {
            return MemoryConsolidationOutcome.Skipped("长期记忆已关闭")
        }
        val conversation = chatDao.getConversation(conversationId)
            ?: return MemoryConsolidationOutcome.Skipped("会话不存在")
        val personaId = conversation.personaId
            ?: return MemoryConsolidationOutcome.Skipped("无角色会话不产生长期记忆")
        val persona = personaDao.getPersona(personaId)
        if (persona != null && !persona.memoryEnabled) {
            return MemoryConsolidationOutcome.Skipped("该角色已关闭长期记忆")
        }
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
                    memories += consolidateBatch(
                        batch = batch,
                        conversationId = conversationId,
                        personaId = personaId,
                        bookId = conversation.bookId,
                        currentProfile = personaDao.getPersona(personaId)?.userProfile.orEmpty(),
                        memorySettings = memorySettings,
                        cheapClient = cheap.client,
                        cheapOptions = cheap.options,
                        embedClient = embedding.client
                    )
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

    /** @return 本批新增/更新的记忆条数 */
    private suspend fun consolidateBatch(
        batch: MemoryBatch,
        conversationId: Long,
        personaId: Long,
        bookId: Long?,
        currentProfile: String,
        memorySettings: CompanionMemorySettings,
        cheapClient: ChatApiClient,
        cheapOptions: ChatOptions,
        embedClient: ChatApiClient
    ): Int {
        val summaries = summarize(batch, cheapClient, cheapOptions)
        if (summaries.isEmpty()) return 0

        val vectors = embedClient.embed(summaries)
        check(vectors.size == summaries.size) { "记忆 embedding 数量与条目数不一致" }
        val conformed = vectors.map(Embeddings::conformToIndex)

        // 写入前检索：拿每条候选去找同角色最相似的旧记忆，交给模型判断该新增还是改写。
        val neighbours = conformed.flatMap { vector ->
            runCatching {
                VectorQueries.searchMemories(
                    vectorStore,
                    personaId,
                    vector,
                    NEIGHBOUR_TOP_K,
                    bookId,
                    batch.maskId
                ).map { it.get() }
            }.getOrDefault(emptyList())
        }.distinctBy(MemoryEntry::id)

        val maskName = batch.maskId.takeIf { it > 0L }?.let { id ->
            runCatching { userMaskStore.settings.first().masks.firstOrNull { it.id == id }?.name }
                .getOrNull()
        }
        val draft = resolveOperations(
            summaries = summaries,
            neighbours = neighbours,
            currentProfile = currentProfile,
            transcript = MemoryBatchPlanner.transcript(batch),
            maskName = maskName,
            includeSharedBooks = memorySettings.crossBookEnabled,
            client = cheapClient,
            options = cheapOptions
        )

        val operations = draft.operations.ifEmpty {
            // 模型没给出可用操作时退回原行为：全部新增。宁可冗余，不可丢失。
            summaries.map(MemoryOperation::Add)
        }
        val applied = applyOperations(
            operations = operations,
            summaryVectors = summaries.zip(conformed).toMap(),
            embedClient = embedClient,
            personaId = personaId,
            bookId = bookId,
            conversationId = conversationId,
            sourceMessageId = batch.throughMessageId,
            maskId = batch.maskId
        )

        // 画像只记本人：面具期间的批次不改写它（提示词也已声明，这里是第二道闸）。
        if (batch.maskId == 0L) {
            draft.userProfile?.takeIf { it != currentProfile }?.let { profile ->
                personaDao.updateUserProfile(personaId, profile)
            }
        }
        return applied
    }

    private suspend fun applyOperations(
        operations: List<MemoryOperation>,
        summaryVectors: Map<String, FloatArray>,
        embedClient: ChatApiClient,
        personaId: Long,
        bookId: Long?,
        conversationId: Long,
        sourceMessageId: Long,
        maskId: Long
    ): Int {
        val box = vectorStore.boxFor(MemoryEntry::class.java)
        val now = System.currentTimeMillis()
        var written = 0
        val additions = mutableListOf<MemoryEntry>()
        val updates = mutableListOf<MemoryEntry>()
        val deletions = mutableListOf<Long>()
        // 没有现成向量的（模型改写过措辞）留到最后统一补算，避免逐条发请求。
        val needsEmbedding = mutableListOf<Pair<MemoryEntry, String>>()

        operations.forEach { operation ->
            when (operation) {
                is MemoryOperation.Add -> {
                    val entry = MemoryEntry().also {
                        it.personaId = personaId
                        it.bookId = bookId
                        it.conversationId = conversationId
                        it.sourceMessageId = sourceMessageId
                        it.maskId = maskId
                        it.summary = operation.summary
                        it.sourceType = SOURCE_TYPE
                        it.createdAt = now + written
                    }
                    val vector = summaryVectors[operation.summary]
                    if (vector != null) {
                        entry.embedding = vector
                        additions += entry
                    } else {
                        needsEmbedding += entry to operation.summary
                    }
                    written++
                }
                is MemoryOperation.Update -> {
                    val existing = box.get(operation.id)
                    // 只改当前角色、当前书、当前面具的记忆，避免相似内容跨书互相覆盖。
                    if (existing == null ||
                        existing.personaId != personaId ||
                        existing.bookId != bookId ||
                        existing.maskId != maskId
                    ) return@forEach
                    existing.summary = operation.summary
                    existing.createdAt = now + written
                    // 被本批取代的旧条目改挂到本批，重跑同批时幂等检查才认得出它。
                    existing.conversationId = conversationId
                    existing.sourceMessageId = sourceMessageId
                    val vector = summaryVectors[operation.summary]
                    if (vector != null) {
                        existing.embedding = vector
                        updates += existing
                    } else {
                        needsEmbedding += existing to operation.summary
                    }
                    written++
                }
                is MemoryOperation.Delete -> {
                    val existing = box.get(operation.id)
                    if (existing != null &&
                        existing.personaId == personaId &&
                        existing.bookId == bookId &&
                        existing.maskId == maskId
                    ) {
                        deletions += operation.id
                    }
                }
                MemoryOperation.NoOp -> Unit
            }
        }

        if (needsEmbedding.isNotEmpty()) {
            val texts = needsEmbedding.map { it.second }
            val vectors = runCatching { embedClient.embed(texts) }.getOrNull()
            if (vectors != null && vectors.size == texts.size) {
                needsEmbedding.forEachIndexed { index, (entry, _) ->
                    entry.embedding = Embeddings.conformToIndex(vectors[index])
                    if (entry.id == 0L) additions += entry else updates += entry
                }
            } else {
                // 算不出向量的条目直接放弃：没有 embedding 的记忆永远检索不到，写进去只是垃圾。
                written -= needsEmbedding.size
            }
        }

        if (additions.isEmpty() && updates.isEmpty() && deletions.isEmpty()) return 0
        vectorStore.runInTx {
            if (deletions.isNotEmpty()) box.removeByIds(deletions)
            if (additions.isNotEmpty()) box.put(additions)
            if (updates.isNotEmpty()) box.put(updates)
        }
        return written.coerceAtLeast(0)
    }

    private suspend fun summarize(
        batch: MemoryBatch,
        client: ChatApiClient,
        options: ChatOptions
    ): List<String> {
        val response = client.chat(
            messages = listOf(
                ChatMessage(ChatRole.SYSTEM, SUMMARY_PROMPT),
                ChatMessage(ChatRole.USER, MemoryBatchPlanner.transcript(batch))
            ),
            options = options
        )
        return MemorySummaryParser.parse(response)
    }

    /** 候选 + 相似旧记忆 + 现有画像 → 操作数组与画像改写。失败时返回空草案，调用方回落全量新增。 */
    private suspend fun resolveOperations(
        summaries: List<String>,
        neighbours: List<MemoryEntry>,
        currentProfile: String,
        transcript: String,
        maskName: String?,
        includeSharedBooks: Boolean,
        client: ChatApiClient,
        options: ChatOptions
    ): MemoryConsolidationDraft {
        val prompt = buildString {
            append("【本批新提炼的候选记忆】\n")
            summaries.forEach { append("- ").append(it).append('\n') }
            append("\n【该角色已有的相似记忆】\n")
            if (neighbours.isEmpty()) {
                append("（没有相似的旧记忆）\n")
            } else {
                neighbours.forEach { entry ->
                    append("id=").append(entry.id).append("：").append(entry.summary).append('\n')
                }
            }
            append("\n【当前用户画像】\n")
            append(currentProfile.ifBlank { "（还没有画像）" }).append('\n')
            if (maskName != null) {
                append("\n【注意】本段对话中用户戴着「").append(maskName)
                append("」面具，其中的身份与经历属于扮演，不得写进用户画像。\n")
            }
            append("\n【本批对话原文】\n").append(transcript)
        }
        val response = try {
            client.chat(
                messages = listOf(
                    ChatMessage(ChatRole.SYSTEM, operationsPrompt(includeSharedBooks)),
                    ChatMessage(ChatRole.USER, prompt)
                ),
                options = options
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return MemoryConsolidationDraft(emptyList(), null)
        }
        return UserProfileParser.parse(response)
    }

    private fun Throwable.isConfigurationIssue(): Boolean =
        this is AiClientException.NotConfigured ||
            this is AiClientException.MissingKey ||
            this is AiClientException.InvalidKey ||
            this is AiClientException.Unsupported ||
            this is IllegalArgumentException

    private companion object {
        const val SOURCE_TYPE = "CHAT_SUMMARY"
        const val NEIGHBOUR_TOP_K = 3

        val SUMMARY_PROMPT = """
            你负责把伴读对话固化为长期记忆。只提取未来交流仍有用的用户偏好、事实、约定与共同经历；
            不记录临时寒暄，不猜测，不补充对话外信息。用角色第一人称表述，例如“用户告诉我……”。
            只输出 JSON 字符串数组，0 到 5 条，每条独立且简洁，不要 Markdown。
        """.trimIndent()

        fun operationsPrompt(includeSharedBooks: Boolean): String = """
            你在维护一份长期记忆库和一份用户画像。根据候选记忆与已有相似记忆，决定每条候选的处置。

            输出严格为 JSON 对象，不要 Markdown：
            {"operations":[{"action":"ADD","summary":"..."},
                           {"action":"UPDATE","id":12,"summary":"..."},
                           {"action":"DELETE","id":34},
                           {"action":"NOOP"}],
             "user_profile":"..."}

            操作规则（务必保守）：
            - ADD：这是新信息，已有记忆里没有。默认选它。
            - UPDATE：候选与某条旧记忆讲的是同一件事，且信息更新或更准确。summary 写合并后的完整表述。
            - DELETE：旧记忆已被明确推翻（用户亲口否认或改口）。只有确凿时才用。
            - NOOP：候选与旧记忆完全重复，没有新增信息。
            拿不准一律用 ADD。宁可留下冗余，也不要删掉用户说过的话。

            user_profile 规则：
            - 它是常驻小抄，不是记忆流水。包含：称呼与身份、偏好与雷点、阅读口味、关系进展${
            if (includeSharedBooks) "、一起读过的书（书名 + 一句话）" else ""
        }。
            - 整段重写，不超过 800 字，不用 Markdown 标题。
            - 只记录用户本人的真实信息；角色扮演设定、面具身份一律不写。
            - 若这批对话没有带来画像层面的变化，把 user_profile 设为 null。
        """.trimIndent()
    }
}
