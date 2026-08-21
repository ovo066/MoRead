package com.mozhi.reader.ai.audiobook

import com.mozhi.reader.ai.agent.AgentEvent
import com.mozhi.reader.ai.agent.AgentLoop
import com.mozhi.reader.ai.agent.ReaderToolset
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.ai.client.AiJson
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

        // 本地规则先锁定高置信显式说话人；AI 在保留整章顺序的原文中只处理剩余歧义对白。
        // 坐标始终由本地确定，模型只返回稳定 ID、角色和表达信息。
        val ruleSegments = DialogueRuleSegmenter.segment(body)
        val dialogueIndices = ruleSegments.indices
            .filter { ruleSegments[it].kind == AudiobookSegmentKind.DIALOGUE }
            .toSet()
        val narrator = roles.firstOrNull { it.kind == AudiobookRoleKind.NARRATOR.name }
            ?: roles.first()
        val characters = roles.filter { it.kind == AudiobookRoleKind.CHARACTER.name }
        val dialogueFallbacks = characters.filter { it.engine == com.mozhi.reader.core.library.AudiobookEngine.AI.name }
            .ifEmpty { characters }
        val lockedRoles = dialogueIndices.mapNotNull { index ->
            val draft = ruleSegments[index]
            resolveAudiobookRole(roles, draft.roleName)
                ?.takeIf { draft.confidence >= LOCAL_LOCK_CONFIDENCE }
                ?.let { index to it }
        }.toMap()
        // 所有对白都进入 AI：高置信说话人保持锁定，但仍需生成情绪、语气与停顿。
        val targetIndices = dialogueIndices
        val aiAssignments = if (useAi && targetIndices.isNotEmpty() && characters.isNotEmpty()) {
            val previousChapterTail = previousChapterTail(bookId, chapterIndex)
            buildAudiobookAttributionBatches(
                body = body,
                segments = ruleSegments,
                targetIndices = targetIndices,
                lockedRoleNames = lockedRoles.mapValues { it.value.name }
            ).flatMap { batch ->
                val raw = runAgentBatch(
                    bookId = bookId,
                    chapterTitle = chapter.title,
                    previousChapterTail = previousChapterTail,
                    roles = characters,
                    batch = batch
                )
                AudiobookScriptParser.parseAssignments(extractJsonPayload(raw), batch.targetIndices)
            }.distinctBy(ParsedAudiobookAssignment::segmentIndex)
        } else {
            emptyList()
        }
        val assignments = aiAssignments.associateBy(ParsedAudiobookAssignment::segmentIndex)
        val revision = audiobookRevision(
            body,
            readerSettingsRepository.settings.first().textReplacementRules
        )
        val recentDialogueRoles = ArrayDeque<AudiobookRoleEntity>()
        val entities = ruleSegments.mapIndexed { index, draft ->
            val performanceAssignment = assignments[index]
            val roleAssignment = performanceAssignment?.takeIf {
                it.confidence == null || it.confidence >= MIN_AI_ASSIGNMENT_CONFIDENCE
            }
            val proposedRole = if (draft.kind == AudiobookSegmentKind.NARRATION) {
                narrator
            } else {
                lockedRoles[index]
                    ?: resolveAudiobookRole(characters, roleAssignment?.roleName)
                    ?: chooseDialogueFallback(draft, characters, dialogueFallbacks, recentDialogueRoles.toList())
                    ?: narrator
            }
            if (draft.kind == AudiobookSegmentKind.DIALOGUE && proposedRole.kind == AudiobookRoleKind.CHARACTER.name) {
                recentDialogueRoles.addLast(proposedRole)
                while (recentDialogueRoles.size > RECENT_DIALOGUE_ROLE_LIMIT) recentDialogueRoles.removeFirst()
            }
            AudiobookSegmentEntity(
                bookId = bookId,
                chapterIndex = chapterIndex,
                startCharOffset = draft.startCharOffset,
                endCharOffset = draft.endCharOffset,
                roleId = proposedRole.id,
                emotion = normalizeEmotion(performanceAssignment?.emotion),
                instruction = performanceAssignment?.instruction?.trim()?.takeIf(String::isNotEmpty),
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

    private suspend fun runAgentBatch(
        bookId: Long,
        chapterTitle: String,
        previousChapterTail: String,
        roles: List<AudiobookRoleEntity>,
        batch: AudiobookAttributionBatch
    ): String {
        val roleList = roles.joinToString("\n") { role ->
            val identity = roleIdentity(role)
            buildString {
                append("- 精确名称：").append(role.name)
                append("；别名：").append(role.aliases.ifBlank { "无" })
                append("；性别：").append(role.gender)
                if (identity.isNotBlank()) append("；身份：").append(identity)
            }
        }
        val targetIds = batch.targetIndices.sorted().joinToString(",")
        val history = listOf(
            ChatMessage(
                ChatRole.SYSTEM,
                """
                你是中文小说有声书的对白归因与表演标注专家。程序已经锁定对白边界，并把对白嵌入连续原文：
                - target="true" 的 dialogue 必须标注；存在 locked_speaker 时，role 必须原样复制该名称，禁止修改，但仍要判断情绪和表演方式。
                - 按证据优先级判断说话人：对白前后明确“某人说/问/答” > 同段动作与称呼 > 代词与人物指代 > 连续对话的问答、轮替和话题承接 > 规则猜测。
                - 连续对话不能机械地全部继承上一人；只有文本支持时才使用轮替。不要根据角色性别、声音或主角地位臆测。
                - role 必须逐字使用角色表中的精确名称，禁止输出旁白、未知、男主、角色1或自造人物。
                - confidence 为 0 到 1；evidence 用不超过 24 个中文字概括直接证据。
                - 情绪只能是开心、悲伤、愤怒、恐惧、厌恶、惊讶、中性之一；“中性”只用于真正平静、无明显潜台词的对白。
                - instruction 用 1 到 3 个可执行短语组合，优先使用：轻声、低声、高声、急促、缓慢、颤抖、哽咽、句末短停。文本有明确表演线索时不要留空。
                只输出 JSON，不要 Markdown。格式：
                {"assignments":[{"segment_id":1,"role":"角色表中的精确名称","confidence":0.92,"evidence":"后置‘苏晚说’","emotion":"中性","instruction":"轻声，句末稍停"}]}
                每个指定 segment_id 必须输出且只能输出一次，不得输出其他 ID。
                """.trimIndent()
            ),
            ChatMessage(
                ChatRole.USER,
                """
                章节：$chapterTitle
                待标注 segment_id：$targetIds

                角色档案：
                $roleList

                上一章末尾（仅用于章节开头的指代承接，不要标注）：
                ${previousChapterTail.ifBlank { "无" }}

                连续章节原文：
                ${batch.markedContext}
                """.trimIndent()
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
            maxRounds = 3,
            modelRole = ModelRole.CHAT
        ).collect { event ->
            if (event is AgentEvent.Text) output.append(event.text)
        }
        return output.toString()
    }

    private suspend fun previousChapterTail(bookId: Long, chapterIndex: Int): String {
        val previous = libraryRepository.getChapters(bookId)
            .filter { it.chapterIndex < chapterIndex }
            .maxByOrNull { it.chapterIndex }
            ?: return ""
        return libraryRepository.readChapterText(bookId, previous)
            .takeLast(PREVIOUS_CHAPTER_CONTEXT_CHARS)
            .trim()
    }

    private fun roleIdentity(role: AudiobookRoleEntity): String = runCatching {
        AiJson.parseToJsonElement(role.extraJson).jsonObject["identity"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            .orEmpty()
    }.getOrDefault("")

    private fun normalizeEmotion(value: String?): String = value?.trim()
        ?.takeIf { it in VALID_EMOTIONS } ?: "中性"

    private companion object {
        val SAFE_TOOL_NAMES = setOf(
            "search_book",
            "read_book_section"
        )
        val VALID_EMOTIONS = setOf("开心", "悲伤", "愤怒", "恐惧", "厌恶", "惊讶", "中性")
        const val LOCAL_LOCK_CONFIDENCE = 0.85f
        const val MIN_AI_ASSIGNMENT_CONFIDENCE = 0.42f
        const val PREVIOUS_CHAPTER_CONTEXT_CHARS = 1_200
        const val RECENT_DIALOGUE_ROLE_LIMIT = 4
    }
}

private fun chooseDialogueFallback(
    draft: DraftAudiobookSegment,
    characters: List<AudiobookRoleEntity>,
    preferredCharacters: List<AudiobookRoleEntity>,
    recentRoles: List<AudiobookRoleEntity>
): AudiobookRoleEntity? {
    val recentDistinct = recentRoles.asReversed().distinctBy(AudiobookRoleEntity::id).take(2)
    if (recentDistinct.size == 2) return recentDistinct[1]
    resolveAudiobookRole(characters, draft.roleName)?.let { return it }
    return preferredCharacters.firstOrNull()
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
