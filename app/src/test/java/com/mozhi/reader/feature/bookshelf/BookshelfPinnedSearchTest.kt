package com.mozhi.reader.feature.bookshelf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.ui.theme.MoReadTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookshelfPinnedSearchTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: View
    private fun check(grid: Boolean, fontScale: Float = 1f) {
        val books = (1..40).map { BookEntity(id = it.toLong(), title = "书籍 $it", author = "作者 $it", coverPath = null,
            epubPath = "", sourceType = BookSourceType.TXT, importedAt = 0, totalChapters = 10) }
        var imports = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) { MoReadTheme {
                root = LocalView.current.rootView
                var query by remember { mutableStateOf("") }
                val drag = remember { ShelfCollectionDragState() }
                val state = BookshelfUiState(books = books, allBooks = books, totalBooks = books.size)
                if (grid) BookGrid(books.map { ShelfEntry.Book(it) }, books.size, state, query, { query = it },
                    {}, {}, {}, { _, _ -> }, drag, { _, _ -> }, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, { imports++ })
                else BookList(books.map { ShelfEntry.Book(it) }, books.size, state, query, { query = it },
                    {}, {}, {}, { _, _ -> }, drag, { _, _ -> }, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, { imports++ })
            } }
        }
        val date = compose.onNodeWithText("宜读书", substring = true).assertIsDisplayed().fetchSemanticsNode()
        // The complete date line must fit below the greeting, including larger accessibility text.
        assertTrue(date.boundsInRoot.height >= 12f * fontScale)
        val searchBounds = compose.onNodeWithTag("shelf-search-capsule").fetchSemanticsNode().boundsInRoot
        val searchTop = searchBounds.top
        val importBounds = compose.onNodeWithContentDescription("导入书籍").fetchSemanticsNode().boundsInRoot
        val filterBounds = compose.onNodeWithContentDescription("视图与筛选").fetchSemanticsNode().boundsInRoot
        assertTrue(importBounds.top < searchTop && filterBounds.top < searchTop)
        assertTrue(importBounds.left > root.width / 2f && filterBounds.left > root.width / 2f)
        compose.onNodeWithContentDescription("导入书籍").performClick()
        assertEquals(1, imports)
        if (fontScale > 1f) capture("shelf-large-font-header.png")
        compose.onNodeWithTag("shelf-scroll").performScrollToIndex(30)
        val pinnedBounds = compose.onNodeWithTag("shelf-search-capsule").fetchSemanticsNode().boundsInRoot
        assertTrue(pinnedBounds.top < searchTop)
        compose.onNodeWithContentDescription("导入书籍").assertDoesNotExist()
        compose.onNodeWithContentDescription("视图与筛选").assertDoesNotExist()
        compose.onNodeWithTag("shelf-scroll").performScrollToIndex(34)
        assertEquals(pinnedBounds, compose.onNodeWithTag("shelf-search-capsule").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("shelf-search").assertIsDisplayed().performTextInput("鲁迅")
        // Compare the capsule, not Latin/Chinese glyph metrics inside BasicTextField.
        assertEquals(pinnedBounds, compose.onNodeWithTag("shelf-search-capsule").fetchSemanticsNode().boundsInRoot)
        capture("shelf-${if (grid) "grid" else "list"}.png")
    }
    private fun capture(name: String) {
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File("build/reports/reader-enhancements/$name").apply { parentFile.mkdirs() }
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun gridPinsOnlySearchWhileTopRightActionsScrollAway() = check(true)
    @Test fun listPinsOnlySearchWhileTopRightActionsScrollAway() = check(false)
    @Test fun largeFontKeepsTheFullGreetingDateAndOnlySearchPinned() = check(true, 1.6f)
}
