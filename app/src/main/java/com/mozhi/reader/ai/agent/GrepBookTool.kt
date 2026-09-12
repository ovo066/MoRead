package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.ToolSpec
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.library.BookTextException
import com.mozhi.reader.core.retrieval.BookGrep
import com.mozhi.reader.core.retrieval.BookSourceRegistry
import com.mozhi.reader.core.retrieval.GrepLimits
import com.mozhi.reader.core.retrieval.GrepOptions
import com.mozhi.reader.core.retrieval.GrepSource
import com.mozhi.reader.core.retrieval.ReadingScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** Deterministic local scanning, independent of embeddings and the model's choice of scope. */
internal class GrepBookTool(
    private val bookId: Long,
    private val getBook: suspend () -> BookEntity?,
    private val loadChapter: suspend (Int) -> ChapterDocument?,
    private val getRevision: suspend () -> String,
    private val readingScope: ReadingScope,
    private val registry: BookSourceRegistry,
    private val currentScope: suspend () -> ReadingScope = { readingScope },
    private val limits: GrepLimits = GrepLimits()
) : AgentTool {
    override val displayName = "逐字查找与计数"
    override val spec = ToolSpec(
        name = "grep_book",
        description = "在应用给定的阅读水位内逐章穷举字面串（非重叠、区分大小写、不跨章，章内保留换行）。" +
            "用于出现几次、定位原文、列出匹配；不做错字/同义/语义匹配，情节描述或错名请用 search_book。" +
            "完整扫描计数不受 max_samples 限制；partial 只提供下界，只有 complete 且 count=0 才表示该字面串未出现。" +
            "零命中不证明事件不存在，字面次数不等于人物出场/事件数量。source_ref 配合原始 matched_text 可用于 add_annotation。" +
            "可用 from_chapter/to_chapter 限定章节；计数只针对返回的实际范围，不能当作全书次数。" +
            "游标固定内容版本和范围；继续时保留 pattern/normalize/context_chars，章节参数保留原值或省略，范围增长不扩大旧查询，缩小或内容变化则失效。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("pattern") { put("type", "string"); put("description", "非空字面串，最多 200 个 UTF-16 字符；空白及换行也是字面字符") }
                putJsonObject("mode") { put("type", "string"); putJsonArray("enum") { add("literal") }; put("description", "仅支持 literal，默认 literal，不执行正则") }
                putJsonObject("normalize") {
                    put("type", "boolean")
                    put("description", "默认 true：全角 ASCII/空格、弯引号及 —―– 统一为半角；不删空白，不转换中文数字或简繁，坐标与引文仍是原文")
                }
                putJsonObject("context_chars") { put("type", "integer"); put("description", "每侧上下文 UTF-16 长度，默认 80，上限 300") }
                putJsonObject("max_samples") { put("type", "integer"); put("description", "单页样本上限，默认 20，1-50；另有输出体积上限，不限制总计数") }
                putJsonObject("from_chapter") { put("type", "integer"); put("description", "起始章节号（从 1 开始），默认第 1 章") }
                putJsonObject("to_chapter") { put("type", "integer"); put("description", "结束章节号（包含），默认可读末章；只查某一章时两端填写同一章号") }
                putJsonObject("cursor") { put("type", "string"); put("description", "上页返回的 next_cursor；过期/进程重启后请重新查询") }
            }
            putJsonArray("required") { add("pattern") }
        }
    )

    override suspend fun execute(arguments: JsonObject): String = withContext(Dispatchers.Default) {
        try {
            withTimeoutOrNull(15_000) { executeLocal(arguments) }
                ?: errorResult("RESOURCE_LIMIT", "达到本地扫描时间上限，请重新查询；没有完整计数")
        } catch (cancelled: CancellationException) {
            throw cancelled // cancellation is not a successful zero-match response
        } catch (error: BookTextException) {
            errorResult(error.code, error.message ?: "规范正文不可用")
        } catch (_: Exception) {
            errorResult("READ_FAILED", "规范正文读取失败，不能将错误视为零命中")
        }
    }

    private suspend fun executeLocal(args: JsonObject): String {
        fun primitive(key: String) = args[key] as? JsonPrimitive
        val pattern = primitive("pattern")?.takeIf { it.isString }?.content
            ?: return errorResult("INVALID_ARGUMENT", "pattern 必须是非空字符串")
        try { BookGrep.validatePattern(pattern) }
        catch (error: BookGrep.InvalidPattern) { return errorResult("INVALID_ARGUMENT", error.message.orEmpty()) }
        val mode = primitive("mode")?.contentOrNull ?: "literal"
        if (args.containsKey("mode") && (mode != "literal" || primitive("mode")?.isString != true)) {
            return errorResult("UNSUPPORTED_MODE", "本版仅支持 mode=literal，不执行正则")
        }
        if (args.containsKey("normalize") && (primitive("normalize")?.booleanOrNull == null || primitive("normalize")?.isString == true) ||
            args.containsKey("context_chars") && (primitive("context_chars")?.intOrNull == null || primitive("context_chars")?.isString == true) ||
            args.containsKey("max_samples") && (primitive("max_samples")?.intOrNull == null || primitive("max_samples")?.isString == true) ||
            args.containsKey("cursor") && primitive("cursor")?.isString != true) {
            return errorResult("INVALID_ARGUMENT", "normalize/context_chars/max_samples/cursor 类型无效")
        }
        val normalize = primitive("normalize")?.booleanOrNull ?: true
        val contextChars = (primitive("context_chars")?.intOrNull ?: 80).coerceIn(0, 300)
        val maxSamples = (primitive("max_samples")?.intOrNull ?: 20).coerceIn(1, 50)
        val bounds = try { parseChapterSearchBounds(args) }
            catch (error: IllegalArgumentException) { return errorResult("INVALID_ARGUMENT", error.message.orEmpty()) }
        val cursor = primitive("cursor")?.content
        val page = cursor?.let { registry.page(it) }
        if (cursor != null && page == null) return errorResult("CURSOR_INVALID", "游标无效或已过期，请重新查询")
        if (page != null && (page.bookId != bookId || page.pattern != pattern || page.normalize != normalize || page.contextChars != contextChars)) {
            return errorResult("CURSOR_INVALID", "游标不属于当前书籍或匹配配置，请重新查询")
        }
        if (page != null && (bounds.fromChapter?.let { it != page.firstChapterIndex + 1 } == true ||
                bounds.toChapter?.let { it != page.scope.maxChapterIndex + 1 && it != page.requestedToChapter } == true)) {
            return errorResult("CURSOR_INVALID", "游标绑定的章节范围不能更改，请重新查询")
        }
        val book = getBook() ?: return errorResult("SOURCE_MISSING", "未找到当前书籍")
        if (book.removedAt > 0L) return errorResult("SOURCE_MISSING", "本书正文已移除，当前仅保留个人记录")
        if (book.totalChapters <= 0) return errorResult("SOURCE_MISSING", "当前书籍尚无规范章节正文")
        val allowed = readingScope.intersect(currentScope())
        if (page != null && !allowed.contains(page.scope)) {
            return errorResult("CURSOR_INVALID", "当前允许范围已缩小，原游标不能继续使用，请重新查询")
        }
        val revision = getRevision()
        if (page != null && page.revision != revision) {
            return errorResult("CURSOR_INVALID", "书籍正文版本发生变化，请重新查询")
        }
        val range = if (page != null) ChapterSearchRange(page.firstChapterIndex, page.scope) else try {
            bounds.resolve(book.totalChapters, allowed)
        } catch (error: IllegalArgumentException) { return errorResult("INVALID_ARGUMENT", error.message.orEmpty()) }
        val first = range.firstIndex
        val last = range.lastIndex
        var snapshot = range.scope
        suspend fun source(index: Int): GrepSource {
            if (index == snapshot.maxChapterIndex && snapshot.maxCharOffset == 0) return GrepSource(index, "")
            return try {
                val document = loadChapter(index)
                if (document == null || document.chapterIndex != index || document.readError != null) {
                    GrepSource(index, null, "SOURCE_MISSING")
                } else GrepSource(index, snapshot.readableText(index, document.body))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: BookTextException) { GrepSource(index, null, error.code) }
            catch (_: Exception) { GrepSource(index, null, "READ_FAILED") }
        }
        val boundary = source(last)
        if (page == null && boundary.body != null) snapshot = ReadingScope.upto(last, boundary.body.length)
        val result = BookGrep.scan(
            pattern, (first..last).take(limits.maxChapters.coerceAtMost(20_000) + 1), { if (it == last) boundary else source(it) },
            GrepOptions(normalize, contextChars, maxSamples, page?.skip ?: 0), limits
        )
        if (getRevision() != revision) {
            return errorResult(if (page == null) "CONTENT_CHANGED" else "CURSOR_INVALID", "扫描期间正文变化，请重新查询")
        }
        if (page != null && page.coverageSignature != result.coverageSignature) {
            return errorResult("CURSOR_INVALID", "章节可读性或扫描覆盖已变化，请重新查询以免漏项或重复")
        }
        if (!currentScope().contains(snapshot)) {
            return errorResult(if (page == null) "SCOPE_CHANGED" else "CURSOR_INVALID", "允许范围已缩小，请重新查询")
        }
        val nextCursor = result.nextSkip?.let { skip -> registry.issueCursor(BookSourceRegistry.Page(
            bookId, revision, snapshot, pattern, normalize, contextChars, skip, result.coverageSignature,
            firstChapterIndex = first, requestedToChapter = if (page != null) page.requestedToChapter else bounds.toChapter
        )) }
        return buildJsonObject {
            put("status", if (result.countIsExact) "complete" else "partial")
            putJsonObject("scope") {
                put("book_revision", revision)
                put("from_chapter", first + 1)
                put("to_chapter", snapshot.maxChapterIndex + 1)
                put("end_char", if (snapshot.maxCharOffset == Int.MAX_VALUE) JsonNull else JsonPrimitive(snapshot.maxCharOffset))
            }
            put("normalization_version", BookGrep.NORMALIZATION_VERSION)
            putJsonObject("count") { put("value", result.count); put("relation", if (result.countIsExact) "exact" else "lower_bound") }
            putJsonArray("by_chapter") { result.byChapter.forEach { row -> add(buildJsonObject { put("chapter", row.chapterIndex + 1); put("hits", row.hits) }) } }
            put("by_chapter_truncated", result.byChapterTruncated)
            put("samples_truncated", result.samplesTruncated)
            put("next_cursor", nextCursor?.let(::JsonPrimitive) ?: JsonNull)
            putJsonArray("samples") {
                result.samples.forEach { sample ->
                    val ref = registry.issueSource(BookSourceRegistry.Source(bookId, revision, snapshot,
                        sample.chapterIndex, sample.matchStart, sample.matchEnd, sample.matchedText))
                    add(buildJsonObject {
                        put("chapter", sample.chapterIndex + 1)
                        put("match_start", sample.matchStart); put("match_end", sample.matchEnd)
                        put("matched_text", sample.matchedText)
                        put("context_start", sample.contextStart); put("context_end", sample.contextEnd)
                        put("context_text", sample.contextText); put("source_ref", ref)
                    })
                }
            }
            putJsonArray("unreadable_chapters") { result.unreadableChapters.forEach { add(it + 1) } }
            put("unreadable_chapters_truncated", result.unreadableChapterCount > result.unreadableChapters.size)
            put("error", if (result.countIsExact) JsonNull else buildJsonObject {
                put("code", if (result.resourceLimited) "RESOURCE_LIMIT" else "SOURCE_UNAVAILABLE")
                put("message", "扫描未完整覆盖：缺章、读取失败或资源受限；计数及章节分布仅是部分结果，不能解释为没有匹配")
            })
        }.toString()
    }

    private fun errorResult(code: String, message: String): String = buildJsonObject {
        put("status", "error"); put("scope", JsonNull); put("count", JsonNull)
        putJsonArray("by_chapter") {}; put("by_chapter_truncated", false)
        put("samples_truncated", false); put("next_cursor", JsonNull); putJsonArray("samples") {}
        putJsonObject("error") { put("code", code); put("message", message) }
    }.toString()
}
