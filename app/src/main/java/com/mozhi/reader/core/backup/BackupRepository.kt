package com.mozhi.reader.core.backup

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class BackupProgress(
    val phase: String,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val percent: Int = 0
)

@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val archiveManager: BackupArchiveManager,
    private val settingsStore: BackupSettingsStore,
    private val webDavClient: WebDavClient
) {
    private val operationMutex = Mutex()

    suspend fun testConnection() = operationMutex.withLock {
        webDavClient.test(settingsStore.credentials())
    }

    suspend fun listRemote(): List<RemoteBackup> = operationMutex.withLock {
        webDavClient.list(settingsStore.credentials())
    }

    suspend fun backupToWebDav(
        mode: BackupMode = BackupMode.FULL,
        onProgress: (BackupProgress) -> Unit = {}
    ): RemoteBackup = operationMutex.withLock {
        val settings = settingsStore.current()
        require(settings.configured) { "请先配置 WebDAV" }
        val credentials = settingsStore.credentials()
        onProgress(BackupProgress("正在整理备份", percent = 0))
        val archive = archiveManager.create(mode) { completed, total ->
            onProgress(
                BackupProgress(
                    phase = if (mode == BackupMode.LIGHTWEIGHT) "正在生成轻量备份" else "正在生成完整备份",
                    completedBytes = completed,
                    totalBytes = total,
                    percent = scaledPercent(completed, total, start = 0, span = 35)
                )
            )
        }
        try {
            val uploadProgress: (Long, Long) -> Unit = { completed, total ->
                onProgress(
                    BackupProgress(
                        phase = "正在上传到 WebDAV",
                        completedBytes = completed,
                        totalBytes = total,
                        percent = scaledPercent(completed, total, start = 35, span = 65)
                    )
                )
            }
            webDavClient.upload(credentials, archive, archive.name, uploadProgress)
            settingsStore.markBackup()
            if (mode == BackupMode.LIGHTWEIGHT) {
                runCatching { pruneLightweightBackups(credentials) }
            }
            onProgress(BackupProgress("备份完成", archive.length(), archive.length(), 100))
            RemoteBackup(archive.name, archive.length(), System.currentTimeMillis())
        } finally {
            archive.delete()
        }
    }

    suspend fun stageRemoteRestore(
        remoteName: String,
        onProgress: (BackupProgress) -> Unit = {}
    ): BackupManifest = operationMutex.withLock {
        // 不放 cacheDir：下载期间系统可能回收缓存。noBackupFilesDir 由应用独占且不会进系统备份。
        val temporary = File(context.noBackupFilesDir, "restore/download.moread.zip.part")
        temporary.parentFile?.mkdirs()
        temporary.delete()
        val downloadProgress: (Long, Long) -> Unit = { completed, total ->
            onProgress(
                BackupProgress(
                    phase = "正在下载备份",
                    completedBytes = completed,
                    totalBytes = total,
                    percent = scaledPercent(completed, total, start = 0, span = 80)
                )
            )
        }
        try {
            webDavClient.download(
                settingsStore.credentials(), remoteName, temporary, downloadProgress
            )
            onProgress(BackupProgress("正在校验并准备恢复", percent = 85))
            temporary.inputStream().buffered().use { archiveManager.stageRestore(it) }
                .also { onProgress(BackupProgress("恢复包已准备好", percent = 100)) }
        } finally {
            temporary.delete()
        }
    }

    suspend fun exportLocal(uri: Uri) = operationMutex.withLock {
        val archive = archiveManager.create(BackupMode.FULL)
        withContext(Dispatchers.IO) {
            try {
                val output = context.contentResolver.openOutputStream(uri, "w")
                    ?: error("无法写入所选位置")
                output.buffered().use { sink -> archive.inputStream().buffered().use { it.copyTo(sink) } }
            } finally {
                archive.delete()
            }
        }
    }

    suspend fun stageLocalRestore(uri: Uri): BackupManifest = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(uri) ?: error("无法读取备份文件")
            archiveManager.stageRestore(input.buffered())
        }
    }

    private suspend fun pruneLightweightBackups(credentials: WebDavCredentials) {
        webDavClient.list(credentials)
            .filter { it.name.startsWith("backup-lite-") }
            .sortedByDescending(RemoteBackup::modifiedAt)
            .drop(MAX_LIGHTWEIGHT_BACKUPS)
            .forEach { stale -> webDavClient.delete(credentials, stale.name) }
    }

    private fun scaledPercent(completed: Long, total: Long, start: Int, span: Int): Int {
        if (total <= 0L) return start
        return (start + ((completed.coerceIn(0L, total) * span) / total).toInt())
            .coerceIn(start, start + span)
    }

    private companion object {
        const val MAX_LIGHTWEIGHT_BACKUPS = 7
    }
}
