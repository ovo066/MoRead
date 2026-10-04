package com.mozhi.reader.feature.bookdetail

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.ui.components.MoReadBackdrop
import com.mozhi.reader.ui.theme.AppearanceSettings
import com.mozhi.reader.ui.theme.MoReadTheme
import com.mozhi.reader.ui.theme.ThemeMode
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real detail page with a generated cover: the backdrop, accent and book object come from the cover. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = com.mozhi.reader.feature.review.ReviewVisualApplication::class, qualifiers = "zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookDetailVisualTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: View

    private fun cover(name: String, top: Int, bottom: Int): String = File("build/tmp/book-detail/$name.png").apply {
        requireNotNull(parentFile).mkdirs()
        val bitmap = Bitmap.createBitmap(300, 430, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, 300f, 430f, Paint().apply { shader = LinearGradient(0f, 0f, 0f, 430f, top, bottom, Shader.TileMode.CLAMP) })
        canvas.drawCircle(210f, 120f, 56f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF3E6C8.toInt() })
        canvas.drawRect(30f, 300f, 270f, 306f, Paint().apply { color = 0xCCFFFFFF.toInt() })
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }.absolutePath

    private fun show(book: BookEntity, dark: Boolean) {
        val vm = mockk<BookDetailViewModel>(relaxed = true)
        every { vm.uiState } returns MutableStateFlow(BookDetailUiState(book = book, isLoading = false, totalDurationMs = 19_560_000,
            readingDays = 14, streakDays = 4,
            description = "雨夜，一封没有落款的信被送到灯塔。守塔人循着信里的暗号，走进了一段被海雾遮住的往事。"))
        every { vm.coverCandidates } returns MutableStateFlow(emptyList())
        every { vm.coverSearchQueries } returns MutableStateFlow(emptyList())
        every { vm.coverSearchAgentEnhanced } returns MutableStateFlow(false)
        every { vm.pendingCover } returns MutableStateFlow(null)
        every { vm.coverGenerationProgress } returns MutableStateFlow(null)
        every { vm.events } returns emptyFlow()
        compose.setContent {
            val view = LocalView.current
            SideEffect { root = view.rootView }
            MoReadTheme(AppearanceSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
                MoReadBackdrop { BookDetailScreen(bookId = book.id, onBack = {}, onContinueReading = {}, viewModel = vm) }
            }
        }
        // The palette is decoded off the main thread.
        compose.waitUntil(5_000) { true }
        Thread.sleep(300)
        compose.waitForIdle()
    }

    private fun book(coverPath: String?) = BookEntity(id = 1, title = "雨夜里的灯塔", author = "林知秋", coverPath = coverPath,
        epubPath = "", sourceType = BookSourceType.EPUB, importedAt = 1, totalChapters = 48, lastReadAt = 1,
        lastReadChapterIndex = 17, maxReachedChapterIndex = 17)

    @Test fun lightCoverTintsTheWholePage() {
        show(book(cover("teal", 0xFF1F6F78.toInt(), 0xFF0E2F3A.toInt())), dark = false)
        compose.onNodeWithText("继续阅读").assertIsDisplayed()
        compose.onNodeWithText("读到第 18 / 48 章").assertIsDisplayed()
        capture("book-detail-light.png")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("笔记"))
        capture("book-detail-light-scrolled.png")
    }

    @Test fun darkThemeWithAWarmCover() {
        show(book(cover("rust", 0xFFB5532E.toInt(), 0xFF4A1C12.toInt())), dark = true)
        capture("book-detail-dark.png")
    }

    @Test fun booksWithoutACoverStillGetAQuietAtmosphere() {
        show(book(null), dark = false)
        compose.onNodeWithText("雨夜里的灯塔").assertIsDisplayed()
        capture("book-detail-no-cover.png")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File("build/reports/book-detail/$name").apply { parentFile.mkdirs() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            assertTrue(file.length() > 0)
        }
    }
}
