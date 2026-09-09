package com.mozhi.reader.feature.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.mozhi.reader.core.datastore.CompanionAutonomySettings
import com.mozhi.reader.core.datastore.ProactiveAnnotationNotice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProactiveAnnotationSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(SettingsUiState())

    private fun mount() = compose.setContent {
        ProactiveAnnotationSettingsContent(null, state, {}, {}, { _, _ -> }, {}, {})
    }

    @Test fun coldEntryDoesNotRenderDefaultsBeforeSavedSettings() {
        mount()
        compose.onNodeWithText("总开关").assertDoesNotExist()
        compose.runOnIdle {
            state = SettingsUiState(isLoaded = true, autonomy = CompanionAutonomySettings(proactiveAnnotationsEnabled = true))
        }
        compose.onNodeWithText("总开关").assertExists()
    }

    @Test fun fastModelPreviewIsCharacterSpeechNotACompletionCount() {
        state = SettingsUiState(isLoaded = true,
            autonomy = CompanionAutonomySettings(annotationNotice = ProactiveAnnotationNotice.FAST_MODEL))
        mount()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("还挺有意思，你怎么看？"))
        compose.onNodeWithText("还挺有意思，你怎么看？").assertExists()
        compose.onNodeWithText("知墨 写了 3 条段评").assertDoesNotExist()
    }
}
