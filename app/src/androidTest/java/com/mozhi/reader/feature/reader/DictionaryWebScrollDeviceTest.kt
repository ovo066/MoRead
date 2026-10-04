package com.mozhi.reader.feature.reader

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mozhi.reader.core.dictionary.DictionaryDefinition
import com.mozhi.reader.core.dictionary.DictionaryLookupHit
import com.mozhi.reader.core.dictionary.LocalDictionary
import com.mozhi.reader.core.dictionary.LocalDictionaryRepository
import com.mozhi.reader.ui.theme.MoReadTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Chromium scrolling, complementing the JVM test of AndroidView gesture dispatch. */
@RunWith(AndroidJUnit4::class)
class DictionaryWebScrollDeviceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var dialogView: View

    @Test fun htmlScrollsBothWaysWithoutMovingSheetAndRetainsSourceAnchors() {
        val html = "<h2>故</h2>" + (1..30).joinToString("") {
            "<p>$it. 故，可以指旧的事物，也可以指原因。温故而知新，可以为师矣。</p>"
        }
        val live = mutableStateOf(EnglishLearningState(
            dictionaries = listOf(LocalDictionary("a", "古汉语", 0), LocalDictionary("b", "汉语", 0)),
            hit = DictionaryLookupHit("故", "温故而知新", 2, 4),
            definitions = listOf(DictionaryDefinition("a", "古汉语", html), DictionaryDefinition("b", "汉语", html))
        ))
        val repository = LocalDictionaryRepository(InstrumentationRegistry.getInstrumentation().context)
        var dismissed = 0
        compose.setContent {
            MoReadTheme {
                DictionaryLookupSheet(live.value, companionChatPalette(), emptyList(), {}, {}, { _, _ -> }, {}, { dismissed++ }) { entry, modifier ->
                    val view = LocalView.current
                    SideEffect { dialogView = view }
                    DictionaryWebContent(entry, false, repository, {}, modifier)
                }
            }
        }
        waitForPage()
        val viewport = bounds("navigation-viewport")
        val tabs = bounds("dictionary-sources")
        val target = compose.onNodeWithTag("dictionary-definition")
        fun assertSheetStable() {
            assertEquals(viewport.top, bounds("navigation-viewport").top, 1f)
            assertEquals(tabs.top, bounds("dictionary-sources").top, 1f)
            assertEquals(dialogView.height.toFloat(), bounds("navigation-sheet").bottom, 1f)
            assertEquals(0, dismissed)
        }

        // Pulling at the top must keep the popup in place, including while the finger is down.
        target.performTouchInput { down(center); moveBy(Offset(0f, 100f)) }
        assertSheetStable()
        target.performTouchInput { up() }
        repeat(3) {
            target.performTouchInput { swipeUp(durationMillis = 350) }
            settleWebScroll()
            val below = compose.runOnIdle { web().scrollY }
            assertTrue("HTML must scroll down", below > 0)
            target.performTouchInput { swipeDown(durationMillis = 350) }
            settleWebScroll()
            assertTrue("HTML must scroll back up", compose.runOnIdle { web().scrollY } < below)
            assertSheetStable()
        }
        // Reach the real document bottom, then fling beyond it repeatedly.
        repeat(24) {
            if (compose.runOnIdle { web().canScrollVertically(1) }) {
                target.performTouchInput { swipeUp(durationMillis = 100) }
                settleWebScroll()
            }
        }
        assertFalse("Long definition must be readable to the end", compose.runOnIdle { web().canScrollVertically(1) })
        repeat(2) { target.performTouchInput { swipeUp(durationMillis = 100) }; settleWebScroll() }
        assertSheetStable()
        target.performTouchInput { swipeDown(durationMillis = 500) }
        settleWebScroll()
        val anchor = compose.runOnIdle { web().scrollY }
        assertTrue(anchor > 0)
        compose.runOnIdle { live.value = live.value.copy(lookingUp = true) }
        assertEquals(anchor, compose.runOnIdle { web().scrollY })
        compose.runOnIdle { live.value = live.value.copy(lookingUp = false) }
        compose.onNodeWithText("汉语", substring = false).performClick()
        waitForPage()
        assertEquals(0, compose.runOnIdle { web().scrollY })
        compose.onNodeWithText("古汉语", substring = false).performClick()
        waitForPage()
        compose.waitUntil(5000) { compose.runOnIdle { kotlin.math.abs(web().scrollY - anchor) <= 1 } }
        assertSheetStable()
        compose.onNodeWithContentDescription("关闭词典").performClick()
        assertEquals(1, dismissed)
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun web(): WebView = findWebView(dialogView) ?: error("Dictionary WebView is missing")

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }

    private fun waitForPage() {
        compose.waitUntil(10_000) { compose.runOnIdle {
            findWebView(dialogView)?.let { it.progress == 100 && it.canScrollVertically(1) } == true
        } }
        settleWebScroll()
    }

    private fun settleWebScroll() {
        var last = -1
        var changed = SystemClock.uptimeMillis()
        compose.waitUntil(5000) {
            val current = compose.runOnIdle { web().scrollY }
            if (current != last) { last = current; changed = SystemClock.uptimeMillis() }
            SystemClock.uptimeMillis() - changed > 250
        }
    }
}
