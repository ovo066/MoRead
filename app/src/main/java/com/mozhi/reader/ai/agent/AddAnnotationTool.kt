package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.ToolSpec
import com.mozhi.reader.core.database.entity.AnnotationStyle
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.library.QuoteLocation
import com.mozhi.reader.core.library.BookTextException
import com.mozhi.reader.core.retrieval.BookSourceRegistry
import com.mozhi.reader.core.retrieval.ReadingScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** All writes pass through fresh canonical text, version and scope validation. */
internal class AddAnnotationTool(
    private val bookId: Long,
    private val getBook: suspend () -> BookEntity?,
    private val loadChapter: suspend (Int) -> ChapterDocument?,
    private val getRevision: suspend () -> String,
    private val registry: BookSourceRegistry,
    private val readingScope: ReadingScope,
    private val currentScope: suspend () -> ReadingScope = { readingScope },
    private val save: suspend (QuoteLocation, String, String, AnnotationStyle, ReadingScope) -> Long
) : AgentTool {
    override val displayName: String = "添加段落批注"

    override val spec: ToolSpec = ToolSpec(
        name = "add_annotation",
        description = "对用户已读原文添加一条可在正文段落讨论区看到的角色批注（划线样式承载语义，帮读者一眼识别批注类型）。" +
            "quote 必须逐字复制自 read_book_section/search_book 或 grep_book 的 matched_text；grep 样本请同时传 source_ref 以唯一定位重复引文；用户未要求或没有值得补充的观点时不要擅自调用。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("quote") {
                    put("type", "string")
                    put("description", "要批注的原文连续引文，必须逐字复制并尽量包含足够上下文以保证唯一")
                }
                putJsonObject("comment") {
                    put("type", "string")
                    put("description", "显示在该段讨论区的批注内容")
                }
                putJsonObject("style") {
                    put("type", "string")
                    put("enum", kotlinx.serialization.json.buildJsonArray {
                        add(JsonPrimitive("highlight"))
                        add(JsonPrimitive("underline"))
                        add(JsonPrimitive("wavy"))
                    })
                    put(
                        "description",
                        "划线样式，按内容语义选择：highlight 荧光=金句/精彩段落；" +
                            "wavy 波浪=伏笔/暗线/前后呼应；underline 直线=知识点/典故/术语。默认 highlight"
                    )
                }
                putJsonObject("source_ref") {
                    put("type", "string")
                    put("description", "grep_book 签发的来源句柄；必须配套原始 matched_text，不可自造；失效后重新 grep")
                }
                putJsonObject("chapter_number") {
                    put("type", "integer")
                    put("description", "可选章节号，从 1 开始，用于消除同文歧义")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("quote"))
                add(JsonPrimitive("comment"))
            }
        }
    )

    override suspend fun execute(arguments: JsonObject): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        try {
            executeChecked(arguments)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: BookTextException) {
            "正文核验失败（${error.code}），未写入批注，请重新读取原文。"
        } catch (_: Exception) {
            "正文核验或批注保存失败，请重新读取并确认批注列表，不能视为已成功添加。"
        }
    }

    private suspend fun executeChecked(args: JsonObject): String {
        fun text(key: String) = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val reference = text("source_ref")
        if ("source_ref" in args && reference.isNullOrBlank()) return "source_ref 无效，请重新 grep_book。"
        // Keep literal whitespace when consuming a source; old quote-only calls retain trimming.
        val quote = text("quote")?.let { if (reference == null) it.trim() else it }.orEmpty()
        val comment = text("comment")?.trim().orEmpty()
        if (quote.isEmpty()) return "缺少原文 quote"
        if (comment.isEmpty()) return "缺少批注 comment"
        if (quote.length > 2_000) return "quote 过长，请选择 2000 字以内的连续原文"
        val style = AnnotationStyle.fromWire(text("style"))
        val book = getBook() ?: return "未找到当前书籍"
        if (book.totalChapters <= 0) return "规范正文未就绪，未写入批注。"
        val scope = readingScope.intersect(currentScope())
        val chapterNumber = (args["chapter_number"] as? JsonPrimitive)?.intOrNull
        if ("chapter_number" in args && chapterNumber == null) return "chapter_number 必须是整数。"
        val last = scope.clampLastChapter(book.totalChapters)
        if (chapterNumber != null && chapterNumber !in 1..last + 1) return "章节超出当前可见范围。"
        val revision = getRevision()
        val match: QuoteLocation
        if (reference != null) {
            val source = registry.source(reference) ?: return "source_ref 无效或已过期，请重新 grep_book。"
            if (chapterNumber != null && chapterNumber - 1 != source.chapterIndex) return "source_ref 与 chapter_number 不一致。"
            if (source.bookId != bookId || source.revision != revision ||
                !scope.allowsChunk(source.chapterIndex, source.start, source.end)) {
                return "source_ref 的书籍、版本或阅读范围已失效，请重新 grep_book。"
            }
            val chapter = loadChapter(source.chapterIndex)
            if (chapter == null || chapter.chapterIndex != source.chapterIndex || chapter.readError != null) {
                return "来源正文不可读，未写入批注。"
            }
            match = registry.verify(source, bookId, revision, scope, chapter.body, quote)
                ?: return "source_ref 与原始 quote 或区间不一致，未写入批注。"
        } else {
            val matches = ArrayList<QuoteLocation>()
            var scanned = 0
            val indexes = chapterNumber?.let { (it - 1)..(it - 1) } ?: 0..last
            if (indexes.count() > MAX_LEXICAL_CHAPTERS) return "范围超过核验上限，请先 grep_book 并提供 source_ref。"
            for (index in indexes) {
                currentCoroutineContext().ensureActive()
                if (index == scope.maxChapterIndex && scope.maxCharOffset == 0) continue
                val chapter = loadChapter(index)
                if (chapter == null || chapter.chapterIndex != index || chapter.readError != null) {
                    return "正文覆盖不完整，不能确认唯一位置；请先 grep_book 并提供 source_ref。"
                }
                val readable = scope.readableText(index, chapter.body)
                scanned += readable.length
                if (scanned > MAX_LEXICAL_CHARS) return "范围超过核验上限，请先 grep_book 并提供 source_ref。"
                matches += locateExactQuote(listOf(chapter.copy(body = readable)), quote, maxMatches = 2)
                if (matches.size > 1) return "这段 quote 在已读范围出现多次，无法确定位置；请提供 chapter_number、唯一引文或 grep_book 的 source_ref。"
            }
            match = matches.singleOrNull() ?: return "已读原文中找不到这段 quote；请从 read_book_section/search_book/grep_book 逐字复制。"
        }
        if (getRevision() != revision) return "正文版本已变化，未写入批注，请重新读取原文。"
        val finalScope = scope.intersect(currentScope())
        if (!finalScope.allowsChunk(match.chapterIndex, match.startCharOffset, match.endCharOffset)) {
            return "阅读范围已缩小，未写入批注。"
        }
        currentCoroutineContext().ensureActive()
        val id = save(match, quote, comment.take(10_000), style, finalScope)
        return "已在第 ${match.chapterIndex + 1} 章添加段落批注（编号 $id，样式 ${style.wire.lowercase()}），" +
            "读者点击正文旁的批注标记即可在讨论区看到。"
    }
}
