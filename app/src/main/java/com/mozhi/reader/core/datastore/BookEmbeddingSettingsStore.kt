package com.mozhi.reader.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 用户明确选择建立 AI 向量索引的书籍；未选择的书只使用本地关键词检索。 */
@Singleton
class BookEmbeddingSettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val enabledBookIds: Flow<Set<Long>> = dataStore.data.map { preferences ->
        preferences[KEY_ENABLED_BOOK_IDS]
            .orEmpty()
            .mapNotNull(String::toLongOrNull)
            .toSet()
    }

    suspend fun isEnabled(bookId: Long): Boolean = bookId in enabledBookIds.first()

    suspend fun setEnabled(bookId: Long, enabled: Boolean) {
        dataStore.edit { preferences ->
            val current = preferences[KEY_ENABLED_BOOK_IDS].orEmpty().toMutableSet()
            if (enabled) current += bookId.toString() else current -= bookId.toString()
            preferences[KEY_ENABLED_BOOK_IDS] = current
        }
    }

    private companion object {
        val KEY_ENABLED_BOOK_IDS = stringSetPreferencesKey("embedding_enabled_book_ids_v1")
    }
}
