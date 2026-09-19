package com.mozhi.reader.feature.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.mozhi.reader.core.datastore.AnnotationContextMode
import com.mozhi.reader.core.datastore.BookProactiveAnnotationLimits
import com.mozhi.reader.core.datastore.ProactiveAnnotationContextSettings
import com.mozhi.reader.core.datastore.ProactiveAnnotationLimits
import com.mozhi.reader.ui.theme.MoReadTheme
import java.io.File
import org.junit.Assert.*
import org.robolectric.annotation.GraphicsMode
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
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProactiveAnnotationSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(SettingsUiState())

    private lateinit var rootView: View
    private fun mount(bookId: Long? = null) = compose.setContent {
        val view = LocalView.current
        SideEffect { rootView = view.rootView }
        MoReadTheme {
            ProactiveAnnotationSettingsContent(bookId, state, {}, { limits ->
                state = state.copy(autonomy = state.autonomy.copy(annotationLimits = limits))
            }, { id, limits ->
                state = state.copy(autonomy = state.autonomy.copy(annotationLimitsByBook =
                    if (limits == null) state.autonomy.annotationLimitsByBook - id
                    else state.autonomy.annotationLimitsByBook + (id to limits)))
            }, {}, {})
        }
    }

    @Test fun presetsAndCustomBudgetUpdateGlobalSettings() {
        state = SettingsUiState(isLoaded = true)
        mount()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("前文与上下文"))
        compose.onNodeWithText("充分").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(32_000, state.autonomy.annotationLimits.context.budgetChars) }
        compose.onNodeWithText("每次 32000 字符").assertExists()
        compose.onNodeWithText("自定义").performClick()
        compose.onNodeWithText("自定义上限").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("自定义上限 增大").performClick()
        compose.runOnIdle { assertEquals(25_000, state.autonomy.annotationLimits.context.budgetChars) }
        compose.onNodeWithContentDescription("自定义上限 减小").performClick()
        compose.runOnIdle {
            assertEquals(AnnotationContextMode.CUSTOM, state.autonomy.annotationLimits.context.mode)
            val image = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
            rootView.draw(Canvas(image))
            File("build/reports/ui-qa/annotation-context-settings.png").apply { requireNotNull(parentFile).mkdirs() }
                .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    @Test fun disabledBookOverrideFollowsGlobalAndEnabledChangesStayInBook() {
        val global = ProactiveAnnotationLimits(context = ProactiveAnnotationContextSettings(AnnotationContextMode.FULL))
        val book = BookProactiveAnnotationLimits(false,
            ProactiveAnnotationLimits(context = ProactiveAnnotationContextSettings(AnnotationContextMode.ECONOMY)))
        state = SettingsUiState(isLoaded = true, autonomy = CompanionAutonomySettings(
            annotationLimits = global, annotationLimitsByBook = mapOf(7L to book)))
        mount(7)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("前文与上下文"))
        compose.onNodeWithText("每次 32000 字符").assertExists()
        compose.onNodeWithText("均衡").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(book, state.autonomy.annotationLimitsByBook[7]) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("本书单独设置"))
        compose.onNodeWithText("本书单独设置").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("前文与上下文"))
        compose.onNodeWithText("每次 32000 字符").assertExists()
        compose.onNodeWithText("均衡").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(16_000, state.autonomy.annotationLimitsFor(7).context.budgetChars)
            assertEquals(global, state.autonomy.annotationLimits)
        }
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
