package com.mozhi.reader.core.speech

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.*
import java.io.File
import kotlinx.coroutines.*
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TtsProviderProfilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun data(scope: CoroutineScope, file: File) = PreferenceDataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer, producePath = { file.absolutePath.toPath() }), scope = scope)

    @Test fun cloudProfilesSurviveSwitchingAndRestartWithoutSwitchingLocalMode() = runBlocking {
        val file = File(temporary.root, "tts.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = TtsSettingsStore(data(scope, file))
        try {
            assertEquals(TtsEngineMode.SYSTEM, store.current().engineMode)
            store.update { it.copy(aiBaseUrl = "https://my-minimax.test/v1", aiModel = "my-model", aiVoiceId = "my-voice",
                aiGroupId = "group", aiSpeed = 1.3f, aiVolume = 0.8f, aiPitch = -2,
                synthesisConcurrency = 1, maxSynthesisChars = 800, prefetchCount = 5) }
            val original = store.current()
            store.update { it.copy(aiProvider = TtsApiProvider.GEMINI) }
            assertEquals(TtsApiProvider.GEMINI.defaultModel(), store.current().aiModel)
            assertEquals(TtsEngineMode.SYSTEM, store.current().engineMode)
            store.update { it.copy(aiBaseUrl = "https://my-gemini.test/v1beta", aiVoiceId = "voice_custom") }
            store.update { it.copy(aiProvider = TtsApiProvider.MINIMAX_CN) }
            assertEquals(original, store.current())
            val savedGemini = store.forProvider(TtsApiProvider.GEMINI)
            assertEquals("https://my-gemini.test/v1beta", savedGemini.aiBaseUrl)
            assertEquals("voice_custom", savedGemini.aiVoiceId)
            assertEquals(original, store.current())
        } finally { scope.coroutineContext.job.cancelAndJoin() }
        val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reopened = TtsSettingsStore(data(reopenedScope, file))
            reopened.update { it.copy(aiProvider = TtsApiProvider.GEMINI) }
            assertEquals("https://my-gemini.test/v1beta", reopened.current().aiBaseUrl)
            assertEquals("voice_custom", reopened.current().aiVoiceId)
        } finally { reopenedScope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun localEngineVoicesAndUnsavedLegacyConfigurationAreRestored() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val data = data(scope, File(temporary.root, "legacy.preferences_pb"))
            data.edit { prefs ->
                prefs[stringPreferencesKey("tts_engine_mode")] = "AI"
                prefs[stringPreferencesKey("tts_ai_base_url")] = "https://existing.test/v1"
                prefs[stringPreferencesKey("tts_ai_model")] = "existing"
                prefs[stringPreferencesKey("tts_system_engine")] = "engine.first"
                prefs[stringPreferencesKey("tts_system_voice")] = "voice.first"
                prefs[floatPreferencesKey("tts_system_rate")] = 1.4f
            }
            val store = TtsSettingsStore(data)
            assertEquals(TtsEngineMode.AI, store.current().engineMode)
            store.update { it.copy(systemEnginePackage = "engine.second") }
            store.update { it.copy(systemVoiceName = "voice.second", systemRate = 0.8f) }
            store.update { it.copy(systemEnginePackage = "engine.first", engineMode = TtsEngineMode.SYSTEM) }
            assertEquals("voice.first", store.current().systemVoiceName)
            assertEquals(1.4f, store.current().systemRate)
            store.update { it.copy(aiProvider = TtsApiProvider.GEMINI) }
            store.update { it.copy(aiProvider = TtsApiProvider.MINIMAX_CN) }
            assertEquals("https://existing.test/v1", store.current().aiBaseUrl)
            assertEquals("existing", store.current().aiModel)
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }
}
