package com.mozhi.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.update.AppUpdateRepository
import com.mozhi.reader.core.update.AppUpdateState
import com.mozhi.reader.core.update.UpdatePreferencesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppUpdateUiState(
    val update: AppUpdateState = AppUpdateState(),
    val autoCheck: Boolean = true
)

sealed interface AppUpdateEvent {
    data class Install(val file: File) : AppUpdateEvent
    data class Message(val text: String) : AppUpdateEvent
}

@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    private val repository: AppUpdateRepository,
    private val preferences: UpdatePreferencesStore
) : ViewModel() {
    val uiState = combine(repository.state, preferences.autoCheck) { update, auto ->
        AppUpdateUiState(update, auto)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUpdateUiState())

    private val eventChannel = Channel<AppUpdateEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            if (preferences.autoCheck.first()) repository.check(force = false)
        }
    }

    fun setAutoCheck(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoCheck(enabled) }
    }

    fun checkNow() {
        viewModelScope.launch { repository.check(force = true) }
    }

    fun downloadOrInstall() {
        val release = uiState.value.update.available ?: return
        viewModelScope.launch {
            runCatching { repository.download(release) }
                .onSuccess { eventChannel.send(AppUpdateEvent.Install(it)) }
                .onFailure { error ->
                    eventChannel.send(AppUpdateEvent.Message(error.message ?: "下载更新失败"))
                }
        }
    }

    fun clearError() = repository.clearError()
}
