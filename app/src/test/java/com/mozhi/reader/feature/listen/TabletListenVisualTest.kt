package com.mozhi.reader.feature.listen

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mozhi.reader.ai.listen.*
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.speech.*
import com.mozhi.reader.ui.theme.*
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1400dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabletListenVisualTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: View
    @Test fun listeningKeepsArtworkBesideReachableTransportControls() {
        val vm = mockk<ListenPlayerViewModel>(relaxed = true)
        val tuning = mockk<TtsTuningViewModel>(relaxed = true)
        every { tuning.uiState } returns MutableStateFlow(TtsTuningUiState())
        every { vm.listenState } returns MutableStateFlow(ListenState(1, "雨夜里的灯塔", chapterIndex = 1, chapterTitle = "第二章 · 雨中来客",
            chapterCount = 40, sentenceStart = 0, sentenceEnd = 50, chapterProgress = .37f, isPlaying = false, engineMode = TtsEngineMode.SYSTEM))
        every { vm.sleepTimer } returns MutableStateFlow(null)
        every { vm.book } returns MutableStateFlow(BookEntity(1, "雨夜里的灯塔", "演示作者", null, "", BookSourceType.TXT, 1, 40))
        every { vm.chapters } returns MutableStateFlow(emptyList())
        every { vm.playbackMode } returns ListenPlaybackMode.STANDARD
        compose.setContent {
            root = LocalView.current.rootView
            MoReadTheme { ListenPlayerScreen(1, {}, {}, {}, vm, tuning) }
        }
        compose.onNodeWithTag("detail-summary").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放").assertIsDisplayed().performClick()
        verify(exactly = 1) { vm.toggle() }
        compose.runOnIdle {
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(image))
            File("build/reports/tablet-ui/secondary/tablet-listen.png").apply { parentFile.mkdirs() }.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
}
