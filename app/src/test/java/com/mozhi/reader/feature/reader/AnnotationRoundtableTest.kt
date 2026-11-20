package com.mozhi.reader.feature.reader

import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.agent.AnnotationDiscussionService
import com.mozhi.reader.ai.agent.discussionRequest
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.library.AnnotationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationRoundtableTest {
    private fun persona(id: Long) = PersonaEntity(id = id, name = "角色$id", personality = "", isRoleplay = true, createdAt = 0)
    private fun annotation(personaId: Long?, note: String = "想法") = AnnotationEntity(id = 8, bookId = 7, personaId = personaId,
        chapterIndex = 2, startCharOffset = 20, endCharOffset = 30, selectedText = "这是一段划线原文。", note = note, createdAt = 0)
    private fun reply(personaId: Long?) = AnnotationReplyEntity(annotationId = 8, personaId = personaId, contentMarkdown = "话", createdAt = 0)
    private val everyone = (1L..5L).map(::persona)

    @Test fun peopleAlreadyInTheThreadSitFirstAndTheLastSpeakerGoesLast() {
        // 3 号写的段评，2 号接过话，读者此刻点名 5 号。
        val seats = roundtableSpeakers(listOf(annotation(3)), listOf(reply(2), reply(null)), everyone, preferred = 5)
        assertEquals(listOf(3L, 5L, 2L), seats)
    }

    @Test fun emptyThreadSeatsThePreferredCompanionThenListOrderUpToThree() {
        assertEquals(listOf(4L, 1L, 2L), roundtableSpeakers(listOf(annotation(null)), emptyList(), everyone, preferred = 4))
        assertEquals(listOf(1L, 2L, 3L), roundtableSpeakers(listOf(annotation(null)), emptyList(), everyone, preferred = null))
        // 纯划线的角色批注（没写话）不算说过话；已删除的角色不入座。
        assertEquals(listOf(1L, 2L, 3L),
            roundtableSpeakers(listOf(annotation(4, note = "")), listOf(reply(99)), everyone, preferred = 99))
        assertEquals(listOf(1L), roundtableSpeakers(listOf(annotation(1)), emptyList(), everyone.take(1), preferred = 1))
    }

    @Test fun companionsMayTalkTwoRoundsOnTheirOwnUntilTheReaderSpeaksAgain() {
        assertEquals(0, personaRunLength(emptyList()))
        assertEquals(2, personaRunLength(listOf(reply(1), reply(null), reply(2), reply(3))))
        assertEquals(0, personaRunLength(listOf(reply(1), reply(2), reply(null))))
        assertTrue(roundtableAllowed(speakers = 3, run = 5))
        assertFalse(roundtableAllowed(speakers = 3, run = 6))
        assertFalse(roundtableAllowed(speakers = 1, run = 0))
    }

    @Test fun roundtableRequestTellsLaterSpeakersToBuildOnEarlierOnes() {
        val opening = discussionRequest("观澜", latestUser = null, roundtable = true, othersSpoke = false)
        assertTrue("请大家一起聊聊" in opening)
        assertFalse("前面已经有人说过了" in opening)
        val following = discussionRequest("阿澈", latestUser = "这里是伏笔吧", roundtable = true, othersSpoke = true)
        assertTrue("这里是伏笔吧" in following)
        assertTrue("别重复已经说过的意思" in following)
        assertTrue(following.endsWith("请以阿澈的身份直接给出你在讨论串里的下一条发言。"))
        // 单独点名时仍是原来的请求语。
        assertEquals("用户邀请你点评这段划线原文，没有附加想法。\n请以观澜的身份直接给出你在讨论串里的下一条发言。",
            discussionRequest("观澜", latestUser = null, roundtable = false, othersSpoke = true))
    }

    @Test fun roundtableSpeaksInOrderAndStopsAtTheFirstSilence() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val service = mockk<AnnotationDiscussionService>()
        val settings = mockk<ReaderSettingsRepository>()
        every { settings.discussionPersonaId } returns flowOf(null)
        every { service.respond(7, 8, 1, true) } returns flowOf(AnnotationDiscussionService.Event.Done(11))
        every { service.respond(7, 8, 2, true) } returns flowOf(AnnotationDiscussionService.Event.Failed("没说出话"))
        every { service.respond(7, 8, 3, true) } returns flowOf(AnnotationDiscussionService.Event.Done(13))
        val vm = AnnotationDiscussionViewModel(mockk<AnnotationRepository>(), service, settings)
        try {
            vm.roundtable(7, 8, listOf(1, 2, 3))
            // 连点不会再开一轮。
            vm.roundtable(7, 8, listOf(1, 2, 3))
            assertEquals(1L, vm.uiState.value.streaming?.personaId)
            runCurrent()
            verifyOrder { service.respond(7, 8, 1, true); service.respond(7, 8, 2, true) }
            verify(exactly = 1) { service.respond(7, 8, 1, true) }
            verify(exactly = 0) { service.respond(7, 8, 3, any()) }
            assertNull(vm.uiState.value.streaming)
            assertEquals("没说出话", vm.uiState.value.error)
            // 一个人不成「聊」。
            vm.roundtable(7, 8, listOf(3))
            runCurrent()
            verify(exactly = 0) { service.respond(7, 8, 3, any()) }
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
