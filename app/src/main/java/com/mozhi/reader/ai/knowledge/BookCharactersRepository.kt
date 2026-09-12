package com.mozhi.reader.ai.knowledge

import androidx.room.withTransaction
import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ResolvedChatClient
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.BookCharacterGuideEntity
import com.mozhi.reader.core.database.entity.BookCharacterPartEntity
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.library.BookContentMutation
import com.mozhi.reader.core.library.LibraryRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

class BookCharactersPlan internal constructor(
    val bookId: Long, val bookTitle: String, internal val revision: String,
    internal val chapters: List<ChapterEntity>, internal val model: ResolvedChatClient,
    internal val resumeGenerationId: String?, val completedParts: Int
) {
    val chapterCount: Int get() = chapters.size
    val sourceCharacters: Long = chapters.sumOf { it.charCount.toLong() }
    val modelLabel: String get() = model.modelName
    internal val modelKey = ChapterKnowledgeCodec.hash("${model.provider.id}|${model.provider.baseUrl}|${model.modelName}|${model.options}")
    // A paragraph-aware part is at least half PART_CHARS, except for the final part.
    val maximumRequests: Long = chapters.sumOf { (it.charCount.toLong() + ChapterKnowledgeCodec.PART_CHARS / 2 - 1) / (ChapterKnowledgeCodec.PART_CHARS / 2) } * 2
    val resuming: Boolean get() = resumeGenerationId != null
}

/** Whole-book extraction has its own explicit consent and never inherits the reader's progress boundary. */
@Singleton
class BookCharactersRepository @Inject constructor(
    private val library: LibraryRepository,
    private val database: MoReadDatabase,
    private val clients: AiClientFactory,
    private val agent: ChapterKnowledgeAgent
) {
    private val dao get() = database.bookCharacterDao()
    private val generating = ConcurrentHashMap.newKeySet<Long>()

    fun observe(bookId: Long): Flow<BookCharactersSnapshot> =
        combine(library.observeBook(bookId), dao.observeGuide(bookId), dao.observeCheckpoint(bookId)) { _, guide, checkpoint -> guide to checkpoint }
            .mapLatest { (guide, checkpoint) -> withContext(Dispatchers.IO) { BookContentMutation.withBook(bookId) {
                val book = library.getBook(bookId)?.takeIf { it.removedAt == 0L } ?: return@withBook BookCharactersSnapshot()
                val revision = if (guide != null || checkpoint != null) library.bookTextRevision(book.id) else ""
                val visible = BookCharactersCodec.visible(guide, revision)
                val resumable = checkpoint?.takeIf { it.generationId != guide?.generationId && it.sourceRevision == revision && it.promptVersion == BookCharactersCodec.PROMPT_VERSION }
                BookCharactersSnapshot(visible, guide != null && visible == null, resumable?.completedParts ?: 0)
            } } }

    /** Metadata only: opening the page and its confirmation never uploads book text. */
    suspend fun preview(bookId: Long): BookCharactersPlan = withContext(Dispatchers.IO) {
        val model = clients.forRole(ModelRole.CHEAP)
        BookContentMutation.withBook(bookId) {
            val book = library.getBook(bookId)?.takeIf { it.removedAt == 0L } ?: error("书籍正文已移除")
            val chapters = library.getChapters(bookId).filter { it.charCount > 0 }.sortedBy { it.chapterIndex }
            require(chapters.isNotEmpty()) { "这本书没有可提取的正文" }
            val revision = library.bookTextRevision(bookId)
            val key = ChapterKnowledgeCodec.hash("${model.provider.id}|${model.provider.baseUrl}|${model.modelName}|${model.options}")
            val published = dao.getGuide(bookId)
            val checkpoint = dao.getCheckpoint(bookId)?.takeIf { it.generationId != published?.generationId &&
                it.sourceRevision == revision && it.modelKey == key && it.promptVersion == BookCharactersCodec.PROMPT_VERSION }
            BookCharactersPlan(bookId, book.title, revision, chapters, model, checkpoint?.generationId, checkpoint?.completedParts ?: 0)
        }
    }

    suspend fun generate(plan: BookCharactersPlan, onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }): BookCharacterGuideEntity {
        check(generating.add(plan.bookId)) { "这本书正在提取人物" }
        try {
            val generationId = withContext(Dispatchers.IO) { BookContentMutation.withBook(plan.bookId) {
                validateLocked(plan)
                if (plan.resumeGenerationId != null) {
                    require(dao.getCheckpoint(plan.bookId)?.generationId == plan.resumeGenerationId) { "提取进度已变化，请重新确认" }
                    plan.resumeGenerationId
                } else {
                    dao.deleteParts(plan.bookId)
                    UUID.randomUUID().toString()
                }
            } }
            val accumulator = BookCharactersCodec.Accumulator()
            for ((chapterNumber, chapter) in plan.chapters.withIndex()) {
                currentCoroutineContext().ensureActive()
                val source = withContext(Dispatchers.IO) { BookContentMutation.withBook(plan.bookId) {
                    validateLocked(plan)
                    library.readChapterTextStrict(plan.bookId, chapter)
                } }
                val cached = withContext(Dispatchers.IO) { dao.getChapterParts(plan.bookId, chapter.chapterIndex).associateBy { it.start } }
                if (source.isNotBlank()) for (part in ChapterKnowledgeCodec.parts(source, enforceChapterLimit = false).filter { it.text.isNotBlank() }) {
                    currentCoroutineContext().ensureActive()
                    onProgress(chapterNumber, plan.chapterCount, chapter.title)
                    val hash = ChapterKnowledgeCodec.hash(part.text)
                    val reused = cached[part.start]?.takeIf { row ->
                        row.generationId == generationId && row.sourceRevision == plan.revision && row.modelKey == plan.modelKey &&
                            row.promptVersion == BookCharactersCodec.PROMPT_VERSION && row.end == part.start + part.text.length && row.sourceHash == hash
                    }?.let { row -> cachedCharacters(row.contentJson, part) }
                    val people = reused ?: agent.extractCharacters(plan.model, plan.bookTitle, chapter.title, part) { validate(plan) }
                    withContext(Dispatchers.IO) { BookContentMutation.withBook(plan.bookId) {
                        validateLocked(plan)
                        if (reused == null) dao.savePart(BookCharacterPartEntity(plan.bookId, chapter.chapterIndex, part.start,
                            part.start + part.text.length, generationId, plan.revision, hash, plan.modelKey,
                            BookCharactersCodec.PROMPT_VERSION, AiJson.encodeToString(people), System.currentTimeMillis()))
                    } }
                    accumulator.add(chapter.chapterIndex, people)
                }
                onProgress(chapterNumber + 1, plan.chapterCount, chapter.title)
            }
            val entry = BookCharacterGuideEntity(plan.bookId, generationId, plan.revision, plan.modelKey, plan.modelLabel,
                BookCharactersCodec.PROMPT_VERSION, AiJson.encodeToString(accumulator.guide(plan.chapterCount, plan.sourceCharacters)), System.currentTimeMillis())
            return withContext(Dispatchers.IO) { BookContentMutation.withBook(plan.bookId) {
                validateLocked(plan)
                database.withTransaction {
                    dao.saveGuide(entry)
                    dao.deleteParts(plan.bookId)
                }
                entry
            } }
        } finally { generating.remove(plan.bookId) }
    }

    suspend fun locate(entry: BookCharacterGuideEntity, evidence: CharacterEvidence): Pair<Int, Int> = withContext(Dispatchers.IO) {
        BookContentMutation.withBook(entry.bookId) {
            library.getBook(entry.bookId)?.takeIf { it.removedAt == 0L } ?: error("原书已移除，无法核对")
            require(BookCharactersCodec.visible(entry, library.bookTextRevision(entry.bookId)) != null) { "正文已变化，请重新提取人物" }
            val chapter = library.getChapter(entry.bookId, evidence.chapterIndex) ?: error("原章节不存在")
            val text = library.readChapterTextStrict(entry.bookId, chapter)
            val fact = evidence.fact
            require(fact.start >= 0 && fact.end <= text.length && fact.end > fact.start && text.substring(fact.start, fact.end) == fact.quote) { "无法核对这条原文" }
            evidence.chapterIndex to fact.start
        }
    }

    suspend fun delete(bookId: Long) {
        check(generating.add(bookId)) { "请先停止人物提取" }
        try { database.withTransaction { dao.deleteGuide(bookId); dao.deleteParts(bookId) } }
        finally { generating.remove(bookId) }
    }

    private fun cachedCharacters(raw: String, part: KnowledgePart): List<KnowledgeCharacter>? = runCatching {
        AiJson.decodeFromString<List<KnowledgeCharacter>>(raw).also { people ->
            require(people.size <= 24)
            people.forEach { person ->
                require(person.name.isNotBlank() && part.text.contains(person.name) && person.facts.size in 1..4)
                person.facts.forEach { fact ->
                    val start = fact.start - part.start
                    val end = fact.end - part.start
                    require(fact.text.isNotBlank() && fact.quote.length in 4..300 && start >= 0 && end <= part.text.length && end > start &&
                        part.text.substring(start, end) == fact.quote && part.text.indexOf(fact.quote) == start && part.text.indexOf(fact.quote, start + 1) == -1)
                }
            }
        }
    }.getOrNull()

    private suspend fun validate(plan: BookCharactersPlan) = withContext(Dispatchers.IO) { BookContentMutation.withBook(plan.bookId) { validateLocked(plan) } }
    private suspend fun validateLocked(plan: BookCharactersPlan) {
        currentCoroutineContext().ensureActive()
        library.getBook(plan.bookId)?.takeIf { it.removedAt == 0L } ?: error("原书已移除，本次提取已停止")
        require(library.bookTextRevision(plan.bookId) == plan.revision) { "正文已变化，请重新提取人物" }
    }
}
