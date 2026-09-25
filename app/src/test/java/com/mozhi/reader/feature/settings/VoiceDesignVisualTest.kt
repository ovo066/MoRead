package com.mozhi.reader.feature.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mozhi.reader.ai.client.*
import com.mozhi.reader.ui.components.MoReadPageDialog
import com.mozhi.reader.ui.theme.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w412dp-h892dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceDesignVisualTest {
    @get:Rule val compose = createComposeRule()
    private val description = "年轻的女性，清晰自然的普通话，声线温暖柔和，略带气声，适合安静的文学叙事。吐字清楚，呼吸自然，节奏从容。"
    private fun ready() = VoiceDesignState(name = "苏晚 · 温柔叙事", description = description,
        candidate = VoiceDesignCandidate("voice_demo", GeminiVoiceDesignRequest("苏晚 · 温柔叙事", description), "https://example.test", "/preview.wav"),
        chats = listOf(VoiceDesignChat(1, ChatRole.USER, "给苏晚设计一个温柔、略带沙哑的普通话女声。"),
            VoiceDesignChat(2, ChatRole.ASSISTANT, "我参考了苏晚从容温柔的表达方式，已生成一版试听。听听音色是否合适，也可以继续告诉我想调整的地方。")))

    @Test fun lightPreviewAndConfirmationStayVisible() = verify(false, "phone-light")
    @Test fun darkPreviewAndConfirmationStayVisible() = verify(true, "phone-dark")
    @Test @Config(qualifiers = "zh-rCN-w1200dp-h900dp-mdpi")
    fun tabletPreviewRemainsCenteredAndReadable() = verify(false, "tablet")
    @Test @Config(qualifiers = "zh-rCN-w412dp-h540dp-mdpi")
    fun compactViewportKeepsPreviewAndConfirmationReachable() = verify(false, "phone-compact")

    @Test fun initialConversationHasAnObviousComposerAndNoPrematureSaveAction() {
        compose.setContent { MoReadTheme(AppearanceSettings(themeMode = ThemeMode.LIGHT)) {
            MoReadPageDialog({}) { VoiceDesignContent(VoiceDesignState(), {}, VoiceDesignUiActions()) }
        } }
        compose.onNodeWithContentDescription("发送需求").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("满意，入库").assertDoesNotExist()
        compose.onNodeWithText("说说你的想法，或继续调整…").performTextInput("给我一个适合悬疑故事的低沉男声")
        compose.onNodeWithContentDescription("发送需求").assertIsEnabled()
        screenshot("initial-conversation")
    }

    private fun verify(dark: Boolean, name: String) {
        var saved = 0
        compose.setContent { MoReadTheme(AppearanceSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
            MoReadPageDialog({}) { VoiceDesignContent(ready(), {}, VoiceDesignUiActions(save = { saved++ })) }
        } }
        compose.onNodeWithContentDescription("播放试听").assertIsDisplayed()
        compose.onNodeWithText("满意，入库").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("secondary-page-list").performScrollToNode(hasText("当前试听"))
        compose.onNodeWithText(description).assertIsDisplayed()
        screenshot(name)
        compose.onNodeWithText("满意，入库").performClick()
        assertEquals(1, saved)
    }

    @Test fun manualEditsInvalidateOldPreviewAndLongTextCannotCoverBottomActions() {
        var state by mutableStateOf(ready())
        compose.setContent { MoReadTheme(AppearanceSettings(themeMode = ThemeMode.LIGHT)) {
            MoReadPageDialog({}) { VoiceDesignContent(state, {}, VoiceDesignUiActions(description = { state = state.copy(description = it) })) }
        } }
        compose.onNodeWithTag("secondary-page-list").performScrollToNode(hasText("手动描述"))
        compose.onNodeWithText("手动描述").performClick()
        compose.onNodeWithTag("secondary-page-list").performScrollToNode(hasText("声音描述"))
        compose.onNodeWithText("声音描述").performTextReplacement(description.repeat(8))
        compose.onNodeWithText("满意，入库").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("重新生成试听").assertIsDisplayed().assertIsEnabled()
        screenshot("manual-long")
    }

    private fun screenshot(name: String) = compose.runOnIdle {
        val view = WindowInspector.getGlobalWindowViews().last { it.isShown && it.width > 0 }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/outputs/voice-design-visual/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
