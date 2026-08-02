package com.mozhi.reader.ai.embedding

import com.mozhi.reader.ai.client.AiClientException
import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.library.LibraryRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 整书 embedding 管线：解析 EMBEDDING 角色分配的模型，读章节正文，交给 [ChapterEmbedder]。
 * 配置类问题（未分配 / 无 Key / 方言不支持 / Key 无效）一律归为 Skipped——
 * 重试解决不了，等用户改配置后由下一次触发（导入完成或下次启动）续跑。
 */
@Singleton
class BookEmbeddingPipeline @Inject constructor(
    private val clientFactory: AiClientFactory,
    private val libraryRepository: LibraryRepository,
    private val chapterEmbedder: ChapterEmbedder,
    private val progressTracker: EmbeddingProgressTracker
) {
    suspend fun embedBook(bookId: Long): EmbedOutcome {
        val book = libraryRepository.getBook(bookId) ?: return EmbedOutcome.Completed
        val chapters = libraryRepository.getChapters(bookId)
        val totalChapters = chapters.count { it.charCount > 0 }
        val resolved = try {
            clientFactory.forRole(ModelRole.EMBEDDING)
        } catch (error: AiClientException.NotConfigured) {
            progressTracker.markBlocked(bookId, error.message.orEmpty())
            return EmbedOutcome.Skipped(error.message.orEmpty())
        } catch (error: AiClientException.MissingKey) {
            progressTracker.markBlocked(bookId, error.message.orEmpty())
            return EmbedOutcome.Skipped(error.message.orEmpty())
        }
        if (book.textVersion < LibraryRepository.CURRENT_TEXT_VERSION) {
            val reason = "《${book.title}》正文尚未落盘，等待补齐任务"
            progressTracker.markBlocked(bookId, reason)
            return EmbedOutcome.Skipped(reason)
        }
        val outcome = chapterEmbedder.embedChapters(
            bookId = bookId,
            chapters = chapters,
            readText = { chapter -> libraryRepository.readChapterText(bookId, chapter) },
            embed = { texts -> resolved.client.embed(texts) },
            onProgress = { indexed, total ->
                progressTracker.markIndexing(
                    bookId = bookId,
                    bookTitle = book.title,
                    indexedChapters = indexed,
                    totalChapters = total
                )
            }
        )
        return when {
            outcome is EmbedOutcome.Failed && outcome.error.isConfigProblem() -> {
                progressTracker.markBlocked(
                    bookId,
                    outcome.error.message ?: "Embedding 模型配置不可用"
                )
                EmbedOutcome.Skipped(outcome.error.message.orEmpty())
            }
            outcome is EmbedOutcome.Failed -> {
                progressTracker.markFailed(
                    bookId,
                    outcome.error.message ?: "向量生成失败，请检查网络后重试"
                )
                outcome
            }
            else -> {
                progressTracker.markReady(bookId, totalChapters)
                outcome
            }
        }
    }

    private fun Throwable.isConfigProblem(): Boolean =
        this is AiClientException.Unsupported ||
            this is AiClientException.InvalidKey ||
            this is IllegalArgumentException // 维度不足等模型不可用（Embeddings.conformToIndex）
}
