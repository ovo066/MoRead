package com.mozhi.reader.ai.audiobook

import com.mozhi.reader.ai.agent.AgentEvent
import com.mozhi.reader.ai.agent.AgentLoop
import com.mozhi.reader.ai.agent.ReaderToolset
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.core.database.entity.AudiobookRoleEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.database.entity.TtsVoiceEntity
import com.mozhi.reader.core.library.AudiobookEngine
import com.mozhi.reader.core.library.AudiobookRoleKind
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.retrieval.ReadingScope
import com.mozhi.reader.core.speech.TtsVoiceRepository
import com.mozhi.reader.core.speech.SystemTtsSpeaker
import com.mozhi.reader.core.speech.SystemTtsVoiceInfo
import com.mozhi.reader.core.speech.TtsSettingsStore
import com.mozhi.reader.core.speech.TtsEngineMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class AudiobookRoleExtractionResult(
    val roles: List<AudiobookRoleEntity>,
    val usedAi: Boolean
)

@Singleton
class AudiobookRoleExtractor @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val voiceRepository: TtsVoiceRepository,
    private val agentLoop: AgentLoop,
    private val systemTtsSpeaker: SystemTtsSpeaker,
    private val settingsStore: TtsSettingsStore,
    private val readerToolset: ReaderToolset
) {
    suspend fun extract(
        bookId: Long, useAi: Boolean, chapterIndex: Int? = null,
        existingRoles: List<AudiobookRoleEntity> = emptyList()
    ): AudiobookRoleExtractionResult {
        val chapters = libraryRepository.getChapters(bookId)
        val indices = if (chapterIndex != null) listOf(chapterIndex) else {
            listOfNotNull(libraryRepository.getBook(bookId)?.lastReadChapterIndex) + representativeChapterIndices(chapters.size)
        }
        val samples = indices.distinct().mapNotNull { index ->
            chapters.firstOrNull { it.chapterIndex == index }?.let { chapter ->
                chapter.title to libraryRepository.readChapterText(bookId, chapter)
            }
        }.flatMap { (title, body) ->
            if (chapterIndex != null) body.chunked(MAX_SAMPLE_CHARS_PER_CHAPTER).map { title to it }
            else listOf(title to body.take(MAX_SAMPLE_CHARS_PER_CHAPTER))
        }
        val settings = settingsStore.current()
        val useSystem = settings.engineMode == TtsEngineMode.SYSTEM
        val voices = if (useSystem) {
            systemTtsSpeaker.voices(settings.systemEnginePackage).map {
                TtsVoiceEntity(voiceId = it.id, displayName = it.name, tags = it.description, providerHint = "SYSTEM")
            }
        } else voiceRepository.getVoices()
        val local = localRoles(bookId, samples.map(Pair<String, String>::second), voices)
        if (!useAi || samples.isEmpty()) return AudiobookRoleExtractionResult(local.map { if (useSystem) it.copy(engine = AudiobookEngine.SYSTEM.name) else it }, false)

        val parsed = samples.chunked(3).flatMap { batch ->
            parseRoles(bookId, runAgent(bookId, batch, voices, existingRoles), voices)
        }.distinctBy { it.name }
        return if (parsed.size > 1) {
            AudiobookRoleExtractionResult(parsed.map { if (useSystem) it.copy(engine = AudiobookEngine.SYSTEM.name) else it }, true)
        } else {
            AudiobookRoleExtractionResult(local.map { if (useSystem) it.copy(engine = AudiobookEngine.SYSTEM.name) else it }, false)
        }
    }

    /** Explicit recasting uses current engine candidates without changing role IDs or dialogue attribution. */
    suspend fun assignVoices(roles: List<AudiobookRoleEntity>): List<AudiobookRoleEntity> {
        val settings = settingsStore.current()
        val system = settings.engineMode == TtsEngineMode.SYSTEM
        val voices = if (system) systemTtsSpeaker.voices(settings.systemEnginePackage).map {
            TtsVoiceEntity(voiceId = it.id, displayName = it.name, tags = it.description, providerHint = "SYSTEM")
        } else voiceRepository.getVoices()
        require(voices.isNotEmpty()) { "当前引擎没有可用音色，请先刷新本地音色或配置云端音色库" }
        val candidates = voices.joinToString("\n") { it.voiceId+"｜"+it.displayName+"｜"+it.gender+"｜"+it.tags }
        val characters = roles.joinToString("\n") { it.name+"｜"+it.gender+"｜"+it.extraJson }
        val output = StringBuilder()
        agentLoop.runDetached(
            history = listOf(
                ChatMessage(ChatRole.SYSTEM, "你是有声书选角导演。为给定角色含旁白选择音色，结合身份、性格、年龄、性别；主要角色尽量不同，候选不足时允许复用。只能使用提供的精确角色名和音色ID，不能编造。只输出JSON：{\"voiceAssignments\":{\"角色名\":\"voice-id\"}}"),
                ChatMessage(ChatRole.USER, "角色：\n"+characters+"\n候选音色：\n"+candidates)
            ), tools = emptyList(), maxRounds = 1, modelRole = ModelRole.CHEAP
        ).collect { if (it is AgentEvent.Text) output.append(it.text) }
        val assignments = VoiceAssignmentParser.parse(extractJsonPayload(output.toString()), voices.map { it.voiceId }.toSet())
        require(roles.all { assignments.containsKey(it.name) }) { "AI 未完整分配音色，原分配已保留，请重试" }
        return roles.map { it.copy(engine = if (system) AudiobookEngine.SYSTEM.name else AudiobookEngine.AI.name,
            voiceId = assignments.getValue(it.name)) }
    }

    private suspend fun runAgent(
        bookId: Long,
        samples: List<Pair<String, String>>,
        voices: List<TtsVoiceEntity>,
        existingRoles: List<AudiobookRoleEntity>
    ): String {
        val sampleText = buildString {
            samples.forEachIndexed { index, (title, body) ->
                append("\n--- 样本 ").append(index + 1).append("：").append(title).append(" ---\n")
                append(body.take(MAX_SAMPLE_CHARS_PER_CHAPTER)).append('\n')
            }
        }.take(MAX_SAMPLE_CHARS)
        val existingText = existingRoles.joinToString("；") { it.name + "（" + it.aliases + "）" }
        val voiceText = voices.joinToString("\n") { voice ->
            "- ${voice.voiceId}｜${voice.displayName}｜性别=${voice.gender}｜标签=${voice.tags}"
        }.ifBlank { "（当前引擎没有公开可选音色；voiceAssignments 输出空对象）" }
        val history = listOf(
            ChatMessage(
                ChatRole.SYSTEM,
                """
                你是小说有声书角色识别专家。你可以检索整本书核对人物，但不得写批注、笔记、摘要或生成图片。
                只输出一个 JSON 对象，不要 Markdown。格式：
                {"roles":[{"name":"角色名","aliases":["别名"],"gender":"MALE|FEMALE|UNSPECIFIED","identity":"简述","frequency":12}],"voiceAssignments":{"角色名":"voice-id"}}
                规则：旁白不放在 roles；角色名必须是人名或稳定称呼，不得使用“他、她、我、你”；只保留实际说话角色；voiceAssignments 只能使用候选音色 id。综合年龄、身份、性格、性别和声音标签分配；主要角色尽量使用不同音色，没有性别信息的音色不要臆断。将同一人物的姓名、绰号、敬称合并为一个角色的 aliases。
                """.trimIndent()
            ),
            ChatMessage(
                ChatRole.USER,
                "已知人物（同一人物沿用精确姓名）：$existingText\n候选音色：\n$voiceText\n\n请结合以下代表性章节抽取角色并分配音色：\n$sampleText"
            )
        )
        val output = StringBuilder()
        agentLoop.runDetached(
            history = history,
            tools = readerToolset.forBook(
                bookId = bookId,
                enabledTools = SAFE_TOOL_NAMES,
                readingScope = ReadingScope.WholeBook
            ),
            modelRole = ModelRole.CHEAP
        ).collect { event ->
            if (event is AgentEvent.Text) output.append(event.text)
        }
        return output.toString()
    }

    private fun localRoles(
        bookId: Long,
        chapterBodies: List<String>,
        voices: List<TtsVoiceEntity>
    ): List<AudiobookRoleEntity> {
        val counts = linkedMapOf<String, Int>()
        chapterBodies.forEach { body ->
            DialogueRuleSegmenter.segment(body)
                .asSequence()
                .filter { it.kind == AudiobookSegmentKind.DIALOGUE && it.confidence >= 0.85f && it.roleName !in INVALID_ROLE_NAMES }
                .forEach { segment -> counts[segment.roleName] = (counts[segment.roleName] ?: 0) + 1 }
        }
        val roles = counts.entries
            .sortedByDescending(Map.Entry<String, Int>::value)
            .take(MAX_LOCAL_ROLES)
            .mapIndexed { index, (name, count) ->
                AudiobookRoleEntity(
                    bookId = bookId,
                    name = name,
                    kind = AudiobookRoleKind.CHARACTER.name,
                    engine = if (voices.firstOrNull()?.providerHint == "SYSTEM") AudiobookEngine.SYSTEM.name else AudiobookEngine.AI.name,
                    voiceId = pickVoice(voices, "UNSPECIFIED", index)?.voiceId.orEmpty(),
                    extraJson = buildJsonObject { put("frequency", count) }.toString(),
                    color = roleColor(index + 1),
                    sortOrder = index + 1,
                    source = "RULE"
                )
            }
        return listOf(narrator(bookId, voices)) + roles
    }

    private fun parseRoles(
        bookId: Long,
        raw: String,
        voices: List<TtsVoiceEntity>
    ): List<AudiobookRoleEntity> {
        val payload = extractJsonPayload(raw)
        val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject
            ?: return emptyList()
        val allowedVoices = voices.map(TtsVoiceEntity::voiceId).toSet()
        val assignments = VoiceAssignmentParser.parse(payload, allowedVoices)
        val items = root["roles"] as? JsonArray ?: return emptyList()
        val seen = mutableSetOf<String>()
        val roles = items.mapIndexedNotNull { index, element ->
            val item = element as? JsonObject ?: return@mapIndexedNotNull null
            val name = item.string("name")?.take(MAX_ROLE_NAME_CHARS) ?: return@mapIndexedNotNull null
            if (name in INVALID_ROLE_NAMES || !seen.add(name)) return@mapIndexedNotNull null
            val aliases = when (val value = item["aliases"]) {
                is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                is JsonPrimitive -> value.contentOrNull.orEmpty().split(',', '，').map(String::trim)
                else -> emptyList()
            }.filter(String::isNotEmpty).distinct().joinToString(",")
            val gender = item.string("gender")?.uppercase()
                ?.takeIf { it in VALID_GENDERS } ?: "UNSPECIFIED"
            val identity = item.string("identity", "description").orEmpty()
            val frequency = (item["frequency"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            val assigned = assignments[name]
                ?: pickVoice(voices, gender, index)?.voiceId.orEmpty()
            AudiobookRoleEntity(
                bookId = bookId,
                name = name,
                aliases = aliases,
                kind = AudiobookRoleKind.CHARACTER.name,
                gender = gender,
                engine = if (SystemTtsVoiceInfo.decode(assigned) != null) AudiobookEngine.SYSTEM.name else AudiobookEngine.AI.name,
                voiceId = assigned,
                extraJson = buildJsonObject {
                    put("identity", identity)
                    put("frequency", frequency)
                }.toString(),
                color = roleColor(index + 1),
                sortOrder = index + 1,
                source = "AI"
            )
        }
        return listOf(narrator(bookId, voices)) + roles
    }

    private fun narrator(bookId: Long, voices: List<TtsVoiceEntity>) = AudiobookRoleEntity(
        bookId = bookId,
        name = "旁白",
        kind = AudiobookRoleKind.NARRATOR.name,
        engine = AudiobookEngine.SYSTEM.name,
        voiceId = voices.firstOrNull { it.providerHint == "SYSTEM" }?.voiceId.orEmpty(),
        color = roleColor(0),
        sortOrder = 0,
        source = "SYSTEM"
    )

    private fun pickVoice(
        voices: List<TtsVoiceEntity>,
        gender: String,
        offset: Int
    ): TtsVoiceEntity? {
        val matching = voices.filter { it.gender.equals(gender, ignoreCase = true) }
            .ifEmpty { voices }
        return matching.getOrNull(offset % matching.size.coerceAtLeast(1))
    }

    private fun JsonObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun representativeChapterIndices(count: Int): List<Int> {
        if (count <= 0) return emptyList()
        return listOf(0, count / 3, count * 2 / 3, count - 1)
            .map { it.coerceIn(0, count - 1) }
            .distinct()
    }

    private fun roleColor(index: Int): String = ROLE_COLORS[index % ROLE_COLORS.size]

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val SAFE_TOOL_NAMES = setOf("search_book")
        val INVALID_ROLE_NAMES = setOf("旁白", "他", "她", "它", "我", "你", "对白", "未知", "角色", "null")
        val VALID_GENDERS = setOf("MALE", "FEMALE", "UNSPECIFIED")
        val ROLE_COLORS = listOf(
            "#607D8B", "#5C6BC0", "#26A69A", "#EC407A",
            "#AB47BC", "#FF7043", "#42A5F5", "#66BB6A"
        )
        const val MAX_SAMPLE_CHARS_PER_CHAPTER = 7_000
        const val MAX_SAMPLE_CHARS = 24_000
        const val MAX_LOCAL_ROLES = 24
        const val MAX_ROLE_NAME_CHARS = 40
    }
}

internal fun extractJsonPayload(raw: String): String {
    val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        .findAll(raw).lastOrNull()?.groupValues?.getOrNull(1)?.trim()
    if (!fenced.isNullOrBlank()) return fenced
    val trimmed = raw.trim()
    val objectStart = trimmed.indexOf('{')
    val arrayStart = trimmed.indexOf('[')
    val start = listOf(objectStart, arrayStart).filter { it >= 0 }.minOrNull() ?: return trimmed
    val end = maxOf(trimmed.lastIndexOf('}'), trimmed.lastIndexOf(']'))
    return if (end >= start) trimmed.substring(start, end + 1) else trimmed
}
