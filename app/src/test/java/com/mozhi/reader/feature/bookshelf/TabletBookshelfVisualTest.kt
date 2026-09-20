package com.mozhi.reader.feature.bookshelf

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.imageDecoderEnabled
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.datastore.ShelfLayout
import com.mozhi.reader.ui.*
import com.mozhi.reader.ui.components.MoReadBackdrop
import com.mozhi.reader.ui.theme.*
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TabletShelfVisualApplication::class, qualifiers = "w1400dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabletBookshelfVisualTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: View
    private lateinit var loader: ImageLoader
    private var filter by mutableStateOf(ShelfFilter())
    private var viewport by mutableStateOf<Int?>(null)
    private var query by mutableStateOf("")
    private var layout by mutableStateOf(ShelfLayout.GRID)
    private var revision by mutableIntStateOf(0)
    private var openedBook: Long? = null
    private var imported = false

    @OptIn(DelicateCoilApi::class)
    @Before fun imageLoader() {
        val app = ApplicationProvider.getApplicationContext<TabletShelfVisualApplication>()
        loader = app.newImageLoader(app)
        SingletonImageLoader.setUnsafe(loader)
    }

    @OptIn(DelicateCoilApi::class)
    @After fun close() { SingletonImageLoader.reset(); loader.shutdown() }

    private fun show(dark: Boolean = false) {
        val titles = listOf("长安的荔枝", "山茶文具店", "人类群星闪耀时", "夜航西飞", "乡土中国", "看不见的城市",
            "局外人", "城南旧事", "瓦尔登湖", "月亮与六便士", "活着", "悉达多", "小王子", "草木有本心", "树上的男爵", "山居笔记", "苏东坡传", "一个人的朝圣")
        val authors = listOf("马伯庸", "小川糸", "斯蒂芬·茨威格", "柏瑞尔·马卡姆", "费孝通", "伊塔洛·卡尔维诺",
            "阿尔贝·加缪", "林海音", "亨利·戴维·梭罗", "威廉·萨默塞特·毛姆", "余华", "赫尔曼·黑塞", "安托万·德·圣埃克苏佩里", "周华诚", "伊塔洛·卡尔维诺", "余秋雨", "林语堂", "蕾秋·乔伊斯")
        val books = titles.mapIndexed { index, title ->
            BookEntity(id = index + 1L, title = title, author = authors[index], coverPath = cover(index, title, authors[index]),
                epubPath = "", sourceType = BookSourceType.EPUB, importedAt = 1, totalChapters = 30,
                lastReadAt = if (index < 7) 100L - index else 0L,
                lastReadChapterIndex = when (index) { 0 -> 10; in 3..6 -> 29; else -> 4 },
                reachedEnd = index in 3..6, groupId = index % 3 + 1L)
        }
        val groups = listOf("文学与小说", "历史与社会", "随笔与散文").mapIndexed { index, name -> ShelfGroupEntity(index + 1L, name, createdAt = 1) }
        val tags = listOf("值得重读", "睡前阅读", "旅行途中").mapIndexed { index, name -> BookTagEntity(index + 1L, name, "blue", createdAt = 1) }
        val refs = books.map { BookTagRefEntity(it.id, it.id % 3 + 1) }
        val app = ApplicationProvider.getApplicationContext<Application>()
        books.forEach { book ->
            val result = runBlocking { loader.execute(ImageRequest.Builder(app).data(File(book.coverPath!!)).allowHardware(false).build()) }
            assertTrue("Fixture cover must decode", result is SuccessResult)
        }
        compose.setContent {
            root = LocalView.current.rootView
            MoReadTheme(AppearanceSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
                Box(if (viewport == null) Modifier.fillMaxSize() else Modifier.width(viewport!!.dp).fillMaxHeight()) {
                    MoReadBackdrop {
                        MoReadWindowLayout { window ->
                            val haze = rememberHazeState()
                            val expanded = window == MoReadWindowWidth.EXPANDED
                            val nav = rememberNavController()
                            val entry by nav.currentBackStackEntryAsState()
                            val selected = entry?.destination?.route
                            val state = BookshelfUiState(
                                books = filterShelfBooks(books, refs, filter), allBooks = books, totalBooks = books.size,
                                filter = filter, layout = layout, groups = groups, tags = tags, tagRefs = refs,
                                groupCounts = books.groupingBy { it.groupId }.eachCount(),
                                tagCounts = refs.groupingBy { it.tagId }.eachCount(), recentBook = books.first(),
                                recentChapterTitle = "第十一章 · 一骑红尘" + if (revision == 0) "" else " "
                            )
                            val select: (RootDestination) -> Unit = { destination ->
                                nav.navigate(destination.route) {
                                    popUpTo("bookshelf") { saveState = true }; launchSingleTop = true; restoreState = true
                                }
                            }
                            MoReadNavigationScaffold { padding ->
                                MoReadNavigationHost(nav) {
                                    rootComposable("bookshelf", expanded, window == MoReadWindowWidth.MEDIUM) {
                                        Box(Modifier.padding(padding)) {
                                            val visibleBooks = state.books.filter { it.title.contains(query) || it.author.contains(query) }
                                            val drag = remember { ShelfCollectionDragState() }
                                            val entries = visibleBooks.map { ShelfEntry.Book(it) }
                                            if (layout == ShelfLayout.GRID) BookGrid(entries, visibleBooks.size, state, query, { query = it },
                                                { openedBook = it }, { openedBook = it.book.id }, {}, { _, _ -> }, drag, { _, _ -> },
                                                { layout = it }, { filter = filter.copy(readState = it) },
                                                { id, ungrouped -> filter = filter.copy(groupId = id, ungroupedOnly = ungrouped) },
                                                {}, {}, {}, { filter = ShelfFilter() }, {}, {}, {}, { imported = true })
                                            else BookList(entries, visibleBooks.size, state, query, { query = it },
                                                { openedBook = it }, { openedBook = it.book.id }, {}, { _, _ -> }, drag, { _, _ -> },
                                                { layout = it }, { filter = filter.copy(readState = it) },
                                                { id, ungrouped -> filter = filter.copy(groupId = id, ungroupedOnly = ungrouped) },
                                                {}, {}, {}, { filter = ShelfFilter() }, {}, {}, {}, { imported = true })
                                        }
                                    }
                                    rootComposable("stats", expanded, window == MoReadWindowWidth.MEDIUM) { Text("阅读统计") }
                                    rootComposable("companion", expanded, window == MoReadWindowWidth.MEDIUM) { Text("AI 伴读") }
                                    rootComposable("settings", expanded, window == MoReadWindowWidth.MEDIUM) { Text("设置") }
                                }
                            }
                            if (expanded) MoReadTabletSidebar(selected, state, select,
                                onReadState = { filter = filter.copy(readState = it); select(RootDestination.Bookshelf) },
                                onGroup = { id, ungrouped -> filter = filter.copy(groupId = id, ungroupedOnly = ungrouped); select(RootDestination.Bookshelf) },
                                onTag = { id -> filter = filter.copy(tagIds = if (id in filter.tagIds) filter.tagIds - id else filter.tagIds + id) },
                                onClearFilters = { filter = ShelfFilter(); select(RootDestination.Bookshelf) },
                                onManageGroups = {}, onManageTags = {})
                            else MoReadNavigationDock(true, haze, window == MoReadWindowWidth.MEDIUM, selected,
                                Modifier.align(if (window == MoReadWindowWidth.MEDIUM) Alignment.CenterStart else Alignment.BottomCenter), select)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test @Config(qualifiers = "w800dp-h1200dp-mdpi")
    fun mediumTabletUsesCompactRailAndAdaptiveCovers() {
        show()
        capture("tablet-library-medium.png")
        compose.onNodeWithText("全部书籍").assertIsDisplayed()
    }

    @Test fun landscapeLibraryRendersAndFiltersWithoutMovingSidebar() {
        show()
        capture("tablet-library-landscape-light.png")
        val sidebar = compose.onNodeWithTag("tablet-sidebar").fetchSemanticsNode().boundsInRoot
        assertEquals(224f, sidebar.width, .5f)
        compose.onNodeWithTag("tablet-resume-1").performClick()
        assertEquals(1L, openedBook)
        compose.onNodeWithContentDescription("导入书籍").performClick()
        assertTrue(imported)
        compose.onNodeWithTag("tablet-filter-READING").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(BookReadState.READING, filter.readState) }
        compose.onNodeWithTag("tablet-filter-all").performClick()
        compose.onNodeWithTag("tablet-group-1").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(1L, filter.groupId) }
        compose.onNodeWithTag("tablet-filter-all").performClick()
        compose.onNodeWithTag("shelf-search").performTextInput("局外人")
        compose.onNodeWithText("搜索结果").assertIsDisplayed()
        compose.onNodeWithText("1 本").assertIsDisplayed()
        assertEquals(sidebar, compose.onNodeWithTag("tablet-sidebar").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("shelf-search").performTextClearance()
        switchToList()
        capture("tablet-library-list-light.png")
    }

    @Test fun darkLibraryAndListUseTheSameState() {
        show(dark = true)
        capture("tablet-library-landscape-dark.png")
        switchToList()
        compose.runOnIdle { assertEquals(ShelfLayout.LIST, layout) }
        capture("tablet-library-list-dark.png")
    }

    @Test @Config(qualifiers = "w900dp-h1200dp-mdpi") fun portraitKeepsReadableContentBesideSidebar() {
        show()
        compose.onNodeWithTag("tablet-resume-1").assertIsDisplayed()
        compose.onNodeWithTag("tablet-resume-2").assertDoesNotExist()
        val toolbar = compose.onNodeWithTag("tablet-library-toolbar").fetchSemanticsNode().boundsInRoot
        assertTrue(toolbar.left >= 224f)
        compose.onNodeWithTag("shelf-search").assertIsDisplayed()
        capture("tablet-library-portrait-light.png")
    }

    @Test fun pinnedSearchAndReadingRefreshKeepTheShelfAnchor() {
        show()
        compose.onNodeWithTag("shelf-scroll").performScrollToIndex(14)
        val sidebar = compose.onNodeWithTag("tablet-sidebar").fetchSemanticsNode().boundsInRoot
        val scroll = compose.onNodeWithTag("shelf-scroll")
        val anchor = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.runOnIdle { revision++ }
        assertEquals(anchor, scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
        assertEquals(sidebar, compose.onNodeWithTag("tablet-sidebar").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("shelf-search").assertIsDisplayed()
    }

    @Test fun narrowWindowRetainsQueryAndFilterAndRestoresSidebar() {
        show()
        compose.onNodeWithTag("tablet-filter-READING").performClick()
        compose.onNodeWithTag("shelf-search").performTextInput("长安")
        compose.runOnIdle { viewport = 640 }
        compose.onNodeWithTag("tablet-sidebar").assertDoesNotExist()
        compose.runOnIdle { assertEquals("长安", query); assertEquals(BookReadState.READING, filter.readState) }
        compose.runOnIdle { viewport = null }
        compose.onNodeWithTag("tablet-filter-READING").assertIsSelected()
        compose.onNodeWithText("1 本").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w411dp-h891dp-mdpi") fun phoneRetainsItsExistingLayout() {
        show()
        compose.onNodeWithTag("tablet-sidebar").assertDoesNotExist()
        compose.onNodeWithText("宜读书", substring = true).assertIsDisplayed()
        capture("phone-library-light.png")
    }

    @Test @Config(qualifiers = "w411dp-h891dp-mdpi") fun phoneListShowsUnboxedRows() {
        layout = ShelfLayout.LIST
        show()
        compose.onNodeWithTag("shelf-scroll").performScrollToIndex(4)
        compose.onNodeWithTag("shelf-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -64f) }
        capture("phone-library-list-light.png")
    }

    private fun switchToList() {
        compose.onNodeWithTag("shelf-scroll").performScrollToIndex(0)
        compose.onNodeWithContentDescription("视图与筛选").performClick()
        compose.onNodeWithText("布局").performClick()
        compose.onNodeWithText("列表").performClick()
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File("build/reports/tablet-ui/$name").apply { requireNotNull(parentFile).mkdirs() }.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    /** Original graphic covers for demonstration data; no production books or files are modified. */
    private fun cover(index: Int, title: String, author: String): String {
        val file = File("build/reports/tablet-ui/fixtures/cover-$index.png").absoluteFile
        requireNotNull(file.parentFile).mkdirs()
        val bitmap = Bitmap.createBitmap(320, 460, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val palette = listOf(0xFF6E3039, 0xFFD3DDCA, 0xFFE6D6BC, 0xFF294856, 0xFFB59770, 0xFF496354,
            0xFFCC773A, 0xFFC8D6DB, 0xFF193D37, 0xFF2D4467, 0xFF9A3B2D, 0xFFDDD0AA)
        canvas.drawColor(palette[index % palette.size].toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val ink = if (index % 12 in listOf(1, 2, 4, 7, 11)) Color.rgb(40, 45, 39) else Color.rgb(251, 244, 228)
        paint.color = Color.argb(32, 255, 255, 255)
        canvas.drawCircle(240f, 320f, 160f, paint)
        paint.color = Color.argb(40, 0, 0, 0)
        canvas.drawRect(0f, 0f, 12f, 460f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.3f
        paint.color = Color.argb(85, Color.red(ink), Color.green(ink), Color.blue(ink))
        if (index % 3 == 0) {
            repeat(7) { canvas.drawOval(70f + it * 12, 250f - it * 7, 260f + it * 8, 420f - it * 7, paint) }
        } else if (index % 3 == 1) {
            repeat(8) { canvas.drawLine(45f, 240f + it * 17, 275f, 285f + it * 12, paint) }
            canvas.drawCircle(215f, 258f, 44f, paint)
        } else {
            repeat(5) { canvas.drawRect(50f + it * 21, 255f - it * 13, 240f + it * 8, 387f - it * 8, paint) }
        }
        paint.style = Paint.Style.FILL
        paint.color = ink
        paint.typeface = Typeface.create("serif", Typeface.BOLD)
        paint.textSize = if (title.length in 5..6) 40f else 43f
        (if (title.length <= 6) listOf(title) else title.chunked(4)).forEachIndexed { row, text ->
            canvas.drawText(text, 30f, 89f + row * 59, paint)
        }
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = 16f
        canvas.drawText(author, 32f, 195f, paint)
        paint.textSize = 10f
        canvas.drawText("M O R E A D   /   R E A D I N G   C O L L E C T I O N", 32f, 428f, paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file.path
    }
}

class TabletShelfVisualApplication : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context).imageDecoderEnabled(false).build()
}
