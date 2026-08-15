package com.mozhi.reader.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.speech.SpeechCacheStats
import com.mozhi.reader.core.speech.SpeechCacheStore
import com.mozhi.reader.core.speech.SpeechCacheSync
import com.mozhi.reader.core.speech.SpeechCacheSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SpeechCacheUiState(
    val stats: SpeechCacheStats = SpeechCacheStats(),
    val webDavConfigured: Boolean = false,
    val syncing: Boolean = false
)

sealed interface SpeechCacheEvent {
    data class Message(val text: String) : SpeechCacheEvent
}

@HiltViewModel
class SpeechCacheViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SpeechCacheStore,
    private val sync: SpeechCacheSync
) : ViewModel() {

    private val mutableState = MutableStateFlow(SpeechCacheUiState())
    val state = mutableState.asStateFlow()

    private val eventChannel = Channel<SpeechCacheEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            // 老版本的缓存在 cacheDir 里，进这一页正好顺手接管过来。
            store.migrateLegacyCache()
            refresh()
        }
    }

    private suspend fun refresh() {
        mutableState.update {
            it.copy(
                stats = store.stats(),
                webDavConfigured = sync.credentialsOrNull() != null
            )
        }
    }

    fun setBudgetBytes(value: Long) {
        viewModelScope.launch {
            store.setBudgetBytes(value)
            store.enforceBudget()
            refresh()
        }
    }

    fun setAutoSync(enabled: Boolean) {
        viewModelScope.launch {
            store.setAutoSyncOnWifi(enabled)
            if (enabled) {
                SpeechCacheSyncWorker.schedule(context)
            } else {
                SpeechCacheSyncWorker.cancel(context)
            }
            refresh()
        }
    }

    fun syncNow() {
        if (mutableState.value.syncing) return
        mutableState.update { it.copy(syncing = true) }
        viewModelScope.launch {
            runCatching { sync.sync() }
                .onSuccess { result ->
                    eventChannel.send(SpeechCacheEvent.Message("同步完成：${result.summary}"))
                }
                .onFailure { error ->
                    eventChannel.send(SpeechCacheEvent.Message(error.message ?: "同步失败"))
                }
            mutableState.update { it.copy(syncing = false) }
            refresh()
        }
    }

    fun clear() {
        viewModelScope.launch {
            store.clear()
            refresh()
            eventChannel.send(SpeechCacheEvent.Message("已清空语音缓存"))
        }
    }
}
