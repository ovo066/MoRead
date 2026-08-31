package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.client.ToolSpec
import com.mozhi.reader.ai.embedding.BookEmbeddingScheduler
import com.mozhi.reader.ai.media.AgentMediaResult
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.ai.search.WebSearchService
import com.mozhi.reader.core.database.entity.AnnotationColors
import com.mozhi.reader.core.database.entity.AnnotationStyle
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.datastore.BookEmbeddingSettingsStore
import com.mozhi.reader.core.library.AnnotationRepository
import com.mozhi.reader.core.library.BookQuoteLocator
import com.mozhi.reader.core.library.QuoteChapter
import com.mozhi.reader.core.library.QuoteLocation
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.NoteRepository
import com.mozhi.reader.core.retrieval.Bm25LexicalRecall
import com.mozhi.reader.core.retrieval.NeighborExpander
import com.mozhi.reader.core.retrieval.ReadingScope
import com.mozhi.reader.core.retrieval.RetrievalCandidate
import com.mozhi.reader.core.retrieval.RetrievalPipeline
import com.mozhi.reader.core.retrieval.RetrievalRecall
import com.mozhi.reader.core.retrieval.RetrievalRequest
import com.mozhi.reader.core.vector.ChapterChunker
import com.mozhi.reader.core.vector.Embeddings
import com.mozhi.reader.core.vector.VectorQueries
import io.objectbox.BoxStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 本轮工具可见的记忆范围。默认值等于「全开」，即改动前的行为；
 * 调用方从设置与当前面具算出实际值再传进来。
 */
data class MemoryScope(
    /** 长期记忆总开关。关闭时连 recall_memory 都不注册——留着一个永远回空的工具只会误导模型。 */
    val longTermEnabled: Boolean = true,
    val crossBookChatSearch: Boolean = true,
    val maskId: Long = 0L
)

/**
 * Builds the tool set available to reading-side agents for one book.
 *
 * [forBook] 的 enabledTools 是 Persona 的工具白名单（null = 不过滤）；
 * recall_memory 只在给了 personaId 时注册——记忆按角色隔离，没有角色就没有记忆可回。
 */
@Singleton
class ReaderToolset @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val noteRepository: NoteRepository,
    private val annotationRepository: AnnotationRepository,
    private val mediaService: AiMediaGenerationService,
    private val clientFactory: dagger.Lazy<AiClientFactory>,
    private val vectorStore: dagger.Lazy<BoxStore>,
    private val embeddingScheduler: BookEmbeddingScheduler,
    private val embeddingSettingsStore: BookEmbeddingSettingsStore,
    private val webSearchService: WebSearchService
) {
    fun forBook(
        bookId: Long,
        personaId: Long? = null,
        conversationId: Long? = null,
        enabledTools: Collection<String>? = null,
        readingScope: ReadingScope,
        memoryScope: MemoryScope = MemoryScope()
    ): List<AgentTool> {
        val embedQuery: suspend (String) -> FloatArray = { query ->
            val resolved = clientFactory.get().forRole(ModelRole.EMBEDDING)
            Embeddings.conformToIndex(resolved.client.embed(listOf(query)).first())
        }
        val tools = buildList {
            add(
                GetReadingProgressTool(
                    libraryRepository = libraryRepository,
                    noteRepository = noteRepository,
                    annotationRepository = annotationRepository,
                    bookId = bookId,
                    readingScope = readingScope
                )
            )
            add(ListChaptersTool(libraryRepository, bookId, readingScope))
            add(
                ListAnnotationsTool(
                    libraryRepository = libraryRepository,
                    annotations = annotationRepository,
                    bookId = bookId,
                    currentPersonaId = personaId,
                    readingScope = readingScope
                )
            )
            add(ListNotesTool(noteRepository, bookId, personaId, readingScope))
            add(
                SearchBookTool(
                    bookId = bookId,
                    getBook = { libraryRepository.getBook(bookId) },
                    chapterTitle = { index -> libraryRepository.getChapterTitle(bookId, index) },
                    loadChapter = { index ->
                        libraryRepository.getChapters(bookId)
                            .firstOrNull { it.chapterIndex == index }
                            ?.let { chapter ->
                                ChapterDocument(
                                    chapterIndex = chapter.chapterIndex,
                                    title = chapter.title,
                                    body = libraryRepository.readChapterText(bookId, chapter)
                                )
                            }
                    },
                    loadChaptersThrough = { maxChapterIndex ->
                        libraryRepository.getChapters(bookId)
                            .filter { it.chapterIndex <= maxChapterIndex }
                            .map { chapter ->
                                ChapterDocument(
                                    chapterIndex = chapter.chapterIndex,
                                    title = chapter.title,
                                    body = libraryRepository.readChapterText(bookId, chapter)
                                )
                            }
                    },
                    embedQuery = embedQuery,
                    store = { vectorStore.get() },
                    requestIndex = { embeddingScheduler.enqueueForBook(bookId) },
                    indexingEnabled = { embeddingSettingsStore.isEnabled(bookId) },
                    readingScope = readingScope
                )
            )
            add(ReadBookSectionTool(libraryRepository, bookId, readingScope))
            add(WebSearchTool(webSearchService))
            add(WebScrapeTool(webSearchService))
            if (personaId != null) {
                if (memoryScope.longTermEnabled) {
                    add(
                        RecallMemoryTool(
                            personaId = personaId,
                            embedQuery = embedQuery,
                            store = { vectorStore.get() },
                            // 关掉「跨书对话检索」＝ recall_memory 只在本书范围内回忆。
                            bookId = bookId.takeUnless { memoryScope.crossBookChatSearch },
                            currentBookId = bookId,
                            maskId = memoryScope.maskId,
                            readingScope = readingScope
                        )
                    )
                }
                add(
                    AddAnnotationTool(
                        bookId = bookId,
                        personaId = personaId,
                        libraryRepository = libraryRepository,
                        annotations = annotationRepository,
                        readingScope = readingScope
                    )
                )
                add(
                    WriteNoteTool(
                        bookId = bookId,
                        personaId = personaId,
                        conversationId = conversationId,
                        getBook = { libraryRepository.getBook(bookId) },
                        notes = noteRepository,
                        readingScope = readingScope
                    )
                )
                add(
                    SavePlotSummaryTool(
                        bookId = bookId,
                        personaId = personaId,
                        conversationId = conversationId,
                        getBook = { libraryRepository.getBook(bookId) },
                        notes = noteRepository,
                        readingScope = readingScope
                    )
                )
                add(
                    GenerateImageTool(
                        bookId = bookId,
                        personaId = personaId,
                        getBook = { libraryRepository.getBook(bookId) },
                        mediaService = mediaService,
                        readingScope = readingScope
                    )
                )
                add(
                    SynthesizeSpeechTool(
                        bookId = bookId,
                        mediaService = mediaService
                    )
                )
            }
        }
        return enabledTools?.let { allowed ->
            tools.filter { tool -> tool.spec.name in READ_ONLY_BASE_TOOLS || tool.spec.name in allowed }
        } ?: tools
    }
}

private class WebScrapeTool(
    private val service: WebSearchService
) : AgentTool {
    override val displayName: String = "抓取网页正文"

    override val spec: ToolSpec = ToolSpec(
        name = "web_scrape",
        description = "抓取一个已知网址的网页正文。当搜索结果摘要不足以回答，或用户直接给出网址时使用；不要用它绕过书籍防剧透范围。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "要抓取的完整 http/https 网址")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("url")) }
        }
    )

    override suspend fun execute(arguments: JsonObject): String {
        val url = arguments["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (url.isEmpty()) return "缺少网址 url"
        val result = try {
            service.scrape(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return error.message ?: "网页抓取失败"
        }
        return buildString {
            append("网页正文（回答时请标明来源链接）：\n")
            append("标题：").append(result.title).append('\n')
            append("来源：").append(result.url).append("\n\n")
            append(result.content)
        }
    }
}

private class WebSearchTool(
    private val service: WebSearchService
) : AgentTool {
    override val displayName: String = "搜索互联网"

    override val spec: ToolSpec = ToolSpec(
        name = "web_search",
        description = "搜索互联网以获取书外知识、近期事实和可引用来源。不要用它查询尚未阅读的书中后续剧情；需要核对本书已读内容时应使用 search_book。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "简洁、独立且适合搜索引擎的查询词")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "返回结果数量，1-8，默认 5")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("query")) }
        }
    )

    override suspend fun execute(arguments: JsonObject): String {
        val query = arguments["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) return "缺少搜索词 query"
        val limit = (arguments["limit"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 8)
        val results = try {
            service.search(query, limit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return error.message ?: "网络搜索失败"
        }
        if (results.isEmpty()) return "没有找到与「$query」相关的网页结果。"
        return buildString {
            append("互联网搜索结果（回答时请标明来源链接）：\n")
            results.forEachIndexed { index, result ->
                append("\n[").append(index + 1).append("] ").append(result.title)
                append("\n").append(result.url)
                if (result.snippet.isNotBlank()) append("\n").append(result.snippet)
                append('\n')
            }
        }
    }
}

private class GetReadingProgressTool(
    private val libraryRepository: LibraryRepository,
    private val noteRepository: NoteRepository,
    private val annotationRepository: AnnotationRepository,
    private val bookId: Long,
    private val readingScope: ReadingScope
) : AgentTool {

    override val displayName: String = "查询书籍与阅读进度"

    override val spec: ToolSpec = ToolSpec(
        name = "get_reading_progress",
        description = "获取当前书籍、阅读进度、阅读统计及已有笔记/批注/书签概况；回答与进度、章节范围或存量素材相关的问题前应先调用。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }
    )

    override suspend fun execute(arguments: JsonObject): String {
        val book = libraryRepository.getBook(bookId) ?: return "未找到当前书籍"
        val currentIndex = book.lastReadChapterIndex.coerceAtLeast(0)
        val notes = noteRepository.getForBook(bookId)
        return formatProgressOverview(
            overview = ProgressOverview(
                book = book,
                currentChapter = libraryRepository.getChapter(bookId, currentIndex),
                previousChapter = currentIndex.takeIf { it > 0 }
                    ?.let { libraryRepository.getChapter(bookId, it - 1) },
                nextChapter = currentIndex.takeIf { it + 1 < book.totalChapters }
                    ?.let { libraryRepository.getChapter(bookId, it + 1) },
                totalCharacters = libraryRepository.getTotalCharacterCount(bookId),
                charactersBeforeCurrent = libraryRepository.getCharacterCountBefore(bookId, currentIndex),
                tags = libraryRepository.getTagNames(bookId),
                readingDays = libraryRepository.getReadingDays(bookId),
                noteCount = notes.count { it.kind == NoteRepository.KIND_NOTE },
                plotSummaries = notes.filter { it.kind == NoteRepository.KIND_PLOT_SUMMARY },
                annotationCount = annotationRepository.getCountForBook(bookId),
                currentChapterAnnotationCount = annotationRepository.getCountForChapter(bookId, currentIndex),
                bookmarkCount = libraryRepository.getBookmarks(bookId).size
            ),
            readingScope = readingScope
        )
    }
}

internal data class ChapterDocument(
    val chapterIndex: Int,
    val title: String,
    val body: String
)

/**
 * 按明确章节/范围读取原文，不依赖向量服务。它既是“概括第 N 章”的可靠数据源，
 * 也是 embedding 暂时不可用时的精确回退；当前章严格截到用户的 UTF-16 阅读偏移。
 */
internal class ReadBookSectionTool(
    private val libraryRepository: LibraryRepository,
    private val bookId: Long,
    private val readingScope: ReadingScope
) : AgentTool {
    override val displayName: String = "读取指定已读章节"

    override val spec: ToolSpec = ToolSpec(
        name = "read_book_section",
        description = if (!readingScope.isWholeBook) {
            "读取用户指定的已读章节或章节范围原文。概括某章或某部分时使用；不确定章节号时先调用 list_chapters 核对，不要猜测。章节号从 1 开始，当前章内容只返回到实际阅读位置。"
        } else {
            "读取用户指定的章节或章节范围原文。概括某章或某部分时使用；不确定章节号时先调用 list_chapters 核对，不要猜测。章节号从 1 开始。"
        },
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("from_chapter") {
                    put("type", "integer")
                    put("description", "起始章节号，从 1 开始")
                }
                putJsonObject("to_chapter") {
                    put("type", "integer")
                    put("description", "结束章节号（包含），省略时等于起始章节")
                }
                putJsonObject("start_char") {
                    put("type", "integer")
                    put("description", "起始章内 UTF-16 字符偏移，续读长章节时使用，默认 0")
                }
                putJsonObject("max_chars") {
                    put("type", "integer")
                    put("description", "本次最多返回的原文字符数，1000-24000，默认 12000")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("from_chapter")) }
        }
    )

    override suspend fun execute(arguments: JsonObject): String {
        val fromChapter = arguments["from_chapter"]?.jsonPrimitive?.intOrNull
            ?: return "缺少起始章节号 from_chapter"
        val toChapter = arguments["to_chapter"]?.jsonPrimitive?.intOrNull ?: fromChapter
        val startChar = (arguments["start_char"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
        val maxChars = (arguments["max_chars"]?.jsonPrimitive?.intOrNull ?: DEFAULT_SECTION_CHARS)
            .coerceIn(MIN_SECTION_CHARS, MAX_SECTION_CHARS)
        if (fromChapter < 1 || toChapter < fromChapter) return "章节范围无效：$fromChapter-$toChapter"

        val book = libraryRepository.getBook(bookId) ?: return "未找到当前书籍"
        val maxReadableChapter = readingScope.clampLastChapter(book.totalChapters) + 1
        if (toChapter > maxReadableChapter) {
            return if (!readingScope.isWholeBook) {
                "超出已读范围：用户只读到第 $maxReadableChapter 章，不能读取第 $toChapter 章。"
            } else {
                "章节超出本书范围：本书共 $maxReadableChapter 章，不能读取第 $toChapter 章。"
            }
        }
        val chapterEntities = libraryRepository.getChapters(bookId).associateBy { it.chapterIndex }
        val readableChapters = buildList {
            for (chapterNumber in fromChapter..toChapter) {
                val chapterIndex = chapterNumber - 1
                val chapter = chapterEntities[chapterIndex] ?: return "未找到第 $chapterNumber 章"
                val fullBody = libraryRepository.readChapterText(bookId, chapter)
                add(
                    ChapterDocument(
                        chapterIndex = chapterIndex,
                        title = chapter.title,
                        body = if (!readingScope.isWholeBook && chapterIndex == readingScope.maxChapterIndex) {
                            fullBody.take(readingScope.maxCharOffset.coerceIn(0, fullBody.length))
                        } else {
                            fullBody
                        }
                    )
                )
            }
        }
        return formatBookSection(
            chapters = readableChapters,
            fromChapter = fromChapter,
            toChapter = toChapter,
            startChar = startChar,
            maxChars = maxChars
        )
    }

    private companion object {
        const val DEFAULT_SECTION_CHARS = 12_000
        const val MIN_SECTION_CHARS = 1_000
        const val MAX_SECTION_CHARS = 24_000
    }
}

internal fun formatBookSection(
    chapters: List<ChapterDocument>,
    fromChapter: Int,
    toChapter: Int,
    startChar: Int,
    maxChars: Int
): String {
    val byIndex = chapters.associateBy(ChapterDocument::chapterIndex)
    val output = StringBuilder()
    var remaining = maxChars
    for (chapterNumber in fromChapter..toChapter) {
        val chapter = byIndex[chapterNumber - 1] ?: return "未找到第 $chapterNumber 章"
        val chapterStart = if (chapterNumber == fromChapter) startChar else 0
        if (chapterStart > chapter.body.length) {
            return "第 $chapterNumber 章可读内容只到字符偏移 ${chapter.body.length}，start_char=$chapterStart 超出范围。"
        }
        val header = buildString {
            if (output.isNotEmpty()) append("\n\n")
            append("【第 ").append(chapterNumber).append(" 章")
            chapter.title.takeIf(String::isNotBlank)?.let { append("「").append(it).append("」") }
            append("】\n")
        }
        if (header.length >= remaining) {
            output.append("\n[内容未完：请从第 $chapterNumber 章 start_char=$chapterStart 继续读取]")
            break
        }
        output.append(header)
        remaining -= header.length
        val takeEnd = (chapterStart + remaining).coerceAtMost(chapter.body.length)
        output.append(chapter.body, chapterStart, takeEnd)
        remaining -= takeEnd - chapterStart
        if (takeEnd < chapter.body.length) {
            output.append(
                "\n[内容未完：请继续调用 read_book_section，from_chapter=$chapterNumber，" +
                    "to_chapter=$toChapter，start_char=$takeEnd]"
            )
            break
        }
        if (remaining <= 0 && chapterNumber < toChapter) {
            output.append(
                "\n[内容未完：请继续调用 read_book_section，from_chapter=${chapterNumber + 1}，" +
                    "to_chapter=$toChapter，start_char=0]"
            )
            break
        }
    }
    return output.toString().ifBlank { "指定范围内还没有已读正文。" }
}

private class AddAnnotationTool(
    private val bookId: Long,
    private val personaId: Long,
    private val libraryRepository: LibraryRepository,
    private val annotations: AnnotationRepository,
    private val readingScope: ReadingScope
) : AgentTool {
    override val displayName: String = "添加段落批注"

    override val spec: ToolSpec = ToolSpec(
        name = "add_annotation",
        description = "对用户已读原文添加一条可在正文段落讨论区看到的角色批注（划线样式承载语义，帮读者一眼识别批注类型）。" +
            "quote 必须逐字复制自 read_book_section/search_book 的结果；用户未要求或没有值得补充的观点时不要擅自调用。",
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

    override suspend fun execute(arguments: JsonObject): String {
        val quote = arguments["quote"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val comment = arguments["comment"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (quote.isEmpty()) return "缺少原文 quote"
        if (comment.isEmpty()) return "缺少批注 comment"
        if (quote.length > MAX_ANNOTATION_QUOTE_CHARS) return "quote 过长，请选择 2000 字以内的连续原文"
        val style = AnnotationStyle.fromWire(arguments["style"]?.jsonPrimitive?.contentOrNull)
        val book = libraryRepository.getBook(bookId) ?: return "未找到当前书籍"
        val chapterNumber = arguments["chapter_number"]?.jsonPrimitive?.intOrNull
        val maxVisibleChapter = readingScope.clampLastChapter(book.totalChapters)
        if (chapterNumber != null && chapterNumber !in 1..maxVisibleChapter + 1) {
            return "章节超出当前可见范围：最多可访问第 ${maxVisibleChapter + 1} 章。"
        }
        val chapters = libraryRepository.getChapters(bookId)
            .filter { it.chapterIndex <= maxVisibleChapter }
            .filter { chapterNumber == null || it.chapterIndex == chapterNumber - 1 }
            .map { chapter ->
                val full = libraryRepository.readChapterText(bookId, chapter)
                ChapterDocument(
                    chapter.chapterIndex,
                    chapter.title,
                    if (!readingScope.isWholeBook && chapter.chapterIndex == readingScope.maxChapterIndex) {
                        full.take(readingScope.maxCharOffset.coerceIn(0, full.length))
                    } else {
                        full
                    }
                )
            }
            .toList()
        val matches = locateExactQuote(chapters, quote)
        if (matches.isEmpty()) {
            return "已读原文中找不到这段 quote。请先用 read_book_section 或 search_book 读取原文，再逐字复制更准确的引文。"
        }
        if (matches.size > 1) {
            return "这段 quote 在已读范围出现 ${matches.size} 次，无法确定位置；请提供 chapter_number 或复制更长的唯一引文。"
        }
        val match = matches.single()
        val id = annotations.add(
            bookId = bookId,
            personaId = personaId,
            chapterIndex = match.chapterIndex,
            startCharOffset = match.startCharOffset,
            endCharOffset = match.endCharOffset,
            selectedText = quote,
            note = comment.take(MAX_ANNOTATION_COMMENT_CHARS),
            // 角色颜色不占用户色板：按 personaId 稳定散列，同角色永远同色
            colorTag = AnnotationColors.forPersona(personaId),
            style = style,
            sourceScopeChapterIndex = readingScope.maxChapterIndex,
            sourceScopeCharOffset = readingScope.maxCharOffset
        )
        return "已在第 ${match.chapterIndex + 1} 章添加段落批注（编号 $id，样式 ${style.wire.lowercase()}），" +
            "读者点击正文旁的批注标记即可在讨论区看到。"
    }

    private companion object {
        const val MAX_ANNOTATION_QUOTE_CHARS = 2_000
        const val MAX_ANNOTATION_COMMENT_CHARS = 10_000
    }
}

/** 逐字定位交给共享实现；批注要求唯一命中，聊天页的跳转允许多处，规则只有一份。 */
internal fun locateExactQuote(chapters: List<ChapterDocument>, quote: String): List<QuoteLocation> =
    BookQuoteLocator.locateAll(
        chapters.map { QuoteChapter(it.chapterIndex, it.body) },
        quote
    )

private class GenerateImageTool(
    private val bookId: Long,
    private val personaId: Long,
    private val getBook: suspend () -> BookEntity?,
    private val mediaService: AiMediaGenerationService,
    private val readingScope: ReadingScope
) : AgentTool {
    override val displayName: String = "生成并保存插图"

    override val spec: ToolSpec = ToolSpec(
        name = "generate_image",
        description = "调用用户分配的生图模型生成小说插图，并永久保存到本书插图廊。提示词只能依据用户已读内容；系统会按当前后端自动改写提示词，NovelAI 使用 Danbooru tags。适合用户明确要求画面、插图或角色形象时调用。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("prompt") {
                    put("type", "string")
                    put("description", "完整画面提示词：人物、环境、构图、光线、风格；不要文字和水印")
                }
                putJsonObject("source_text") {
                    put("type", "string")
                    put("description", "可选：作为插图依据的已读原文或简述")
                }
                putJsonObject("chapter_number") {
                    put("type", "integer")
                    put("description", "可选关联章节号，从 1 开始")
                }
                putJsonObject("char_offset") {
                    put("type", "integer")
                    put("description", "可选章内 UTF-16 锚点")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("prompt")) }
        }
    )

    override suspend fun execute(arguments: JsonObject): String {
        val prompt = arguments["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (prompt.isEmpty()) return "缺少生图提示词 prompt"
        val book = getBook() ?: return "未找到当前书籍"
        val chapterNumber = arguments["chapter_number"]?.jsonPrimitive?.intOrNull
        val maxVisibleChapter = readingScope.clampLastChapter(book.totalChapters)
        if (chapterNumber != null && chapterNumber !in 1..maxVisibleChapter + 1) {
            return "插图锚点超出当前可见范围：最多可访问第 ${maxVisibleChapter + 1} 章。"
        }
        val chapterIndex = chapterNumber?.minus(1) ?: if (readingScope.isWholeBook) {
            book.lastReadChapterIndex
        } else {
            readingScope.maxChapterIndex
        }
        val requestedOffset = arguments["char_offset"]?.jsonPrimitive?.intOrNull
        val charOffset = (requestedOffset ?: if (!readingScope.isWholeBook && chapterIndex == readingScope.maxChapterIndex) {
            readingScope.maxCharOffset
        } else {
            0
        }).coerceAtLeast(0)
        if (!readingScope.isWholeBook && chapterIndex == readingScope.maxChapterIndex && charOffset > readingScope.maxCharOffset) {
            return "插图锚点超出当前阅读水位（最大字符偏移 ${readingScope.maxCharOffset}）。"
        }
        val illustration = mediaService.generateIllustration(
            bookId = bookId,
            chapterIndex = chapterIndex,
            charOffset = charOffset,
            sourceText = arguments["source_text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            prompt = prompt,
            personaId = personaId
        )
        return AgentMediaResult(
            mediaKind = "image",
            mediaId = illustration.id,
            path = illustration.imagePath,
            mediaType = illustration.mediaType,
            message = "插图已保存到《${book.title}》插图廊"
        ).encode()
    }
}

private class SynthesizeSpeechTool(
    private val bookId: Long,
    private val mediaService: AiMediaGenerationService
) : AgentTool {
    override val displayName: String = "合成并缓存语音"

    override val spec: ToolSpec = ToolSpec(
        name = "synthesize_speech",
        description = "使用用户分配的 OpenAI 或 MiniMax TTS 模型合成语音。相同文本、模型、音色和参数会直接复用缓存。用户要求朗读、配音或有声片段时调用。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "要朗读的文本，最长 8000 字；只能包含当前已知内容")
                }
                putJsonObject("voice_id") {
                    put("type", "string")
                    put("description", "可选音色 ID。OpenAI 如 alloy、nova；MiniMax 可填系统/克隆音色 ID。省略则使用模型配置")
                }
                putJsonObject("speed") {
                    put("type", "number")
                    put("description", "语速倍率；OpenAI 0.25-4.0，MiniMax 0.5-2.0，省略使用模型配置")
                }
                putJsonObject("volume") {
                    put("type", "number")
                    put("description", "MiniMax 音量 0-10，可选")
                }
                putJsonObject("pitch") {
                    put("type", "integer")
                    put("description", "MiniMax 音调 -12 到 12，可选")
                }
                putJsonObject("format") {
                    put("type", "string")
                    put("enum", kotlinx.serialization.json.buildJsonArray {
                        add(JsonPrimitive("mp3")); add(JsonPrimitive("wav"));
                        add(JsonPrimitive("flac")); add(JsonPrimitive("aac"))
                    })
                }
            }
            putJsonArray("required") { add(JsonPrimitive("text")) }
        }
    )

    override suspend fun execute(arguments: JsonObject): String {
        val text = arguments["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) return "缺少朗读文本 text"
        val speech = mediaService.synthesizeSpeech(
            bookId = bookId,
            text = text,
            voiceId = arguments["voice_id"]?.jsonPrimitive?.contentOrNull,
            speed = arguments["speed"]?.jsonPrimitive?.floatOrNull,
            volume = arguments["volume"]?.jsonPrimitive?.floatOrNull,
            pitch = arguments["pitch"]?.jsonPrimitive?.intOrNull,
            format = arguments["format"]?.jsonPrimitive?.contentOrNull
        )
        return AgentMediaResult(
            mediaKind = "audio",
            path = speech.path,
            mediaType = speech.mediaType,
            message = if (speech.cacheHit) "已复用语音缓存，可直接播放" else "语音已生成并缓存，可直接播放"
        ).encode()
    }
}

private class WriteNoteTool(
    private val bookId: Long,
    private val personaId: Long,
    private val conversationId: Long?,
    private val getBook: suspend () -> BookEntity?,
    private val notes: NoteRepository,
    private val readingScope: ReadingScope
) : AgentTool {
    override val displayName: String = "写读书笔记"

    override val spec: ToolSpec = ToolSpec(
        name = "write_note",
        description = "把用户明确要求保存的读书笔记写入本书笔记库。给出 note_id 时更新该条，否则新建；用户手写和其他角色的笔记不可改写。内容使用 Markdown；不要在用户没有要求保存时擅自调用。",
        parameters = noteParameters("笔记标题", "笔记 Markdown 正文")
    )

    override suspend fun execute(arguments: JsonObject): String = saveNote(
        arguments = arguments,
        kind = NoteRepository.KIND_NOTE,
        defaultTitle = "伴读笔记"
    )

    private suspend fun saveNote(
        arguments: JsonObject,
        kind: String,
        defaultTitle: String
    ): String {
        val content = arguments["content_md"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (content.isEmpty()) return "缺少笔记正文 content_md"
        val book = getBook() ?: return "未找到当前书籍"
        val title = arguments["title"]?.jsonPrimitive?.contentOrNull?.trim()
            .orEmpty().ifBlank { defaultTitle }
        val requestedId = arguments["note_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val requested = requestedId?.let { notes.getNote(it) }
        if (requestedId != null && requested == null) return "未找到第 $requestedId 条笔记"
        return when (val target = resolveNoteWriteTarget(
            requested = requested,
            latest = null,
            bookId = bookId,
            personaId = personaId,
            kind = kind,
            asNew = false
        )) {
            NoteWriteTarget.Create -> {
                val noteId = notes.create(
                    bookId = bookId,
                    personaId = personaId,
                    title = title.take(120),
                    contentMarkdown = content.take(MAX_NOTE_CHARS),
                    kind = kind,
                    sourceConversationId = conversationId,
                    relatedChapterIndex = if (readingScope.isWholeBook) null else readingScope.maxChapterIndex,
                    relatedCharOffset = if (readingScope.isWholeBook) null else readingScope.maxCharOffset,
                    sourceScopeChapterIndex = readingScope.maxChapterIndex,
                    sourceScopeCharOffset = readingScope.maxCharOffset
                )
                "已保存到《${book.title}》的笔记（编号 $noteId），来源范围：${scopeLabel(readingScope)}。"
            }
            is NoteWriteTarget.Update -> {
                val before = target.note.contentMarkdown.length
                val updatedContent = content.take(MAX_NOTE_CHARS)
                notes.updateContentAndPosition(
                    noteId = target.note.id,
                    title = title.take(120),
                    contentMarkdown = updatedContent,
                    relatedChapterIndex = if (readingScope.isWholeBook) null else readingScope.maxChapterIndex,
                    relatedCharOffset = if (readingScope.isWholeBook) null else readingScope.maxCharOffset,
                    sourceScopeChapterIndex = readingScope.maxChapterIndex,
                    sourceScopeCharOffset = readingScope.maxCharOffset
                )
                "已更新第 ${target.note.id} 条读书笔记（$before → ${updatedContent.length} 字），来源范围：${scopeLabel(readingScope)}。"
            }
            is NoteWriteTarget.Reject -> target.reason
        }
    }
}

private class SavePlotSummaryTool(
    private val bookId: Long,
    private val personaId: Long,
    private val conversationId: Long?,
    private val getBook: suspend () -> BookEntity?,
    private val notes: NoteRepository,
    private val readingScope: ReadingScope
) : AgentTool {
    override val displayName: String = "保存剧情梗概"

    override val spec: ToolSpec = ToolSpec(
        name = "save_plot_summary",
        description = "保存或更新截至当前阅读进度的剧情梗概。梗概是滚动文档，默认覆盖当前角色最新一条；先用 list_notes 读回旧稿，只有 as_new=true 才新建。严禁写入未读剧情或改写用户手写内容。",
        parameters = plotSummaryParameters()
    )

    override suspend fun execute(arguments: JsonObject): String {
        val content = arguments["content_md"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (content.isEmpty()) return "缺少梗概正文 content_md"
        val book = getBook() ?: return "未找到当前书籍"
        val currentChapter = readingScope.clampLastChapter(book.totalChapters) + 1
        val fromChapter = arguments["from_chapter"]?.jsonPrimitive?.intOrNull ?: 1
        val toChapter = arguments["to_chapter"]?.jsonPrimitive?.intOrNull ?: currentChapter
        if (fromChapter < 1 || toChapter < fromChapter) {
            return "梗概章节范围无效：$fromChapter-$toChapter"
        }
        if (toChapter > currentChapter) {
            return "梗概超出已读范围：用户只读到第 $currentChapter 章，不能保存到第 $toChapter 章。"
        }
        val defaultTitle = if (fromChapter == 1 && toChapter == currentChapter) {
            "剧情梗概 · 截至第 $toChapter 章"
        } else if (fromChapter == toChapter) {
            "剧情梗概 · 第 $fromChapter 章"
        } else {
            "剧情梗概 · 第 $fromChapter-$toChapter 章"
        }
        val title = arguments["title"]?.jsonPrimitive?.contentOrNull?.trim()
            .orEmpty().ifBlank { defaultTitle }
        val requestedId = arguments["note_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val requested = requestedId?.let { notes.getNote(it) }
        if (requestedId != null && requested == null) return "未找到第 $requestedId 条笔记"
        val asNew = arguments["as_new"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val latest = if (requested == null && !asNew) {
            notes.latestByKind(bookId, personaId, NoteRepository.KIND_PLOT_SUMMARY)
        } else null
        return when (val target = resolveNoteWriteTarget(
            requested = requested,
            latest = latest,
            bookId = bookId,
            personaId = personaId,
            kind = NoteRepository.KIND_PLOT_SUMMARY,
            asNew = asNew
        )) {
            NoteWriteTarget.Create -> {
                val noteId = notes.create(
                    bookId = bookId,
                    personaId = personaId,
                    title = title.take(120),
                    contentMarkdown = content.take(MAX_NOTE_CHARS),
                    kind = NoteRepository.KIND_PLOT_SUMMARY,
                    sourceConversationId = conversationId,
                    relatedChapterIndex = toChapter - 1,
                    relatedCharOffset = if (!readingScope.isWholeBook && toChapter == currentChapter) readingScope.maxCharOffset else null,
                    sourceScopeChapterIndex = readingScope.maxChapterIndex,
                    sourceScopeCharOffset = readingScope.maxCharOffset
                )
                "第 $fromChapter-$toChapter 章剧情梗概已保存（编号 $noteId），可在书籍详情的「剧情梗概与笔记」中回顾。"
            }
            is NoteWriteTarget.Update -> {
                val previousTo = target.note.relatedChapterIndex?.plus(1)
                val before = target.note.contentMarkdown.length
                notes.updateContentAndPosition(
                    noteId = target.note.id,
                    title = title.take(120),
                    contentMarkdown = content.take(MAX_NOTE_CHARS),
                    relatedChapterIndex = toChapter - 1,
                    relatedCharOffset = if (!readingScope.isWholeBook && toChapter == currentChapter) readingScope.maxCharOffset else null,
                    sourceScopeChapterIndex = readingScope.maxChapterIndex,
                    sourceScopeCharOffset = readingScope.maxCharOffset
                )
                val oldRange = previousTo?.let { "原覆盖至第 $it 章 → " }.orEmpty()
                "已更新第 ${target.note.id} 条剧情梗概（${oldRange}现第 $fromChapter-$toChapter 章，$before → ${content.take(MAX_NOTE_CHARS).length} 字）。"
            }
            is NoteWriteTarget.Reject -> target.reason
        }
    }
}

private fun scopeLabel(scope: ReadingScope): String =
    if (scope.isWholeBook) "全书" else "第 ${scope.maxChapterIndex + 1} 章字符 ${scope.maxCharOffset}"

private fun noteParameters(titleDescription: String, contentDescription: String): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("title") {
                put("type", "string")
                put("description", titleDescription)
            }
            putJsonObject("content_md") {
                put("type", "string")
                put("description", contentDescription)
            }
            putJsonObject("note_id") {
                put("type", "integer")
                put("description", "要覆盖的笔记编号；省略则新建")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("title"))
            add(JsonPrimitive("content_md"))
        }
    }

private fun plotSummaryParameters(): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("title") {
            put("type", "string")
            put("description", "梗概标题；可留空由应用按章节范围生成")
        }
        putJsonObject("content_md") {
            put("type", "string")
            put("description", "完整的 Markdown 剧情梗概")
        }
        putJsonObject("from_chapter") {
            put("type", "integer")
            put("description", "梗概起始章节号，从 1 开始；默认 1")
        }
        putJsonObject("to_chapter") {
            put("type", "integer")
            put("description", "梗概结束章节号（包含）；默认用户当前章节")
        }
        putJsonObject("note_id") {
            put("type", "integer")
            put("description", "要覆盖的梗概编号；省略时默认更新当前角色最新梗概")
        }
        putJsonObject("as_new") {
            put("type", "boolean")
            put("description", "是否强制新建一条梗概，默认 false")
        }
    }
    putJsonArray("required") { add(JsonPrimitive("content_md")) }
}

private val READ_ONLY_BASE_TOOLS = setOf(
    "get_reading_progress",
    "search_book",
    "read_book_section",
    "list_chapters",
    "list_annotations",
    "list_notes",
    "recall_memory"
)

private const val MAX_NOTE_CHARS = 50_000

/**
 * 混合书内检索：优先向量，Embedding/索引不可用时自动退回本地关键词扫描。
 * 章节上限先在 ObjectBox 查询层过滤；当前章再按 UTF-16 阅读偏移做二次硬过滤，
 * 防止“已进入本章但尚未读到的后半章”通过向量命中泄露。
 */
internal class SearchBookTool(
    private val bookId: Long,
    private val getBook: suspend () -> BookEntity?,
    private val chapterTitle: suspend (Int) -> String?,
    private val embedQuery: suspend (String) -> FloatArray,
    private val store: () -> BoxStore,
    private val readingScope: ReadingScope,
    private val loadChapter: suspend (Int) -> ChapterDocument? = { null },
    private val loadChaptersThrough: suspend (Int) -> List<ChapterDocument> = { emptyList() },
    private val requestIndex: () -> Unit = {},
    private val indexingEnabled: suspend () -> Boolean = { true }
) : AgentTool {

    override val displayName: String = "检索书中原文"

    override val spec: ToolSpec = ToolSpec(
        name = "search_book",
        description = if (readingScope.isWholeBook) {
            "在整本书中搜索人物、场景或情节。使用向量与 BM25 混合检索，并补充相邻片段；若用户指定明确章节范围并要求概括，应改用 read_book_section。"
        } else {
            "在用户阅读进度水位内搜索人物、场景或情节。使用向量与 BM25 混合检索，并补充相邻片段；若用户指定明确章节范围并要求概括，应改用 read_book_section。"
        },
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "要找的内容，用一句话描述情节、人物或场景")
                }
                putJsonObject("top_k") {
                    put("type", "integer")
                    put("description", "返回片段数量，1-8，默认 5")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("query")) }
        }
    )

    override suspend fun execute(arguments: JsonObject): String {
        val query = arguments["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) return "缺少检索词 query"
        val topK = (arguments["top_k"]?.jsonPrimitive?.intOrNull ?: DEFAULT_TOP_K).coerceIn(1, 8)
        val book = getBook() ?: return "未找到当前书籍"
        val maxChapterIndex = readingScope.clampLastChapter(book.totalChapters)
        val hasIndex = runCatching { VectorQueries.chaptersWithChunks(store(), bookId).isNotEmpty() }
            .getOrDefault(false)
        val allowVectorIndex = hasIndex || indexingEnabled()
        if (allowVectorIndex && !hasIndex) requestIndex()

        suspend fun persistedCandidates(): List<RetrievalCandidate> =
            VectorQueries.listChunks(store(), bookId, maxChapterIndex).map { chunk ->
                chunk.toCandidate(resolveLegacyOffsets(chunk, readingScope, loadChapter))
            }

        val pipeline = RetrievalPipeline(
            vectorRecall = RetrievalRecall { request ->
                if (!allowVectorIndex || !hasIndex) return@RetrievalRecall emptyList()
                val vector = embedQuery(request.query)
                VectorQueries.searchChunks(
                    store(),
                    bookId,
                    vector,
                    request.recallDepth,
                    maxChapterIndex
                ).map { hit ->
                    val chunk = hit.get()
                    chunk.toCandidate(resolveLegacyOffsets(chunk, readingScope, loadChapter)).copy(
                        vectorDistance = hit.score.toDouble()
                    )
                }
            },
            lexicalRecall = RetrievalRecall { request ->
                val candidates = if (hasIndex) {
                    persistedCandidates()
                } else {
                    loadChaptersThrough(maxChapterIndex).flatMap { document ->
                        ChapterChunker.chunkWithOffsets(document.body).mapIndexed { index, chunk ->
                            RetrievalCandidate(
                                bookId = bookId,
                                chapterIndex = document.chapterIndex,
                                chunkIndex = index,
                                text = chunk.text,
                                startCharOffset = chunk.startCharOffset,
                                endCharOffset = chunk.endCharOffset
                            )
                        }
                    }
                }
                Bm25LexicalRecall.rank(candidates, request.query, request.recallDepth)
            },
            expander = NeighborExpander { hits, radius, scope ->
                if (radius == 0 || hits.isEmpty()) return@NeighborExpander hits
                val corpus = if (hasIndex) persistedCandidates() else {
                    loadChaptersThrough(maxChapterIndex).flatMap { document ->
                        ChapterChunker.chunkWithOffsets(document.body).mapIndexed { index, chunk ->
                            RetrievalCandidate(
                                bookId = bookId,
                                chapterIndex = document.chapterIndex,
                                chunkIndex = index,
                                text = chunk.text,
                                startCharOffset = chunk.startCharOffset,
                                endCharOffset = chunk.endCharOffset
                            )
                        }
                    }
                }
                expandNeighborWindows(hits, corpus, radius, scope)
            }
        )
        val result = pipeline.retrieve(
            RetrievalRequest(
                bookId = bookId,
                query = query,
                scope = readingScope,
                topK = topK,
                recallDepth = maxOf(topK * VECTOR_CANDIDATE_MULTIPLIER, DEFAULT_RECALL_DEPTH),
                maxVectorDistance = DEFAULT_MAX_VECTOR_DISTANCE,
                neighborRadius = DEFAULT_NEIGHBOR_RADIUS
            )
        )
        val combined = result.hits

        if (combined.isEmpty()) {
            if (!allowVectorIndex) {
                return "本书未启用 AI 索引；已使用本地 BM25 关键词检索，但${searchRangeLabel(maxChapterIndex)}没有找到与「$query」相关的原文。"
            }
            if (result.vectorFailure != null) {
                return "向量检索不可用：${result.vectorFailure.message ?: "embedding 失败"}；已自动尝试本地 BM25 关键词检索，但${searchRangeLabel(maxChapterIndex)}没有找到与「$query」相关的原文。"
            }
            val indexedInRange = runCatching {
                VectorQueries.chaptersWithChunks(store(), bookId).any { it <= maxChapterIndex }
            }.getOrDefault(false)
            return if (!indexedInRange) {
                "本书向量索引正在后台建立；已自动尝试本地 BM25 关键词检索，但没有找到与「$query」相关的原文。"
            } else {
                "${searchRangeLabel(maxChapterIndex)}没有找到与「$query」相关的原文。"
            }
        }

        return buildString {
            append(
                if (readingScope.isWholeBook) {
                    "以下片段来自整本书（第 1 至 ${maxChapterIndex + 1} 章）：\n"
                } else {
                    "以下片段全部来自用户阅读进度水位（第 1 至 ${maxChapterIndex + 1} 章）：\n"
                }
            )
            if (!allowVectorIndex) {
                append("（本书未启用 AI 索引，本次为本地 BM25 关键词检索结果。）\n")
            } else if (result.vectorFailure != null) {
                append("（向量服务当前不可用，已自动切换到本地 BM25 关键词检索。）\n")
            } else if (!hasIndex) {
                append("（本书向量索引正在后台建立，本次为本地 BM25 关键词检索结果。）\n")
            }
            if (result.rerankFailure != null) append("（重排暂不可用，已按融合排序返回。）\n")
            combined.forEach { hit ->
                append("\n【第 ").append(hit.chapterIndex + 1).append(" 章")
                chapterTitle(hit.chapterIndex)
                    ?.takeIf(String::isNotBlank)
                    ?.let { append("「").append(it).append("」") }
                append("】\n").append(hit.text).append('\n')
            }
        }
    }

    private fun searchRangeLabel(maxChapterIndex: Int): String = if (readingScope.isWholeBook) {
        "全书（第 1 至 ${maxChapterIndex + 1} 章）中"
    } else {
        "阅读进度水位内"
    }

    private companion object {
        const val DEFAULT_TOP_K = 5
        const val VECTOR_CANDIDATE_MULTIPLIER = 8
        const val DEFAULT_RECALL_DEPTH = 60
        const val DEFAULT_NEIGHBOR_RADIUS = 1
        const val DEFAULT_MAX_VECTOR_DISTANCE = 0.80
    }
}

private suspend fun resolveLegacyOffsets(
    chunk: com.mozhi.reader.core.vector.BookChunk,
    scope: ReadingScope,
    loadChapter: suspend (Int) -> ChapterDocument?
): Pair<Int, Int> {
    if (chunk.endCharOffset > chunk.startCharOffset) return chunk.startCharOffset to chunk.endCharOffset
    if (scope.isWholeBook || chunk.chapterIndex < scope.maxChapterIndex) return 0 to Int.MAX_VALUE
    val text = chunk.text?.takeIf(String::isNotBlank) ?: return -1 to -1
    val body = loadChapter(chunk.chapterIndex)?.body ?: return -1 to -1
    val rebuilt = ChapterChunker.chunkWithOffsets(body).getOrNull(chunk.chunkIndex)
        ?: return -1 to -1
    return if (rebuilt.text == text) {
        rebuilt.startCharOffset to rebuilt.endCharOffset
    } else {
        -1 to -1
    }
}

private fun com.mozhi.reader.core.vector.BookChunk.toCandidate(offsets: Pair<Int, Int>) = RetrievalCandidate(
    bookId = bookId,
    chapterIndex = chapterIndex,
    chunkIndex = chunkIndex,
    text = text.orEmpty(),
    startCharOffset = offsets.first,
    endCharOffset = offsets.second
)

internal fun expandNeighborWindows(
    hits: List<RetrievalCandidate>,
    corpus: List<RetrievalCandidate>,
    radius: Int,
    scope: ReadingScope
): List<RetrievalCandidate> {
    if (hits.isEmpty()) return emptyList()
    val byChapter = corpus.filter { scope.allowsChunk(it.chapterIndex, it.startCharOffset, it.endCharOffset) }
        .groupBy(RetrievalCandidate::chapterIndex)
    return hits.groupBy(RetrievalCandidate::chapterIndex).flatMap { (chapterIndex, chapterHits) ->
        val intervals = chapterHits.map { hit ->
            (hit.chunkIndex - radius).coerceAtLeast(0)..(hit.chunkIndex + radius)
        }.sortedBy(IntRange::first)
        val merged = mutableListOf<IntRange>()
        intervals.forEach { range ->
            val previous = merged.lastOrNull()
            if (previous != null && range.first <= previous.last + 1) {
                merged[merged.lastIndex] = previous.first..maxOf(previous.last, range.last)
            } else {
                merged += range
            }
        }
        val chapterChunks = byChapter[chapterIndex].orEmpty().associateBy(RetrievalCandidate::chunkIndex)
        merged.mapNotNull { range ->
            val chunks = range.mapNotNull(chapterChunks::get).sortedBy(RetrievalCandidate::chunkIndex)
            if (chunks.isEmpty()) null else RetrievalCandidate(
                bookId = chunks.first().bookId,
                chapterIndex = chapterIndex,
                chunkIndex = chunks.first().chunkIndex,
                text = chunks.joinToString("\n") { it.text },
                startCharOffset = chunks.first().startCharOffset,
                endCharOffset = chunks.last().endCharOffset,
                vectorDistance = chapterHits.mapNotNull(RetrievalCandidate::vectorDistance).minOrNull(),
                lexicalScore = chapterHits.mapNotNull(RetrievalCandidate::lexicalScore).maxOrNull()
            )
        }
    }
}

internal data class TextSearchHit(val chapterIndex: Int, val text: String, val score: Int = 0)

/** Pure local fallback for books whose persisted chunk index does not exist yet. */
internal fun rankLexicalChapters(
    chapters: List<ChapterDocument>,
    query: String,
    topK: Int
): List<TextSearchHit> {
    val candidates = chapters.flatMap { chapter ->
        ChapterChunker.chunkWithOffsets(chapter.body).mapIndexed { index, chunk ->
            RetrievalCandidate(
                bookId = 0,
                chapterIndex = chapter.chapterIndex,
                chunkIndex = index,
                text = chunk.text,
                startCharOffset = chunk.startCharOffset,
                endCharOffset = chunk.endCharOffset
            )
        }
    }
    return Bm25LexicalRecall.rank(candidates, query, topK).map { hit ->
        TextSearchHit(hit.chapterIndex, hit.text, ((hit.lexicalScore ?: 0.0) * 1000).toInt())
    }
}


/**
 * 角色长期记忆检索。记忆按 personaId 隔离，再按两条边界收窄：
 * [bookId] 非 null 时只回忆这本书（跨书对话检索关闭），[maskId] 保证面具间互不穿帮。
 */
internal class RecallMemoryTool(
    private val personaId: Long,
    private val embedQuery: suspend (String) -> FloatArray,
    private val store: () -> BoxStore,
    private val bookId: Long? = null,
    private val currentBookId: Long? = bookId,
    private val maskId: Long = 0L,
    private val readingScope: ReadingScope
) : AgentTool {

    override val displayName: String = "回忆过往交流"

    override val spec: ToolSpec = ToolSpec(
        name = "recall_memory",
        description = "检索你与用户过往交流沉淀下来的长期记忆。用户提到之前说过的话、个人偏好或约定时使用。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "要回忆的内容线索")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("query")) }
        }
    )

    override suspend fun execute(arguments: JsonObject): String {
        val query = arguments["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) return "缺少检索词 query"
        val vector = try {
            embedQuery(query)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return "记忆检索不可用：${error.message ?: "embedding 失败"}"
        }
        val hits = VectorQueries.searchMemories(
            store(), personaId, vector, TOP_K, bookId, maskId,
            currentBookId, readingScope.maxChapterIndex, readingScope.maxCharOffset
        )
        if (hits.isEmpty()) return "还没有与此相关的长期记忆。"
        return "相关记忆（按相关度排序）：\n" +
            hits.joinToString("\n") { "- ${it.get().summary}" }
    }

    private companion object {
        const val TOP_K = 5
    }
}
