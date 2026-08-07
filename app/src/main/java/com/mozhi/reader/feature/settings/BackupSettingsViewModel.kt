package com.mozhi.reader.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.backup.BackupManifest
import com.mozhi.reader.core.backup.BackupRepository
import com.mozhi.reader.core.backup.BackupSettings
import com.mozhi.reader.core.backup.BackupSettingsStore
import com.mozhi.reader.core.backup.RemoteBackup
import com.mozhi.reader.core.backup.WebDavBackupScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BackupSettingsUiState(
    val settings: BackupSettings = BackupSettings(),
    val hasPassword: Boolean = false,
    val remoteBackups: List<RemoteBackup> = emptyList(),
    val working: Boolean = false
)

sealed interface BackupSettingsEvent {
    data class Message(val text: String) : BackupSettingsEvent
    data class RestoreReady(val manifest: BackupManifest) : BackupSettingsEvent
}

@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: BackupSettingsStore,
    private val repository: BackupRepository
) : ViewModel() {
    private val backups = MutableStateFlow<List<RemoteBackup>>(emptyList())
    private val working = MutableStateFlow(false)
    private val hasPassword = MutableStateFlow(settingsStore.hasPassword())
    private val eventChannel = Channel<BackupSettingsEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    val uiState = combine(settingsStore.settings, hasPassword, backups, working) {
            settings, hasKey, remote, busy ->
        BackupSettingsUiState(settings, hasKey, remote, busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupSettingsUiState())

    fun save(settings: BackupSettings, password: String) {
        viewModelScope.launch {
            settingsStore.save(settings, password.takeIf(String::isNotBlank))
            hasPassword.value = settingsStore.hasPassword()
            WebDavBackupScheduler.update(context, settings.autoBackup)
            eventChannel.send(BackupSettingsEvent.Message("WebDAV 配置已保存"))
        }
    }

    fun clearPassword() {
        settingsStore.clearPassword()
        hasPassword.value = false
    }

    fun test(settings: BackupSettings, password: String) = runOperation("连接成功") {
        settingsStore.save(settings, password.takeIf(String::isNotBlank))
        hasPassword.value = settingsStore.hasPassword()
        repository.testConnection()
    }

    fun backupNow(settings: BackupSettings, password: String) = runOperation("备份已上传到 WebDAV") {
        settingsStore.save(settings, password.takeIf(String::isNotBlank))
        hasPassword.value = settingsStore.hasPassword()
        repository.backupToWebDav()
        backups.value = repository.listRemote()
    }

    fun refreshRemote() = runOperation(null) {
        backups.value = repository.listRemote()
    }

    fun restoreRemote(remote: RemoteBackup) = runOperation(null) {
        val manifest = repository.stageRemoteRestore(remote.name)
        eventChannel.send(BackupSettingsEvent.RestoreReady(manifest))
    }

    fun exportLocal(uri: Uri) = runOperation("本地备份已导出") {
        repository.exportLocal(uri)
    }

    fun restoreLocal(uri: Uri) = runOperation(null) {
        val manifest = repository.stageLocalRestore(uri)
        eventChannel.send(BackupSettingsEvent.RestoreReady(manifest))
    }

    fun setAutoBackup(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setAutoBackup(enabled)
            WebDavBackupScheduler.update(context, enabled)
        }
    }

    private fun runOperation(successMessage: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (working.value) return@launch
            working.value = true
            runCatching { block() }
                .onSuccess {
                    successMessage?.let { eventChannel.send(BackupSettingsEvent.Message(it)) }
                }
                .onFailure { error ->
                    eventChannel.send(BackupSettingsEvent.Message(error.message ?: "操作失败"))
                }
            working.value = false
        }
    }
}
