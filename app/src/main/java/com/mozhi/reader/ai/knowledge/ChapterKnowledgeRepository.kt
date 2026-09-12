package com.mozhi.reader.ai.knowledge

import androidx.room.withTransaction
import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.client.ResolvedChatClient
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.ChapterKnowledgeEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.library.BookContentMutation
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.retrieval.ReadingScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withContext

data class KnowledgeSnapshot(val book: BookEntity? = null, val chapters: List<VisibleChapterKnowledge> = emptyList(), val staleChapters: Int = 0)

data class KnowledgeSource(
    val bookId: Long, val bookTitle: String, val chapterIndex: Int, val chapterTitle: String,
    val text: String, val chapterLength: Int, val revision: String
) {
    val partial: Boolean get() = text.length < chapterLength
}

class KnowledgeGenerationPlan internal constructor(val source: KnowledgeSource, internal val model: ResolvedChatClient) {
    internal val parts = ChapterKnowledgeCodec.parts(source.text).filter { it.text.isNotBlank() }
    val requestCount: Int get() = parts.size + if (parts.size > 1) 1 else 0
    val maximumRequests: Int get() = requestCount * 2
    val modelLabel: String get() = model.modelName
}

@Singleton
class ChapterKnowledgeRepository @Inject constructor(
    private val library: LibraryRepository,
    private val database: MoReadDatabase,
    private val clients: AiClientFactory,
    private val agent: ChapterKnowledgeAgent
) {
    private val dao get() = database.chapterKnowledgeDao()
    private val generating = ConcurrentHashMap.newKeySet<Pair<Long, Int>>()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(bookId: Long): Flow<KnowledgeSnapshot> = combine(library.observeBook(bookId), dao.observe(bookId)) { book, rows -> book to rows }
        .mapLatest { (_, rows) -> withContext(Dispatchers.IO) {
            BookContentMutation.withBook(bookId) {
                val book = library.getBook(bookId)?.takeIf { it.removedAt == 0L } ?: return@withBook KnowledgeSnapshot()
                val revision = if (rows.isEmpty()) "" else library.bookTextRevision(bookId)
                val scope = ReadingScope.uptoProgress(book)
                val visible = rows.mapNotNull { ChapterKnowledgeCodec.visible(it, scope, revision) }
                KnowledgeSnapshot(book, visible, rows.size - visible.size)
            }
        } }

    /** Local preview only. Selecting a tab or a chapter never makes a paid model request. */
    suspend fun preview(bookId: Long, chapterIndex: Int): KnowledgeGenerationPlan = withContext(Dispatchers.IO) {
        val source = BookContentMutation.withBook(bookId) {
            val book = library.getBook(bookId)?.takeIf { it.removedAt == 0L } ?: error("书籍正文已移除")
            val scope = ReadingScope.uptoProgress(book)
            require(scope.allowsChapter(chapterIndex)) { "这一章还没有读到" }
            val chapter = library.getChapter(bookId, chapterIndex) ?: error("章节不存在")
            val body = scope.readableText(chapterIndex, library.readChapterTextStrict(bookId, chapter))
            require(body.isNotBlank()) { "这一章还没有读到正文" }
            require(body.length <= ChapterKnowledgeCodec.MAX_SOURCE_CHARS) { "本章已读内容超过 60000 字，暂不支持一次整理" }
            KnowledgeSource(bookId, book.title, chapterIndex, chapter.title, body, chapter.charCount, library.bookTextRevision(bookId))
        }
        KnowledgeGenerationPlan(source, clients.forRole(ModelRole.CHEAP))
    }

    suspend fun generate(plan: KnowledgeGenerationPlan, onProgress: (Int, Int) -> Unit = { _, _ -> }): ChapterKnowledgeEntity {
        val key = plan.source.bookId to plan.source.chapterIndex
        check(generating.add(key)) { "这一章正在生成" }
        try {
            val source = plan.source
            val results = mutableListOf<ChapterKnowledge>()
            for ((index, part) in plan.parts.withIndex()) {
                validate(source)
                onProgress(index + 1, plan.requestCount)
                val result = agent.extract(plan, part) { validate(source) }
                validate(source)
                results += result
            }
            val content = ChapterKnowledgeCodec.merge(results).let { merged ->
                if (results.size == 1) merged else {
                    onProgress(plan.requestCount, plan.requestCount)
                    merged.copy(outline = agent.composeChapter(plan, results) { validate(source) })
                }
            }
            val entry = ChapterKnowledgeEntity(source.bookId, source.chapterIndex, source.revision, source.text.length,
                ChapterKnowledgeCodec.hash(source.text),
                ChapterKnowledgeCodec.hash("${plan.model.provider.id}|${plan.model.provider.baseUrl}|${plan.model.modelName}|${plan.model.options}"),
                plan.modelLabel, ChapterKnowledgeCodec.PROMPT_VERSION, ChapterKnowledgeCodec.encode(content), System.currentTimeMillis())
            // One atomic replacement, after every part has succeeded and the source is still valid.
            return withContext(Dispatchers.IO) { BookContentMutation.withBook(source.bookId) {
                validateLocked(source)
                database.withTransaction { dao.save(entry) }
                entry
            } }
        } finally { generating.remove(key) }
    }

    suspend fun locate(entry: ChapterKnowledgeEntity, fact: KnowledgeFact): Pair<Int, Int> = withContext(Dispatchers.IO) {
        BookContentMutation.withBook(entry.bookId) {
            val book = library.getBook(entry.bookId)?.takeIf { it.removedAt == 0L } ?: error("原书已移除，无法核对")
            val chapter = library.getChapter(entry.bookId, entry.chapterIndex) ?: error("原章节不存在")
            require(ChapterKnowledgeCodec.visible(entry, ReadingScope.uptoProgress(book), library.bookTextRevision(entry.bookId)) != null) {
                "阅读范围或正文已变化，请重新整理"
            }
            val text = library.readChapterTextStrict(entry.bookId, chapter)
            require(entry.sourceEnd <= text.length && ChapterKnowledgeCodec.hash(text.take(entry.sourceEnd)) == entry.sourceHash) { "正文已变化，请重新整理" }
            require(fact.start >= 0 && fact.end <= entry.sourceEnd && fact.end > fact.start && text.substring(fact.start, fact.end) == fact.quote) { "无法核对这条原文" }
            entry.chapterIndex to fact.start
        }
    }

    suspend fun delete(bookId: Long, chapterIndex: Int) {
        val key = bookId to chapterIndex
        check(generating.add(key)) { "请先停止这一章的生成" }
        try { dao.delete(bookId, chapterIndex) } finally { generating.remove(key) }
    }

    private suspend fun validate(source: KnowledgeSource) = withContext(Dispatchers.IO) {
        BookContentMutation.withBook(source.bookId) { validateLocked(source) }
    }

    private suspend fun validateLocked(source: KnowledgeSource) {
        val book = library.getBook(source.bookId)?.takeIf { it.removedAt == 0L } ?: error("原书已移除，本次整理已取消")
        require(ReadingScope.uptoProgress(book).allowsChunk(source.chapterIndex, 0, source.text.length)) { "已读范围已重置，本次整理已取消" }
        require(library.bookTextRevision(source.bookId) == source.revision) { "正文已变化，请重新整理" }
    }

}
