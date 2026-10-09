package com.mozhi.reader.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mozhi.reader.core.dictionary.LearningLanguage
import com.mozhi.reader.ui.components.MoReadChoiceDialog
import com.mozhi.reader.ui.theme.MoReadTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LanguageChoiceDialogVisualTest {
    @get:Rule val compose = createComposeRule()

    @Test fun languagePickerShowsBadgesAndSelectsOne() {
        var selected by mutableStateOf(LearningLanguage.AUTO)
        var open by mutableStateOf(true)
        compose.setContent {
            MoReadTheme {
                if (open) MoReadChoiceDialog("本书语言", learningLanguageChoices(), selected,
                    onSelect = { selected = it }, onDismiss = { open = false })
            }
        }
        compose.onNodeWithTag("choice-dialog").assertIsDisplayed()
        compose.onNodeWithText("日语").assertIsDisplayed()
        compose.runOnIdle {
            val view = ShadowDialog.getLatestDialog().window!!.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/reports/language/language-picker.png").apply { parentFile.mkdirs() }
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithText("日语").performClick()
        compose.runOnIdle { assertEquals(LearningLanguage.JA, selected); assertEquals(false, open) }
    }
}
