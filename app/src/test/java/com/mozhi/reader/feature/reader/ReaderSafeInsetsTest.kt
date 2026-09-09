package com.mozhi.reader.feature.reader

import android.app.Application
import android.graphics.Rect
import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Compose window-inset updates, including hidden status bars and physical cutouts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReaderSafeInsetsTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var view: View
    private lateinit var measured: ReaderSafeInsets
    private var visibleStatusTop = -1

    private fun mount() = compose.setContent {
        val currentView = LocalView.current
        val safe = readerSafeInsets()
        val visibleTop = WindowInsets.statusBars.getTop(LocalDensity.current)
        SideEffect {
            view = currentView
            measured = safe
            visibleStatusTop = visibleTop
        }
    }

    private fun dispatch(statusVisible: Boolean, statusTop: Int = 24, cutoutTop: Int = 0, navigationBottom: Int = 32) {
        compose.runOnIdle {
            val status = Insets.of(0, statusTop, 0, 0)
            val navigation = Insets.of(0, 0, 0, navigationBottom)
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), if (statusVisible) status else Insets.NONE)
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), status)
                .setVisible(WindowInsetsCompat.Type.statusBars(), statusVisible)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), navigation)
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), navigation)
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .setDisplayCutout(if (cutoutTop == 0) null else DisplayCutoutCompat(
                    Rect(0, cutoutTop, 0, 0), listOf(Rect(120, 0, 200, cutoutTop))
                ))
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        compose.waitForIdle()
    }

    @Test fun hidingStatusBarDoesNotCollapseOrChangeTheReaderSafeArea() {
        mount()
        dispatch(statusVisible = true)
        val visible = compose.runOnIdle {
            assertEquals(24, visibleStatusTop)
            assertEquals(ReaderSafeInsets(24f, 32f), measured)
            measured
        }
        dispatch(statusVisible = false)
        compose.runOnIdle {
            assertEquals(0, visibleStatusTop)
            assertEquals(visible, measured)
        }
    }

    @Test fun physicalCutoutStillWinsOverASmallerStatusBarWhenImmersive() {
        mount()
        dispatch(statusVisible = true, cutoutTop = 44)
        compose.runOnIdle { assertEquals(44f, measured.topPx, 0f) }
        dispatch(statusVisible = false, cutoutTop = 44)
        compose.runOnIdle {
            assertEquals(0, visibleStatusTop)
            assertEquals(ReaderSafeInsets(44f, 32f), measured)
        }
    }

    @Test fun realSystemGeometryChangesStillUpdateTheSafeArea() {
        mount()
        dispatch(statusVisible = false)
        val original = compose.runOnIdle { measured }
        dispatch(statusVisible = false, statusTop = 36, navigationBottom = 48)
        compose.runOnIdle {
            assertNotEquals(original, measured)
            assertEquals(ReaderSafeInsets(36f, 48f), measured)
        }
    }
}
