package com.mozhi.reader.core.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class UpdatePreferencesStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val autoCheck: Flow<Boolean> = dataStore.data.map { it[AUTO_CHECK] ?: true }

    suspend fun setAutoCheck(enabled: Boolean) {
        dataStore.edit { it[AUTO_CHECK] = enabled }
    }

    suspend fun shouldCheck(now: Long = System.currentTimeMillis()): Boolean {
        val preferences = dataStore.data.first()
        return now - (preferences[LAST_CHECK] ?: 0L) >= CHECK_INTERVAL_MS
    }

    suspend fun markChecked(now: Long = System.currentTimeMillis()) {
        dataStore.edit { it[LAST_CHECK] = now }
    }

    private companion object {
        val AUTO_CHECK = booleanPreferencesKey("update_auto_check")
        val LAST_CHECK = longPreferencesKey("update_last_check_at")
        const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1_000L
    }
}
