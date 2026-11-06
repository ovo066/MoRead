package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.client.ToolSpec
import com.mozhi.reader.ai.embedding.BookEmbeddingScheduler
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.ai.search.WebSearchService
import com.mozhi.reader.core.database.entity.AnnotationColors
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.datastore.BookEmbeddingSettingsStore
import com.mozhi.reader.core.library.AnnotationRepository
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.NoteRepository
import com.mozhi.reader.core.retrieval.BookSourceRegistry
import com.mozhi.reader.core.retrieval.ReadingScopeResolver
import com.mozhi.reader.core.retrieval.ReadingScope
import com.mozhi.reader.core.vector.Embeddings
import io.objectbox.BoxStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    private val webSearchService: WebSearchService,
    private val settingsRepository: ReaderSettingsRepository,
    private val reranker: ConfiguredChunkReranker
) {
    private val sourceRegistry = BookSourceRegistry()

    fun forBook(
        bookId: Long,
        personaId: Long? = null,
        conversationId: Long? = null,
        enabledTools: Collection<String>? = null,
        readingScope: ReadingScope,
        memoryScope: MemoryScope = MemoryScope(),
        buildMissingIndex: Boolean = true
    ): List<AgentTool> {
        val embedQuery: suspend (String) -> FloatArray = { query ->
            val resolved = clientFactory.get().forRole(ModelRole.EMBEDDING)
            Embeddings.conformToIndex(resolved.client.embed(listOf(query)).first())
        }
        val currentScope: suspend () -> ReadingScope = {
            val book = libraryRepository.getBook(bookId) ?: error("书籍已不存在")
            readingScope.intersect(ReadingScopeResolver.resolve(
                settingsRepository.companionSpoilerProtectionEnabled.first(), book
            ))
        }
        val loadChapter: suspend (Int) -> ChapterDocument? = { index ->
            libraryRepository.getChapter(bookId, index)?.let { chapter ->
                ChapterDocument(chapter.chapterIndex, chapter.title,
                    libraryRepository.readChapterTextStrict(bookId, chapter))
            }
        }
        val getRevision: suspend () -> String = { libraryRepository.bookTextRevision(bookId) }
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
                    loadChapter = loadChapter,
                    currentScope = currentScope,
                    embedQuery = embedQuery,
                    store = { vectorStore.get() },
                    requestIndex = { embeddingScheduler.enqueueForBook(bookId) },
                    canRequestIndex = buildMissingIndex,
                    indexingEnabled = { embeddingSettingsStore.isEnabled(bookId) },
                    reranker = reranker,
                    sourceRevision = getRevision,
                    readingScope = readingScope
                )
            )
            add(GrepBookTool(bookId, { libraryRepository.getBook(bookId) }, loadChapter,
                getRevision, readingScope, sourceRegistry, currentScope))
            add(CountMentionsTool({ libraryRepository.getBook(bookId)?.totalChapters ?: 0 }, loadChapter, readingScope, currentScope))
            add(CreateChartTool())
            add(CreateDocumentTool())
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
                        getBook = { libraryRepository.getBook(bookId) },
                        loadChapter = loadChapter,
                        getRevision = getRevision,
                        registry = sourceRegistry,
                        readingScope = readingScope,
                        currentScope = currentScope,
                        save = { match, quote, comment, style, scope ->
                            annotationRepository.add(
                                bookId = bookId, personaId = personaId,
                                chapterIndex = match.chapterIndex,
                                startCharOffset = match.startCharOffset, endCharOffset = match.endCharOffset,
                                selectedText = quote, note = comment,
                                colorTag = AnnotationColors.forPersona(personaId), style = style,
                                sourceScopeChapterIndex = scope.maxChapterIndex,
                                sourceScopeCharOffset = scope.maxCharOffset
                            )
                        }
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

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val book = libraryRepository.getBook(bookId) ?: return ToolResult.Failure("SOURCE_MISSING", "未找到当前书籍")
        val currentIndex = book.lastReadChapterIndex.coerceAtLeast(0)
        val notes = noteRepository.getForBook(bookId)
        val visibleAnnotations = annotationRepository.getVisibleCounts(bookId, currentIndex, readingScope)
        return ToolResult.Success(formatProgressOverview(
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
                annotationCount = visibleAnnotations.total,
                currentChapterAnnotationCount = visibleAnnotations.currentChapter,
                bookmarkCount = libraryRepository.getBookmarks(bookId).size
            ),
            readingScope = readingScope
        ))
    }
}

private val READ_ONLY_BASE_TOOLS = setOf(
    "get_reading_progress",
    "search_book",
    "grep_book",
    "read_book_section",
    "list_chapters",
    "list_annotations",
    "list_notes",
    "recall_memory",
    "count_mentions",
    "create_chart",
    "create_document"
)
