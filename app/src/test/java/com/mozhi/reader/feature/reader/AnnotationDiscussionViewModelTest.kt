package com.mozhi.reader.feature.reader

import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.agent.AnnotationDiscussionService
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.library.AnnotationRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class AnnotationDiscussionViewModelTest {
    private val annotation = AnnotationEntity(id = 8, bookId = 7, chapterIndex = 2,
        startCharOffset = 20, endCharOffset = 30, selectedText = "这是一段划线原文。", createdAt = 0)

    @Test fun blankSendInvitesAiWithoutCreatingFakeUserTextAndIgnoresDoubleTap() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = mockk<AnnotationRepository>()
        val service = mockk<AnnotationDiscussionService>()
        every { service.respond(7, 8, 3) } returns flowOf(AnnotationDiscussionService.Event.Done(19))
        val vm = AnnotationDiscussionViewModel(repository, service)
        try {
            vm.sendUserReply(7, annotation, "   \n", 3)
            vm.sendUserReply(7, annotation, "", 3)
            assertEquals(3L, vm.uiState.value.streaming?.personaId)
            runCurrent()
            verify(exactly = 1) { service.respond(7, 8, 3) }
            coVerify(exactly = 0) { repository.updateNote(any(), any()) }
            coVerify(exactly = 0) { repository.addReply(any(), any(), any(), any()) }
            assertNull(vm.uiState.value.streaming)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun emptyInputWithoutACompanionDoesNothingAndWrittenNotesStayUserOwned() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = mockk<AnnotationRepository>()
        val service = mockk<AnnotationDiscussionService>()
        coEvery { repository.updateNote(8, "我的想法") } just Runs
        val vm = AnnotationDiscussionViewModel(repository, service)
        try {
            vm.sendUserReply(7, annotation, "", null)
            vm.sendUserReply(7, annotation, " 我的想法 ", null)
            runCurrent()
            coVerify(exactly = 1) { repository.updateNote(8, "我的想法") }
            verify(exactly = 0) { service.respond(any(), any(), any()) }
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
