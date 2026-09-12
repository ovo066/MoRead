package com.mozhi.reader.feature.reader

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.AutoReadSettings
import com.mozhi.reader.core.datastore.PageMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AutoReadUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun touchingChildPausesButDoesNotSwallowItsAction() {
        val session = AutoReadSession().apply { start(AutoReadSettings()); onReady(PageMode.SCROLL) }
        var clicked = false
        compose.setContent {
            Box(Modifier.size(200.dp).pauseAutoReadOnTouch(session)) {
                Text("菜单", Modifier.testTag("menu").clickable { clicked = true })
            }
        }
        compose.onNodeWithTag("menu").performTouchInput { click() }
        compose.runOnIdle {
            assertTrue(clicked)
            assertEquals(AutoReadPauseReason.TOUCH, session.reason)
            assertFalse(session.running)
        }
    }

    @Test fun modeSelectionOnlyAppliesOnExplicitStart() {
        var result: AutoReadSettings? = null
        compose.setContent {
            MaterialTheme { AutoReadSettingsSheet(AutoReadSettings(), false, { result = it }, {}) }
        }
        compose.onNodeWithText("定时翻页").performClick()
        compose.runOnIdle { assertNull(result) }
        compose.onNodeWithText("开始阅读").performClick()
        compose.runOnIdle { assertEquals(PageMode.PAGINATED, result?.mode) }
    }
}
