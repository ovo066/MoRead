package com.mozhi.reader.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mozhi.reader.ui.components.MoReadBackdrop
import com.mozhi.reader.ui.theme.MoReadTheme
import com.mozhi.reader.ui.theme.AppearanceSettings
import com.mozhi.reader.ui.theme.ColorSchemePreset
import com.mozhi.reader.ui.theme.NavStyle
import com.mozhi.reader.ui.theme.ShapeStyle
import com.mozhi.reader.ui.theme.SurfaceStyle
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercise the production NavHost, Scaffold, per-entry rail and animated dock together. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationStabilityTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var nav: NavHostController
    private lateinit var view: View
    private val lists = mutableMapOf<String, LazyListState>()
    private val sizes = mutableMapOf<String, IntSize>()
    private val widthsInRoot = mutableMapOf<String, Float>()
    private val positions = mutableMapOf<String, Offset>()
    private val measuredSizes = mutableMapOf<String, MutableList<IntSize>>()
    private val anchors = mutableListOf<Pair<Int, Int>>()
    private var revision by mutableIntStateOf(0)
    private var visibleStatusTop = -1
    private var selectionMode by mutableStateOf(false)
    private var appearance by mutableStateOf(AppearanceSettings())

    private fun mount() {
        compose.setContent {
            nav = rememberNavController()
            val entry by nav.currentBackStackEntryAsState()
            val route = entry?.destination?.route
            val haze = rememberHazeState()
            val localView = LocalView.current
            val statusTop = WindowInsets.statusBars.getTop(LocalDensity.current)
            SideEffect { view = localView; visibleStatusTop = statusTop }
            MoReadTheme(appearance) {
                MoReadBackdrop {
                    MoReadWindowLayout { width ->
                        val expanded = width == MoReadWindowWidth.EXPANDED
                        MoReadNavigationScaffold { padding ->
                            MoReadNavigationHost(nav, Modifier.fillMaxSize().recordGeometry("host").hazeSource(haze)) {
                                RootDestination.entries.forEach { root ->
                                    rootComposable(root.route, expanded) { Page(root.route, padding) }
                                }
                                pushComposable("reader/{bookId}") { Page("reader") }
                                pushComposable("child") { Page("child") }
                            }
                        }
                        MoReadNavigationDock(
                            visible = isRootRoute(route) && !selectionMode,
                            hazeState = haze,
                            vertical = expanded,
                            currentRoute = route,
                            modifier = Modifier.align(if (expanded) Alignment.CenterStart else Alignment.BottomCenter).testTag("navigation-overlay"),
                            onSelect = { root -> selectRoot(root.route) }
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        dispatchInsets(true)
    }

    private fun Modifier.recordGeometry(key: String): Modifier = onGloballyPositioned { coordinates ->
        sizes[key] = coordinates.size
        positions[key] = coordinates.localToRoot(Offset.Zero)
        widthsInRoot[key] = coordinates.localToRoot(Offset(coordinates.size.width.toFloat(), 0f)).x -
            coordinates.localToRoot(Offset.Zero).x
        measuredSizes.getOrPut(key) { mutableListOf() }.add(coordinates.size)
    }

    @Composable private fun Page(key: String, padding: PaddingValues = PaddingValues()) {
        val list = rememberLazyListState()
        SideEffect { lists[key] = list }
        Box(Modifier.fillMaxSize().recordGeometry("page-$key")) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().padding(padding).testTag("list-$key")
                    .recordGeometry("list-$key").onGloballyPositioned {
                        if (key == "bookshelf") anchors += list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
                    }
            ) {
                items(100, key = { it }) { index ->
                    Text("$key $index / $revision", Modifier.fillMaxWidth().height(56.dp))
                }
            }
        }
    }

    private fun selectRoot(route: String) {
        nav.navigate(route) {
            popUpTo("bookshelf") { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    private fun dispatchInsets(visible: Boolean, statusTop: Int = 24, cutoutTop: Int = 0, bottom: Int = 32) {
        compose.runOnIdle {
            val status = Insets.of(0, statusTop, 0, 0)
            val navigation = Insets.of(0, 0, 0, bottom)
            ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), if (visible) status else Insets.NONE)
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), status)
                .setVisible(WindowInsetsCompat.Type.statusBars(), visible)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), navigation)
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), navigation)
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .setDisplayCutout(if (cutoutTop == 0) null else DisplayCutoutCompat(
                    Rect(0, cutoutTop, 0, 0), listOf(Rect(120, 0, 200, cutoutTop))
                )).build())
        }
        compose.waitForIdle()
        // With autoAdvance disabled, inset snapshot updates still need a composition frame.
        if (!compose.mainClock.autoAdvance) frame()
    }

    private fun frame() { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
    private fun settle() { repeat(24) { frame() } }
    private fun freeze() { compose.mainClock.autoAdvance = false }

    @Test fun rootSwitchNeverScalesOrMovesEitherPage() {
        mount()
        freeze()
        val original = sizes.getValue("page-bookshelf")
        repeat(3) {
            val target = if (it % 2 == 0) "stats" else "bookshelf"
            compose.runOnIdle { selectRoot(target) }
            repeat(22) {
                frame()
                listOf("bookshelf", "stats").forEach { root ->
                    sizes["page-$root"]?.let { size ->
                        assertEquals(original, size)
                        assertEquals(original.width.toFloat(), widthsInRoot.getValue("page-$root"), 0.5f)
                        assertEquals(0f, positions.getValue("page-$root").x, 0.5f)
                        compose.onAllNodesWithTag("list-$root").fetchSemanticsNodes().forEach { node ->
                            assertEquals(original.width.toFloat(), node.boundsInRoot.width, 0.5f)
                        }
                    }
                }
            }
        }
        capture("root-tabs.png")
    }

    @Test fun readerPopKeepsInsetGeometryAndSavedListAnchorAcrossEveryFrame() {
        mount()
        compose.onNodeWithTag("list-bookshelf").performScrollToIndex(25)
        val anchor = lists.getValue("bookshelf").let { it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset }
        val original = sizes.getValue("list-bookshelf")
        freeze()
        compose.runOnIdle { nav.navigate("reader/1") }
        settle()
        dispatchInsets(false)
        assertEquals(0, visibleStatusTop)
        anchors.clear()
        compose.runOnIdle { nav.popBackStack() }
        val returnOffsets = mutableListOf<Float>()
        repeat(23) { i ->
            frame()
            // Reproduce restoration from ReaderScreen.onDispose, not just before navigation.
            if (i == 7) dispatchInsets(true)
            if (i == 11) compose.runOnIdle { revision++ }
            if (i == 4) capture("reader-return-mid.png")
            assertEquals(original, sizes.getValue("list-bookshelf"))
            assertEquals(24f, positions.getValue("list-bookshelf").y, 0.5f)
            returnOffsets += positions.getValue("page-bookshelf").x
        }
        assertTrue("root must return from the left, not fade/scale in place: $returnOffsets", returnOffsets.any { it < -1f })
        assertTrue(returnOffsets.all { it <= 0.5f })
        assertEquals(0f, returnOffsets.last(), 0.5f)
        assertEquals(24, visibleStatusTop)
        assertTrue(anchors.isNotEmpty())
        assertTrue("restored list must never flash at item 0: $anchors", anchors.all { it == anchor })
        capture("reader-return.png")
    }

    @Test fun cutoutAndActualGeometryChangesAreRespectedWithoutVisibilityJumps() {
        mount()
        dispatchInsets(true, cutoutTop = 44)
        assertEquals(44f, positions.getValue("list-bookshelf").y, 0.5f)
        val original = sizes.getValue("list-bookshelf")
        dispatchInsets(false, cutoutTop = 44)
        assertEquals(original, sizes.getValue("list-bookshelf"))
        assertEquals(44f, positions.getValue("list-bookshelf").y, 0.5f)
        dispatchInsets(false, statusTop = 52, cutoutTop = 44, bottom = 48)
        assertEquals(52f, positions.getValue("list-bookshelf").y, 0.5f)
        assertEquals(original.height - 8 - 16, sizes.getValue("list-bookshelf").height)
    }

    @Test @Config(qualifiers = "w1024dp-h768dp-mdpi")
    fun expandedPushPopAndNestedNavigationNeverRemeasureTheOutgoingViewport() {
        mount()
        freeze()
        val host = sizes.getValue("host")
        val root = sizes.getValue("page-bookshelf")
        assertEquals(host.width - 96, root.width)
        repeat(3) {
            measuredSizes.clear()
            compose.runOnIdle { nav.navigate("reader/1") }
            repeat(22) {
                frame()
                assertEquals(host, sizes.getValue("host"))
                assertEquals(root, sizes.getValue("page-bookshelf"))
            }
            assertEquals(host, sizes.getValue("page-reader"))
            compose.runOnIdle { nav.navigate("child") }
            settle()
            compose.runOnIdle { nav.popBackStack() }
            settle()
            compose.runOnIdle { nav.popBackStack() }
            repeat(22) {
                frame()
                assertEquals(host, sizes.getValue("host"))
                assertEquals(host, sizes.getValue("page-reader"))
                assertEquals(root, sizes.getValue("page-bookshelf"))
            }
            assertTrue(measuredSizes["page-reader"].orEmpty().all { it == host })
            assertTrue(measuredSizes["page-bookshelf"].orEmpty().all { it == root })
        }
        capture("expanded-return.png")
    }

    @Test fun dockKeepsTheSelectedLabelAndWidthUntilItsExitFinishes() {
        mount()
        compose.runOnIdle { selectRoot("settings") }
        compose.waitForIdle()
        val label = compose.onNodeWithText("设置", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        freeze()
        compose.runOnIdle { nav.navigate("child") }
        repeat(5) {
            frame()
            assertEquals(label.width, compose.onNodeWithText("设置", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.width, 0.5f)
        }
        settle()
        compose.onNodeWithText("设置").assertDoesNotExist()
        compose.runOnIdle { nav.popBackStack() }
        repeat(5) {
            frame()
            assertEquals(label.width, compose.onNodeWithText("设置", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.width, 0.5f)
        }
        settle()
        compose.runOnIdle { selectionMode = true }
        settle()
        compose.onNodeWithText("设置").assertDoesNotExist()
    }

    @Test fun quickRootSwitchesAndRefreshRetainEachRootScrollPosition() {
        mount()
        compose.onNodeWithTag("list-bookshelf").performScrollToIndex(21)
        compose.runOnIdle { selectRoot("stats") }
        compose.waitForIdle()
        compose.onNodeWithTag("list-stats").performScrollToIndex(34)
        freeze()
        repeat(4) {
            compose.runOnIdle { selectRoot("bookshelf"); revision++ }
            repeat(5) { frame() }
            compose.runOnIdle { selectRoot("stats") }
            repeat(5) { frame() }
        }
        settle()
        assertEquals(34, lists.getValue("stats").firstVisibleItemIndex)
        compose.runOnIdle { selectRoot("bookshelf") }
        settle()
        assertEquals(21, lists.getValue("bookshelf").firstVisibleItemIndex)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun fullWidthBarStaysAtBottomAndRetainsEachRootAnchorAcrossTextureChanges() {
        appearance = AppearanceSettings(colorScheme = ColorSchemePreset.HAZE_BLUE, navStyle = NavStyle.BAR,
            surfaceStyle = SurfaceStyle.FLAT, shapeStyle = ShapeStyle.EXPRESSIVE)
        mount()
        compose.onNodeWithTag("list-bookshelf").performScrollToIndex(21)
        compose.runOnIdle { selectRoot("stats") }
        compose.waitForIdle()
        compose.onNodeWithTag("list-stats").performScrollToIndex(34)
        SurfaceStyle.entries.forEach { style ->
            compose.runOnIdle { appearance = appearance.copy(surfaceStyle = style) }
            repeat(3) {
                compose.runOnIdle { selectRoot("bookshelf"); revision++ }
                compose.waitForIdle()
                assertEquals(21, lists.getValue("bookshelf").firstVisibleItemIndex)
                compose.runOnIdle { selectRoot("stats") }
                compose.waitForIdle()
                assertEquals(34, lists.getValue("stats").firstVisibleItemIndex)
            }
            val bar = compose.onNodeWithTag("navigation-overlay").fetchSemanticsNode().boundsInRoot
            assertEquals(view.rootView.height.toFloat(), bar.bottom, 1f)
            assertEquals(view.rootView.width.toFloat(), bar.width, 1f)
            compose.onNode(hasText("统计") and isSelectable()).assertIsSelected()
        }
        capture("bar-small.png")
    }

    @Test @Config(qualifiers = "w1024dp-h768dp-mdpi")
    fun choosingBarOnATabletRetainsTheSideNavigationAndViewport() {
        appearance = AppearanceSettings(colorScheme = ColorSchemePreset.SAGE, navStyle = NavStyle.BAR,
            surfaceStyle = SurfaceStyle.FLAT, shapeStyle = ShapeStyle.EXPRESSIVE)
        mount()
        val rail = compose.onNodeWithTag("navigation-overlay").fetchSemanticsNode().boundsInRoot
        assertTrue(rail.height > rail.width)
        assertTrue(rail.width < 160f)
        assertEquals(sizes.getValue("host").width - 96, sizes.getValue("page-bookshelf").width)
        compose.runOnIdle { selectRoot("settings") }
        compose.waitForIdle()
        assertEquals(rail, compose.onNodeWithTag("navigation-overlay").fetchSemanticsNode().boundsInRoot)
        capture("bar-tablet-rail.png")
    }

    private fun capture(name: String) {
        compose.runOnIdle {
            val root = view.rootView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val output = File("build/navigation-visuals/$name")
            output.parentFile?.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
