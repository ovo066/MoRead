package com.mozhi.reader.feature.bookshelf

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShelfCollectionDragComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun consecutiveDragsKeepEveryEarlierMoveWithUnchangedBookKeys() {
        val fixture = DragFixture()
        compose.setContent { fixture.Content() }

        drag(1, 2, after = true)
        compose.runOnIdle { assertEquals(listOf(2L, 1L, 3L, 4L, 5L), fixture.bookIds()) }
        drag(3, 2, after = false)
        compose.runOnIdle { assertEquals(listOf(3L, 2L, 1L, 4L, 5L), fixture.bookIds()) }
        drag(1, 5, after = true)
        compose.runOnIdle {
            assertEquals(listOf(3L, 2L, 4L, 5L, 1L), fixture.bookIds())
            assertEquals(3, fixture.drops.size)
        }
    }

    @Test
    fun recompositionDuringHeldDragKeepsGestureAndReadsLatestCallbacks() {
        val fixture = DragFixture()
        compose.setContent { fixture.Content() }
        val source = compose.onNodeWithTag("book:1")
        val sourceBounds = source.fetchSemanticsNode().boundsInRoot
        val targetBounds = compose.onNodeWithTag("book:2").fetchSemanticsNode().boundsInRoot
        source.performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(center + Offset(12f, 0f))
        }
        compose.runOnIdle {
            assertEquals(1L, fixture.state.sourceBook?.id)
            assertEquals(listOf(HapticFeedbackType.LongPress), fixture.haptics)
            fixture.revision = 7
        }
        source.performTouchInput {
            moveTo(Offset(targetBounds.right - 2f, targetBounds.center.y) - sourceBounds.topLeft)
            up()
        }
        compose.runOnIdle {
            assertEquals(listOf(7), fixture.drops)
            assertEquals(listOf(2L, 1L, 3L, 4L, 5L), fixture.bookIds())
            assertEquals(1, fixture.pins)
            assertEquals(1, fixture.releases)
            assertNull(fixture.state.sourceBook)
        }

        compose.onNodeWithTag("book:1").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(listOf(7), fixture.menus)
            fixture.revision = 8
        }
        compose.onNodeWithTag("book:1").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.runOnIdle { assertEquals(listOf(7, 8), fixture.menus) }
        val moveAfter = compose.onNodeWithTag("book:1").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == "向后移动" }
        compose.runOnIdle { assertTrue(moveAfter.action()) }
        compose.runOnIdle {
            assertEquals(listOf(2L, 3L, 1L, 4L, 5L), fixture.bookIds())
            assertEquals(listOf(7, 8), fixture.drops)
        }
    }

    @Test
    fun cancelledPointerReleasesPinWithoutSavingOrOpeningMenu() {
        val fixture = DragFixture()
        compose.setContent { fixture.Content() }
        val source = compose.onNodeWithTag("book:1")
        val sourceBounds = source.fetchSemanticsNode().boundsInRoot
        val targetBounds = compose.onNodeWithTag("book:2").fetchSemanticsNode().boundsInRoot
        source.performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(Offset(targetBounds.right - 2f, targetBounds.center.y) - sourceBounds.topLeft)
            cancel()
        }
        compose.runOnIdle {
            assertNull(fixture.state.sourceBook)
            assertNull(fixture.state.activeDrop)
            assertTrue(fixture.drops.isEmpty())
            assertTrue(fixture.menus.isEmpty())
            assertEquals(1, fixture.pins)
            assertEquals(1, fixture.releases)
        }
        drag(1, 2, after = true)
        compose.runOnIdle { assertEquals(listOf(2L, 1L, 3L, 4L, 5L), fixture.bookIds()) }
    }

    @Test
    fun autoScrollSurvivesTargetChangesAndStopsRequestingScrollAtTheBoundary() {
        compose.mainClock.autoAdvance = false
        val state = ShelfCollectionDragState()
        var scrollCalls = 0
        var consumedDistance = 0f
        var atBoundary = false
        val scrollState = ScrollableState { delta ->
            scrollCalls++
            if (atBoundary) 0f else delta.also { consumedDistance += it }
        }
        compose.setContent {
            ShelfAutoScrollEffect(state, scrollState) {}
            Box(Modifier.size(200.dp))
        }
        val target = ShelfDropTarget("book:2", 2, null)
        val owner = Any()
        compose.runOnIdle {
            state.setViewport(Rect(0f, 0f, 300f, 1000f))
            state.register(target, Rect(0f, 800f, 300f, 1000f), owner)
            state.begin(testBook(1), Offset(50f, 850f), Rect(0f, 800f, 100f, 950f), false, true)
            state.dragBy(Offset(0f, 80f), minDistancePx = 8f)
        }
        compose.mainClock.advanceTimeBy(160)
        var beforeRelayout = 0f
        compose.runOnIdle {
            assertTrue(consumedDistance > 0f)
            beforeRelayout = consumedDistance
            state.unregister(target.entryKey, owner)
        }
        compose.mainClock.advanceTimeBy(160)
        compose.runOnIdle {
            assertTrue(consumedDistance > beforeRelayout)
            beforeRelayout = consumedDistance
            state.register(ShelfDropTarget("book:3", 3, null), Rect(0f, 800f, 300f, 1000f), Any())
        }
        compose.mainClock.advanceTimeBy(160)
        compose.runOnIdle {
            assertTrue(consumedDistance > beforeRelayout)
            atBoundary = true
        }
        compose.mainClock.advanceTimeBy(80)
        var callsAtBoundary = 0
        compose.runOnIdle { callsAtBoundary = scrollCalls }
        compose.mainClock.advanceTimeBy(320)
        compose.runOnIdle {
            assertEquals(callsAtBoundary, scrollCalls)
            state.cancel()
        }
    }

    @Test
    fun lazyListKeepsTheSourceGestureWhileScrollingPastItsOriginalItem() {
        compose.mainClock.autoAdvance = false
        val books = (1L..30L).map(::testBook)
        val state = ShelfCollectionDragState()
        lateinit var listState: LazyListState
        var drops = 0
        compose.setContent {
            listState = rememberLazyListState()
            ShelfAutoScrollEffect(state, listState) {
                listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .size(width = 300.dp, height = 320.dp)
                    .testTag("viewport")
                    .onGloballyPositioned { state.setViewport(it.boundsInRoot()) }
            ) {
                items(books, key = BookEntity::id) { book ->
                    var bounds by remember { mutableStateOf(Rect.Zero) }
                    val target = remember(book.id) { ShelfEntry.Book(book).dropTarget() }
                    val owner = remember { Any() }
                    val pinnableContainer = LocalPinnableContainer.current
                    DisposableEffect(book.id) {
                        onDispose { state.unregister(target.entryKey, owner) }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .testTag(target.entryKey)
                            .onGloballyPositioned {
                                bounds = it.boundsInRoot()
                                state.register(target, bounds, owner)
                            }
                            .collectionDragSource(
                                book, { bounds }, { bounds }, horizontal = false,
                                allowMerge = false, enabled = true, pinnableContainer = pinnableContainer,
                                state = state, onDrop = { _, _ -> drops++ }, onLongPressOnly = {}
                            )
                    ) { BasicText(book.title) }
                }
            }
        }
        val source = compose.onNodeWithTag("book:1")
        val sourceBounds = source.fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        source.performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(Offset(viewport.center.x, viewport.bottom - 4f) - sourceBounds.topLeft)
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle {
            assertTrue("The list should scroll past the pinned source item", listState.firstVisibleItemIndex > 0)
            assertEquals(1L, state.sourceBook?.id)
        }
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle {
            assertNull(state.sourceBook)
            assertEquals(1, drops)
        }
    }

    private fun drag(sourceId: Long, targetId: Long, after: Boolean) {
        val source = compose.onNodeWithTag("book:$sourceId")
        val bounds = source.fetchSemanticsNode().boundsInRoot
        val target = compose.onNodeWithTag("book:$targetId").fetchSemanticsNode().boundsInRoot
        val destination = Offset(if (after) target.right - 2f else target.left + 2f, target.center.y)
        source.performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(destination - bounds.topLeft)
            up()
        }
    }

    private class DragFixture {
        // These entities never change: the test must exercise the original pointerInput keys.
        var entries: List<ShelfEntry> by mutableStateOf((1L..5L).map { ShelfEntry.Book(testBook(it)) })
        var revision by mutableStateOf(0)
        val state = ShelfCollectionDragState()
        val drops = mutableListOf<Int>()
        val menus = mutableListOf<Int>()
        val haptics = mutableListOf<HapticFeedbackType>()
        var pins = 0
        var releases = 0
        private val container = object : PinnableContainer {
            override fun pin(): PinnableContainer.PinnedHandle {
                pins++
                return object : PinnableContainer.PinnedHandle {
                    override fun release() { releases++ }
                }
            }
        }
        private val feedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                haptics += hapticFeedbackType
            }
        }

        fun bookIds() = entries.flatMap(ShelfEntry::bookIds)

        @Composable
        fun Content() {
            val snapshot = entries
            val callbackRevision = revision
            val onDrop: (BookEntity, ShelfDrop) -> Unit = { book, drop ->
                drops += callbackRevision
                entries = reorderShelfEntries(snapshot, book.id, drop.target.entryKey, drop.placement == ShelfDropPlacement.AFTER)
            }
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                Row {
                    snapshot.forEach { item ->
                        key(item.key) {
                            val entry = item as ShelfEntry.Book
                            var bounds by remember { mutableStateOf(Rect.Zero) }
                            val owner = remember { Any() }
                            DisposableEffect(entry.key) {
                                onDispose { state.unregister(entry.key, owner) }
                            }
                            Box(
                                Modifier
                                    .size(width = 52.dp, height = 96.dp)
                                    .testTag(entry.key)
                                    .onGloballyPositioned {
                                        bounds = it.boundsInRoot()
                                        state.register(entry.dropTarget(), bounds, owner)
                                    }
                                    .collectionDragSource(
                                        book = entry.book,
                                        bounds = { bounds },
                                        coverBounds = { bounds },
                                        horizontal = true,
                                        allowMerge = false,
                                        enabled = true,
                                        pinnableContainer = container,
                                        state = state,
                                        onDrop = onDrop,
                                        onLongPressOnly = { menus += callbackRevision },
                                        reorderActions = shelfReorderActions(snapshot, entry.book, onDrop)
                                    )
                            ) { BasicText(entry.book.title) }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private fun testBook(id: Long) = BookEntity(
            id = id,
            title = "书$id",
            author = "",
            coverPath = null,
            epubPath = "/$id.epub",
            sourceType = BookSourceType.EPUB,
            importedAt = id,
            totalChapters = 1
        )
    }
}
