package com.mozhi.reader.feature.reader.render

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.mozhi.reader.core.datastore.PageMode
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.WidePageLayout
import com.mozhi.reader.feature.reader.SpreadLayoutPolicy
import com.mozhi.reader.feature.reader.ReaderPalette
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReaderColumnStyleTest {
    private val palette = ReaderPalette(
        background = Color.White,
        onBackground = Color.Black,
        muted = Color.Gray,
        glass = Color.White,
        glassStrong = Color.White,
        glassBorder = Color.LightGray,
        accent = Color.Blue,
        accentContainer = Color.LightGray,
        onAccent = Color.White,
        scrim = Color.Black,
        isDark = false
    )

    private fun style(width: Int): ReaderPageStyle {
        val settings = ReaderSettings()
        return ReaderPageStyle.resolve(settings, palette, Density(1f),
            width, 800, 0f, 0f, SpreadLayoutPolicy.singleColumnWidthDp(width.toFloat()))
    }

    @Test
    fun `1280 single surface centers the same640 column without capping bitmap or gestures`() {
        val wide = style(1280)
        val column = style(640)
        assertEquals(1280, wide.viewWidth)
        assertEquals(column.spec.visibleWidth, wide.spec.visibleWidth, 0f)
        assertEquals(column.paddingLeft + 320f, wide.paddingLeft, 0f)
        assertEquals(column.paddingRight + 320f, wide.paddingRight, 0f)
        assertTrue(SpreadLayoutPolicy.resolve(WidePageLayout.DUAL, 1280f, 1280f, PageMode.PAGINATED))
    }

    @Test
    fun `phone and narrow companion pane are never widened or shifted outside available space`() {
        val phone = style(400)
        val pane = style(624)
        assertEquals(phone.paddingLeft, pane.paddingLeft, 0f)
        assertEquals(224f, pane.spec.visibleWidth - phone.spec.visibleWidth, 0f)
        assertEquals(400f, SpreadLayoutPolicy.singleColumnWidthDp(400f), 0f)
        assertEquals(640f, SpreadLayoutPolicy.singleColumnWidthDp(1280f), 0f)
    }
}
