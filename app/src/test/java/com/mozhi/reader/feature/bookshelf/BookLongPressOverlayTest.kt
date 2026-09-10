package com.mozhi.reader.feature.bookshelf

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w400dp-h800dp-mdpi")
class BookLongPressOverlayTest {
    @get:Rule val compose = createComposeRule()
    private var dockClicks = 0
    private var deletes = 0
    private var dismisses = 0
    private val book = BookEntity(
        id = 1, title = "A sample book", author = "Author", coverPath = null, epubPath = "",
        sourceType = BookSourceType.EPUB, importedAt = 0, totalChapters = 12
    )

    private fun mount() {
        compose.setContent {
            MaterialTheme {
                var size by remember { mutableStateOf(IntSize.Zero) }
                var open by remember { mutableStateOf(true) }
                Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
                    if (open && size.height > 0) BookLongPressOverlay(
                        target = BookLongPressTarget(book, Rect(24f, size.height - 180f, 132f, size.height - 40f)),
                        readSpan = null, rootSize = size, contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
                        onDismiss = { dismisses++; open = false },
                        onSetReadState = {}, onEditDetails = {}, onChangeCover = {},
                        onTogglePinned = {}, onStartSelection = {}, onDelete = { deletes++ }
                    )
                    // Even a later app-root layer with extreme zIndex cannot cover a Popup window.
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(124.dp)
                        .zIndex(Float.MAX_VALUE).clickable { dockClicks++ }) { Text("Dock") }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun bottomShelfActionsUseASeparateWindowAboveTheDock() {
        mount()
        compose.onNode(isPopup()).assertExists()
        compose.onNodeWithText("Mark as finished").assertIsDisplayed()
        compose.onNodeWithText("Remove from shelf").performScrollTo().assertIsDisplayed()
            .performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, deletes)
            assertEquals(1, dismisses)
            assertEquals(0, dockClicks)
        }
        compose.onNode(isPopup()).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "en-rUS-w400dp-h360dp-mdpi")
    fun shortWindowsKeepTheLastActionReachableByScrolling() {
        mount()
        compose.onNode(isPopup()).assertExists()
        compose.onNodeWithText("Remove from shelf").performScrollTo().assertIsDisplayed()
            .performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, deletes)
            assertEquals(0, dockClicks)
        }
    }
}
