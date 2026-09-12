package com.mozhi.reader.feature.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.feature.reader.BookmarkPullFeedback
import com.mozhi.reader.feature.reader.BookmarkPullIndicator
import com.mozhi.reader.feature.reader.readerPalette
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.storage.*
import com.mozhi.reader.ui.theme.MoReadTheme
import io.mockk.*
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StorageScreenVisualTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var rootView: View

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            val view = LocalView.current
            SideEffect { rootView = view.rootView }
            MoReadTheme { content() }
        }
    }
    @Test fun spaceBreakdownAndRetainedRecordsHaveReachableActions() {
        val book = BookEntity(id = 7, title = "示例书籍：保留阅读记忆", author = "", coverPath = null,
            epubPath = "/book.epub", sourceType = BookSourceType.EPUB, importedAt = 1, totalChapters = 3)
        val usage = StorageSnapshot(
            listOf(StorageCategoryUsage(StorageCategory.ORIGINALS, 20_000_000),
                StorageCategoryUsage(StorageCategory.SPEECH, 120_000_000),
                StorageCategoryUsage(StorageCategory.ILLUSTRATIONS, 8_000_000)),
            listOf(BookStorageUsage(book.copy(removedAt = 1), 0, 0, 0, 0, 0, 8_000_000, 0, false)),
            mapOf(StorageCleanup.TEMPORARY to 2_000_000, StorageCleanup.COVERS to 10_000, StorageCleanup.ORPHANS to 5_000_000), 1)
        val vm = mockk<DataSettingsViewModel>()
        every { vm.usage } returns MutableStateFlow(usage)
        every { vm.working } returns MutableStateFlow(false)
        every { vm.events } returns emptyFlow()
        every { vm.refresh() } just Runs
        show { DataSettingsScreen({}, {}, {}, {}, {}, {}, vm) }
        compose.onNodeWithText("本地数据总量").assertIsDisplayed()
        capture("storage-overview.png")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("保留的阅读记录 · 1 本"))
        compose.onNodeWithText("保留的阅读记录 · 1 本").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("彻底删除记录"))
        compose.onNodeWithText("查看记录与插图").assertIsDisplayed()
        capture("storage-records.png")
    }

    @Test fun imageTabsSeparateCoverAssetsAndBackgroundActions() {
        val vm = mockk<ImageLibraryViewModel>()
        every { vm.uiState } returns MutableStateFlow(ImageLibraryUiState(images = listOf(
            ReaderImageAsset("bg", "阅读背景素材", "/missing/background.png", purpose = ReaderImagePurpose.BACKGROUND),
            ReaderImageAsset("cover", "书籍封面素材", "/missing/cover.png", purpose = ReaderImagePurpose.COVER))))
        every { vm.events } returns emptyFlow()
        show { ImageLibraryScreen({}, vm) }
        compose.onNodeWithText("封面图 1").performClick()
        compose.onNodeWithText("书籍封面素材").assertIsDisplayed()
        compose.onNodeWithText("阅读背景素材").assertDoesNotExist()
        compose.onNodeWithText("设为背景").assertDoesNotExist()
        capture("image-library-covers.png")
    }

    @Test fun applicationFontButtonDoesNotSelectTheReadingFont() {
        val vm = mockk<FontLibraryViewModel>()
        every { vm.uiState } returns MutableStateFlow(FontLibraryUiState(fonts = listOf(
            ReaderFontAsset("custom", "自定义字体示例", "/missing/font.ttf"))))
        every { vm.events } returns emptyFlow()
        every { vm.selectForApp("custom") } just Runs
        show { FontLibraryScreen({}, vm) }
        compose.onNodeWithText("设为应用字体").performClick()
        verify(exactly = 1) { vm.selectForApp("custom") }
        verify(exactly = 0) { vm.selectForBody(any()) }
        capture("application-font.png")
    }

    @Test fun readyBookmarkUsesTheRibbonOverlay() {
        val feedback = BookmarkPullFeedback().apply { progress = 1f }
        var label = ""
        show {
            val palette = readerPalette(ReaderSettings(), false)
            label = LocalContext.current.getString(com.mozhi.reader.R.string.reader_bookmark_release)
            Box(Modifier.fillMaxSize().background(palette.background)) {
                BookmarkPullIndicator(feedback, palette, Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 20.dp))
            }
        }
        compose.onNodeWithText(label).assertIsDisplayed()
        capture("bookmark-ribbon.png")
    }

    private fun capture(name: String) {
        val file = File("build/reports/ui-qa/$name").apply { parentFile.mkdirs() }
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
            rootView.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
