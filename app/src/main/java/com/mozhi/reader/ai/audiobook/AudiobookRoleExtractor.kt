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
    private val readerToolset: ReaderToolset
) {
    suspend fun extract(bookId: Long, useAi: Boolean): AudiobookRoleExtractionResult {
        val chapters = libraryRepository.getChapters(bookId)
        val samples = representativeChapterIndices(chapters.size).mapNotNull { chapterIndex ->
            chapters.firstOrNull { it.chapterIndex == chapterIndex }?.let { chapter ->
                chapter.title to libraryRepository.readChapterText(bookId, chapter)
            }
        }
        val voices = voiceRepository.getVoices()
        val local = localRoles(bookId, samples.map(Pair<String, String>::second), voices)
        if (!useAi || samples.isEmpty()) return AudiobookRoleExtractionResult(local, false)

        val raw = runAgent(bookId, samples, voices)
        val parsed = parseRoles(bookId, raw, voices)
        return if (parsed.size > 1) {
            AudiobookRoleExtractionResult(parsed, true)
        } else {
            AudiobookRoleExtractionResult(local, false)
        }
    }

    private suspend fun runAgent(
        bookId: Long,
        samples: List<Pair<String, String>>,
        voices: List<TtsVoiceEntity>
    ): String {
        val sampleText = buildString {
            samples.forEachIndexed { index, (title, body) ->
                append("\n--- 样本 ").append(index + 1).append("：").append(title).append(" ---\n")
                append(body.take(MAX_SAMPLE_CHARS_PER_CHAPTER)).append('\n')
            }
        }.take(MAX_SAMPLE_CHARS)
        val voiceText = voices.joinToString("\n") { voice ->
            "- ${voice.voiceId}｜${voice.displayName}｜性别=${voice.gender}｜标签=${voice.tags}"
        }.ifBlank { "（当前没有可用 AI 音色；voiceAssignments 输出空对象）" }
        val history = listOf(
            ChatMessage(
                ChatRole.SYSTEM,
                """
                你是小说有声书角色识别专家。你可以检索整本书核对人物，但不得写批注、笔记、摘要或生成图片。
                只输出一个 JSON 对象，不要 Markdown。格式：
                {"roles":[{"name":"角色名","aliases":["别名"],"gender":"MALE|FEMALE|UNSPECIFIED","identity":"简述","frequency":12}],"voiceAssignments":{"角色名":"voice-id"}}
                规则：旁白不放在 roles；角色名必须是人名或稳定称呼，不得使用“他、她、我、你”；只保留实际说话角色；voiceAssignments 只能使用候选音色 id。
                """.trimIndent()
            ),
            ChatMessage(
                ChatRole.USER,
                "候选音色：\n$voiceText\n\n请结合以下代表性章节抽取角色并分配音色：\n$sampleText"
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
                .filter { it.kind == AudiobookSegmentKind.DIALOGUE }
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
                    engine = AudiobookEngine.AI.name,
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
                is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                is JsonPrimitive -> value.contentOrNull.orEmpty().split(',', '，').map(String::trim)
                else -> emptyList()
            }.filter(String::isNotEmpty).distinct().joinToString(",")
            val gender = item.string("gender")?.uppercase()
                ?.takeIf { it in VALID_GENDERS } ?: "UNSPECIFIED"
            val identity = item.string("identity", "description").orEmpty()
            val frequency = item["frequency"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val assigned = assignments[name]
                ?: pickVoice(voices, gender, index)?.voiceId.orEmpty()
            AudiobookRoleEntity(
                bookId = bookId,
                name = name,
                aliases = aliases,
                kind = AudiobookRoleKind.CHARACTER.name,
                gender = gender,
                engine = AudiobookEngine.AI.name,
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
        voiceId = voices.firstOrNull { "旁白" in it.tags }?.voiceId.orEmpty(),
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
        get(key)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
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
        val INVALID_ROLE_NAMES = setOf("旁白", "他", "她", "它", "我", "你", "null")
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
