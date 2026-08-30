package com.mozhi.reader.core.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mozhi.reader.core.security.ApiKeyStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class BackupSettings(
    val webDavUrl: String = "",
    val username: String = "",
    val remoteDirectory: String = "MoRead",
    val autoBackup: Boolean = false,
    val lastBackupAt: Long = 0L
) {
    val configured: Boolean get() = webDavUrl.isNotBlank() && username.isNotBlank()
}

data class WebDavCredentials(
    val baseUrl: String,
    val username: String,
    val password: String,
    val remoteDirectory: String
)

@Singleton
class BackupSettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val apiKeyStore: ApiKeyStore
) {
    val settings: Flow<BackupSettings> = dataStore.data.map { preferences ->
        // 测试版曾短暂提供 Google Drive。若用户当时选中过它，先关闭自动备份，
        // 避免移除功能后意外改用旧 WebDAV 配置；下次保存 WebDAV 时会清理遗留字段。
        val retiredProviderSelected = preferences[LEGACY_PROVIDER] == RETIRED_GOOGLE_DRIVE_PROVIDER
        BackupSettings(
            webDavUrl = preferences[URL].orEmpty(),
            username = preferences[USERNAME].orEmpty(),
            remoteDirectory = preferences[REMOTE_DIRECTORY] ?: "MoRead",
            autoBackup = (preferences[AUTO_BACKUP] ?: false) && !retiredProviderSelected,
            lastBackupAt = preferences[LAST_BACKUP] ?: 0L
        )
    }

    suspend fun current(): BackupSettings = settings.first()

    suspend fun save(settings: BackupSettings, password: String? = null) {
        dataStore.edit { preferences ->
            preferences[URL] = settings.webDavUrl.trim()
            preferences[USERNAME] = settings.username.trim()
            preferences[REMOTE_DIRECTORY] = settings.remoteDirectory.trim().ifBlank { "MoRead" }
            preferences[AUTO_BACKUP] = settings.autoBackup
            clearRetiredGoogleDrivePreferences(preferences)
        }
        password?.takeIf(String::isNotBlank)?.let { apiKeyStore.put(PASSWORD_ALIAS, it) }
    }

    fun hasPassword(): Boolean = !apiKeyStore.get(PASSWORD_ALIAS).isNullOrBlank()

    fun clearPassword() = apiKeyStore.remove(PASSWORD_ALIAS)

    suspend fun credentials(): WebDavCredentials {
        val current = current()
        require(current.webDavUrl.isNotBlank()) { "请填写 WebDAV 地址" }
        require(current.username.isNotBlank()) { "请填写 WebDAV 用户名" }
        val password = apiKeyStore.get(PASSWORD_ALIAS)?.takeIf(String::isNotBlank)
            ?: error("请保存 WebDAV 密码或应用专用密码")
        return WebDavCredentials(
            current.webDavUrl,
            current.username,
            password,
            current.remoteDirectory
        )
    }

    suspend fun setAutoBackup(enabled: Boolean) {
        dataStore.edit {
            it[AUTO_BACKUP] = enabled
            clearRetiredGoogleDrivePreferences(it)
        }
    }

    suspend fun markBackup(now: Long = System.currentTimeMillis()) {
        dataStore.edit { it[LAST_BACKUP] = now }
    }

    private fun clearRetiredGoogleDrivePreferences(preferences: MutablePreferences) {
        preferences.remove(LEGACY_PROVIDER)
        preferences.remove(LEGACY_GOOGLE_DRIVE_CONNECTED)
        preferences.remove(LEGACY_GOOGLE_DRIVE_TREE_URI)
        preferences.remove(LEGACY_GOOGLE_DRIVE_FOLDER_NAME)
    }

    companion object {
        const val PASSWORD_ALIAS = "webdav-password"
        private const val RETIRED_GOOGLE_DRIVE_PROVIDER = "GOOGLE_DRIVE"
        private val URL = stringPreferencesKey("backup_webdav_url")
        private val USERNAME = stringPreferencesKey("backup_webdav_username")
        private val REMOTE_DIRECTORY = stringPreferencesKey("backup_webdav_directory")
        private val AUTO_BACKUP = booleanPreferencesKey("backup_webdav_auto")
        private val LAST_BACKUP = longPreferencesKey("backup_last_success_at")
        private val LEGACY_PROVIDER = stringPreferencesKey("backup_provider")
        private val LEGACY_GOOGLE_DRIVE_CONNECTED = booleanPreferencesKey("backup_google_drive_connected")
        private val LEGACY_GOOGLE_DRIVE_TREE_URI = stringPreferencesKey("backup_google_drive_tree_uri")
        private val LEGACY_GOOGLE_DRIVE_FOLDER_NAME = stringPreferencesKey("backup_google_drive_folder_name")
    }
}
