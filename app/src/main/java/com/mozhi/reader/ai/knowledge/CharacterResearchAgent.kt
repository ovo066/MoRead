package com.mozhi.reader.ai.knowledge

import com.mozhi.reader.ai.agent.AgentEvent
import com.mozhi.reader.ai.agent.AgentLoop
import com.mozhi.reader.ai.agent.AgentTool
import com.mozhi.reader.ai.agent.MemoryScope
import com.mozhi.reader.ai.agent.ReaderToolset
import com.mozhi.reader.ai.agent.ToolResult
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.ai.client.ResolvedChatClient
import com.mozhi.reader.ai.client.ToolSpec
import com.mozhi.reader.core.retrieval.ReadingScope
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 快速人物提取一次运行所需的全部输入；正文已截到 [scope] 以内。 */
internal class CharacterResearchRequest(
    val bookId: Long,
    val bookTitle: String,
    val scope: ReadingScope,
    val progressBounded: Boolean,
    /** (章节序号, 可读正文)，按章节顺序。 */
    val chapters: List<Pair<Int, String>>,
    val chapterTitle: (Int) -> String,
    val mainModel: ResolvedChatClient,
    val helperModel: ResolvedChatClient,
    val webSearch: Boolean,
    val validate: suspend () -> Unit
)

internal data class CharacterResearchResult(
    val people: List<Pair<Int, KnowledgeCharacter>>,
    val order: List<String>,
    val externalNotes: List<ExternalCharacterNote>,
    val helperRequests: Int
)

/**
 * 快速人物提取：一个主 agent 统筹，便宜模型的子 agent 按人物并行整理。
 *
 * 主 agent 先看本地统计出的候选人名，用检索工具核对、合并称呼，再分批派发子 agent；
 * 人物事实只来自子 agent 核对过原文的结果，主 agent 自己写不进去。联网结果只进「书外资料」。
 * 相比逐段扫描整本书，请求数与人物数相关而与书的长度无关。
 */
class CharacterResearchAgent @Inject internal constructor(
    private val loop: AgentLoop,
    private val agent: ChapterKnowledgeAgent,
    private val readerTools: ReaderToolset
) {
    internal suspend fun run(request: CharacterResearchRequest, onProgress: (String) -> Unit): CharacterResearchResult {
        val candidates by lazy { CharacterCandidates.rank(request.chapters, limit = 40) }
        val mutex = Mutex()
        val profiled = linkedMapOf<String, List<Pair<Int, KnowledgeCharacter>>>()
        val notes = mutableListOf<ExternalCharacterNote>()
        var order = emptyList<String>()
        var submitted = false
        var helperRequests = 0

        suspend fun profile(targets: List<Pair<String, List<String>>>): String = coroutineScope {
            val fresh = mutex.withLock { targets.filter { it.first !in profiled }.take(MAX_PROFILES_PER_CALL) }
            if (fresh.isEmpty()) return@coroutineScope "这些人物已经整理过了。"
            onProgress(PROGRESS_PROFILING + fresh.joinToString("、") { it.first })
            val results = fresh.map { (name, aliases) ->
                async {
                    val passages = CharacterPassages.collect(request.chapters, listOf(name) + aliases)
                    val result = if (passages.isEmpty()) {
                        Result.success(emptyList())
                    } else {
                        mutex.withLock { helperRequests++ }
                        try {
                            Result.success(agent.profileCharacter(request.helperModel, request.bookTitle, name, aliases, passages,
                                request.chapterTitle, request.validate))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Result.failure(error)
                        }
                    }
                    name to result
                }
            }.awaitAll()
            mutex.withLock {
                results.forEach { (name, result) -> result.getOrNull()?.takeIf { it.isNotEmpty() }?.let { profiled[name] = it } }
            }
            results.joinToString("\n") { (name, result) ->
                result.fold(
                    onSuccess = { people -> summarize(name, people) },
                    onFailure = { "$name：整理失败（${it.message?.take(80) ?: "未知原因"}）" }
                )
            }
        }

        val tools = buildList {
            add(tool("rank_name_candidates", "列出本地统计的候选人名（字面频次、对白次数、出现章数、首次出现章节）。会混入非人名，需自行判断。",
                buildJsonObject { putJsonObject("limit") { put("type", "integer"); put("description", "返回条数，默认 30，最多 40") } }) { arguments ->
                val limit = (arguments["limit"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 40)
                val rows = candidates.take(limit)
                if (rows.isEmpty()) ToolResult.Success("没有统计出明显的人名，请用 search_book 检索。")
                else ToolResult.Success(rows.joinToString("\n", prefix = "候选（名字 | 出现 | 对白 | 章数 | 首次）：\n") {
                    "${it.name} | ${it.count} | ${it.dialogue} | ${it.chapters} | 第${it.firstChapter + 1}章"
                })
            })
            add(tool("profile_characters", "派助手为一批人物各自整理有原文依据的资料（身份、经历、外貌、关系），每次最多 $MAX_PROFILES_PER_CALL 人，同批并行。" +
                "同一人物的其他称呼放进 aliases，助手会一并检索。返回每人整理到的要点。",
                buildJsonObject {
                    putJsonObject("characters") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("name") { put("type", "string") }
                                putJsonObject("aliases") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                            }
                            putJsonArray("required") { add("name") }
                        }
                    }
                }, required = listOf("characters")) { arguments ->
                val targets = (arguments["characters"] as? JsonArray).orEmpty().mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.length in 1..40 } ?: return@mapNotNull null
                    val aliases = (obj["aliases"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                        .filter { it.length in 1..40 && it != name }.distinct().take(6)
                    name to aliases
                }
                if (targets.isEmpty()) ToolResult.Failure("INVALID_ARGUMENT", "characters 至少需要一个 name")
                else ToolResult.Success(profile(targets))
            })
            if (request.webSearch) add(tool("note_external", "记录一条联网查到的书外资料（附来源网址）。它会单独标为书外资料，不会写进人物事实。",
                buildJsonObject {
                    putJsonObject("text") { put("type", "string"); put("description", "200 字以内") }
                    putJsonObject("character") { put("type", "string") }
                    putJsonObject("url") { put("type", "string") }
                    putJsonObject("title") { put("type", "string") }
                }, required = listOf("text")) { arguments ->
                val text = arguments["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (text.isEmpty()) return@tool ToolResult.Failure("INVALID_ARGUMENT", "缺少 text")
                mutex.withLock {
                    if (notes.size < MAX_EXTERNAL_NOTES) notes += ExternalCharacterNote(
                        text.take(200),
                        arguments["character"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(40),
                        arguments["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(500),
                        arguments["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(80)
                    )
                }
                ToolResult.Success("已记录。")
            })
            add(tool("submit_character_guide", "整理完成后提交按重要性排序的人物名单（只填 profile_characters 整理过的人物名），提交后结束。",
                buildJsonObject {
                    putJsonObject("order") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                }, required = listOf("order")) { arguments ->
                val names = (arguments["order"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                val known = mutex.withLock { profiled.keys.toSet() }
                if (known.isEmpty()) return@tool ToolResult.Failure("NOTHING_PROFILED", "还没有整理任何人物，请先调用 profile_characters")
                order = names.filter { it in known }.distinct()
                submitted = true
                ToolResult.Success("已提交 ${known.size} 位人物。")
            })
            val readOnly = setOf("search_book", "grep_book", "read_book_section", "list_chapters") +
                if (request.webSearch) setOf("web_search", "web_scrape") else emptySet()
            addAll(readerTools.forBook(request.bookId, enabledTools = readOnly, readingScope = request.scope,
                memoryScope = MemoryScope(longTermEnabled = false), buildMissingIndex = false).filter { it.spec.name in readOnly })
        }

        onProgress(PROGRESS_PLANNING)
        loop.runDetachedWith(
            history = listOf(
                ChatMessage(ChatRole.SYSTEM, systemPrompt(request)),
                ChatMessage(ChatRole.USER, "开始整理《${request.bookTitle}》的人物资料。")
            ),
            tools = tools,
            maxRounds = MAX_MAIN_ROUNDS
        ) {
            val model = request.mainModel
            AgentLoop.Streamer { messages, specs ->
                kotlinx.coroutines.flow.flow {
                    request.validate()
                    emitAll(model.client.chatStream(messages, specs, model.options))
                }
            }
        }.takeWhile { !submitted }.collect { event ->
            if (event is AgentEvent.ToolRun && event.toolName != "profile_characters") onProgress(PROGRESS_SEARCHING)
        }
        request.validate()
        // 主 agent 没有派发任何整理时（模型不擅长工具调用），按候选前几位直接整理，保证有结果。
        if (mutex.withLock { profiled.isEmpty() }) {
            candidates.take(FALLBACK_PROFILES).map { it.name to emptyList<String>() }.chunked(MAX_PROFILES_PER_CALL).forEach { profile(it) }
        }
        val people = mutex.withLock { profiled.toMap() }
        require(people.isNotEmpty()) { "没有整理出有原文依据的人物，请换用更强的模型或改用完整扫描" }
        return CharacterResearchResult(
            people = people.values.flatten(),
            order = order.ifEmpty { people.keys.toList() },
            externalNotes = mutex.withLock { notes.toList() },
            helperRequests = helperRequests
        )
    }

    private fun systemPrompt(request: CharacterResearchRequest): String = buildString {
        append("你是人物资料整理的主编，为《").append(request.bookTitle).append("》整理主要人物。")
        append(if (request.progressBounded) "范围只到读者当前的阅读进度，工具只返回已读内容。" else "范围是整本书。")
        append("\n可用工具：rank_name_candidates 查看本地统计的候选人名；search_book / grep_book / read_book_section 检索核对；")
        append("profile_characters 派助手为人物各自整理有原文依据的资料；submit_character_guide 提交最终名单。")
        append("\n建议流程：先看候选，剔除非人名（如称谓、普通词语），把同一人的不同称呼合并为 aliases；")
        append("按重要性分批派发 profile_characters（通常 8–20 人，可以在同一轮里同时发起多批）；")
        append("对候选里没有、但检索中反复出现的重要人物补充派发；最后调用 submit_character_guide 给出按重要性排序的名单。")
        append("\n人物事实只能来自 profile_characters 的结果，你不要自己编写或补全人物资料。不要输出与整理无关的长篇文字。")
        if (request.webSearch) {
            if (request.progressBounded) {
                append("\n可以联网，但只能用来确认书名、作者、通行译名与人物称呼的写法，严禁查询、记录或透露读者尚未读到的任何剧情。")
            } else {
                append("\n可以联网查人物背景与通行译名。")
            }
            append("联网得到的信息只能用 note_external 记录并附来源网址，它们会单独标为书外资料。")
        }
    }

    private fun summarize(name: String, people: List<Pair<Int, KnowledgeCharacter>>): String {
        if (people.isEmpty()) return "$name：段落中没有足够依据，未整理。"
        val target = people.filter { it.second.name == name }.ifEmpty { people.filter { it.second.name == people.first().second.name } }
        val facts = target.flatMap { it.second.facts }.map { it.text }.distinct().take(3)
        val aliases = target.flatMap { it.second.attributes }.filter { it.kind == CharacterAttributeKind.ALIAS }.map { it.value }.distinct()
        val relations = target.flatMap { it.second.relationships }.map { "${it.target}（${it.relation}）" }.distinct().take(5)
        val others = people.map { it.second.name }.distinct().filter { it != target.first().second.name }
        return buildString {
            append(target.first().second.name).append("：").append(facts.joinToString("；"))
            if (aliases.isNotEmpty()) append(" | 别名：").append(aliases.joinToString("、"))
            if (relations.isNotEmpty()) append(" | 关系：").append(relations.joinToString("、"))
            if (others.isNotEmpty()) append(" | 顺带整理：").append(others.joinToString("、"))
        }
    }

    private fun tool(name: String, description: String, properties: JsonObject, required: List<String> = emptyList(),
        run: suspend (JsonObject) -> ToolResult): AgentTool = object : AgentTool {
        override val displayName = name
        // 派发与检索可以并行；提交与记录书外资料有状态，保持串行。
        override val concurrencySafe = name == "rank_name_candidates" || name == "profile_characters"
        override val spec = ToolSpec(name, description, buildJsonObject {
            put("type", "object")
            put("properties", properties)
            putJsonArray("required") { required.forEach { add(it) } }
        })
        override suspend fun execute(arguments: JsonObject): ToolResult = run(arguments)
    }

    internal companion object {
        const val MAX_MAIN_ROUNDS = 8
        const val MAX_PROFILES_PER_CALL = 6
        const val MAX_EXTERNAL_NOTES = 20
        const val FALLBACK_PROFILES = 12
        // 进度文案在设置层翻译；这里只给出阶段前缀，UI 按前缀匹配资源。
        const val PROGRESS_PLANNING = "planning"
        const val PROGRESS_SEARCHING = "searching"
        const val PROGRESS_PROFILING = "profiling:"
    }
}

/** 为一个人物从可读正文里挑段落：覆盖首次出场、中段与最近的出现，合并重叠窗口，总量有上限。 */
internal object CharacterPassages {
    private const val BEFORE = 300
    private const val AFTER = 420
    private const val MAX_PASSAGES = 8
    private const val MAX_CHARS = 9_000

    fun collect(chapters: List<Pair<Int, String>>, names: List<String>): List<SourcePassage> {
        val hits = ArrayList<Pair<Int, Int>>()
        chapters.forEachIndexed { position, (_, text) ->
            names.forEach { name ->
                var from = 0
                while (hits.size < 5_000) {
                    val at = text.indexOf(name, from)
                    if (at < 0) break
                    hits += position to at
                    from = at + name.length
                }
            }
        }
        if (hits.isEmpty()) return emptyList()
        hits.sortWith(compareBy({ it.first }, { it.second }))
        val picks = if (hits.size <= MAX_PASSAGES) hits else
            (0 until MAX_PASSAGES).map { hits[(it.toLong() * (hits.size - 1) / (MAX_PASSAGES - 1)).toInt()] }.distinct()
        val windows = picks.map { (position, at) ->
            val text = chapters[position].second
            val start = sentenceStart(text, (at - BEFORE).coerceAtLeast(0))
            val end = sentenceEnd(text, (at + AFTER).coerceAtMost(text.length))
            Triple(position, start, end)
        }.groupBy { it.first }.flatMap { (_, rows) ->
            rows.sortedBy { it.second }.fold(mutableListOf<Triple<Int, Int, Int>>()) { merged, row ->
                val last = merged.lastOrNull()
                if (last != null && row.second <= last.third) merged[merged.lastIndex] = last.copy(third = maxOf(last.third, row.third))
                else merged += row
                merged
            }
        }.sortedWith(compareBy({ it.first }, { it.second }))
        var budget = MAX_CHARS
        return windows.mapNotNull { (position, start, end) ->
            if (budget <= 0) return@mapNotNull null
            val clippedEnd = minOf(end, start + budget)
            budget -= clippedEnd - start
            val (chapterIndex, text) = chapters[position]
            SourcePassage(chapterIndex, start, text.substring(start, clippedEnd))
        }
    }

    private fun sentenceStart(text: String, from: Int): Int {
        if (from == 0) return 0
        val boundary = (from downTo maxOf(0, from - 80)).firstOrNull { it > 0 && text[it - 1] in "\n。！？!?" }
        return boundary ?: from
    }

    private fun sentenceEnd(text: String, to: Int): Int {
        if (to >= text.length) return text.length
        val boundary = (to until minOf(text.length, to + 80)).firstOrNull { text[it] in "\n。！？!?" }
        return boundary?.plus(1) ?: to
    }
}
