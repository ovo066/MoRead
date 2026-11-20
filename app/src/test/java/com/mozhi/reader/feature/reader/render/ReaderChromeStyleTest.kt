package com.mozhi.reader.feature.reader.render

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.mozhi.reader.core.datastore.ReaderChromeItem
import com.mozhi.reader.core.datastore.ReaderChromeLayout
import com.mozhi.reader.core.datastore.ReaderChromeSlot
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.feature.reader.ReaderPalette
import com.mozhi.reader.feature.reader.engine.RenderPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 页眉页脚分项：没东西可画的一条按隐藏处理，宽边距放宽后左右能真正贴到 0。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReaderChromeStyleTest {
    private val palette = ReaderPalette(
        background = Color.White, onBackground = Color.Black, muted = Color.Gray, glass = Color.White,
        glassStrong = Color.White, glassBorder = Color.LightGray, accent = Color.Blue,
        accentContainer = Color.LightGray, onAccent = Color.White, scrim = Color.Black, isDark = false
    )

    private fun style(settings: ReaderSettings, title: String = "") =
        ReaderPageStyle.resolve(settings, palette, Density(1f), 400, 800, 24f, 16f, bookTitle = title)

    @Test fun `默认布局与旧版页眉页脚几何一致`() {
        val style = style(ReaderSettings())
        assertTrue(style.showHeader)
        assertTrue(style.showFooter)
        assertEquals(ReaderChromeLayout.DEFAULT, style.chromeLayout)
    }

    @Test fun `页眉三格都空时把页眉那一条还给正文`() {
        val shown = style(ReaderSettings())
        val empty = ReaderChromeLayout.DEFAULT.with(ReaderChromeSlot.HEADER_LEFT, emptyList())
        val hidden = style(ReaderSettings(chromeLayout = empty))
        assertFalse(hidden.showHeader)
        assertTrue(hidden.contentTop < shown.contentTop)
        assertEquals(style(ReaderSettings(showHeader = false)).contentTop, hidden.contentTop, 0f)
    }

    @Test fun `页脚只放时间电量且状态栏可见时自动隐藏`() {
        val clockOnly = ReaderChromeLayout.DEFAULT
            .with(ReaderChromeSlot.FOOTER_LEFT, emptyList())
            .with(ReaderChromeSlot.FOOTER_RIGHT, listOf(ReaderChromeItem.TIME, ReaderChromeItem.BATTERY))
            .copy(hideClockWithStatusBar = true)
        assertFalse(style(ReaderSettings(chromeLayout = clockOnly, immersiveReading = false)).showFooter)
        assertTrue(style(ReaderSettings(chromeLayout = clockOnly, immersiveReading = true)).showFooter)
    }

    @Test fun `左右边距放宽到负值时贴到屏幕边缘为止`() {
        val tight = style(ReaderSettings(pageMarginLeft = -1.4f, pageMarginRight = -1.4f))
        assertEquals(0f, tight.paddingLeft, 0.01f)
        assertEquals(0f, tight.paddingRight, 0.01f)
        assertEquals(400f, tight.contentWidth, 0.01f)
        assertEquals(0f, ReaderPageStyle.horizontalMarginDp(-3f), 0f)
    }

    @Test fun `各种槽位组合都能画出来且不越界`() {
        val layouts = listOf(
            ReaderChromeLayout.DEFAULT,
            ReaderChromeLayout.DEFAULT
                .with(ReaderChromeSlot.HEADER_CENTER, listOf(ReaderChromeItem.BOOK_TITLE))
                .with(ReaderChromeSlot.HEADER_RIGHT, listOf(ReaderChromeItem.TIME, ReaderChromeItem.BATTERY))
                .with(ReaderChromeSlot.FOOTER_CENTER, listOf(ReaderChromeItem.CHAPTER_PERCENT, ReaderChromeItem.CHAPTER_PAGE))
                .with(ReaderChromeSlot.FOOTER_RIGHT, listOf(ReaderChromeItem.BOOK_PERCENT))
        )
        for (layout in layouts) {
            val style = style(ReaderSettings(chromeLayout = layout), title = "一本书名特别特别长的小说，长到一行放不下也要能截断")
            val renderer = PageBitmapRenderer(style)
            val page = RenderPage.Placeholder(chapterIndex = 0, chapterTitle = "第一章 很长很长很长很长很长很长很长很长很长的章名")
            val bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
            renderer.render(page, bitmap, bookProgress = .375f, timeText = "14:20", batteryPercent = 80)
        }
    }
}
