package com.mozhi.reader.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 类似 SillyTavern user persona 的用户侧身份；它描述“我是谁”，不替代 AI 角色卡。 */
@Serializable
data class UserMask(
    val id: Long = 0L,
    val name: String = "",
    val description: String = ""
)

@Serializable
data class UserMaskSettings(
    val enabled: Boolean = false,
    val activeMaskId: Long? = null,
    val masks: List<UserMask> = emptyList()
) {
    fun activeMask(): UserMask? = if (enabled) {
        activeMaskId?.let { id -> masks.firstOrNull { it.id == id } }
    } else {
        null
    }
}

@Singleton
class UserMaskStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val settings: Flow<UserMaskSettings> = dataStore.data.map { preferences ->
        decode(preferences[KEY])
    }

    suspend fun activeMask(): UserMask? = settings.first().activeMask()

    suspend fun setEnabled(enabled: Boolean) {
        update { current ->
            current.copy(enabled = enabled && current.masks.isNotEmpty())
        }
    }

    suspend fun select(id: Long) {
        update { current ->
            if (current.masks.none { it.id == id }) current
            else current.copy(activeMaskId = id)
        }
    }

    suspend fun save(mask: UserMask): Long {
        var assignedId = mask.id
        update { current ->
            if (assignedId == 0L) {
                assignedId = (current.masks.maxOfOrNull(UserMask::id) ?: 0L) + 1L
            }
            val saved = mask.copy(
                id = assignedId,
                name = mask.name.trim().take(MAX_NAME_CHARS),
                description = mask.description.trim().take(MAX_DESCRIPTION_CHARS)
            )
            current.copy(
                masks = current.masks.filterNot { it.id == assignedId } + saved,
                activeMaskId = current.activeMaskId ?: assignedId
            )
        }
        return assignedId
    }

    suspend fun delete(id: Long) {
        update { current ->
            val remaining = current.masks.filterNot { it.id == id }
            val nextActive = if (current.activeMaskId == id) remaining.firstOrNull()?.id
            else current.activeMaskId
            current.copy(
                enabled = current.enabled && remaining.isNotEmpty(),
                activeMaskId = nextActive,
                masks = remaining
            )
        }
    }

    private suspend fun update(transform: (UserMaskSettings) -> UserMaskSettings) {
        dataStore.edit { preferences ->
            preferences[KEY] = json.encodeToString(transform(decode(preferences[KEY])))
        }
    }

    private fun decode(raw: String?): UserMaskSettings = raw
        ?.takeIf(String::isNotBlank)
        ?.let { encoded -> runCatching { json.decodeFromString<UserMaskSettings>(encoded) }.getOrNull() }
        ?: UserMaskSettings()

    private companion object {
        val KEY = stringPreferencesKey("companion_user_masks_v1")
        const val MAX_NAME_CHARS = 24
        const val MAX_DESCRIPTION_CHARS = 4_000
        val json = Json { ignoreUnknownKeys = true }
    }
}
