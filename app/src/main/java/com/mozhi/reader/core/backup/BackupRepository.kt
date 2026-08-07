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

    suspend fun backupToWebDav(): RemoteBackup = operationMutex.withLock {
        val credentials = settingsStore.credentials()
        val archive = archiveManager.create()
        webDavClient.upload(credentials, archive, archive.name)
        settingsStore.markBackup()
        RemoteBackup(archive.name, archive.length(), System.currentTimeMillis())
    }

    suspend fun stageRemoteRestore(remoteName: String): BackupManifest = operationMutex.withLock {
        val temporary = File(context.cacheDir, "backups/restore-download.moread.zip")
        webDavClient.download(settingsStore.credentials(), remoteName, temporary)
        temporary.inputStream().buffered().use { archiveManager.stageRestore(it) }
            .also { temporary.delete() }
    }

    suspend fun exportLocal(uri: Uri): File = operationMutex.withLock {
        val archive = archiveManager.create()
        withContext(Dispatchers.IO) {
            val output = context.contentResolver.openOutputStream(uri, "w")
                ?: error("无法写入所选位置")
            output.buffered().use { sink -> archive.inputStream().buffered().use { it.copyTo(sink) } }
        }
        archive
    }

    suspend fun stageLocalRestore(uri: Uri): BackupManifest = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(uri) ?: error("无法读取备份文件")
            archiveManager.stageRestore(input.buffered())
        }
    }

}
