package com.mozhi.reader.ui.components

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ui.theme.MoReadTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h892dp-mdpi")
class MoReadBottomSheetTest {
    @get:Rule val compose = createComposeRule()
    private var dismissed = 0
    private var count by mutableIntStateOf(60)

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show() {
        compose.setContent {
            MoReadTheme {
                MoReadBottomSheet({ dismissed++ }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), dragHandle = null) {
                    Column(Modifier.fillMaxWidth().height(600.dp).testTag("plain-sheet")) {
                        Text("可下拉关闭", Modifier.fillMaxWidth().height(64.dp).testTag("plain-header"))
                        val list = rememberLazyListState()
                        // Deliberately no per-list modifier: new sheets inherit the shared behavior.
                        LazyColumn(state = list, modifier = Modifier.weight(1f).testTag("plain-list")) {
                            items((0 until count).toList(), key = { it }) { Text("记录 $it", Modifier.fillMaxWidth().height(56.dp)) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun ordinaryListNeedsNoPatchAndStopsAtBothEdgesDuringDrag() {
        show()
        val top = compose.onNodeWithTag("plain-header").fetchSemanticsNode().boundsInRoot.top
        val list = compose.onNodeWithTag("plain-list")
        list.performTouchInput { down(center); moveBy(Offset(0f, 140f)) }
        assertEquals(top, compose.onNodeWithTag("plain-header").fetchSemanticsNode().boundsInRoot.top, .5f)
        list.performTouchInput { up() }
        list.performTouchInput { swipeUp() }
        assertTrue(list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0)
        list.performScrollToIndex(59)
        repeat(3) { list.performTouchInput { swipeUp(durationMillis = 100) } }
        assertEquals(top, compose.onNodeWithTag("plain-header").fetchSemanticsNode().boundsInRoot.top, .5f)
        list.performScrollToIndex(20)
        val anchor = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.runOnIdle { count = 64 }
        assertEquals(anchor, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .01f)
        assertEquals(0, dismissed)
    }

    @Test fun headerStillDragsToDismiss() {
        show()
        compose.onNodeWithTag("plain-header").performTouchInput {
            swipe(Offset(centerX, 20f), Offset(centerX, 660f), durationMillis = 400)
        }
        compose.waitUntil(5000) { dismissed > 0 }
    }

    @Test fun productionSheetsCannotBypassTheCommonHost() {
        val files = File("src/main/java").walkTopDown().filter { it.extension == "kt" && it.name != "MoReadBottomSheet.kt" }
        val bypasses = files.filter { Regex("\\bModalBottomSheet\\s*\\(").containsMatchIn(it.readText()) }.toList()
        assertTrue("Use MoReadBottomSheet or NavigationSheet: $bypasses", bypasses.isEmpty())
    }
}
