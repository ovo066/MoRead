package com.mozhi.reader.feature.stats

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.view.inspector.WindowInspector
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.imageDecoderEnabled
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.datastore.StatsWidget
import com.mozhi.reader.core.datastore.StatsWidgets
import com.mozhi.reader.ui.theme.AppearanceSettings
import com.mozhi.reader.ui.theme.MoReadTheme
import com.mozhi.reader.ui.theme.ThemeMode
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = StatsVisualApplication::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatsScreenVisualTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: View
    private lateinit var imageLoader: ImageLoader

    @OptIn(DelicateCoilApi::class)
    @Before fun installImageLoader() {
        val application = ApplicationProvider.getApplicationContext<StatsVisualApplication>()
        imageLoader = application.newImageLoader(application)
        // Coil can retain a loader created by an earlier Robolectric application in a full run.
        // Explicitly own this test's loader; the real PNG decode assertion below must stay enabled.
        SingletonImageLoader.setUnsafe(imageLoader)
    }

    @OptIn(DelicateCoilApi::class)
    @After fun clearImageLoader() {
        SingletonImageLoader.reset()
        if (::imageLoader.isInitialized) imageLoader.shutdown()
    }
    private val today = LocalDate.of(2026, 9, 13)
    private val fixture: StatsUiState get() = fixtureFor()
    private fun fixtureFor(period: StatsPeriod = StatsPeriod.MONTH): StatsUiState {
        val titles = listOf("长安的荔枝", "山茶文具店", "人类简史", "夜航西飞", "置身事内", "看不见的城市")
        val authors = listOf("马伯庸", "小川糸", "尤瓦尔·赫拉利", "柏瑞尔·马卡姆", "兰小欢", "伊塔洛·卡尔维诺")
        val books = titles.mapIndexed { i, title -> BookEntity(id = i + 1L, title = title, author = authors[i], coverPath = fixtureCover(i, title), epubPath = "",
            sourceType = BookSourceType.TXT, importedAt = 1, totalChapters = 30, reachedEnd = i == 1, lastReadAt = 1) }
        val days = (1..13).filterNot { it == 4 || it == 9 }.flatMap { day ->
            listOf(ReadingDailyEntity((day % 6) + 1L, today.withDayOfMonth(day).toEpochDay(), (19L + day % 5 * 13) * 60_000, 1),
                ReadingDailyEntity((day % 3) + 1L, today.withDayOfMonth(day).toEpochDay(), 15 * 60_000, 1))
        } + listOf(ReadingDailyEntity(1, today.minusMonths(1).toEpochDay(), 150 * 60_000, 1),
            ReadingDailyEntity(1, today.withDayOfMonth(7).toEpochDay(), 12 * 60_000, 1),
            ReadingDailyEntity(1, today.withDayOfMonth(8).toEpochDay(), 18 * 60_000, 1),
            ReadingDailyEntity(1, today.withDayOfMonth(10).toEpochDay(), 24 * 60_000, 1))
        val hours = (10..13).flatMap { day -> listOf(ReadingHourlyEntity(1, today.withDayOfMonth(day).toEpochDay(), 21, 25 * 60_000),
            ReadingHourlyEntity(1, today.withDayOfMonth(day).toEpochDay(), 7, 15 * 60_000), ReadingHourlyEntity(2, today.withDayOfMonth(day).toEpochDay(), 22, 20 * 60_000)) }
        val tags = listOf("文学", "历史", "小说", "旅行", "社会", "治愈", "城市", "人文").mapIndexed { i, name -> BookTagEntity(i + 1L, name, "blue", createdAt = 1) }
        val refs = books.flatMap { book -> listOf(BookTagRefEntity(book.id, book.id), BookTagRefEntity(book.id, 1), BookTagRefEntity(book.id, 8)) }
        return buildStatsState(days, books, 28, 16, StatsSelection(period, today), today, hours, tags, refs)
    }

    private fun show(
        dark: Boolean = false, input: () -> StatsUiState = { fixture }, onDay: (LocalDate) -> Unit = {},
        onVisible: (StatsWidget, Boolean) -> Unit = { _, _ -> }, onMove: (StatsWidget, Int) -> Unit = { _, _ -> },
        onDate: (LocalDate) -> Unit = {}
    ) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        input().monthTimeline.flatMap { it.books }.mapNotNull { it.book.coverPath }.distinct().forEach { path ->
            val result = runBlocking { context.imageLoader.execute(ImageRequest.Builder(context).data(File(path)).allowHardware(false).build()) }
            assertTrue("测试封面必须真实解码：$result", result is SuccessResult)
        }
        compose.setContent {
            val view = LocalView.current
            SideEffect { root = view.rootView }
            MoReadTheme(AppearanceSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
                StatsDashboard(input(), onDay = onDay, onWidgetVisible = onVisible, onMoveWidget = onMove, onDate = onDate)
            }
        }
    }

    @Test fun periodControlsHeatmapAndCoverCalendarHaveClearRoles() {
        var selected: LocalDate? = null
        var selectedMonth: LocalDate? = null
        show(onDay = { selected = it }, onDate = { selectedMonth = it })
        compose.onNodeWithText("我的阅读记录").assertDoesNotExist()
        val periods = compose.onNodeWithTag("stats-periods").fetchSemanticsNode().boundsInRoot
        val date = compose.onNodeWithTag("stats-date-range").fetchSemanticsNode().boundsInRoot
        assertTrue(date.top >= periods.bottom)
        compose.onNodeWithContentDescription("调整统计组件").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一周期").assertIsNotEnabled()
        val overview = compose.onNodeWithTag("stats-overview").fetchSemanticsNode().boundsInRoot
        val heatmap = compose.onNodeWithTag("stats-card-阅读热力").fetchSemanticsNode().boundsInRoot
        assertTrue("阅读热力必须紧接在当月阅读下面", heatmap.top >= overview.bottom && heatmap.top - overview.bottom <= 20f)
        assertTrue("七列热力格不能退回全年密集小格", compose.onNodeWithTag("heatmap-day-2026-09-11")
            .fetchSemanticsNode().boundsInRoot.width >= 28f)
        capture("stats-overview-light.png")
        compose.onNodeWithTag("heatmap-day-2026-09-11").performScrollTo().performClick()
        assertEquals(LocalDate.of(2026, 9, 11), selected)
        scrollTo("阅读月历")
        capture("stats-calendar-light.png")
        compose.onNodeWithTag("calendar-2026-09-11").performScrollTo().performClick()
        compose.onNodeWithText("当日阅读").assertIsDisplayed()
        fixture.monthTimeline.single { it.epochDay == LocalDate.of(2026, 9, 11).toEpochDay() }.books.forEach { stat ->
            compose.onNodeWithTag("timeline-book-${LocalDate.of(2026, 9, 11).toEpochDay()}-${stat.book.id}").assertIsDisplayed()
        }
        capture("stats-calendar-day-light.png", "navigation-viewport")
        compose.onNodeWithContentDescription("关闭阅读时间线").performClick()
        compose.onNodeWithTag("calendar-2026-09-30").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("月历下一月").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("月历上一月").performScrollTo().performClick()
        assertEquals(LocalDate.of(2026, 8, 1), selectedMonth)
        scrollTo("阅读时间线")
        val week = LocalDate.of(2026, 9, 7).toEpochDay()
        compose.onNodeWithTag("timeline-span-$week-1-0-1", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("timeline-span-$week-1-0-3", useUnmergedTree = true).assertDoesNotExist()
        capture("stats-timeline-light.png")
        scrollTo("阅读时间段")
        capture("stats-hours-light.png")
        scrollTo("标签云")
        capture("stats-clouds-light.png")
    }

    @Test fun componentSettingsDeliverVisibilityAndOrderingChanges() {
        val live = mutableStateOf(fixture)
        var moved: Pair<StatsWidget, Int>? = null
        show(input = { live.value }, onVisible = { widget, visible ->
            live.value = live.value.copy(widgets = live.value.widgets.copy(hidden = if (visible) emptySet() else setOf(widget)))
        }, onMove = { widget, direction -> moved = widget to direction })
        compose.onNodeWithContentDescription("调整统计组件").performClick()
        compose.onNodeWithTag("widget-toggle-CALENDAR").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithContentDescription("下移阅读月历").performClick()
        assertEquals(StatsWidget.CALENDAR to 1, moved)
        compose.onNodeWithContentDescription("关闭组件设置").performClick()
        assertFalse(StatsWidget.CALENDAR in live.value.widgets.visible)
        compose.onNodeWithContentDescription("调整统计组件").performClick()
        compose.onNodeWithTag("widget-toggle-CALENDAR").assertIsOff()
    }

    @Test fun totalDimensionShowsAllHistoryWithoutDateNavigation() {
        show(input = { fixtureFor(StatsPeriod.TOTAL) })
        compose.onNode(hasText("总") and hasClickAction()).assertIsSelected()
        compose.onNodeWithText("全部阅读记录").assertIsDisplayed()
        compose.onNodeWithContentDescription("上一周期").assertIsNotEnabled()
        compose.onNodeWithContentDescription("下一周期").assertIsNotEnabled()
        capture("stats-total-light.png")
    }

    @Test fun timelineUsesTheRealStableNavigationSheet() {
        val live = mutableStateOf(fixture)
        show(input = { live.value })
        scrollTo("阅读时间线")
        compose.onNodeWithText("查看全部 ${fixture.timeline.size} 天").performScrollTo().performClick()
        val sheet = compose.onNodeWithTag("navigation-viewport")
        val bounds = sheet.fetchSemanticsNode().boundsInRoot
        assertEquals(root.height.toFloat(), bounds.bottom, 1f)
        val headerTop = compose.onNodeWithTag("stats-history-header").fetchSemanticsNode().boundsInRoot.top
        capture("stats-timeline-sheet-light.png", "navigation-viewport")
        val list = compose.onNodeWithTag("stats-timeline-list")
        list.performScrollToIndex(0)
        repeat(3) { list.performTouchInput { swipeDown(durationMillis = 140) } }
        assertEquals(bounds.top, sheet.fetchSemanticsNode().boundsInRoot.top, .5f)
        assertEquals(headerTop, compose.onNodeWithTag("stats-history-header").fetchSemanticsNode().boundsInRoot.top, .5f)
        list.performScrollToIndex(fixture.timeline.lastIndex)
        repeat(3) { list.performTouchInput { swipeUp(durationMillis = 110) } }
        assertEquals(bounds.top, sheet.fetchSemanticsNode().boundsInRoot.top, .5f)
        list.performScrollToIndex(5)
        val anchor = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.runOnIdle {
            live.value = live.value.copy(timeline = live.value.timeline.map { day ->
                day.copy(books = day.books.map { it.copy(durationMs = it.durationMs + 1000) })
            })
        }
        assertEquals(anchor, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
        assertEquals(bounds.bottom, sheet.fetchSemanticsNode().boundsInRoot.bottom, .5f)
        compose.onNodeWithContentDescription("关闭阅读时间线").performClick()
    }

    @Test @Config(qualifiers = "w320dp-h740dp-mdpi") fun darkSmallScreensKeepTheHeatmapCalendarAndTimelineReadable() {
        show(dark = true)
        capture("stats-overview-dark-small.png")
        scrollTo("阅读热力")
        compose.onNodeWithTag("stats-card-阅读热力").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("heatmap-day-2026-09-11").fetchSemanticsNode().boundsInRoot.width >= 28f)
        capture("stats-heatmap-dark-small.png")
        scrollTo("阅读月历")
        assertTrue(compose.onNodeWithTag("calendar-2026-09-11").fetchSemanticsNode().boundsInRoot.height >= 58f)
        capture("stats-calendar-dark-small.png")
        scrollTo("阅读时间线")
        capture("stats-timeline-dark-small.png")
        compose.onNodeWithText("查看全部 ${fixture.timeline.size} 天").performScrollTo().performClick()
        val bounds = compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(root.height.toFloat(), bounds.bottom, 1f)
        capture("stats-timeline-sheet-dark-small.png", "navigation-viewport")
        compose.onNodeWithContentDescription("关闭阅读时间线").performClick()
        scrollTo("作者云")
        capture("stats-clouds-dark-small.png")
    }

    private fun scrollTo(title: String) = compose.onNodeWithTag("stats-list").performScrollToNode(hasTestTag("stats-card-$title"))
    private fun capture(name: String, tag: String? = null) {
        compose.waitForIdle()
        if (tag != null) compose.onNodeWithTag(tag).assertIsDisplayed()
        compose.runOnIdle {
            val target = if (tag == null) root else WindowInspector.getGlobalWindowViews().last { it.isShown && it !== root }
            val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
            target.draw(Canvas(bitmap))
            File("build/reports/ui-qa/$name").apply { requireNotNull(parentFile).mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun fixtureCover(index: Int, title: String): String {
        val file = File("build/reports/ui-qa/fixtures/stats-cover-$index.png").absoluteFile
        if (file.isFile) return file.path
        requireNotNull(file.parentFile).mkdirs()
        val bitmap = Bitmap.createBitmap(180, 260, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val palette = listOf(0xFF274C47, 0xFFE3C29A, 0xFF8B323D, 0xFF304E7A, 0xFFD1DAD1, 0xFFD7A94C)
        canvas.drawColor(palette[index % palette.size].toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(65, 255, 255, 255)
        canvas.drawCircle(160f, 210f, 98f, paint)
        paint.color = Color.argb(45, 0, 0, 0)
        canvas.drawRect(0f, 170f, 180f, 210f, paint)
        paint.color = if (index % 3 == 1) Color.rgb(40, 47, 43) else Color.WHITE
        paint.typeface = Typeface.create("serif", Typeface.BOLD)
        paint.textSize = 30f
        title.chunked(4).forEachIndexed { row, text -> canvas.drawText(text, 18f, 61f + row * 40, paint) }
        paint.textSize = 10f
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        canvas.drawText("READING COLLECTION", 18f, 234f, paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file.path
    }
}

class StatsVisualApplication : Application(), SingletonImageLoader.Factory {
    // Robolectric 的 Windows ImageDecoder 不支持真实解码；BitmapFactory 使用同一份 PNG。
    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .imageDecoderEnabled(false).build()
}
