package com.mozhi.reader.feature.reader

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.os.SystemClock
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mozhi.reader.core.dictionary.*
import com.mozhi.reader.ui.theme.MoReadTheme
import com.mozhi.reader.ui.theme.AppearanceSettings
import com.mozhi.reader.ui.theme.ThemeMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DictionaryStyleDeviceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: View

    @Test fun localCssKeepsHierarchyInLightAndDarkInsideTheRealSheet() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = "f31ea621-7e69-49b5-a356-1387814d541b"
        val css = File(context.filesDir, "reader-custom/dictionaries/$id/resources/styles/entry.css")
        css.parentFile!!.mkdirs()
        css.writeText("h2{font-size:30px;color:teal}p{font-size:19px;color:black}.example{background-color:white;padding:12px;border-left:3px solid teal}")
        val html = "<link rel='stylesheet' href='styles/entry.css'><h2>【正】</h2><p>正直；端正。</p><div class='example'><p>例句：正其衣冠。</p></div>" +
            (1..35).joinToString("") { "<p>$it. 古文释义与出处，保留词典层级和阅读锚点。</p>" }
        val state = EnglishLearningState(dictionaries = listOf(LocalDictionary(id, "汉语大词典", 1)),
            hit = DictionaryLookupHit("正", "正其衣冠", 0, 0), definitions = listOf(DictionaryDefinition(id, "汉语大词典", html)))
        val dark = mutableStateOf(false)
        val repository = LocalDictionaryRepository(context)
        compose.setContent {
            MoReadTheme(AppearanceSettings(themeMode = if (dark.value) ThemeMode.DARK else ThemeMode.LIGHT)) {
                DictionaryLookupSheet(state, companionChatPalette(), emptyList(), {}, {}, { _, _ -> }, {}, {}) { entry, modifier ->
                    val view = LocalView.current
                    SideEffect { root = view }
                    DictionaryWebContent(entry, dark.value, repository, {}, modifier)
                }
            }
        }
        waitForPage()
        val viewport = compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot
        capture("dictionary-original-light.png")
        val light = computed()
        assertEquals("30px", light.getString(0))
        assertEquals("19px", light.getString(1))
        assertEquals("rgb(0, 128, 128)", light.getString(2))
        compose.onNodeWithTag("dictionary-definition").performTouchInput { swipeUp(durationMillis = 150) }
        compose.waitUntil(5000) { compose.runOnIdle { web(root)?.scrollY ?: 0 } > 0 }
        settleScroll()
        val anchor = compose.runOnIdle { web(root)!!.scrollY }
        compose.runOnIdle { dark.value = true }
        waitForPage()
        compose.waitUntil(5000) { compose.runOnIdle { kotlin.math.abs(web(root)!!.scrollY - anchor) <= 1 } }
        val night = computed()
        assertEquals("30px", night.getString(0))
        assertNotEquals(night.getString(2), night.getString(3))
        assertNotEquals("rgba(0, 0, 0, 0)", night.getString(4))
        assertNotEquals("rgb(255, 255, 255)", night.getString(4))
        val after = compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(viewport.top, after.top, 1f)
        assertEquals(viewport.bottom, after.bottom, 1f)
        compose.runOnIdle { web(root)!!.scrollTo(0, 0) }
        capture("dictionary-original-dark.png")
    }

    private fun web(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { web(view.getChildAt(it)) }
        else -> null
    }
    private fun waitForPage() {
        compose.waitUntil(10_000) { compose.runOnIdle { web(root)?.let { it.progress == 100 && it.contentHeight > 0 } == true } }
    }
    private fun computed(): JSONArray {
        val latch = CountDownLatch(1)
        var response = ""
        compose.runOnIdle {
            val view = web(root)!!
            // Trusted test-only DOM inspection; publisher scripts remain disabled in the app.
            view.settings.javaScriptEnabled = true
            view.evaluateJavascript("JSON.stringify([getComputedStyle(document.querySelector('h2')).fontSize,getComputedStyle(document.querySelector('p')).fontSize,getComputedStyle(document.querySelector('h2')).color,getComputedStyle(document.querySelector('p')).color,getComputedStyle(document.querySelector('.example')).backgroundColor])") {
                response = it
                view.settings.javaScriptEnabled = false
                latch.countDown()
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return JSONArray(JSONTokener(response).nextValue() as String)
    }
    private fun settleScroll() {
        var last = -1
        var changed = SystemClock.uptimeMillis()
        compose.waitUntil(5000) {
            val value = compose.runOnIdle { web(root)!!.scrollY }
            if (value != last) { last = value; changed = SystemClock.uptimeMillis() }
            SystemClock.uptimeMillis() - changed > 250
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), name)
        output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
