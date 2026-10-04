package com.mozhi.reader.feature.companion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.memory.PersonaMemoryRepository
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.database.dao.ChatDao
import com.mozhi.reader.core.library.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CompanionStatsViewModel @Inject constructor(
    chats: ChatDao, library: LibraryRepository, personas: PersonaRepository, memories: PersonaMemoryRepository
) : ViewModel() {
    private val mutable = MutableStateFlow(CompanionStatsSelection())
    val selection = mutable.asStateFlow()
    private val rounds = chats.observeCompletedCompanionRounds()
    private val books = library.observeBooksIncludingRemoved()
    private val reading = combine(library.observeAllReadingDays(), books) { days, all -> days to all.map { it.id }.toSet() }
    // ObjectBox has no observable query here; reload memories whenever a new exchange completes (consolidation follows chats).
    private val memoryNotes = rounds.map { it.size }.distinctUntilChanged().mapLatest {
        memories.recent(MEMORY_LIMIT).map { CompanionMemoryNote(it.id, it.personaId, it.conversationId, it.bookId, it.summary, it.createdAt) }
    }.onStart { emit(emptyList()) }
    private val storySources = combine(memoryNotes, books, personas.observePersonas()) { notes, all, roles ->
        Triple(notes, all.associate { it.id to it.title }, roles.associate { it.id to CompanionPersonaInfo(it.name, it.avatarPath) })
    }
    private val base = combine(rounds, chats.observeCompanionUsage(), mutable, chats.observeCompanionWords(), reading) { completed, usage, selected, words, records ->
        Triple(completed, selected, records) to buildCompanionStatistics(completed, usage, selected, words = words,
            readingDays = records.first, retainedBookIds = records.second)
    }
    val statistics = combine(base, storySources) { (inputs, stats), (notes, titles, roles) ->
        val (completed, selected, records) = inputs
        stats.copy(story = buildCompanionStory(completed, notes, selected, titles, roles, records.first))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompanionStatistics())

    fun selectScope(value: CompanionStatsScope) { mutable.update { it.copy(scope = value) } }
    fun selectPeriod(value: CompanionStatsPeriod) { mutable.update { it.copy(period = value) } }

    private companion object { const val MEMORY_LIMIT = 300 }
}
