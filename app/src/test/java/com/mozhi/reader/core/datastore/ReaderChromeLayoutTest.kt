package com.mozhi.reader.core.datastore

import android.app.Application
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mozhi.reader.core.datastore.ReaderChromeItem.*
import com.mozhi.reader.core.datastore.ReaderChromeSlot.*
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ReaderChromeLayoutTest {
    private val info = ReaderChromeInfo("山海札记", "第三章 归途", "3 / 12 页", .376f, .1234f, "08:09")

    @Test
    fun `默认槽位还原现有页眉页脚`() {
        val expected = mapOf(
            HEADER_LEFT to listOf(CHAPTER_TITLE),
            HEADER_CENTER to emptyList(),
            HEADER_RIGHT to emptyList(),
            FOOTER_LEFT to listOf(CHAPTER_PAGE),
            FOOTER_CENTER to emptyList(),
            FOOTER_RIGHT to listOf(BOOK_PERCENT, TIME, BATTERY)
        )
        assertEquals(expected, ReaderChromeLayout.DEFAULT_SLOTS)
        assertEquals(expected, ReaderChromeLayout.DEFAULT.slots)
        assertEquals(ReaderChromeLayout.DEFAULT, ReaderSettings().chromeLayout)
        assertFalse(ReaderChromeLayout.DEFAULT.hideClockWithStatusBar)
        ReaderChromeSlot.entries.forEach { slot ->
            assertEquals(expected.getValue(slot), ReaderChromeLayout.DEFAULT.items(slot))
            assertEquals(slot in listOf(HEADER_LEFT, HEADER_CENTER, HEADER_RIGHT), slot.isHeader)
        }
    }

    @Test
    fun `槽位读取按枚举去重且缺失槽位为空`() {
        val layout = ReaderChromeLayout(mapOf(HEADER_CENTER to listOf(TIME, BOOK_TITLE, TIME, CHAPTER_PERCENT)))
        assertEquals(listOf(BOOK_TITLE, CHAPTER_PERCENT, TIME), layout.items(HEADER_CENTER))
        assertTrue(layout.items(FOOTER_LEFT).isEmpty())
    }

    @Test
    fun `带版本的编码支持默认自定义和全空布局往返`() {
        val custom = ReaderChromeLayout.DEFAULT.with(HEADER_CENTER, listOf(BOOK_TITLE, TIME))
            .copy(hideClockWithStatusBar = true)
        val empty = ReaderChromeLayout(ReaderChromeSlot.entries.associateWith { emptyList() })
        val sparse = ReaderChromeLayout(mapOf(HEADER_CENTER to listOf(BOOK_TITLE, TIME)))
        listOf(ReaderChromeLayout.DEFAULT, custom, empty, sparse, ReaderChromeLayout(emptyMap())).forEach { layout ->
            val raw = ReaderChromeLayout.encode(layout)
            assertTrue(raw.contains("\"version\":1"))
            assertEquals(layout, ReaderChromeLayout.decode(raw))
        }
    }

    @Test
    fun `空输入和损坏配置回落默认`() {
        listOf(null, "", "  ", "not-json", "{", "null", "[]", "42", "{}", """{"slots":[]} """).forEach { raw ->
            assertEquals(raw, ReaderChromeLayout.DEFAULT, ReaderChromeLayout.decode(raw))
        }
    }

    @Test
    fun `未知名称和局部损坏被跳过且重复项由先出现的槽位保留`() {
        val layout = ReaderChromeLayout.decode("""{
            "version":2,"hideClockWithStatusBar":true,"future":{},
            "slots":{
                "FUTURE_SLOT":["BOOK_TITLE"],
                "FOOTER_RIGHT":["TIME","FUTURE_ITEM",null,{},7,"BOOK_TITLE","TIME"],
                "HEADER_LEFT":["BOOK_TITLE","CHAPTER_TITLE"],
                "HEADER_CENTER":"损坏",
                "FOOTER_LEFT":["CHAPTER_PAGE"]
            }
        }""")
        assertEquals(listOf(BOOK_TITLE, TIME), layout.items(FOOTER_RIGHT))
        assertEquals(listOf(CHAPTER_TITLE), layout.items(HEADER_LEFT))
        assertEquals(listOf(CHAPTER_PAGE), layout.items(FOOTER_LEFT))
        assertTrue(layout.items(HEADER_CENTER).isEmpty())
        assertTrue(layout.hideClockWithStatusBar)
        assertEquals(layout, ReaderChromeLayout.decode(ReaderChromeLayout.encode(layout)))
        assertFalse(ReaderChromeLayout.decode("""{"slots":{},"hideClockWithStatusBar":{}}""").hideClockWithStatusBar)
    }

    @Test
    fun `设置槽位会移动其它槽位的同项并按枚举去重`() {
        val original = ReaderChromeLayout.DEFAULT
        val moved = original.with(HEADER_CENTER, listOf(BATTERY, CHAPTER_TITLE, TIME, TIME))
        assertEquals(listOf(CHAPTER_TITLE, TIME, BATTERY), moved.items(HEADER_CENTER))
        assertTrue(moved.items(HEADER_LEFT).isEmpty())
        assertEquals(listOf(BOOK_PERCENT), moved.items(FOOTER_RIGHT))
        assertEquals(listOf(CHAPTER_PAGE), moved.items(FOOTER_LEFT))
        assertEquals(listOf(CHAPTER_TITLE), original.items(HEADER_LEFT))
        assertTrue(moved.with(HEADER_CENTER, emptyList()).items(HEADER_CENTER).isEmpty())
    }

    @Test
    fun `状态栏与隐藏开关的四种组合只过滤时间电量`() {
        listOf(false, true).forEach { hide ->
            listOf(false, true).forEach { statusBarVisible ->
                val layout = ReaderChromeLayout(mapOf(
                    HEADER_RIGHT to listOf(TIME, BATTERY),
                    FOOTER_CENTER to listOf(BATTERY)
                ), hideClockWithStatusBar = hide)
                val hidden = hide && statusBarVisible
                assertEquals(if (hidden) emptyList<ReaderChromeItem>() else listOf(TIME, BATTERY),
                    layout.visibleItems(HEADER_RIGHT, statusBarVisible))
                assertEquals(if (hidden) emptyList<ReaderChromeItem>() else listOf(BATTERY),
                    layout.visibleItems(FOOTER_CENTER, statusBarVisible))
                assertEquals(hidden, layout.headerEmpty(statusBarVisible))
                assertEquals(hidden, layout.footerEmpty(statusBarVisible))
                val withContent = layout.with(HEADER_LEFT, listOf(CHAPTER_TITLE)).with(FOOTER_RIGHT, listOf(BOOK_PERCENT))
                assertFalse(withContent.headerEmpty(statusBarVisible))
                assertFalse(withContent.footerEmpty(statusBarVisible))
                assertEquals(listOf(BOOK_PERCENT), withContent.visibleItems(FOOTER_RIGHT, statusBarVisible))
            }
        }
        assertTrue(ReaderChromeLayout(emptyMap()).headerEmpty(false))
        assertTrue(ReaderChromeLayout(emptyMap()).footerEmpty(true))
    }

    @Test
    fun `文字按传入顺序拼接且百分比不随地区变化`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("山海札记 · 第三章 归途 · 3 / 12 页 · 38% · 12.3% · 08:09",
                ReaderChromeText.text(ReaderChromeItem.entries, info))
            assertEquals("08:09 · 山海札记", ReaderChromeText.text(listOf(TIME, BOOK_TITLE), info))
            assertEquals("0% · 0.0%", ReaderChromeText.text(listOf(CHAPTER_PERCENT, BOOK_PERCENT),
                info.copy(chapterProgress = 0f, bookProgress = 0f)))
            assertEquals("100% · 100.0%", ReaderChromeText.text(listOf(CHAPTER_PERCENT, BOOK_PERCENT),
                info.copy(chapterProgress = 1f, bookProgress = 1f)))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `缺失进度空白文字与电池不产生文字或多余分隔符`() {
        val missing = info.copy(bookTitle = "  ", chapterTitle = "\n", pageLabel = "", chapterProgress = null, timeText = " ")
        assertEquals("12.3%", ReaderChromeText.text(ReaderChromeItem.entries, missing))
        assertEquals("", ReaderChromeText.text(listOf(CHAPTER_PERCENT, BATTERY), missing))
        assertEquals("", ReaderChromeText.text(ReaderChromeItem.entries,
            missing.copy(chapterProgress = Float.NaN, bookProgress = Float.POSITIVE_INFINITY)))
        assertEquals("", ReaderChromeText.text(emptyList(), info))
        assertTrue(ReaderChromeText.hasBattery(listOf(BATTERY)))
        assertFalse(ReaderChromeText.hasBattery(listOf(TIME, BOOK_PERCENT)))
        assertFalse(ReaderChromeText.hasBattery(emptyList()))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReaderChromeLayoutPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun store(scope: CoroutineScope) = PreferenceDataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer,
            producePath = { File(temporary.root, "chrome.preferences_pb").absolutePath.toPath() }), scope = scope)

    @Test
    fun `全局布局持久化后重新打开与热缓存均可读取`() = runTest {
        val expected = ReaderChromeLayout.DEFAULT.with(HEADER_CENTER, listOf(BOOK_TITLE, TIME))
            .copy(hideClockWithStatusBar = true)
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = ReaderSettingsRepository(store(firstScope))
            assertEquals(ReaderChromeLayout.DEFAULT, repository.settings.first().chromeLayout)
            repository.setChromeLayout(expected)
            assertEquals(expected, repository.settings.first().chromeLayout)
            withContext(Dispatchers.IO) {
                withTimeout(5_000) { repository.cachedSettings.first { it.chromeLayout == expected } }
            }
        } finally {
            firstScope.coroutineContext.job.cancelAndJoin()
        }
        val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val data = store(reopenedScope)
            val repository = ReaderSettingsRepository(data)
            assertEquals(expected, repository.settings.first().chromeLayout)
            assertEquals(expected, ReaderChromeLayout.decode(data.data.first()[stringPreferencesKey("reader_chrome_layout")]))
            data.edit { it[stringPreferencesKey("reader_chrome_layout")] = "损坏的布局" }
            assertEquals(ReaderChromeLayout.DEFAULT, repository.settings.first().chromeLayout)
        } finally {
            reopenedScope.coroutineContext.job.cancelAndJoin()
        }
    }
}
