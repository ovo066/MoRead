package com.mozhi.reader.feature.settings

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mozhi.reader.ui.components.RemoveBookDialog
import com.mozhi.reader.ui.theme.MoReadTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
class DataManagementUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun defaultRemovalKeepsRecords() {
        var erase: Boolean? = null
        compose.setContent { MoReadTheme { RemoveBookDialog("移除书籍？", {}, { erase = it }) } }
        compose.onNodeWithText("移除正文，保留记录").performClick()
        compose.runOnIdle { assertEquals(false, erase) }
    }
    @Test fun erasingPersonalRecordsRequiresAnExplicitChoice() {
        var erase: Boolean? = null
        compose.setContent { MoReadTheme { RemoveBookDialog("移除书籍？", {}, { erase = it }) } }
        compose.onNodeWithText("同时删除个人记录").performClick()
        compose.onNodeWithText("永久删除全部").performClick()
        compose.runOnIdle { assertEquals(true, erase) }
    }
    @Test fun dismissDoesNotInvokeRemoval() {
        var called = false
        var dismissed = false
        compose.setContent { MoReadTheme { RemoveBookDialog("移除书籍？", { dismissed = true }, { called = true }) } }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(dismissed); assertFalse(called) }
    }
}
