package com.mozhi.reader.feature.reader

import android.app.Application
import com.mozhi.reader.core.database.entity.MessageEntity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real LazyColumn measurement + gestures, not just assertions about requested scroll indices. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CompanionChatScrollTest {
    @get:Rule val compose = createComposeRule()
    private var entries by mutableStateOf<List<ChatEntry>>(emptyList())
    private var loading by mutableStateOf(true)
    private var session by mutableStateOf("book-1:persona-1:conversation-1")
    private var visible by mutableStateOf(true)
    private var viewportHeight by mutableStateOf(560.dp)
    private lateinit var scroll: CompanionChatScrollState
    private val measuredPositions = mutableListOf<Pair<Int, Int>>()

    private fun bubble(id: Int, text: String = "Message $id") = ChatEntry.Bubble(
        id = id.toString(),
        part = CompanionBubblePart.Text(text),
        fromUser = id % 2 == 0
    )

    private fun mount(count: Int = 80, deferred: Boolean = false) {
        if (!deferred) {
            entries = (1..count).map(::bubble)
            loading = false
        }
        compose.setContent {
            if (visible) {
                scroll = rememberCompanionChatScrollState(session, entries, loading)
                Box(Modifier.size(320.dp, viewportHeight)) {
                    CompanionChatMessageList(entries, scroll, Modifier.fillMaxSize().testTag("chat").onGloballyPositioned {
                        if (scroll.listState.layoutInfo.totalItemsCount > 0) {
                            measuredPositions += scroll.listState.firstVisibleItemIndex to scroll.listState.firstVisibleItemScrollOffset
                        }
                    }) { entry ->
                        Text(
                            (entry as ChatEntry.Bubble).part.text,
                            Modifier.heightIn(min = 64.dp).padding(8.dp).testTag(entry.key)
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertLatest() = compose.runOnIdle {
        assertTrue(scroll.initiallyPositioned)
        assertTrue(scroll.listState.isAtLatest())
        assertEquals(entries.last().key, scroll.listState.layoutInfo.visibleItemsInfo.last().key)
    }

    private fun browseHistory() {
        compose.onNodeWithTag("chat").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(scroll.followingLatest)
            assertFalse(scroll.listState.isAtLatest())
        }
    }

    private fun anchor(): Pair<Any, Int> = compose.runOnIdle {
        val list = scroll.listState
        list.layoutInfo.visibleItemsInfo.first { it.index == list.firstVisibleItemIndex }.key to
            list.firstVisibleItemScrollOffset
    }

    @Test fun preloadedHistoryIsAtTheTailOnItsVeryFirstMeasure() {
        mount(count = 240)
        compose.runOnIdle { assertTrue(measuredPositions.first().first > 200) }
        assertLatest()
    }

    @Test fun deferredHistoryNeverMeasuresTheOldestMessagesFirst() {
        mount(deferred = true)
        compose.runOnIdle { entries = (1..240).map(::bubble); loading = false }
        compose.runOnIdle { assertTrue(measuredPositions.first().first > 200) }
        assertLatest()
    }

    @Test fun oversizedLastBubbleStartsAtItsBottomWithoutAnIntermediateTopFrame() {
        entries = listOf(bubble(1, "Very long answer\n".repeat(200)))
        loading = false
        mount(deferred = true)
        compose.runOnIdle { assertTrue(measuredPositions.first().second > 1_000) }
        assertLatest()
    }

    @Test fun delayedHistoryOpensAtLatestAfterItHasBeenMeasured() {
        mount(deferred = true)
        compose.runOnIdle { entries = (1..160).map(::bubble); loading = false }
        assertLatest()
    }

    @Test fun reopeningAndSwitchingConversationsStartAtLatest() {
        mount()
        browseHistory()
        compose.runOnIdle { visible = false }
        compose.runOnIdle { visible = true }
        assertLatest()
        browseHistory()
        compose.runOnIdle { session = "other"; entries = (1..24).map(::bubble) }
        assertLatest()
    }

    @Test fun browsingIsNotInterruptedByIncomingBubblesOrStreamCommit() {
        mount()
        browseHistory()
        val before = anchor()
        compose.runOnIdle { entries = entries + bubble(81, "Streaming...") }
        compose.runOnIdle { entries = entries.dropLast(1) + bubble(81, "Answer\n".repeat(100)) }
        compose.runOnIdle { entries = entries.dropLast(1) + bubble(81, "Answer\n".repeat(100) + "Done") }
        assertEquals(before, anchor())
    }

    @Test fun browsingWithinOneLongStreamingBubbleKeepsTheSameTextAnchor() {
        entries = listOf(bubble(1, "Line\n".repeat(150)))
        loading = false
        mount(deferred = true)
        assertLatest()
        browseHistory()
        val before = anchor()
        compose.runOnIdle { entries = listOf(bubble(1, "Line\n".repeat(220))) }
        assertEquals(before, anchor())
    }

    @Test fun actualRoomFirstRoundCommitKeepsAnchorInsideLongReply() {
        val reply = "Reading anchor line\n".repeat(160)
        val saved = MessageEntity(id = 999, conversationId = 1, role = "assistant",
            content = reply, createdAt = 1, clientRoundId = "round-999")
        fun rows(persisted: Boolean, streaming: Boolean = true): List<ChatEntry> =
            buildCompanionChatEntries(
                timeline = if (persisted) buildCompanionTimeline(listOf(saved)) else emptyList(),
                liveSteps = emptyList(), liveReasoning = null, streamingText = reply.takeIf { streaming },
                isStreaming = streaming, toolStatus = null, thinkingLabel = "thinking", error = null,
                greeting = null, embeddingProgress = null, sceneQuote = "chapter",
                multiBubble = false, liveEntryId = "round-999"
            ).filterIsInstance<ChatEntry.Bubble>()
        entries = rows(false)
        loading = false
        mount(deferred = true)
        browseHistory()
        val before = anchor()
        compose.runOnIdle { entries = rows(true) } // Room emitted; commit event still queued.
        assertEquals(before, anchor())
        compose.runOnIdle { entries = rows(true, streaming = false) }
        assertEquals(before, anchor())
        compose.runOnIdle { assertFalse(scroll.followingLatest) }
    }

    @Test fun deferredCitationHeightDoesNotRearmFollowingWhenBrowsing() {
        mount()
        browseHistory()
        val before = anchor()
        compose.runOnIdle {
            entries = entries.mapIndexed { index, entry ->
                if (index == 0 || index == entries.lastIndex) bubble(index + 1, "Citation\n".repeat(20))
                else entry
            }
        }
        assertEquals(before, anchor())
        compose.runOnIdle { assertFalse(scroll.followingLatest) }
    }

    @Test fun bottomFollowsHeightGrowthAndViewportResize() {
        mount()
        compose.runOnIdle { entries = entries + bubble(81, "Answer\n".repeat(90)) }
        assertLatest()
        compose.runOnIdle { entries = entries.dropLast(1) + bubble(81, "Answer\n".repeat(140)) }
        assertLatest()
        compose.runOnIdle { viewportHeight = 300.dp }
        assertLatest()
        compose.runOnIdle { viewportHeight = 560.dp }
        assertLatest()
    }

    @Test fun sendWaitsForTheNewMessageWithoutAnchoringAnOlderQuestion() {
        mount()
        browseHistory()
        compose.runOnIdle { scroll.requestFollowLatest() }
        assertLatest()
        compose.runOnIdle { entries = entries + bubble(81) }
        assertLatest()
        compose.mainClock.advanceTimeBy(2_500)
        assertLatest()
    }

    @Test fun tinyHistoryDragImmediatelyDisablesFollowEvenInsideBottomSlack() {
        mount()
        compose.runOnIdle {
            scroll.nestedScrollConnection.onPreScroll(
                androidx.compose.ui.geometry.Offset(0f, 1f),
                androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput
            )
            scroll.nestedScrollConnection.onPostScroll(
                androidx.compose.ui.geometry.Offset(0f, 1f),
                androidx.compose.ui.geometry.Offset.Zero,
                androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput
            )
            assertFalse(scroll.followingLatest)
        }
    }

    @Test fun returningToLatestReenablesFollowing() {
        mount()
        browseHistory()
        // Programmatic scroll exercises the same suspending button action while Compose drives frames.
        compose.runOnIdle { scroll.requestFollowLatest() }
        assertLatest()
        compose.runOnIdle { entries = entries + bubble(81) }
        assertLatest()
    }
}
