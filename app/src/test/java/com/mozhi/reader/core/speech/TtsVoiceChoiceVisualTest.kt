package com.mozhi.reader.core.speech

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mozhi.reader.ui.components.VoiceChoice
import com.mozhi.reader.ui.components.VoiceChoiceDialog
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
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h892dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TtsVoiceChoiceVisualTest {
    @get:Rule val compose = createComposeRule()
    @Test fun lightCatalogSearchSelectionAndLongNames() = verify(false)
    @Test fun darkCatalogSearchSelectionAndLongNames() = verify(true)

    private fun verify(dark: Boolean) {
        var selected = ""
        compose.setContent {
            MoReadTheme(AppearanceSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
                VoiceChoiceDialog("选择本地音色", (1..80).map {
                    VoiceChoice("voice-"+it, "苏晚 · 温柔叙事与长文本角色朗读音色 "+it, "zh-CN · 本地")
                }, "voice-1", { selected = it }, {})
            }
        }
        compose.onNodeWithText("关闭").assertIsDisplayed()
        compose.onNodeWithText("搜索音色或语言").performTextInput("80")
        compose.onNodeWithText("苏晚 · 温柔叙事与长文本角色朗读音色 80").performClick()
        assertEquals("voice-80", selected)
        compose.runOnIdle {
            val window = WindowInspector.getGlobalWindowViews().last { it.isShown && it.width > 0 }
            val bitmap = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
            window.draw(Canvas(bitmap))
            val file = File("build/outputs/tts-visual/voices-"+(if (dark) "dark" else "light")+".png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
