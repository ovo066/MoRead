package com.mozhi.reader.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mozhi.reader.core.backup.BackupManifest
import com.mozhi.reader.core.backup.BackupMode
import com.mozhi.reader.core.backup.BackupRepository
import com.mozhi.reader.core.backup.BackupSettings
import com.mozhi.reader.core.backup.BackupSettingsStore
import com.mozhi.reader.core.backup.RemoteBackup
import com.mozhi.reader.core.backup.WebDavBackupScheduler
import com.mozhi.reader.core.backup.WebDavBackupWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BackupWorkUiState(
    val running: Boolean = false,
    val progress: Int = 0,
    val phase: String = "",
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L
)

data class BackupSettingsUiState(
    val settings: BackupSettings = BackupSettings(),
    val hasPassword: Boolean = false,
    val remoteBackups: List<RemoteBackup> = emptyList(),
    val working: Boolean = false,
    val backupWork: BackupWorkUiState = BackupWorkUiState()
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
    private val backupWork = MutableStateFlow(BackupWorkUiState())
    private val hasPassword = MutableStateFlow(settingsStore.hasPassword())
    private val eventChannel = Channel<BackupSettingsEvent>(Channel.BUFFERED)
    private val workManager = WorkManager.getInstance(context)
    private var handledWorkId: UUID? = null
    private val observedActiveWorkIds = mutableSetOf<UUID>()
    val events = eventChannel.receiveAsFlow()

    val uiState = combine(
        settingsStore.settings,
        hasPassword,
        backups,
        working,
        backupWork
    ) { settings, hasKey, remote, busy, work ->
        BackupSettingsUiState(settings, hasKey, remote, busy, work)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupSettingsUiState())

    init {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(WebDavBackupWorker.UNIQUE_MANUAL_WORK)
                .collect { infos -> observeBackupWork(infos.firstOrNull()) }
        }
    }

    fun save(settings: BackupSettings, password: String) {
        viewModelScope.launch {
            settingsStore.save(settings, password.takeIf(String::isNotBlank))
            hasPassword.value = settingsStore.hasPassword()
            WebDavBackupScheduler.update(context, settings.autoBackup && settings.configured)
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

    fun backupNow(settings: BackupSettings, password: String, mode: BackupMode) {
        viewModelScope.launch {
            settingsStore.save(settings, password.takeIf(String::isNotBlank))
            hasPassword.value = settingsStore.hasPassword()
            val saved = settingsStore.current()
            if (!saved.configured) {
                eventChannel.send(BackupSettingsEvent.Message("请先配置 WebDAV"))
                return@launch
            }
            backupWork.value = BackupWorkUiState(running = true, phase = "正在加入后台任务")
            WebDavBackupScheduler.enqueueNow(context, mode)
        }
    }

    fun refreshRemote() = runOperation(null) {
        backups.value = repository.listRemote()
    }

    fun restoreRemote(remote: RemoteBackup) = runOperation(null) {
        val manifest = repository.stageRemoteRestore(remote.name)
        eventChannel.send(BackupSettingsEvent.RestoreReady(manifest))
    }

    fun exportLocal(uri: Uri) = runOperation("本地完整备份已导出") {
        repository.exportLocal(uri)
    }

    fun restoreLocal(uri: Uri) = runOperation(null) {
        val manifest = repository.stageLocalRestore(uri)
        eventChannel.send(BackupSettingsEvent.RestoreReady(manifest))
    }

    fun setAutoBackup(enabled: Boolean) {
        viewModelScope.launch {
            val current = settingsStore.current()
            if (enabled && !current.configured) {
                eventChannel.send(BackupSettingsEvent.Message("请先配置 WebDAV"))
                return@launch
            }
            settingsStore.setAutoBackup(enabled)
            WebDavBackupScheduler.update(context, enabled)
        }
    }

    private suspend fun observeBackupWork(info: WorkInfo?) {
        if (info == null) {
            backupWork.value = BackupWorkUiState()
            return
        }
        val progress = info.progress
        backupWork.value = BackupWorkUiState(
            running = info.state == WorkInfo.State.ENQUEUED ||
                info.state == WorkInfo.State.BLOCKED ||
                info.state == WorkInfo.State.RUNNING,
            progress = progress.getInt(WebDavBackupWorker.KEY_PROGRESS, 0),
            phase = progress.getString(WebDavBackupWorker.KEY_PHASE).orEmpty().ifBlank {
                if (info.state == WorkInfo.State.ENQUEUED) "等待网络与系统调度" else ""
            },
            completedBytes = progress.getLong(WebDavBackupWorker.KEY_COMPLETED_BYTES, 0L),
            totalBytes = progress.getLong(WebDavBackupWorker.KEY_TOTAL_BYTES, 0L)
        )
        if (!info.state.isFinished) observedActiveWorkIds += info.id
        if (info.state.isFinished && handledWorkId != info.id) {
            handledWorkId = info.id
            if (info.id !in observedActiveWorkIds) return
            observedActiveWorkIds -= info.id
            when (info.state) {
                WorkInfo.State.SUCCEEDED -> {
                    eventChannel.send(BackupSettingsEvent.Message("WebDAV 备份已完成"))
                    runCatching { backups.value = repository.listRemote() }
                }
                WorkInfo.State.FAILED -> eventChannel.send(
                    BackupSettingsEvent.Message(
                        info.outputData.getString(WebDavBackupWorker.KEY_ERROR) ?: "WebDAV 备份失败"
                    )
                )
                WorkInfo.State.CANCELLED -> eventChannel.send(BackupSettingsEvent.Message("WebDAV 备份已取消"))
                else -> Unit
            }
        }
    }

    private fun runOperation(successMessage: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (working.value || backupWork.value.running) return@launch
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
