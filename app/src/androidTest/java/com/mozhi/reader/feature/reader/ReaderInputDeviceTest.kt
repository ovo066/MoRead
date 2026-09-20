package com.mozhi.reader.feature.reader

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.ui.theme.MoReadTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated reader controls exercise real input dispatch without editing the device library. */
@RunWith(AndroidJUnit4::class)
class ReaderInputDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dialogRecordsVolumeAndRemoteKeysThroughTheAndroidWindow() {
        val live = mutableStateOf(ReaderSettings(volumeKeysPageTurn = true))
        compose.setContent { MoReadTheme {
            ReaderPhysicalKeysSheet(live.value, readerPalette(ReaderTheme.PAPER, false), {},
                { live.value = live.value.copy(volumeKeysPageTurn = it) },
                { live.value = live.value.copy(physicalKeyBindings = it) })
        } }
        compose.onNodeWithTag("record-PREVIOUS_PAGE").performClick()
        compose.onNodeWithText("录制上一页按键").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)
        compose.onNodeWithTag("key-binding-conflict").assertTextEquals("此按键当前用于下一页，保存后改为上一页。")
        compose.onNodeWithTag("save-recorded-key").assertIsEnabled().performClick()
        compose.onNodeWithTag("save-recorded-key").assertDoesNotExist()
        compose.runOnIdle { assertTrue(live.value.physicalKeyBindings.all { it.action == ReaderKeyAction.PREVIOUS_PAGE }) }
        compose.onNodeWithTag("physical-keys-list").performScrollToNode(hasTestTag("record-NEXT_PAGE"))
        compose.onNodeWithTag("record-NEXT_PAGE").performClick()
        compose.onNodeWithText("录制下一页按键").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_PAGE_DOWN)
        compose.onNodeWithText("Page Down").assertIsDisplayed()
        compose.onNodeWithTag("save-recorded-key").performClick()
        compose.runOnIdle {
            assertEquals(ReaderKeyAction.NEXT_PAGE, live.value.physicalKeyBindings.single { it.key.keyCode == KeyEvent.KEYCODE_PAGE_DOWN }.action)
        }
    }

    @Test fun fourConsecutiveSwipesAdvanceFourPagesBeforeEachPreviousSettleFinishes() {
        lateinit var driver: PageTurnDriver
        val turns = mutableIntStateOf(0)
        compose.setContent {
            val scope = rememberCoroutineScope()
            driver = remember { PageTurnDriver(scope, object : PageTurnDriver.Callbacks {
                override fun hasPage(direction: PageTurnDirection) = true
                override fun fillPage(direction: PageTurnDirection) { turns.intValue++ }
                override fun onBoundaryHit(direction: PageTurnDirection) = Unit
                override fun onTurnCommitted() = Unit
                override fun onTurnStarted(direction: PageTurnDirection) = Unit
            }).apply { mode = PageTurnDriver.Mode.MODERN_CURL } }
            Box(Modifier.fillMaxSize().background(Color(0xffefece6)).testTag("input-page").readerPageTouch(true, driver) { _, _ -> },
                contentAlignment = Alignment.Center) {
                Text("正在验证连续翻页，请稍候", color = Color.Black, fontSize = 20.sp)
            }
        }
        compose.mainClock.autoAdvance = false
        repeat(4) { index ->
            compose.onNodeWithTag("input-page").performTouchInput {
                val start = Offset(width * .85f, height * .5f)
                down(start)
                moveTo(start - Offset(width * .22f, 0f), delayMillis = 48)
                up()
            }
            compose.mainClock.advanceTimeBy(64)
            compose.runOnIdle {
                assertTrue(driver.isAnimating)
                assertEquals(index, turns.intValue)
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(4, turns.intValue); assertFalse(driver.isRunning) }
    }
}
