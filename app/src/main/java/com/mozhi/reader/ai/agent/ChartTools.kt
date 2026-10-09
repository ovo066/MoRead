package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ToolSpec
import com.mozhi.reader.core.retrieval.ReadingScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 伴读能画的图。柱状/折线交给 Vico，饼图、雷达、关系图与时间线在 Canvas 上自绘。 */
enum class ChartType(val wire: String) {
    BAR("bar"), LINE("line"), PIE("pie"), RADAR("radar"), RELATION("relation"), TIMELINE("timeline");

    companion object {
        fun fromWire(value: String?): ChartType? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

@Serializable
data class ChartSeries(val name: String = "", val values: List<Double> = emptyList())

@Serializable
data class ChartNode(val id: String, val label: String = "", val group: String = "")

@Serializable
data class ChartEdge(val from: String, val to: String, val label: String = "")

@Serializable
data class ChartEvent(val time: String = "", val title: String, val detail: String = "")

/** 已通过校验的图表规格；落库形态就是 create_chart 的调用参数，不另建表。 */
@Serializable
data class ChartSpec(
    val type: String,
    val title: String = "",
    val unit: String = "",
    val labels: List<String> = emptyList(),
    val series: List<ChartSeries> = emptyList(),
    val nodes: List<ChartNode> = emptyList(),
    val edges: List<ChartEdge> = emptyList(),
    val events: List<ChartEvent> = emptyList()
) {
    val chartType: ChartType get() = requireNotNull(ChartType.fromWire(type))
}

/**
 * 规格校验：工具端用它给模型可修正的错误说明，渲染端用它挡住历史里不合法的参数。
 * 上限按手机一屏能读清来定，超出就让模型合并或抽样，而不是硬塞。
 */
object ChartSpecCodec {
    const val MAX_POINTS = 50
    const val MAX_SERIES = 6
    const val MAX_NODES = 30
    const val MAX_EDGES = 60
    const val MAX_EVENTS = 24
    const val MAX_LABEL = 24
    /** create_chart 成功结果的开头；历史消息据此判断这张图当时是否真的画出来了。 */
    const val SUCCESS_PREFIX = "图表「"

    /** 已落库的一次 create_chart：结果成功且参数仍能通过校验，才在界面上画出来。 */
    fun fromToolCall(arguments: String, result: String?): ChartSpec? {
        if (result == null || !result.startsWith(SUCCESS_PREFIX)) return null
        return (decode(arguments) as? Outcome.Valid)?.spec
    }

    sealed interface Outcome {
        data class Valid(val spec: ChartSpec) : Outcome
        data class Invalid(val reason: String) : Outcome
    }

    fun decode(arguments: String): Outcome = try {
        validate(AiJson.decodeFromString(ChartSpec.serializer(), arguments))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Outcome.Invalid("参数不是合法的图表规格：${error.message?.take(160) ?: "无法解析"}")
    }

    fun validate(raw: ChartSpec): Outcome {
        val type = ChartType.fromWire(raw.type) ?: return Outcome.Invalid("type 只能是 bar/line/pie/radar/relation/timeline")
        val spec = raw.copy(
            type = type.wire,
            title = raw.title.trim().take(40),
            unit = raw.unit.trim().take(8),
            labels = raw.labels.map { it.trim().take(MAX_LABEL) },
            series = raw.series.map { it.copy(name = it.name.trim().take(MAX_LABEL)) },
            nodes = raw.nodes.map { it.copy(id = it.id.trim(), label = it.label.trim().ifBlank { it.id.trim() }.take(MAX_LABEL), group = it.group.trim().take(MAX_LABEL)) },
            edges = raw.edges.map { it.copy(from = it.from.trim(), to = it.to.trim(), label = it.label.trim().take(MAX_LABEL)) },
            events = raw.events.map { it.copy(time = it.time.trim().take(MAX_LABEL), title = it.title.trim().take(40), detail = it.detail.trim().take(120)) }
        )
        val problem = when (type) {
            ChartType.BAR, ChartType.LINE, ChartType.RADAR, ChartType.PIE -> cartesianProblem(spec, type)
            ChartType.RELATION -> relationProblem(spec)
            ChartType.TIMELINE -> when {
                spec.events.isEmpty() -> "timeline 需要 events"
                spec.events.size > MAX_EVENTS -> "events 最多 $MAX_EVENTS 个，请合并相近事件"
                spec.events.any { it.title.isBlank() } -> "每个 event 都需要 title"
                else -> null
            }
        }
        return problem?.let(Outcome::Invalid) ?: Outcome.Valid(spec)
    }

    private fun cartesianProblem(spec: ChartSpec, type: ChartType): String? {
        val labels = spec.labels
        return when {
            labels.isEmpty() -> "需要 labels（横轴或分类名称）"
            labels.size > MAX_POINTS -> "labels 最多 $MAX_POINTS 个，请按区间合并（例如每 5 章一组）"
            spec.series.isEmpty() -> "需要至少一个 series"
            spec.series.size > MAX_SERIES -> "series 最多 $MAX_SERIES 个"
            type == ChartType.PIE && spec.series.size != 1 -> "pie 只能有一个 series"
            type == ChartType.RADAR && labels.size !in 3..12 -> "radar 需要 3-12 个维度"
            spec.series.any { it.values.size != labels.size } -> "每个 series 的 values 数量必须与 labels 相同（${labels.size} 个）"
            spec.series.any { series -> series.values.any { !it.isFinite() } } -> "values 只能是有限数字"
            type == ChartType.PIE && spec.series.single().values.any { it < 0 } -> "pie 的数值不能为负"
            type == ChartType.PIE && spec.series.single().values.sum() <= 0.0 -> "pie 的数值总和必须大于 0"
            else -> null
        }
    }

    private fun relationProblem(spec: ChartSpec): String? {
        val ids = spec.nodes.map { it.id }
        return when {
            spec.nodes.size < 2 -> "relation 至少需要 2 个 nodes"
            spec.nodes.size > MAX_NODES -> "nodes 最多 $MAX_NODES 个，请只保留主要人物"
            ids.any { it.isBlank() } -> "每个 node 都需要 id"
            ids.toSet().size != ids.size -> "node 的 id 不能重复"
            spec.edges.isEmpty() -> "relation 需要 edges"
            spec.edges.size > MAX_EDGES -> "edges 最多 $MAX_EDGES 条"
            spec.edges.any { it.from !in ids || it.to !in ids } -> "edges 的 from/to 必须引用已有 node 的 id"
            spec.edges.any { it.from == it.to } -> "edge 不能指向自己"
            else -> null
        }
    }
}

/** 把一份规格画成图卡。只做本地校验，不调用模型，也不读书。 */
internal class CreateChartTool : AgentTool {
    override val displayName = "绘制图表"
    override val spec = ToolSpec(
        name = "create_chart",
        description = "把整理好的数据画成图表，显示在你的回复上方。适合出场趋势、情绪变化、阵营占比、人物关系、事件时间线等。" +
            "数据必须来自工具结果或已读内容（可先用 count_mentions / search_book 取数），不要编造数字。" +
            "bar/line/radar/pie 用 labels + series（每个 series.values 与 labels 一一对应，pie 只能一个 series）；" +
            "relation 用 nodes + edges；timeline 用 events。调用成功后用一两句话说明要点，不要再用文字表格重复数据。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("type") {
                    put("type", "string")
                    putJsonArray("enum") { ChartType.entries.forEach { add(it.wire) } }
                }
                putJsonObject("title") { put("type", "string"); put("description", "图表标题，40 字以内") }
                putJsonObject("unit") { put("type", "string"); put("description", "数值单位，如 次、章，可空") }
                putJsonObject("labels") {
                    put("type", "array"); putJsonObject("items") { put("type", "string") }
                    put("description", "横轴/分类/雷达维度名称，最多 ${ChartSpecCodec.MAX_POINTS} 个")
                }
                putJsonObject("series") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("name") { put("type", "string") }
                            putJsonObject("values") { put("type", "array"); putJsonObject("items") { put("type", "number") } }
                        }
                        putJsonArray("required") { add("values") }
                    }
                }
                putJsonObject("nodes") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("id") { put("type", "string") }
                            putJsonObject("label") { put("type", "string") }
                            putJsonObject("group") { put("type", "string"); put("description", "阵营或分组，用于配色") }
                        }
                        putJsonArray("required") { add("id") }
                    }
                }
                putJsonObject("edges") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("from") { put("type", "string") }
                            putJsonObject("to") { put("type", "string") }
                            putJsonObject("label") { put("type", "string"); put("description", "关系，如 师徒、宿敌") }
                        }
                        putJsonArray("required") { add("from"); add("to") }
                    }
                }
                putJsonObject("events") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("time") { put("type", "string"); put("description", "章节或故事内时间") }
                            putJsonObject("title") { put("type", "string") }
                            putJsonObject("detail") { put("type", "string") }
                        }
                        putJsonArray("required") { add("title") }
                    }
                }
            }
            putJsonArray("required") { add("type") }
        }
    )

    override suspend fun execute(arguments: JsonObject): ToolResult =
        when (val outcome = ChartSpecCodec.decode(arguments.toString())) {
            is ChartSpecCodec.Outcome.Invalid -> ToolResult.Failure("INVALID_CHART", "图表未生成：${outcome.reason}。请修正后重新调用。")
            is ChartSpecCodec.Outcome.Valid -> ToolResult.Success(
                "${ChartSpecCodec.SUCCESS_PREFIX}${outcome.spec.title.ifBlank { "未命名" }}」已生成并显示给用户。请用一两句话说明要点，不要再列出全部数据。"
            )
        }
}

/**
 * 按章统计若干名字或关键词的出现次数，只在阅读范围以内，结果可以直接交给 create_chart。
 * 纯本地字面计数：不调用模型，不读未读内容；称呼变化（别名、代词）不会被计入。
 */
internal class CountMentionsTool(
    private val totalChapters: suspend () -> Int,
    private val loadChapter: suspend (Int) -> ChapterDocument?,
    private val readingScope: ReadingScope,
    private val currentScope: suspend () -> ReadingScope = { readingScope }
) : AgentTool {
    override val displayName = "统计出现次数"
    override val spec = ToolSpec(
        name = "count_mentions",
        description = "按章统计 1-6 个名字或关键词在已读正文中的出现次数（逐字、区分大小写、可给别名合并），用于画出场趋势等图表。" +
            "只计字面出现，别名请放进同一项的 aliases；结果不等于出场次数或戏份。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("terms") {
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
                putJsonObject("from_chapter") { put("type", "integer"); put("description", "起始章节号（从 1 开始）") }
                putJsonObject("to_chapter") { put("type", "integer"); put("description", "结束章节号（包含），默认可读末章") }
                putJsonObject("bucket") { put("type", "integer"); put("description", "每几章合并为一组，默认按章数自动选择，保证不超过 50 组") }
            }
            putJsonArray("required") { add("terms") }
        }
    )

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Default) {
        val terms = arguments["terms"]?.let { element ->
            runCatching {
                element.jsonArray.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val aliases = (obj["aliases"]?.jsonArray ?: buildJsonArray { })
                        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                    MentionTerm(name, (listOf(name) + aliases).filter { it.isNotEmpty() && it.length <= 40 }.distinct())
                }
            }.getOrNull()
        }.orEmpty().filter { it.name.isNotBlank() }
        if (terms.isEmpty() || terms.size > 6) {
            return@withContext ToolResult.Failure("INVALID_ARGUMENT", "terms 需要 1-6 项，每项给 name，可选 aliases")
        }
        val scope = currentScope().intersect(readingScope)
        val total = totalChapters()
        val last = scope.clampLastChapter(total)
        if (total <= 0 || last < 0) return@withContext ToolResult.Failure("NO_TEXT", "还没有可统计的已读正文")
        val from = ((arguments["from_chapter"]?.jsonPrimitive?.intOrNull ?: 1) - 1).coerceIn(0, last)
        val to = ((arguments["to_chapter"]?.jsonPrimitive?.intOrNull ?: (last + 1)) - 1).coerceIn(from, last)
        val counts = withTimeoutOrNull(15_000) {
            (from..to).map { index ->
                currentCoroutineContext().ensureActive()
                val document = loadChapter(index)
                val readable = document?.takeIf { it.readError == null }?.let { scope.readableText(index, it.body) }.orEmpty()
                terms.map { term -> term.patterns.sumOf { countLiteral(readable, it) } }
            }
        } ?: return@withContext ToolResult.Failure("RESOURCE_LIMIT", "统计超时，请缩小章节范围")
        val requestedBucket = arguments["bucket"]?.jsonPrimitive?.intOrNull
        val result = MentionCounts.bucket(counts, from, requestedBucket)
        ToolResult.Success(MentionCounts.describe(terms.map { it.name }, result, scope.maxChapterIndex < total - 1))
    }

    private data class MentionTerm(val name: String, val patterns: List<String>)
}

/** count_mentions 的纯计算部分，便于单测。 */
internal object MentionCounts {
    data class Buckets(val labels: List<String>, val values: List<List<Int>>)

    fun bucket(perChapter: List<List<Int>>, firstIndex: Int, requested: Int?): Buckets {
        val size = (requested ?: ((perChapter.size + ChartSpecCodec.MAX_POINTS - 1) / ChartSpecCodec.MAX_POINTS))
            .coerceAtLeast((perChapter.size + ChartSpecCodec.MAX_POINTS - 1) / ChartSpecCodec.MAX_POINTS)
            .coerceAtLeast(1)
        val groups = perChapter.indices.chunked(size)
        val labels = groups.map { group ->
            val start = firstIndex + group.first() + 1
            val end = firstIndex + group.last() + 1
            if (start == end) "第${start}章" else "第$start-${end}章"
        }
        val termCount = perChapter.firstOrNull()?.size ?: 0
        val values = (0 until termCount).map { term -> groups.map { group -> group.sumOf { perChapter[it][term] } } }
        return Buckets(labels, values)
    }

    fun describe(names: List<String>, buckets: Buckets, partialBook: Boolean): String = buildString {
        append("按章字面出现次数（已读范围内")
        if (partialBook) append("，未读部分不计")
        append("）。可直接作为 create_chart 的数据：\n")
        append("labels: ").append(kotlinx.serialization.json.JsonArray(buckets.labels.map(::JsonPrimitive)).toString()).append('\n')
        names.forEachIndexed { index, name ->
            val values = buckets.values.getOrNull(index).orEmpty()
            append(name).append("（共 ").append(values.sum()).append(" 次）: ").append(values.joinToString(prefix = "[", postfix = "]")).append('\n')
        }
    }
}

internal fun countLiteral(text: String, pattern: String): Int {
    if (pattern.isEmpty() || text.length < pattern.length) return 0
    var count = 0
    var from = 0
    while (true) {
        val at = text.indexOf(pattern, from)
        if (at < 0) return count
        count++
        from = at + pattern.length
    }
}

/** 新工具在历史步骤里的显示名；界面层的旧表找不到时回落到这里。 */
object ToolDisplayNames {
    fun of(name: String): String? = when (name) {
        "create_chart" -> "绘制图表"
        "count_mentions" -> "统计出现次数"
        else -> null
    }
}
