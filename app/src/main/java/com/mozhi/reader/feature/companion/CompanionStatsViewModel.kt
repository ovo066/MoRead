package com.mozhi.reader.feature.companion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.database.dao.ChatDao
import com.mozhi.reader.core.library.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

@HiltViewModel
class CompanionStatsViewModel @Inject constructor(chats: ChatDao, library: LibraryRepository) : ViewModel() {
    private val mutable = MutableStateFlow(CompanionStatsSelection())
    val selection = mutable.asStateFlow()
    private val reading = combine(library.observeAllReadingDays(), library.observeBooksIncludingRemoved()) { days, books ->
        days to books.map { it.id }.toSet()
    }
    val statistics = combine(chats.observeCompletedCompanionRounds(), chats.observeCompanionUsage(), mutable,
        chats.observeCompanionWords(), reading) { rounds, usage, selected, words, records ->
        buildCompanionStatistics(rounds, usage, selected, words = words, readingDays = records.first, retainedBookIds = records.second)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompanionStatistics())

    fun selectScope(value: CompanionStatsScope) { mutable.update { it.copy(scope = value) } }
    fun selectPeriod(value: CompanionStatsPeriod) { mutable.update { it.copy(period = value) } }
}
