package com.mozhi.reader.ai.knowledge

import com.mozhi.reader.core.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A null chapter identifies the whole-book character job, separate from every outline job. */
data class KnowledgeTaskKey(val bookId: Long, val chapterIndex: Int? = null)
data class KnowledgeTaskState(val active: Boolean = false, val progress: String = "", val error: String? = null)

/** Tasks survive closing the sheet. A cancelled chapter never cancels its siblings. */
@Singleton
class KnowledgeGenerationRunner @Inject constructor(
    private val outlines: ChapterKnowledgeRepository,
    private val characters: BookCharactersRepository,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val lock = Any()
    private val jobs = mutableMapOf<KnowledgeTaskKey, Job>()
    private val mutable = MutableStateFlow<Map<KnowledgeTaskKey, KnowledgeTaskState>>(emptyMap())
    val states = mutable.asStateFlow()

    fun generate(plan: KnowledgeGenerationPlan) = start(KnowledgeTaskKey(plan.source.bookId, plan.source.chapterIndex)) { key ->
        outlines.generate(plan) { index, total -> progress(key, "生成中 $index / $total") }
    }

    fun generateCharacters(plan: BookCharactersPlan) = start(KnowledgeTaskKey(plan.bookId)) { key ->
        characters.generate(plan) { completed, total, _ -> progress(key, "已扫描 $completed / $total 章") }
    }

    fun stop(bookId: Long, chapterIndex: Int? = null) {
        synchronized(lock) { jobs[KnowledgeTaskKey(bookId, chapterIndex)]?.cancel() }
    }

    private fun progress(key: KnowledgeTaskKey, label: String) {
        mutable.update { it + (key to KnowledgeTaskState(active = true, progress = label)) }
    }

    private fun start(key: KnowledgeTaskKey, work: suspend (KnowledgeTaskKey) -> Unit): Boolean = synchronized(lock) {
        if (jobs.containsKey(key)) return@synchronized false
        mutable.update { rows ->
            val keep = rows.filterValues { it.active } + rows.filterValues { !it.active }.entries.toList().takeLast(128).associate { it.toPair() }
            keep + (key to KnowledgeTaskState(active = true, progress = "等待模型…"))
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                work(key)
                mutable.update { it + (key to KnowledgeTaskState(progress = "已保存")) }
            } catch (cancelled: CancellationException) {
                mutable.update { it + (key to KnowledgeTaskState(progress = "已停止，可继续生成")) }
                throw cancelled
            } catch (error: Exception) {
                mutable.update { it + (key to KnowledgeTaskState(error = error.message ?: "生成失败，请重试")) }
            }
        }
        jobs[key] = job
        job.invokeOnCompletion { cause -> synchronized(lock) {
            if (jobs[key] === job) {
                if (cause is CancellationException && mutable.value[key]?.active == true) {
                    mutable.update { it + (key to KnowledgeTaskState(progress = "已停止，可继续生成")) }
                }
                jobs.remove(key)
            }
        } }
        job.start()
        true
    }
}
