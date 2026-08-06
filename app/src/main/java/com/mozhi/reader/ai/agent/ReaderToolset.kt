package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.client.ToolSpec
import com.mozhi.reader.ai.embedding.BookEmbeddingScheduler
import com.mozhi.reader.ai.media.AgentMediaResult
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.core.database.entity.AnnotationColors
import com.mozhi.reader.core.database.entity.AnnotationStyle
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.library.AnnotationRepository
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.NoteRepository
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
    private val embeddingScheduler: BookEmbeddingScheduler
) {
    fun forBook(
        bookId: Long,
        personaId: Long? = null,
        conversationId: Long? = null,
        enabledTools: Collection<String>? = null
    ): List<AgentTool> {
        val embedQuery: suspend (String) -> FloatArray = { query ->
            val resolved = clientFactory.get().forRole(ModelRole.EMBEDDING)
            Embeddings.conformToIndex(resolved.client.embed(listOf(query)).first())
        }
        val tools = buildList {
            add(GetReadingProgressTool(libraryRepository, bookId))
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
                    requestIndex = { embeddingScheduler.enqueueForBook(bookId) }
                )
            )
            add(ReadBookSectionTool(libraryRepository, bookId))
            if (personaId != null) {
                add(
                    RecallMemoryTool(
                        personaId = personaId,
                        embedQuery = embedQuery,
                        store = { vectorStore.get() }
                    )
                )
                add(
                    AddAnnotationTool(
                        bookId = bookId,
                        personaId = personaId,
                        libraryRepository = libraryRepository,
                        annotations = annotationRepository
                    )
                )
                add(
                    WriteNoteTool(
                        bookId = bookId,
                        personaId = personaId,
                        conversationId = conversationId,
                        getBook = { libraryRepository.getBook(bookId) },
                        notes = noteRepository
                    )
                )
                add(
                    SavePlotSummaryTool(
                        bookId = bookId,
                        personaId = personaId,
                        conversationId = conversationId,
                        getBook = { libraryRepository.getBook(bookId) },
                        notes = noteRepository
                    )
                )
                add(
                    GenerateImageTool(
                        bookId = bookId,
                        personaId = personaId,
                        getBook = { libraryRepository.getBook(bookId) },
                        mediaService = mediaService
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
            tools.filter { tool ->
                tool.spec.name in allowed ||
                    (tool.spec.name == "read_book_section" && "search_book" in allowed) ||
                    (tool.spec.name == "save_plot_summary" && "write_note" in allowed)
            }
        } ?: tools
    }
}

private class GetReadingProgressTool(
    private val libraryRepository: LibraryRepository,
    private val bookId: Long
) : AgentTool {

    override val displayName: String = "查询阅读进度"

    override val spec: ToolSpec = ToolSpec(
        name = "get_reading_progress",
        description = "获取当前书籍的阅读进度：书名、作者、总章数、当前读到的章节。回答与进度或剧情范围相关的问题前应先调用。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }
    )

    override suspend fun execute(arguments: JsonObject): String {
        val book = libraryRepository.getBook(bookId)
            ?: return "未找到当前书籍"
        val chapters = libraryRepository.getChapters(bookId)
        val current = chapters.getOrNull(book.lastReadChapterIndex)
        return buildString {
            append("《").append(book.title).append("》")
            if (book.author.isNotBlank()) append("，作者 ").append(book.author)
            append("。全书共 ").append(book.totalChapters).append(" 章，")
            append("当前读到第 ").append(book.lastReadChapterIndex + 1).append(" 章")
            current?.title?.takeIf(String::isNotBlank)?.let { append("「").append(it).append("」") }
            append("。用户尚未读到之后的章节，回答不要涉及后续剧情。")
        }
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
    private val bookId: Long
) : AgentTool {
    override val displayName: String = "读取指定已读章节"

    override val spec: ToolSpec = ToolSpec(
        name = "read_book_section",
        description = "读取用户指定的已读章节或章节范围原文。概括第几章、第几章到第几章、某一部分时优先使用；不依赖向量索引。章节号从 1 开始，当前章内容只会返回到用户实际阅读位置。",
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
        val maxReadableChapter = book.lastReadChapterIndex + 1
        if (toChapter > maxReadableChapter) {
            return "超出已读范围：用户只读到第 $maxReadableChapter 章，不能读取第 $toChapter 章。"
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
                        body = if (chapterIndex == book.lastReadChapterIndex) {
                            fullBody.take(book.lastReadCharOffset.coerceAtLeast(0))
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
    private val annotations: AnnotationRepository
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
        if (chapterNumber != null && chapterNumber !in 1..book.lastReadChapterIndex + 1) {
            return "章节超出已读范围：用户只读到第 ${book.lastReadChapterIndex + 1} 章。"
        }
        val chapters = libraryRepository.getChapters(bookId)
            .filter { it.chapterIndex <= book.lastReadChapterIndex }
            .filter { chapterNumber == null || it.chapterIndex == chapterNumber - 1 }
            .map { chapter ->
                val full = libraryRepository.readChapterText(bookId, chapter)
                ChapterDocument(
                    chapter.chapterIndex,
                    chapter.title,
                    if (chapter.chapterIndex == book.lastReadChapterIndex) {
                        full.take(book.lastReadCharOffset.coerceAtLeast(0))
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
            style = style
        )
        return "已在第 ${match.chapterIndex + 1} 章添加段落批注（编号 $id，样式 ${style.wire.lowercase()}），" +
            "读者点击正文旁的批注标记即可在讨论区看到。"
    }

    private companion object {
        const val MAX_ANNOTATION_QUOTE_CHARS = 2_000
        const val MAX_ANNOTATION_COMMENT_CHARS = 10_000
    }
}

internal data class QuoteLocation(
    val chapterIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int
)

internal fun locateExactQuote(chapters: List<ChapterDocument>, quote: String): List<QuoteLocation> {
    if (quote.isEmpty()) return emptyList()
    return buildList {
        chapters.forEach { chapter ->
            var from = 0
            while (from <= chapter.body.length - quote.length) {
                val index = chapter.body.indexOf(quote, from)
                if (index < 0) break
                add(QuoteLocation(chapter.chapterIndex, index, index + quote.length))
                from = index + quote.length.coerceAtLeast(1)
            }
        }
    }
}

private class GenerateImageTool(
    private val bookId: Long,
    private val personaId: Long,
    private val getBook: suspend () -> BookEntity?,
    private val mediaService: AiMediaGenerationService
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
        if (chapterNumber != null && chapterNumber !in 1..book.lastReadChapterIndex + 1) {
            return "插图锚点超出已读范围：用户只读到第 ${book.lastReadChapterIndex + 1} 章。"
        }
        val chapterIndex = (chapterNumber?.minus(1) ?: book.lastReadChapterIndex)
        val requestedOffset = arguments["char_offset"]?.jsonPrimitive?.intOrNull
        val charOffset = (requestedOffset ?: if (chapterIndex == book.lastReadChapterIndex) {
            book.lastReadCharOffset
        } else {
            0
        }).coerceAtLeast(0)
        if (chapterIndex == book.lastReadChapterIndex && charOffset > book.lastReadCharOffset) {
            return "插图锚点超出当前已读位置（最大字符偏移 ${book.lastReadCharOffset}）。"
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
    private val notes: NoteRepository
) : AgentTool {
    override val displayName: String = "写读书笔记"

    override val spec: ToolSpec = ToolSpec(
        name = "write_note",
        description = "把用户明确要求保存的读书笔记写入本书笔记库。内容使用 Markdown；不要在用户没有要求保存时擅自调用。",
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
        val noteId = notes.create(
            bookId = bookId,
            personaId = personaId,
            title = title.take(120),
            contentMarkdown = content.take(MAX_NOTE_CHARS),
            kind = kind,
            sourceConversationId = conversationId,
            relatedChapterIndex = book.lastReadChapterIndex,
            relatedCharOffset = book.lastReadCharOffset
        )
        return "已保存到《${book.title}》的笔记（编号 $noteId），范围截至第 ${book.lastReadChapterIndex + 1} 章。"
    }
}

private class SavePlotSummaryTool(
    private val bookId: Long,
    private val personaId: Long,
    private val conversationId: Long?,
    private val getBook: suspend () -> BookEntity?,
    private val notes: NoteRepository
) : AgentTool {
    override val displayName: String = "保存剧情梗概"

    override val spec: ToolSpec = ToolSpec(
        name = "save_plot_summary",
        description = "把截至用户当前阅读进度的剧情梗概保存到「剧情梗概」库。用户要求生成并保存、更新或记录梗概时必须调用；严禁写入当前进度之后的剧情。",
        parameters = plotSummaryParameters()
    )

    override suspend fun execute(arguments: JsonObject): String {
        val content = arguments["content_md"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (content.isEmpty()) return "缺少梗概正文 content_md"
        val book = getBook() ?: return "未找到当前书籍"
        val currentChapter = book.lastReadChapterIndex + 1
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
        val noteId = notes.create(
            bookId = bookId,
            personaId = personaId,
            title = title.take(120),
            contentMarkdown = content.take(MAX_NOTE_CHARS),
            kind = NoteRepository.KIND_PLOT_SUMMARY,
            sourceConversationId = conversationId,
            relatedChapterIndex = toChapter - 1,
            relatedCharOffset = if (toChapter == currentChapter) book.lastReadCharOffset else null
        )
        return "第 $fromChapter-$toChapter 章剧情梗概已保存（编号 $noteId），可在书籍详情的「剧情梗概与笔记」中回顾。"
    }
}

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
    }
    putJsonArray("required") { add(JsonPrimitive("content_md")) }
}

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
    private val loadChapter: suspend (Int) -> ChapterDocument? = { null },
    private val loadChaptersThrough: suspend (Int) -> List<ChapterDocument> = { emptyList() },
    private val requestIndex: () -> Unit = {}
) : AgentTool {

    override val displayName: String = "检索书中原文"

    override val spec: ToolSpec = ToolSpec(
        name = "search_book",
        description = "在用户已读范围内搜索人物、场景或情节。优先语义向量检索，向量服务不可用时会自动本地关键词检索；若用户指定明确章节范围并要求概括，应改用 read_book_section。",
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
        val maxChapterIndex = book.lastReadChapterIndex
        var vectorFailure: String? = null

        // 懒索引：这本书还没有任何切片时按需排队单书 embedding，本轮先走词法降级
        val hasIndex = runCatching {
            VectorQueries.chaptersWithChunks(store(), bookId).isNotEmpty()
        }.getOrDefault(false)
        if (!hasIndex) requestIndex()

        val vectorResults = try {
            val vector = embedQuery(query)
            val candidates = VectorQueries.searchChunks(
                store(),
                bookId,
                vector,
                topK * VECTOR_CANDIDATE_MULTIPLIER,
                maxChapterIndex
            ).map { it.get() }
            val currentPrefix = if (candidates.any { it.chapterIndex == maxChapterIndex }) {
                loadChapter(maxChapterIndex)?.body
                    ?.take(book.lastReadCharOffset.coerceAtLeast(0))
            } else {
                null
            }
            candidates.asSequence()
                .filter { chunk ->
                    chunk.chapterIndex < maxChapterIndex ||
                        (chunk.chapterIndex == maxChapterIndex &&
                            currentPrefix != null &&
                            chunk.text?.takeIf(String::isNotBlank)?.let(currentPrefix::contains) == true)
                }
                .map { TextSearchHit(it.chapterIndex, it.text.orEmpty()) }
                .filter { it.text.isNotBlank() }
                .take(topK)
                .toList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            vectorFailure = error.message ?: "embedding 失败"
            emptyList()
        }

        val combined = vectorResults.toMutableList()
        if (combined.size < topK) {
            val documents = loadChaptersThrough(maxChapterIndex).map { document ->
                if (document.chapterIndex == maxChapterIndex) {
                    document.copy(
                        body = document.body.take(book.lastReadCharOffset.coerceAtLeast(0))
                    )
                } else {
                    document
                }
            }
            rankLexicalChapters(documents, query, topK * 2)
                .filterNot { local -> combined.any { it.chapterIndex == local.chapterIndex && it.text == local.text } }
                .take(topK - combined.size)
                .forEach(combined::add)
        }

        if (combined.isEmpty()) {
            if (vectorFailure != null) {
                return "向量检索不可用：$vectorFailure；已自动尝试本地关键词检索，但已读范围内没有找到与「$query」相关的原文。"
            }
            val indexedInRange = runCatching {
                VectorQueries.chaptersWithChunks(store(), bookId).any { it <= maxChapterIndex }
            }.getOrDefault(false)
            return if (!indexedInRange) {
                "本书向量索引正在后台建立；已自动尝试本地关键词检索，但没有找到与「$query」相关的原文。"
            } else {
                "已读范围内没有找到与「$query」相关的原文。"
            }
        }

        return buildString {
            append("以下片段全部来自用户实际已读范围（第 1 至 ${maxChapterIndex + 1} 章）：\n")
            if (vectorFailure != null) {
                append("（向量服务当前不可用，已自动切换到本地关键词检索。）\n")
            } else if (!hasIndex) {
                append("（本书向量索引正在后台建立，本次为本地关键词检索结果。）\n")
            }
            combined.forEach { hit ->
                append("\n【第 ").append(hit.chapterIndex + 1).append(" 章")
                chapterTitle(hit.chapterIndex)
                    ?.takeIf(String::isNotBlank)
                    ?.let { append("「").append(it).append("」") }
                append("】\n").append(hit.text).append('\n')
            }
        }
    }

    private companion object {
        const val DEFAULT_TOP_K = 5
        const val VECTOR_CANDIDATE_MULTIPLIER = 8
    }
}

internal data class TextSearchHit(val chapterIndex: Int, val text: String, val score: Int = 0)

/** 纯本地降级排序，不需要 Key/网络/索引。 */
internal fun rankLexicalChapters(
    chapters: List<ChapterDocument>,
    query: String,
    topK: Int
): List<TextSearchHit> {
    val terms = lexicalTerms(query)
    if (terms.isEmpty()) return emptyList()
    val compactQuery = query.lowercase().filter(Char::isLetterOrDigit)
    return chapters.asSequence()
        .flatMap { chapter ->
            ChapterChunker.chunk(chapter.body).asSequence().map { chunk -> chapter.chapterIndex to chunk }
        }
        .mapNotNull { (chapterIndex, chunk) ->
            val lower = chunk.lowercase()
            val compact = lower.filter(Char::isLetterOrDigit)
            var score = if (compactQuery.length >= 2 && compact.contains(compactQuery)) 500 else 0
            terms.forEach { term ->
                var from = 0
                while (true) {
                    val found = lower.indexOf(term, from)
                    if (found < 0) break
                    score += term.length * term.length
                    from = found + term.length.coerceAtLeast(1)
                }
            }
            if (score > 0) TextSearchHit(chapterIndex, chunk, score) else null
        }
        .sortedWith(compareByDescending<TextSearchHit>(TextSearchHit::score).thenBy(TextSearchHit::chapterIndex))
        .distinctBy { it.chapterIndex to it.text }
        .take(topK.coerceAtLeast(1))
        .toList()
}

private fun lexicalTerms(query: String): List<String> {
    val groups = buildList {
        val current = StringBuilder()
        query.lowercase().forEach { char ->
            if (char.isLetterOrDigit()) {
                current.append(char)
            } else if (current.isNotEmpty()) {
                add(current.toString())
                current.clear()
            }
        }
        if (current.isNotEmpty()) add(current.toString())
    }
    return buildSet {
        groups.forEach { group ->
            if (group.length >= 2) add(group)
            if (group.length > 4) {
                for (size in 2..minOf(4, group.length)) {
                    for (start in 0..group.length - size) add(group.substring(start, start + size))
                }
            }
        }
    }.filterNot { it in LEXICAL_STOP_TERMS }.sortedByDescending(String::length)
}

private val LEXICAL_STOP_TERMS = setOf(
    "什么", "为什么", "怎么", "如何", "请问", "一下", "关于", "内容", "情节", "概括", "总结"
)

/** 角色长期记忆检索；记忆按 personaId 隔离。记忆固化（写入侧）在 M2 后续任务落地。 */
internal class RecallMemoryTool(
    private val personaId: Long,
    private val embedQuery: suspend (String) -> FloatArray,
    private val store: () -> BoxStore
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
        val hits = VectorQueries.searchMemories(store(), personaId, vector, TOP_K)
        if (hits.isEmpty()) return "还没有与此相关的长期记忆。"
        return "相关记忆（按相关度排序）：\n" +
            hits.joinToString("\n") { "- ${it.get().summary}" }
    }

    private companion object {
        const val TOP_K = 5
    }
}
