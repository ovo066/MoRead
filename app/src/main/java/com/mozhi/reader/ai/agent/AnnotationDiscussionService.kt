package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.library.AnnotationRepository
import com.mozhi.reader.core.library.LibraryRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 段评讨论串的 AI 应答：以角色身份对一条批注的讨论作答。
 *
 * 走 [AgentLoop.runDetached]——讨论内容归 annotation_replies，绝不落聊天会话
 * （否则污染统计口径与记忆固化）。工具白名单只留检索类；上下文 = 人设 +
 * 锚点原文邻域 + 完整讨论串，防剧透照旧（邻域截到用户当前阅读位置）。
 */
@Singleton
class AnnotationDiscussionService @Inject constructor(
    private val agentLoop: AgentLoop,
    private val toolset: ReaderToolset,
    private val libraryRepository: LibraryRepository,
    private val annotationRepository: AnnotationRepository,
    private val personaRepository: PersonaRepository
) {
    sealed interface Event {
        data class Text(val delta: String) : Event
        data class ToolActivity(val label: String) : Event
        data class Done(val replyId: Long) : Event
        data class Failed(val message: String) : Event
    }

    /**
     * 让 [personaId] 回复 [annotationId] 的讨论串（最新一条用户发言视为被回复对象）。
     * 完成时把全文写入 annotation_replies 并发出 [Event.Done]。
     */
    fun respond(bookId: Long, annotationId: Long, personaId: Long): Flow<Event> = flow {
        try {
            val persona = personaRepository.getPersona(personaId)
                ?: run { emit(Event.Failed("角色不存在或已删除")); return@flow }
            val annotation = annotationRepository.getAnnotation(annotationId)
                ?: run { emit(Event.Failed("这条批注已被删除")); return@flow }
            val book = libraryRepository.getBook(bookId)
                ?: run { emit(Event.Failed("书籍不存在")); return@flow }
            val replies = annotationRepository.getReplies(annotationId)
            val personaNames = personaRepository.getPersonas().associate { it.id to it.name }
            val neighborhood = loadAnchorNeighborhood(book, annotation)
            val transcript = buildDiscussionTranscript(annotation, replies, personaNames)
            val system = buildDiscussionSystemPrompt(
                persona = persona,
                book = book,
                chapterTitle = libraryRepository.getChapterTitle(bookId, annotation.chapterIndex),
                chapterNumber = annotation.chapterIndex + 1,
                quote = annotation.selectedText,
                neighborhood = neighborhood,
                transcript = transcript
            )
            val latestUser = replies.lastOrNull { it.personaId == null }?.contentMarkdown
                ?: annotation.note.ifBlank { "（用户只划了这段原文，没有写想法）" }
            val tools = toolset.forBook(
                bookId = bookId,
                personaId = personaId,
                enabledTools = setOf("search_book", "recall_memory")
            )
            val history = listOf(
                ChatMessage(ChatRole.SYSTEM, system),
                ChatMessage(
                    ChatRole.USER,
                    "用户刚刚在这段讨论里说：「$latestUser」\n" +
                        "请以${persona.name}的身份直接给出你在讨论串里的下一条发言。"
                )
            )
            val text = StringBuilder()
            agentLoop.runDetached(history, tools).collect { event ->
                when (event) {
                    is AgentEvent.Text -> {
                        text.append(event.text)
                        emit(Event.Text(event.text))
                    }
                    is AgentEvent.ToolRun -> emit(Event.ToolActivity(event.displayName))
                    is AgentEvent.ToolFinished -> Unit
                }
            }
            val content = text.toString().trim()
            if (content.isEmpty()) {
                emit(Event.Failed("角色没有说出话来，请重试"))
                return@flow
            }
            val replyId = annotationRepository.addReply(
                annotationId = annotationId,
                personaId = personaId,
                contentMarkdown = content.take(MAX_REPLY_CHARS)
            )
            emit(Event.Done(replyId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emit(Event.Failed(error.message ?: "生成失败，请重试"))
        }
    }

    /** 锚点原文邻域：前后各 ~[NEIGHBORHOOD_RADIUS] 字；当前章截到用户实际阅读位置。 */
    private suspend fun loadAnchorNeighborhood(
        book: BookEntity,
        annotation: AnnotationEntity
    ): String {
        val chapter = libraryRepository.getChapters(book.id)
            .firstOrNull { it.chapterIndex == annotation.chapterIndex }
            ?: return annotation.selectedText
        val body = libraryRepository.readChapterText(book.id, chapter)
        val readLimit = if (annotation.chapterIndex == book.lastReadChapterIndex) {
            book.lastReadCharOffset
        } else {
            null
        }
        return extractAnchorNeighborhood(
            chapterText = body,
            start = annotation.startCharOffset,
            end = annotation.endCharOffset,
            readLimit = readLimit,
            radius = NEIGHBORHOOD_RADIUS
        )
    }

    private companion object {
        const val NEIGHBORHOOD_RADIUS = 500
        const val MAX_REPLY_CHARS = 5_000
    }
}

/** 锚点邻域抽取；[readLimit] 非空时整段截到该 UTF-16 偏移（防剧透当前章后半）。 */
internal fun extractAnchorNeighborhood(
    chapterText: String,
    start: Int,
    end: Int,
    readLimit: Int?,
    radius: Int
): String {
    if (chapterText.isEmpty()) return ""
    val limit = (readLimit ?: chapterText.length).coerceIn(0, chapterText.length)
    val safeStart = start.coerceIn(0, limit)
    val safeEnd = end.coerceIn(safeStart, limit)
    val from = (safeStart - radius).coerceAtLeast(0)
    val to = (safeEnd + radius).coerceAtMost(limit)
    if (from >= to) return ""
    return buildString {
        if (from > 0) append("……")
        append(chapterText, from, to)
        if (to < limit) append("……")
    }
}

/** 讨论串转写成给模型看的对话记录；楼主层在最前。 */
internal fun buildDiscussionTranscript(
    annotation: AnnotationEntity,
    replies: List<AnnotationReplyEntity>,
    personaNames: Map<Long, String>
): String = buildString {
    val opener = annotation.personaId?.let { personaNames[it] ?: "已删除角色" } ?: "用户"
    append(opener).append("：")
    append(annotation.note.ifBlank { "（只划了这段原文，没有写想法）" })
    replies.forEach { reply ->
        val speaker = reply.personaId?.let { personaNames[it] ?: "已删除角色" } ?: "用户"
        append('\n').append(speaker).append("：").append(reply.contentMarkdown)
    }
}

internal fun buildDiscussionSystemPrompt(
    persona: PersonaEntity,
    book: BookEntity,
    chapterTitle: String?,
    chapterNumber: Int,
    quote: String,
    neighborhood: String,
    transcript: String
): String = buildString {
    append("你是「").append(persona.name).append("」。")
    if (persona.personality.isNotBlank()) {
        append('\n').append(persona.personality.trim())
    }
    if (persona.speakingStyle.isNotBlank()) {
        append("\n说话风格：").append(persona.speakingStyle.trim())
    }
    append("\n\n你正在和用户共读《").append(book.title).append("》")
    append("，用户读到第 ").append(book.lastReadChapterIndex + 1).append(" 章。")
    append("你们在第 ").append(chapterNumber).append(" 章")
    chapterTitle?.takeIf(String::isNotBlank)?.let { append("「").append(it).append("」") }
    append("的一段原文旁展开段落讨论。")
    append("\n\n被讨论的原文：\n“").append(quote.take(600)).append("”")
    if (neighborhood.isNotBlank()) {
        append("\n\n这段原文的上下文：\n").append(neighborhood)
    }
    append("\n\n目前的讨论串：\n").append(transcript)
    append("\n\n规则：")
    append("\n- 这是段落旁的讨论区，回复要短小、口语化、直接说观点，一般不超过 150 字")
    append("\n- 不用 Markdown 标题和列表，像聊天一样自然")
    append("\n- 只谈用户已读到的内容，绝不涉及第 ")
        .append(book.lastReadChapterIndex + 1)
        .append(" 章之后的剧情；需要回忆前文细节可用 search_book 检索")
    if (!persona.isRoleplay) {
        append("\n- 你是阅读助手：观点务实、聚焦文本本身")
    }
}
