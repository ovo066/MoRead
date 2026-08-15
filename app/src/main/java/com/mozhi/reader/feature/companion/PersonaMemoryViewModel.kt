package com.mozhi.reader.feature.companion

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.memory.PersonaMemoryRepository
import com.mozhi.reader.ai.memory.StoredMemory
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.library.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PersonaMemoryState(
    val personaName: String = "",
    val loading: Boolean = true,
    val total: Long = 0,
    val memories: List<StoredMemory> = emptyList(),
    /** bookId → 书名，用来把「哪本书聊出来的」显示成人话。 */
    val bookTitles: Map<Long, String> = emptyMap(),
    val query: String = "",
    val profile: String = "",
    val loadingMore: Boolean = false
) {
    val filtered: List<StoredMemory>
        get() = query.trim().takeIf(String::isNotEmpty)?.let { keyword ->
            memories.filter { it.summary.contains(keyword, ignoreCase = true) }
        } ?: memories

    val canLoadMore: Boolean get() = memories.size < total
}

sealed interface PersonaMemoryEvent {
    data class Message(val text: String) : PersonaMemoryEvent
}

@HiltViewModel
class PersonaMemoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val memories: PersonaMemoryRepository,
    private val personaRepository: PersonaRepository,
    private val libraryRepository: LibraryRepository
) : ViewModel() {

    private val personaId: Long = savedStateHandle.get<String>("personaId")?.toLongOrNull() ?: 0L

    private val mutableState = MutableStateFlow(PersonaMemoryState())
    val state = mutableState.asStateFlow()

    private val eventChannel = Channel<PersonaMemoryEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    init {
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            val persona = personaId.takeIf { it != 0L }?.let { personaRepository.getPersona(it) }
            val page = memories.page(personaId, 0, PAGE_SIZE)
            mutableState.value = PersonaMemoryState(
                personaName = persona?.name.orEmpty(),
                loading = false,
                total = memories.count(personaId),
                memories = page,
                bookTitles = titlesFor(page),
                profile = memories.profile(personaId)
            )
        }
    }

    fun loadMore() {
        val current = mutableState.value
        if (current.loadingMore || !current.canLoadMore) return
        mutableState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val next = memories.page(personaId, current.memories.size, PAGE_SIZE)
            mutableState.update { state ->
                val merged = state.memories + next
                state.copy(
                    memories = merged,
                    bookTitles = state.bookTitles + titlesFor(next),
                    loadingMore = false
                )
            }
        }
    }

    fun setQuery(value: String) = mutableState.update { it.copy(query = value) }

    fun delete(memory: StoredMemory) {
        viewModelScope.launch {
            memories.delete(memory.id)
            mutableState.update { state ->
                state.copy(
                    memories = state.memories.filterNot { it.id == memory.id },
                    total = (state.total - 1).coerceAtLeast(0)
                )
            }
            eventChannel.send(PersonaMemoryEvent.Message("已删除这条记忆"))
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            memories.clear(personaId)
            mutableState.update {
                it.copy(memories = emptyList(), total = 0, profile = "")
            }
            eventChannel.send(PersonaMemoryEvent.Message("已清空该角色的记忆与画像"))
        }
    }

    fun setProfile(value: String) = mutableState.update { it.copy(profile = value) }

    fun saveProfile() {
        viewModelScope.launch {
            memories.saveProfile(personaId, mutableState.value.profile)
            eventChannel.send(PersonaMemoryEvent.Message("画像已保存"))
        }
    }

    private suspend fun titlesFor(page: List<StoredMemory>): Map<Long, String> {
        val ids = page.mapNotNull(StoredMemory::bookId).distinct()
        if (ids.isEmpty()) return emptyMap()
        return ids.mapNotNull { id ->
            libraryRepository.getBook(id)?.let { id to it.title }
        }.toMap()
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
