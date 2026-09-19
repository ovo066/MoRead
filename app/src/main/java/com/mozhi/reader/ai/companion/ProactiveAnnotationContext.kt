package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.agent.ChapterDocument
import com.mozhi.reader.ai.agent.loadReadableCorpus
import com.mozhi.reader.ai.knowledge.ChapterKnowledgeCodec
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.ChapterKnowledgeEntity
import com.mozhi.reader.core.datastore.ProactiveAnnotationContextSettings
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.retrieval.Bm25LexicalRecall
import com.mozhi.reader.core.retrieval.ReadingScope
import com.mozhi.reader.core.retrieval.RetrievalCandidate
import com.mozhi.reader.core.retrieval.RetrievalSelection
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Only prior chapters are cached, for the lifetime of one chapter job. No paid embedding calls. */
@Singleton
class ProactiveAnnotationContextRepository @Inject constructor(
    private val library: LibraryRepository,
    private val database: MoReadDatabase
) {
    internal suspend fun prepare(bookId: Long, chapterIndex: Int): PreparedAnnotationContext = withContext(Dispatchers.IO) {
        val book = library.getBook(bookId)?.takeIf { it.removedAt == 0L } ?: error("书籍正文已移除")
        val revision = library.bookTextRevision(bookId)
        if (chapterIndex <= 0) return@withContext PreparedAnnotationContext(revision)
        val scope = ReadingScope.upto(chapterIndex - 1, Int.MAX_VALUE)
        val titles = mutableMapOf<Int, String>()
        // The target chapter is already loaded; old imports may have a stale chapter count.
        val corpus = loadReadableCorpus(bookId, maxOf(book.totalChapters, chapterIndex), scope,
            loadChapter = { index -> library.getChapter(bookId, index)?.let { chapter ->
                titles[index] = chapter.title
                ChapterDocument(index, chapter.title, library.readChapterTextStrict(bookId, chapter))
            } }, loadChaptersThrough = { emptyList() })
        val outlines = database.chapterKnowledgeDao().getBefore(bookId, chapterIndex).mapNotNull { entry ->
            currentCoroutineContext().ensureActive()
            validatedAnnotationOutline(entry, scope, revision, corpus.bodies[entry.chapterIndex])
        }
        check(isCurrent(bookId, revision)) { "正文已变化，请重新准备段评上下文" }
        PreparedAnnotationContext(revision, corpus.candidates, outlines, titles, !corpus.complete)
    }

    internal suspend fun isCurrent(bookId: Long, revision: String): Boolean = try {
        library.getBook(bookId)?.removedAt == 0L && library.bookTextRevision(bookId) == revision
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { false }
}

internal data class AnnotationOutline(val chapterIndex: Int, val text: String)

/** Never reuse a whole-chapter outline whose source reaches beyond the allowed boundary. */
internal fun validatedAnnotationOutline(
    entry: ChapterKnowledgeEntity, scope: ReadingScope, revision: String, body: String?
): AnnotationOutline? {
    val visible = ChapterKnowledgeCodec.visible(entry, scope, revision) ?: return null
    if (body == null || entry.sourceEnd > body.length ||
        ChapterKnowledgeCodec.hash(body.take(entry.sourceEnd)) != entry.sourceHash) return null
    return AnnotationOutline(entry.chapterIndex, visible.content.readableOutline)
}

internal data class AnnotationReadingContext(val prefix: String, val target: String, val background: String) {
    val chars: Int get() = prefix.length + target.length + background.length
}

internal class PreparedAnnotationContext(
    val revision: String,
    private val candidates: List<RetrievalCandidate> = emptyList(),
    private val outlines: List<AnnotationOutline> = emptyList(),
    private val titles: Map<Int, String> = emptyMap(),
    private val partial: Boolean = false
) {
    suspend fun forParagraph(body: String, paragraph: ProactiveAnnotationParagraph, budgetChars: Int): AnnotationReadingContext =
        withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            val budget = budgetChars.coerceIn(ProactiveAnnotationContextSettings.MIN_CHARS, ProactiveAnnotationContextSettings.MAX_CHARS)
            val target = body.substring(paragraph.start, paragraph.end)
            // Target is repeated for reliable quote grounding. Its two copies both consume budget.
            val backgroundBudget = ((budget - target.length * 2).coerceAtLeast(0) * 0.45).toInt()
            val query = ProactiveAnnotationParagraphs.prefix(body, paragraph, target.length + 400)
            val ranked = Bm25LexicalRecall.rank(candidates, query, 48) { context.ensureActive() }
            val best = ranked.firstOrNull()?.lexicalScore ?: 0.0
            val hits = RetrievalSelection.select(
                ranked.filter { (it.lexicalScore ?: 0.0) >= best * 0.35 },
                topK = 12, maxPerChapter = 2, literalKeys = emptySet(), literalReserve = 0
            ).selected
            val outlineCandidates = outlines.map { outline ->
                RetrievalCandidate(0, outline.chapterIndex, 0, outline.text)
            }
            val relatedOutlines = Bm25LexicalRecall.rank(outlineCandidates, query, 4) { context.ensureActive() }
            // Include immediate continuity when available, without replacing relevant distant chapters.
            val selectedOutlines = (relatedOutlines + outlineCandidates.takeLast(1)).distinctBy { it.chapterIndex }
            fun chapterLabel(index: Int): String = "第 "+ (index + 1) + " 章" +
                titles[index]?.takeIf { it.isNotBlank() }?.let { "「" + it.take(80) + "」" }.orEmpty()
            val summaries = packAnnotationBackground(selectedOutlines.map {
                chapterLabel(it.chapterIndex) + "梗概（辅助资料，以原文为准）：\n" + it.text
            }, backgroundBudget / 3)
            val snippets = packAnnotationBackground(hits.map {
                chapterLabel(it.chapterIndex) + "原文：\n" + it.text
            }, (backgroundBudget - summaries.length - 2).coerceAtLeast(0))
            val warning = if (partial) "前文检索只覆盖部分正文；未命中不代表书中没有。" else ""
            val background = packAnnotationBackground(listOf(warning, summaries, snippets), backgroundBudget)
            val prefix = ProactiveAnnotationParagraphs.prefix(body, paragraph, budget - target.length - background.length)
            AnnotationReadingContext(prefix, target, background)
        }
}

/** Clip on a UTF-16 boundary; labels and separators count against the same reading budget. */
private fun packAnnotationBackground(blocks: List<String>, budget: Int): String = buildString {
    for (block in blocks.filter { it.isNotBlank() }) {
        val separator = if (isEmpty()) "" else "\n\n"
        val room = budget - length - separator.length
        if (room <= 0) break
        var end = minOf(room, block.length)
        if (end < block.length && end > 0 && block[end - 1].isHighSurrogate() && block[end].isLowSurrogate()) end--
        if (end <= 0) break
        append(separator).append(block, 0, end)
    }
}
