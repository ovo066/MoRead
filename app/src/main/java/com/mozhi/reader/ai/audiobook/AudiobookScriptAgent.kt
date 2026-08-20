package com.mozhi.reader.ai.audiobook

import com.mozhi.reader.ai.agent.AgentEvent
import com.mozhi.reader.ai.agent.AgentLoop
import com.mozhi.reader.ai.agent.ReaderToolset
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.core.database.entity.AudiobookRoleEntity
import com.mozhi.reader.core.database.entity.AudiobookSegmentEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.datastore.audiobookRevision
import com.mozhi.reader.core.library.AudiobookRepository
import com.mozhi.reader.core.library.AudiobookRoleKind
import com.mozhi.reader.core.library.LibraryRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

data class AudiobookScriptResult(
    val segments: List<AudiobookSegmentEntity>,
    val usedAi: Boolean,
    val chapterTitle: String,
    val body: String
)

@Singleton
class AudiobookScriptAgent @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val audiobookRepository: AudiobookRepository,
    private val readerSettingsRepository: ReaderSettingsRepository,
    private val agentLoop: AgentLoop,
    private val readerToolset: ReaderToolset
) {
    suspend fun generate(
        bookId: Long,
        chapterIndex: Int,
        useAi: Boolean
    ): AudiobookScriptResult {
        val chapter = libraryRepository.getChapters(bookId)
            .firstOrNull { it.chapterIndex == chapterIndex }
            ?: error("章节不存在")
        val body = libraryRepository.readChapterText(bookId, chapter)
        val roles = audiobookRepository.getRoles(bookId)
        require(roles.isNotEmpty()) { "请先确认角色与音色" }

        // 坐标与旁白/对白边界始终由本地确定；AI 只给带稳定 ID 的对白选角色。
        // 这比让模型计算 UTF-16 start/end 稳定得多，也不会因一个偏移错误把整章变成旁白。
        val ruleSegments = DialogueRuleSegmenter.segment(body)
        val dialogueIndices = ruleSegments.indices
            .filter { ruleSegments[it].kind == AudiobookSegmentKind.DIALOGUE }
            .toSet()
        val aiAssignments = if (
            useAi && dialogueIndices.isNotEmpty() && body.length <= MAX_AI_CHAPTER_CHARS
        ) {
            val raw = runAgent(bookId, chapter.title, body, roles, ruleSegments, dialogueIndices)
            AudiobookScriptParser.parseAssignments(extractJsonPayload(raw), dialogueIndices)
        } else {
            emptyList()
        }
        val assignments = aiAssignments.associateBy(ParsedAudiobookAssignment::segmentIndex)
        val narrator = roles.firstOrNull { it.kind == AudiobookRoleKind.NARRATOR.name }
            ?: roles.first()
        val revision = audiobookRevision(
            body,
            readerSettingsRepository.settings.first().textReplacementRules
        )
        val entities = ruleSegments.mapIndexed { index, draft ->
            val assignment = assignments[index]
            val proposedRole = if (draft.kind == AudiobookSegmentKind.NARRATION) {
                narrator
            } else {
                resolveAudiobookRole(roles, assignment?.roleName)
                    ?: resolveAudiobookRole(roles, draft.roleName)
                    ?: narrator
            }
            AudiobookSegmentEntity(
                bookId = bookId,
                chapterIndex = chapterIndex,
                startCharOffset = draft.startCharOffset,
                endCharOffset = draft.endCharOffset,
                roleId = proposedRole.id,
                emotion = normalizeEmotion(assignment?.emotion),
                instruction = assignment?.instruction?.trim()?.takeIf(String::isNotEmpty),
                revision = revision
            )
        }
        audiobookRepository.replaceScript(bookId, chapterIndex, revision, entities)
        return AudiobookScriptResult(
            segments = audiobookRepository.getSegments(bookId, chapterIndex),
            usedAi = aiAssignments.isNotEmpty(),
            chapterTitle = chapter.title,
            body = body
        )
    }

    private suspend fun runAgent(
        bookId: Long,
        chapterTitle: String,
        body: String,
        roles: List<AudiobookRoleEntity>,
        segments: List<DraftAudiobookSegment>,
        dialogueIndices: Set<Int>
    ): String {
        val roleList = roles.joinToString("\n") { role ->
            "- ${role.name}（别名：${role.aliases.ifBlank { "无" }}）"
        }
        val candidates = dialogueIndices.sorted().joinToString("\n") { index ->
            val segment = segments[index]
            val contextStart = (segment.startCharOffset - CONTEXT_CHARS).coerceAtLeast(0)
            val contextEnd = (segment.endCharOffset + CONTEXT_CHARS).coerceAtMost(body.length)
            val context = body.substring(contextStart, contextEnd)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .take(MAX_CANDIDATE_CHARS)
            "[segment_id=$index][规则猜测=${segment.roleName}] $context"
        }
        val history = listOf(
            ChatMessage(
                ChatRole.SYSTEM,
                """
                你是中文小说有声书对白归属标注专家。分段坐标已由程序锁定，你只判断每个对白是谁说的。
                只输出 JSON，不要 Markdown，格式：{"assignments":[{"segment_id":1,"role":"角色表中的精确名称","emotion":"中性","instruction":""}]}。
                segment_id 必须原样使用；每个候选都要输出且只能输出一次；role 必须逐字使用角色表中的名称，不能写“男主”“角色1”或自造名称。
                情绪只能是开心、悲伤、愤怒、恐惧、厌恶、惊讶、中性之一。无法确定时优先参考规则猜测和上下文；仍无法判断才使用旁白。
                """.trimIndent()
            ),
            ChatMessage(
                ChatRole.USER,
                "章节：$chapterTitle\n角色表：\n$roleList\n\n待标注对白：\n$candidates"
            )
        )
        val output = StringBuilder()
        agentLoop.runDetached(
            history = history,
            tools = readerToolset.forBook(
                bookId = bookId,
                enabledTools = SAFE_TOOL_NAMES,
                spoilerProtectionEnabled = false
            ),
            modelRole = ModelRole.CHEAP
        ).collect { event ->
            if (event is AgentEvent.Text) output.append(event.text)
        }
        return output.toString()
    }

    private fun normalizeEmotion(value: String?): String = value?.trim()
        ?.takeIf { it in VALID_EMOTIONS } ?: "中性"

    private companion object {
        val SAFE_TOOL_NAMES = setOf(
            "search_book",
            "read_book_section",
            "get_reading_progress",
            "web_search",
            "web_scrape"
        )
        val VALID_EMOTIONS = setOf("开心", "悲伤", "愤怒", "恐惧", "厌恶", "惊讶", "中性")
        const val MAX_AI_CHAPTER_CHARS = 60_000
        const val CONTEXT_CHARS = 72
        const val MAX_CANDIDATE_CHARS = 320
    }
}

/** 角色名允许别名、括号说明和轻微格式差异，但避免把“他/她/对白”误配成人物。 */
internal fun resolveAudiobookRole(
    roles: List<AudiobookRoleEntity>,
    proposedName: String?
): AudiobookRoleEntity? {
    val raw = proposedName?.trim().orEmpty()
    if (raw.isBlank() || raw in setOf("对白", "未知", "角色", "null")) return null
    val normalized = normalizeRoleName(raw)
    if (normalized.isBlank()) return null
    val candidates = roles.flatMap { role ->
        (listOf(role.name) + role.aliases.split(',', '，', ';', '；'))
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { alias -> role to normalizeRoleName(alias) }
    }
    candidates.firstOrNull { (_, alias) -> alias == normalized }?.let { return it.first }
    if (normalized.length < 2) return null
    return candidates
        .filter { (_, alias) -> alias.length >= 2 && (normalized.contains(alias) || alias.contains(normalized)) }
        .maxByOrNull { (_, alias) -> alias.length }
        ?.first
}

private fun normalizeRoleName(value: String): String = value
    .lowercase()
    .replace(Regex("[\\s·•._—-]"), "")
    .replace(Regex("[（(【\\[].*?[）)】\\]]"), "")
    .replace(Regex("[^\\p{L}\\p{N}]"), "")
