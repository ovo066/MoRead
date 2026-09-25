package com.mozhi.reader.feature.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.core.security.ApiKeyStore
import com.mozhi.reader.core.speech.*
import com.mozhi.reader.ui.components.GeminiVoicePicker
import com.mozhi.reader.ui.theme.*
import io.mockk.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h892dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TtsSettingsSwitchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unsavedFormAndKeySurviveLocalAndProviderSwitches() {
        val data = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            private val lock = Mutex()
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                lock.withLock { transform(data.value).also { data.value = it } }
        }
        val store = TtsSettingsStore(data)
        val speaker = mockk<SystemTtsSpeaker>(relaxed = true)
        coEvery { speaker.engines() } returns emptyList()
        coEvery { speaker.voices(any()) } returns emptyList()
        val keys = mutableMapOf<String, String>()
        val keyStore = mockk<ApiKeyStore>()
        every { keyStore.get(any()) } answers { keys[firstArg()] }
        every { keyStore.put(any(), any()) } answers { keys[firstArg()] = secondArg() }
        every { keyStore.migrateAlias(any(), any()) } answers {
            val old = keys.remove(firstArg<String>())
            if (old != null) keys.putIfAbsent(secondArg(), old)
            keys[secondArg()]
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val model = TtsSettingsViewModel(store, speaker, mockk<AiMediaGenerationService>(), keyStore, scope)
        try {
            compose.setContent { MoReadTheme(AppearanceSettings()) { TtsSettingsScreen({}, viewModel = model) } }
            compose.onNodeWithText("AI TTS").performScrollTo().performClick()
            compose.onNodeWithText("Base URL").performScrollTo().performTextReplacement("https://draft.example/v1")
            compose.onNodeWithText("模型").performScrollTo().performTextReplacement("draft-model")
            compose.onNodeWithText("API Key").performScrollTo().performTextReplacement("minimax-test-only")
            // Deliberately never press Save or IME Done.
            compose.onNodeWithText("系统 TTS").performScrollTo().performClick()
            compose.waitUntil { runBlocking { store.current().engineMode == TtsEngineMode.SYSTEM && store.current().aiModel == "draft-model" } }
            assertEquals("minimax-test-only", keys[TtsSettingsStore.apiKeyAlias(TtsApiProvider.MINIMAX_CN)])
            compose.onNodeWithText("AI TTS").performScrollTo().performClick()
            compose.onNodeWithText("Base URL").performScrollTo().assertTextContains("https://draft.example/v1")
            compose.onNodeWithText("MiniMax（国内）").performScrollTo().performClick()
            compose.onNodeWithText("Gemini TTS").performClick()
            compose.waitUntil { runBlocking { store.current().aiProvider == TtsApiProvider.GEMINI } }
            assertNull(keys[TtsSettingsStore.apiKeyAlias(TtsApiProvider.GEMINI)])
            compose.onNodeWithText("API Key").performScrollTo().performTextReplacement("gemini-test-only")
            compose.onNodeWithText("Gemini TTS").performScrollTo().performClick()
            compose.onNodeWithText("MiniMax（国内）").performClick()
            compose.waitUntil { runBlocking { store.current().aiProvider == TtsApiProvider.MINIMAX_CN } }
            compose.onNodeWithText("Base URL").performScrollTo().assertTextContains("https://draft.example/v1")
            assertEquals("draft-model", runBlocking { store.current().aiModel })
            assertEquals("gemini-test-only", keys[TtsSettingsStore.apiKeyAlias(TtsApiProvider.GEMINI)])
            assertEquals("minimax-test-only", keys[TtsSettingsStore.apiKeyAlias(TtsApiProvider.MINIMAX_CN)])
        } finally { scope.cancel() }
    }

    @Test fun geminiVoicePickerLight() = voicePicker(false)
    @Test fun geminiVoicePickerDark() = voicePicker(true)

    private fun voicePicker(dark: Boolean) {
        var selected = ""
        compose.setContent {
            MoReadTheme(AppearanceSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
                GeminiVoicePicker("Sulafat", { selected = it }, {})
            }
        }
        compose.onNodeWithText("搜索音色或语言").performTextInput("温暖")
        compose.onNodeWithText("Sulafat · 温暖").assertIsDisplayed().performClick()
        assertEquals("Sulafat", selected)
        compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().last { it.isShown && it.width > 0 }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/outputs/tts-visual/gemini-${if (dark) "dark" else "light"}.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
