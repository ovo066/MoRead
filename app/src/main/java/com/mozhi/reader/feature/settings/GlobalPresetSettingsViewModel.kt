package com.mozhi.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.datastore.GlobalPromptPreset
import com.mozhi.reader.core.datastore.GlobalPromptPresetStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface GlobalPresetEvent {
    data class Message(val text: String) : GlobalPresetEvent
}

@HiltViewModel
class GlobalPresetSettingsViewModel @Inject constructor(
    private val store: GlobalPromptPresetStore
) : ViewModel() {
    val presets = store.presets.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        GlobalPromptPresetStore.DEFAULTS
    )
    private val eventChannel = Channel<GlobalPresetEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    fun save(preset: GlobalPromptPreset) {
        viewModelScope.launch {
            runCatching {
                store.upsert(preset.copy(id = preset.id.ifBlank { UUID.randomUUID().toString() }))
            }.onSuccess {
                eventChannel.send(GlobalPresetEvent.Message("全局预设已保存"))
            }.onFailure { error ->
                eventChannel.send(GlobalPresetEvent.Message(error.message ?: "保存失败"))
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { store.setEnabled(id, enabled) }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            store.delete(id)
            eventChannel.send(GlobalPresetEvent.Message("已删除预设"))
        }
    }
}
