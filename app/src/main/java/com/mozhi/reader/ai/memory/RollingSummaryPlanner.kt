package com.mozhi.reader.ai.memory

import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.core.database.entity.MessageEntity

/**
 * 一次待摘要的工作：[messages] 是「已摘水位之后、历史窗口之前」那段将要滑出上下文的消息。
 */
internal data class RollingSummaryWork(
    val messages: List<MessageEntity>,
    val throughMessageId: Long
)

/**
 * 决定何时把滑出窗口的消息压成前情提要（Memory 2.0 批次 A）。
 *
 * 上下文有两条水位：固化水位（已变成长期记忆，[AgentLoop] 根本不再带）与摘要水位。
 * 裂缝出现在「已滑出 20 条窗口、但还没攒够 30 条去固化」的那段——对话到一半突然失忆
 * 就是它造成的。这里把那段拿出来交给 CHEAP 增量改写成一段不超过 600 字的提要。
 *
 * 纯函数，不碰数据库也不发请求，便于单测覆盖边界。
 */
internal object RollingSummaryPlanner {

    /** 与 AgentLoop 的历史窗口保持一致；窗口内的消息还在上下文里，不该被摘要。 */
    const val WINDOW_MESSAGES = 20

    /** 攒够这么多条才值得花一次 CHEAP 调用。 */
    const val MIN_BATCH = 6

    /** 一次最多喂给 CHEAP 的条数，防止长会话第一次摘要就塞爆请求。 */
    const val MAX_BATCH = 40

    const val MAX_SUMMARY_CHARS = 600

    /**
     * @param messages 会话全部消息（升序）
     * @param consolidatedThrough 固化水位：其之前的消息已变成长期记忆，不再需要摘要
     * @param summarizedThrough 摘要水位
     * @return 需要摘要的一批；不足阈值或没有滑出窗口的消息时为 null
     */
    fun plan(
        messages: List<MessageEntity>,
        consolidatedThrough: Long,
        summarizedThrough: Long
    ): RollingSummaryWork? {
        // AgentLoop 只带固化水位之后的消息，所以「上下文里的内容」以它为起点计算。
        val inContext = messages.filter { it.id > consolidatedThrough }
        if (inContext.size <= WINDOW_MESSAGES) return null

        val outOfWindow = inContext.dropLast(WINDOW_MESSAGES)
        val pending = outOfWindow.filter {
            it.id > summarizedThrough && it.content.isNotBlank() && it.role.isDialogue()
        }
        if (pending.size < MIN_BATCH) return null

        val selected = pending.take(MAX_BATCH)
        return RollingSummaryWork(selected, selected.last().id)
    }

    /**
     * 固化水位推进后，摘要水位要跟着抬到同一位置：那段消息已经变成长期记忆了，
     * 再留在提要里既重复又会让提要越滚越旧。返回 null 表示不需要调整。
     */
    fun watermarkAfterConsolidation(
        consolidatedThrough: Long,
        summarizedThrough: Long
    ): Long? = consolidatedThrough.takeIf { it > summarizedThrough }

    /** 交给 CHEAP 的对话稿；工具消息不入稿——它们是执行细节，不是对话内容。 */
    fun transcript(work: RollingSummaryWork): String = buildString {
        work.messages.forEach { message ->
            append(if (message.role == ChatRole.USER.wire) "用户：" else "我：")
            append(message.content.take(MAX_MESSAGE_CHARS))
            append('\n')
            if (length >= MAX_TRANSCRIPT_CHARS) return@buildString
        }
    }.take(MAX_TRANSCRIPT_CHARS)

    private fun String.isDialogue(): Boolean =
        this == ChatRole.USER.wire || this == ChatRole.ASSISTANT.wire

    private const val MAX_MESSAGE_CHARS = 1_200
    private const val MAX_TRANSCRIPT_CHARS = 12_000
}
