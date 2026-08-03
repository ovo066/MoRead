package com.mozhi.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.diag.ApiCallLogEntry
import com.mozhi.reader.core.diag.ApiCallLogStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ApiLogViewModel @Inject constructor(
    private val store: ApiCallLogStore
) : ViewModel() {

    val enabled: StateFlow<Boolean> = store.enabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        store.recordingEnabled
    )

    val entries: StateFlow<List<ApiCallLogEntry>> = store.entries

    fun setEnabled(value: Boolean) {
        viewModelScope.launch { store.setEnabled(value) }
    }

    fun clear() {
        viewModelScope.launch { store.clear() }
    }
}
