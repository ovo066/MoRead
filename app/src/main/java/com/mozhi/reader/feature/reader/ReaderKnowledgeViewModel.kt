package com.mozhi.reader.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.knowledge.*
import com.mozhi.reader.core.database.entity.BookCharacterGuideEntity
import com.mozhi.reader.core.database.entity.ChapterKnowledgeEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KnowledgeUiState(
    val bookId: Long = 0, val snapshot: KnowledgeSnapshot = KnowledgeSnapshot(), val loading: Boolean = true,
    val characters: BookCharactersSnapshot = BookCharactersSnapshot(),
    val tasks: Map<Int, KnowledgeTaskState> = emptyMap(), val characterTask: KnowledgeTaskState? = null,
    val pending: KnowledgeGenerationPlan? = null, val pendingCharacters: BookCharactersPlan? = null,
    val previewingChapter: Int? = null, val preparingCharacters: Boolean = false, val error: String? = null
)

@HiltViewModel
class ReaderKnowledgeViewModel @Inject constructor(
    private val repository: ChapterKnowledgeRepository,
    private val characters: BookCharactersRepository,
    private val runner: KnowledgeGenerationRunner
) : ViewModel() {
    private val mutable = MutableStateFlow(KnowledgeUiState())
    val state = mutable.asStateFlow()
    private var observation: Job? = null
    private var previewJob: Job? = null
    private val locations = Channel<Pair<Int, Int>>(Channel.BUFFERED)
    val locateEvents = locations.receiveAsFlow()

    fun bind(bookId: Long) {
        if (mutable.value.bookId != bookId) {
            previewJob?.cancel()
            mutable.value = KnowledgeUiState(bookId)
        }
        // Refresh in place. Clearing a visible snapshot collapses lazy items and loses scroll anchors.
        observation?.cancel()
        observation = viewModelScope.launch {
            try {
                combine(repository.observe(bookId), characters.observe(bookId), runner.states) { outline, people, tasks ->
                    Triple(outline, people, tasks.filterKeys { it.bookId == bookId })
                }.collect { (outline, people, tasks) -> update(bookId) {
                    it.copy(snapshot = outline, characters = people, loading = false,
                        tasks = tasks.filterKeys { it.chapterIndex != null }.mapKeys { it.key.chapterIndex!! },
                        characterTask = tasks[KnowledgeTaskKey(bookId)])
                } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { update(bookId) { it.copy(loading = false, error = error.message ?: "无法读取书籍资料") } }
        }
    }

    fun preview(chapterIndex: Int) {
        if (mutable.value.tasks[chapterIndex]?.active == true) return
        prepare(chapterIndex) { book ->
            val plan = repository.preview(book, chapterIndex)
            update(book) { it.copy(pending = plan) }
        }
    }

    fun previewCharacters() {
        if (mutable.value.characterTask?.active == true) return
        prepare(null) { book ->
            val plan = characters.preview(book)
            update(book) { it.copy(pendingCharacters = plan) }
        }
    }

    private fun prepare(chapterIndex: Int?, block: suspend (Long) -> Unit) {
        if (previewJob?.isActive == true) return
        val book = mutable.value.bookId
        update(book) { it.copy(previewingChapter = chapterIndex, preparingCharacters = chapterIndex == null,
            pending = null, pendingCharacters = null, error = null) }
        previewJob = viewModelScope.launch {
            try { block(book) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { update(book) { it.copy(error = error.message ?: "无法准备生成") } }
            finally { update(book) { it.copy(previewingChapter = null, preparingCharacters = false) } }
        }
    }

    fun dismissPreview() { mutable.update { it.copy(pending = null, pendingCharacters = null) } }
    fun generate() { mutable.value.pending?.let(runner::generate); dismissPreview() }
    fun generateCharacters() { mutable.value.pendingCharacters?.let(runner::generateCharacters); dismissPreview() }
    fun cancel(chapterIndex: Int) { runner.stop(mutable.value.bookId, chapterIndex) }
    fun cancelCharacters() { runner.stop(mutable.value.bookId) }

    fun delete(chapterIndex: Int) = localAction { book -> repository.delete(book, chapterIndex) }
    fun deleteCharacters() = localAction { book -> characters.delete(book) }
    fun locate(entry: ChapterKnowledgeEntity, fact: KnowledgeFact) = localAction { locations.send(repository.locate(entry, fact)) }
    fun locateCharacter(entry: BookCharacterGuideEntity, evidence: CharacterEvidence) = localAction { locations.send(characters.locate(entry, evidence)) }

    private fun localAction(block: suspend (Long) -> Unit) {
        val book = mutable.value.bookId
        viewModelScope.launch {
            try { block(book) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { update(book) { it.copy(error = error.message ?: "操作失败，请重试") } }
        }
    }

    private fun update(bookId: Long, transform: (KnowledgeUiState) -> KnowledgeUiState) {
        mutable.update { if (it.bookId == bookId) transform(it) else it }
    }
}
