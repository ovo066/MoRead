package com.mozhi.reader.ai.memory

import com.mozhi.reader.ai.client.AiClientException
import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.core.database.dao.ChatDao
import com.mozhi.reader.core.database.entity.ModelRole
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * 会话前情提要（Memory 2.0 批次 A）。补的是「已滑出 20 条历史窗口、又没攒够 30 条去
 * 固化」那段消息造成的上下文裂缝——用户会直接体感为「聊到一半突然不记得开头了」。
 *
 * 三条纪律：
 * - 提要只在组装请求时以 system 块注入，**从不作为消息落库**（同 systemPrompt 覆写）；
 * - 生成是异步增益，不阻塞当轮发送，这轮没赶上下轮自然会用上；
 * - CHEAP 没配置或调用失败一律静默降级为「只带 20 条」，也就是改动前的行为。
 */
@Singleton
class RollingSummarizer @Inject constructor(
    private val chatDao: ChatDao,
    private val clientFactory: dagger.Lazy<AiClientFactory>
) {
    /**
     * 为 [conversationId] 推进一次提要。返回 true 表示提要有更新（调用方可据此决定是否重取）。
     */
    suspend fun refresh(conversationId: Long): Boolean {
        val conversation = chatDao.getConversation(conversationId) ?: return false
        // 固化水位之前的消息已是长期记忆；提要水位必须跟上，否则提要会重复讲已固化的内容。
        RollingSummaryPlanner.watermarkAfterConsolidation(
            consolidatedThrough = conversation.memoryConsolidatedThroughMessageId,
            summarizedThrough = conversation.summarizedThroughMessageId
        )?.let { advanced ->
            chatDao.updateRollingSummary(conversationId, conversation.rollingSummary, advanced)
        }

        val refreshed = chatDao.getConversation(conversationId) ?: return false
        val work = RollingSummaryPlanner.plan(
            messages = chatDao.getMessages(conversationId),
            consolidatedThrough = refreshed.memoryConsolidatedThroughMessageId,
            summarizedThrough = refreshed.summarizedThroughMessageId
        ) ?: return false

        val resolved = try {
            clientFactory.get().forRole(ModelRole.CHEAP)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (error.isConfigurationIssue()) return false
            throw error
        }

        val summary = try {
            resolved.client.chat(
                messages = listOf(
                    ChatMessage(ChatRole.SYSTEM, SYSTEM_PROMPT),
                    ChatMessage(ChatRole.USER, userPrompt(refreshed.rollingSummary, work))
                ),
                options = resolved.options
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // 提要是增益项：失败就保持原样，下一轮再试。
            return false
        }

        val cleaned = clean(summary)
        if (cleaned.isBlank()) return false
        chatDao.updateRollingSummary(conversationId, cleaned, work.throughMessageId)
        return true
    }

    /** 组装到系统提示词里的块；没有提要时返回 null。 */
    fun block(rollingSummary: String): String? = rollingSummary
        .takeIf(String::isNotBlank)
        ?.let { "【前情提要】本次对话更早的内容（由你自己记录，不必复述）：\n$it" }

    private fun clean(raw: String): String = raw
        .trim()
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
        .take(RollingSummaryPlanner.MAX_SUMMARY_CHARS)

    private fun userPrompt(previous: String, work: RollingSummaryWork): String = buildString {
        if (previous.isNotBlank()) {
            append("已有的前情提要：\n").append(previous).append("\n\n")
            append("以下是这之后新滑出对话窗口的内容，请把两者合并改写成一段新的提要：\n")
        } else {
            append("以下是滑出对话窗口的内容，请写成一段提要：\n")
        }
        append(RollingSummaryPlanner.transcript(work))
    }

    private fun Throwable.isConfigurationIssue(): Boolean =
        this is AiClientException.NotConfigured ||
            this is AiClientException.MissingKey ||
            this is AiClientException.InvalidKey ||
            this is AiClientException.Unsupported ||
            this is IllegalArgumentException

    private companion object {
        val SYSTEM_PROMPT = """
            你在维护一段对话的「前情提要」，供自己在后续对话中回顾。
            要求：
            1. 整段重写，而不是在旧提要后面追加；把旧提要与新内容融合成连贯的一段。
            2. 只保留后续对话仍用得上的信息：用户的诉求与偏好、已达成的结论与约定、
               正在进行的话题；寒暄、重复确认、已经结束且无后续影响的细节一律丢弃。
            3. 用第一人称「我」指代你自己，「用户」指代对方。
            4. 不超过 600 字，不使用 Markdown 标题或列表符号，直接输出提要正文。
            5. 只依据给出的对话内容，不得引入书中尚未提及的情节或任何推测。
        """.trimIndent()
    }
}
