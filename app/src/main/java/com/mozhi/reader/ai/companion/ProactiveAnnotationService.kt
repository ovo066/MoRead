package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.database.entity.AnnotationColors
import com.mozhi.reader.core.database.entity.AnnotationStyle
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.datastore.ProactiveAnnotationQuota
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.library.AnnotationMedia
import com.mozhi.reader.core.library.AnnotationRepository
import com.mozhi.reader.core.library.BookQuoteLocator
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.QuoteChapter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
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
    fun parse(raw: String): List<ProactiveAnnotationDraft> {
        val clean = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return runCatching {
            when (val root = AiJson.parseToJsonElement(clean)) {
                is kotlinx.serialization.json.JsonArray -> root.mapNotNull { element ->
                    runCatching {
                        AiJson.decodeFromJsonElement(ProactiveAnnotationDraft.serializer(), element)
                    }.getOrNull()
                }
                else -> AiJson.decodeFromString(ProactiveAnnotationEnvelope.serializer(), clean).annotations
            }
        }.getOrDefault(emptyList())
            .filter { it.quote.isNotBlank() && it.note.isNotBlank() }
            .take(2)
    }
}

@Singleton
class ProactiveAnnotationService @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val annotationRepository: AnnotationRepository,
    private val personaRepository: PersonaRepository,
    private val settingsRepository: ReaderSettingsRepository,
    private val clientFactory: AiClientFactory,
    private val mediaService: AiMediaGenerationService,
    private val quota: ProactiveAnnotationQuota
) {
    suspend fun generateForCompletedChapter(bookId: Long, chapterIndex: Int) {
        val autonomy = settingsRepository.companionAutonomySettings.first()
        if (!autonomy.proactiveAnnotationsEnabled) return
        val personaId = settingsRepository.activePersonaId.first() ?: return
        val persona = personaRepository.getPersona(personaId) ?: return
        val allowance = quota.reserve(
            bookId = bookId,
            chapterIndex = chapterIndex,
            requestVoice = autonomy.annotationVoiceActive && persona.voiceId.isNotBlank(),
            requestImages = autonomy.annotationImageActive
        )
        if (!allowance.accepted || allowance.maxAnnotations <= 0) return

        val chapter = libraryRepository.getChapters(bookId)
            .firstOrNull { it.chapterIndex == chapterIndex } ?: return
        val body = libraryRepository.readChapterText(bookId, chapter)
        if (body.isBlank()) return
        val book = libraryRepository.getBook(bookId) ?: return
        val resolved = clientFactory.forRole(ModelRole.CHEAP)
        val raw = resolved.client.chat(
            messages = listOf(
                ChatMessage(
                    ChatRole.SYSTEM,
                    """
                    你是阅读应用的随读段评编辑。只根据用户刚读完的这一章写批注，绝不引用或暗示后续剧情。
                    选择最多 ${allowance.maxAnnotations} 段值得回应的原文，quote 必须逐字复制自输入正文，note 用角色口吻写简短中文段评。
                    style 只能是 HIGHLIGHT、UNDERLINE、WAVY。voice 仅在适合像私语一样说出时为 true；image_prompt 仅在值得配图时给中文提示词。
                    只输出 JSON：{"annotations":[{"quote":"原文","note":"段评","style":"HIGHLIGHT","voice":false,"image_prompt":null}]}
                    """.trimIndent()
                ),
                ChatMessage(
                    ChatRole.USER,
                    "书名：《${book.title}》\n章节：${chapter.title}\n角色：${persona.name}\n角色风格：${persona.speakingStyle}\n\n正文：\n${body.take(MAX_CHAPTER_CHARS)}"
                )
            ),
            options = resolved.options
        )

        var created = 0
        var voices = 0
        var images = 0
        ProactiveAnnotationParser.parse(raw).forEach { draft ->
            val location = BookQuoteLocator.locateAll(
                listOf(QuoteChapter(chapterIndex, body)),
                draft.quote.trim()
            ).firstOrNull() ?: return@forEach
            var audioPath: String? = null
            var illustrationId: Long? = null
            if (draft.voice && voices < allowance.maxVoice && persona.voiceId.isNotBlank()) {
                audioPath = runCatching {
                    mediaService.synthesizeSpeech(
                        bookId = bookId,
                        text = draft.note,
                        voiceId = persona.voiceId,
                        emotion = persona.voiceEmotion.takeIf(String::isNotBlank)
                    ).path
                }.getOrNull()
                if (audioPath != null) voices++
            }
            if (!draft.imagePrompt.isNullOrBlank() && images < allowance.maxImages) {
                illustrationId = runCatching {
                    mediaService.generateIllustration(
                        bookId = bookId,
                        chapterIndex = chapterIndex,
                        charOffset = location.startCharOffset,
                        sourceText = draft.quote,
                        prompt = draft.imagePrompt,
                        personaId = persona.id
                    ).id
                }.getOrNull()
                if (illustrationId != null) images++
            }
            annotationRepository.add(
                bookId = bookId,
                personaId = persona.id,
                chapterIndex = chapterIndex,
                startCharOffset = location.startCharOffset,
                endCharOffset = location.endCharOffset,
                selectedText = draft.quote.trim(),
                note = draft.note.trim(),
                colorTag = AnnotationColors.forPersona(persona.id),
                style = AnnotationStyle.fromWire(draft.style),
                mediaJson = AnnotationMedia(audioPath, illustrationId).encode()
            )
            created++
        }
        quota.recordCreated(created, voices, images)
    }

    private companion object {
        const val MAX_CHAPTER_CHARS = 28_000
    }
}
