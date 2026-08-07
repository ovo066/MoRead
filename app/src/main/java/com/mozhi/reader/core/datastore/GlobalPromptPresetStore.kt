package com.mozhi.reader.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 全局提示词的注入位置。前两项进入 system，后两项只改写本次请求里最新一条 user，
 * 不会污染 Room 中保存的原消息。设计语义参考 SillyTavern Prompt Manager。
 */
@Serializable
enum class GlobalPromptInjectionPosition {
    BEFORE_SYSTEM,
    AFTER_SYSTEM,
    BEFORE_LAST_USER,
    AFTER_LAST_USER
}

@Serializable
data class GlobalPromptPreset(
    val id: String,
    val name: String,
    val prompt: String,
    val enabled: Boolean = false,
    val position: GlobalPromptInjectionPosition = GlobalPromptInjectionPosition.AFTER_SYSTEM,
    val builtIn: Boolean = false
)

@Singleton
class GlobalPromptPresetStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val presets: Flow<List<GlobalPromptPreset>> = dataStore.data.map { preferences ->
        decode(preferences[KEY]) ?: DEFAULTS
    }

    suspend fun current(): List<GlobalPromptPreset> = presets.first()

    suspend fun upsert(preset: GlobalPromptPreset) {
        val clean = preset.copy(
            id = preset.id.ifBlank { UUID.randomUUID().toString() },
            name = preset.name.trim().take(MAX_NAME_CHARS),
            prompt = preset.prompt.trim().take(MAX_PROMPT_CHARS)
        )
        require(clean.name.isNotBlank()) { "预设名称不能为空" }
        require(clean.prompt.isNotBlank()) { "提示词不能为空" }
        dataStore.edit { preferences ->
            val existing = decode(preferences[KEY]) ?: DEFAULTS
            preferences[KEY] = encode(existing.filterNot { it.id == clean.id } + clean)
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dataStore.edit { preferences ->
            val existing = decode(preferences[KEY]) ?: DEFAULTS
            preferences[KEY] = encode(existing.map { preset ->
                if (preset.id == id) preset.copy(enabled = enabled) else preset
            })
        }
    }

    suspend fun delete(id: String) {
        dataStore.edit { preferences ->
            val existing = decode(preferences[KEY]) ?: DEFAULTS
            preferences[KEY] = encode(existing.filterNot { it.id == id })
        }
    }

    private fun encode(value: List<GlobalPromptPreset>): String =
        JSON.encodeToString(ListSerializer(GlobalPromptPreset.serializer()), value)

    private fun decode(value: String?): List<GlobalPromptPreset>? = value
        ?.takeIf(String::isNotBlank)
        ?.let { encoded ->
            runCatching {
                JSON.decodeFromString(ListSerializer(GlobalPromptPreset.serializer()), encoded)
            }.getOrNull()
        }

    companion object {
        private val KEY = stringPreferencesKey("global_prompt_presets")
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private const val MAX_NAME_CHARS = 80
        const val MAX_PROMPT_CHARS = 12_000

        val DEFAULTS = listOf(
            GlobalPromptPreset(
                id = "builtin-natural-style",
                name = "自然表达",
                prompt = "使用自然、具体、连贯的中文回答，避免空泛套话和不必要的重复总结。",
                position = GlobalPromptInjectionPosition.AFTER_SYSTEM,
                builtIn = true
            ),
            GlobalPromptPreset(
                id = "builtin-immersive-roleplay",
                name = "沉浸式角色扮演",
                prompt = "保持角色视角与说话方式，通过动作、语气和细节增强沉浸感；不要代替用户决定其言行。",
                position = GlobalPromptInjectionPosition.AFTER_SYSTEM,
                builtIn = true
            ),
            GlobalPromptPreset(
                id = "builtin-concise",
                name = "简洁回答",
                prompt = "优先直接回答问题；除非用户要求展开，否则控制篇幅并省略重复背景。",
                position = GlobalPromptInjectionPosition.BEFORE_LAST_USER,
                builtIn = true
            )
        )
    }
}
