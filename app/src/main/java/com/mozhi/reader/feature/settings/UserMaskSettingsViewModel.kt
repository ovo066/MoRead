package com.mozhi.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.datastore.UserMask
import com.mozhi.reader.core.datastore.UserMaskSettings
import com.mozhi.reader.core.datastore.UserMaskStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class UserMaskSettingsViewModel @Inject constructor(
    private val store: UserMaskStore
) : ViewModel() {
    val uiState = store.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UserMaskSettings()
    )

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { store.setEnabled(enabled) }
    }

    fun select(id: Long) {
        viewModelScope.launch { store.select(id) }
    }

    fun save(mask: UserMask) {
        viewModelScope.launch { store.save(mask) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { store.delete(id) }
    }
}
