package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.core.database.entity.AnnotationColors
import com.mozhi.reader.core.database.entity.AnnotationStyle
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.library.AnnotationMedia
import com.mozhi.reader.core.library.BookQuoteLocator
import com.mozhi.reader.core.library.QuoteChapter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
internal data class ProactiveAnnotationDraft(
    val quote: String = "",
    val note: String = "",
    val style: String = "HIGHLIGHT",
    val voice: Boolean = false,
    @SerialName("image_prompt") val imagePrompt: String? = null
)

@Serializable
private data class ProactiveAnnotationEnvelope(
    val annotations: List<ProactiveAnnotationDraft> = emptyList()
)

internal object ProactiveAnnotationParser {
    fun parse(raw: String, limit: Int): List<ProactiveAnnotationDraft> {
        val clean = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val drafts = runCatching {
            when (val root = AiJson.parseToJsonElement(clean)) {
                is kotlinx.serialization.json.JsonArray -> root.mapNotNull { element ->
                    runCatching {
                        AiJson.decodeFromJsonElement(ProactiveAnnotationDraft.serializer(), element)
                    }.getOrNull()
                }
                is kotlinx.serialization.json.JsonObject -> if ("quote" in root) {
                    listOf(AiJson.decodeFromJsonElement(ProactiveAnnotationDraft.serializer(), root))
                } else AiJson.decodeFromString(ProactiveAnnotationEnvelope.serializer(), clean).annotations
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
            .filter { it.quote.isNotBlank() && it.note.isNotBlank() }
        // limit 可以是 Int.MAX_VALUE（用户选了「不限制」），此时原样放行。
        return if (limit >= drafts.size) drafts else drafts.take(limit.coerceAtLeast(0))
    }
}

/** Scheduler-owned immutable identity and transactional sink; the service never writes progress. */
data class ProactiveAnnotationRequest(
    val bookId: Long,
    val chapterIndex: Int,
    val body: String,
    val persona: com.mozhi.reader.core.database.entity.PersonaEntity,
    val candidateLimit: Int,
    val doneParagraphEnds: Set<Int>
)

data class ProactiveAnnotationGenerationResult(val failed: Boolean, val stopped: Boolean)

@Singleton
class ProactiveAnnotationService @Inject constructor(
    private val clientFactory: AiClientFactory,
    private val mediaService: AiMediaGenerationService
) {
    /** Revalidation is mandatory before every paid call and immediately before the atomic sink. */
    suspend fun generateForChapter(
        request: ProactiveAnnotationRequest,
        allowance: suspend () -> com.mozhi.reader.core.datastore.ProactiveAnnotationAllowance?,
        recordMedia: suspend (voices: Int, images: Int) -> Unit,
        commit: suspend (com.mozhi.reader.core.database.entity.AnnotationEntity, Int, com.mozhi.reader.core.database.entity.IllustrationEntity?) -> Boolean
    ): ProactiveAnnotationGenerationResult {
        var failed = false
        for (paragraph in ProactiveAnnotationParagraphs.candidates(request.body, request.candidateLimit)) {
            if (paragraph.end in request.doneParagraphEnds) continue
            val permit = allowance() ?: return ProactiveAnnotationGenerationResult(failed, stopped = true)
            if (!permit.accepted || permit.maxAnnotations <= 0) return ProactiveAnnotationGenerationResult(failed, true)
            var detachedImage: com.mozhi.reader.core.database.entity.IllustrationEntity? = null
            var committed = false
            try {
                val resolved = clientFactory.forRole(ModelRole.CHEAP)
                val raw = resolved.client.chat(
                    messages = listOf(
                        ChatMessage(ChatRole.SYSTEM, """
                            你是随读段评编辑。只根据给出的正文前缀，写一条对最后一段的简短中文段评。
                            绝不推测后文；quote 必须逐字复制自最后一段，不能引用之前的段落。
                            style 必须按这一处内容的语义选择一个，不能把整批段评固定为同一种，也不要随机轮换或为了凑齐三种而强行选择：
                            HIGHLIGHT（荧光）：金句、精彩描写、值得回味的段落。
                            WAVY（波浪线）：当前正文前缀中已能看出的线索、伏笔、暗线或前后呼应；不能据此断言未读剧情。
                            UNDERLINE（直线）：知识点、典故、术语、需要记住的事实或解释。
                            没有明显线索或知识点时才选 HIGHLIGHT，不要把它当作所有段评的固定样式。
                            voice 表示适合私语；image_prompt 可为 null。
                            只输出一个 JSON 对象，字段为 quote（原文字符串）、note（段评字符串）、style（上述三个枚举之一）、voice（布尔值）、image_prompt（字符串或 null）。不要 Markdown 或额外解释。
                        """.trimIndent()),
                        ChatMessage(ChatRole.USER, "角色：${request.persona.name}\n角色风格：${request.persona.speakingStyle}\n" +
                            "正文前缀（最后一段是唯一目标）：\n${ProactiveAnnotationParagraphs.prefix(request.body, paragraph)}")
                    ),
                    options = resolved.options
                )
                val draft = ProactiveAnnotationParser.parse(raw, 1).firstOrNull()
                if (draft == null) { failed = true; continue }
                val location = BookQuoteLocator.locateAll(
                    listOf(QuoteChapter(request.chapterIndex, request.body)), draft.quote.trim()
                ).firstOrNull { it.startCharOffset >= paragraph.start && it.endCharOffset <= paragraph.end }
                if (location == null || request.body.substring(location.startCharOffset, location.endCharOffset) != draft.quote.trim()) { failed = true; continue }
                var audioPath: String? = null
                if (draft.voice && request.persona.voiceId.isNotBlank()) {
                    val mediaPermit = allowance() ?: return ProactiveAnnotationGenerationResult(failed, true)
                    if (mediaPermit.maxVoice > 0) {
                        // Charge before requesting, so failed/crashed requests cannot silently reuse a media cap.
                        recordMedia(1, 0)
                        audioPath = optionalMedia {
                            mediaService.synthesizeSpeech(bookId = request.bookId, text = draft.note, voiceId = request.persona.voiceId,
                                emotion = request.persona.voiceEmotion.takeIf(String::isNotBlank),
                                beforePaidRequest = { allowance() != null }).path
                        }
                    }
                }
                if (!draft.imagePrompt.isNullOrBlank()) {
                    val mediaPermit = allowance() ?: return ProactiveAnnotationGenerationResult(failed, true)
                    if (mediaPermit.maxImages > 0) {
                        recordMedia(0, 1)
                        detachedImage = optionalMedia {
                            mediaService.generateIllustration(
                                bookId = request.bookId, chapterIndex = request.chapterIndex,
                                charOffset = location.startCharOffset, sourceText = draft.quote,
                                prompt = draft.imagePrompt, personaId = request.persona.id, persist = false,
                                beforePaidRequest = { allowance() != null }
                            )
                        }
                    }
                }
                val row = com.mozhi.reader.core.database.entity.AnnotationEntity(
                    bookId = request.bookId, personaId = request.persona.id,
                    chapterIndex = request.chapterIndex,
                    startCharOffset = location.startCharOffset, endCharOffset = location.endCharOffset,
                    selectedText = draft.quote.trim(), note = draft.note.trim(),
                    colorTag = AnnotationColors.forPersona(request.persona.id),
                    style = AnnotationStyle.fromWire(draft.style).wire,
                    mediaJson = AnnotationMedia(audioPath, null).encode(),
                    sourceScopeChapterIndex = request.chapterIndex, sourceScopeCharOffset = paragraph.end,
                    createdAt = System.currentTimeMillis()
                )
                committed = commit(row, paragraph.end, detachedImage)
                if (!committed) return ProactiveAnnotationGenerationResult(failed, true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true // A bad paragraph must not prevent the remaining paragraphs from being tried.
            } finally {
                // Sink guarantees null/throw means rollback; committed acknowledgement is cancellation-safe.
                if (!committed) detachedImage?.let { java.io.File(it.imagePath).delete() }
            }
        }
        return ProactiveAnnotationGenerationResult(failed, stopped = false)
    }

    private suspend fun <T> optionalMedia(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) { null }
}
