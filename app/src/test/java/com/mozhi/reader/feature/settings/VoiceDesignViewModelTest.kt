package com.mozhi.reader.feature.settings

import com.mozhi.reader.ai.client.*
import com.mozhi.reader.ai.media.VoiceDesignAssistant
import com.mozhi.reader.ai.media.VoiceDesignActions
import com.mozhi.reader.ai.agent.AgentEvent
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.speech.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VoiceDesignViewModelTest {
    private val clients = mockk<AiClientFactory>()
    private val client = mockk<GeminiTtsClient>()
    private val voices = mockk<TtsVoiceRepository>()
    private val previews = mockk<VoiceDesignPreviewStore>()
    private val personas = mockk<PersonaRepository>()
    private val assistant = mockk<VoiceDesignAssistant>()
    private val audio = SynthesizedSpeech(byteArrayOf(1, 2), "audio/wav", null)

    @Before fun prepare() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { personas.observePersonas() } returns flowOf(emptyList())
        every { client.sourceBaseUrl } returns "https://gemini.example/v1beta"
        coEvery { clients.geminiVoiceDesigner() } returns client
        coEvery { client.designVoice(any()) } returns GeminiDesignedVoice("voice_one", audio)
        coEvery { client.deleteDesignedVoice(any()) } returns Unit
        coEvery { previews.save(any(), any()) } returns "/cache/preview.wav"
        coEvery { previews.remove(any()) } returns Unit
        coEvery { voices.saveDesignedVoice(any()) } returns 12
        coEvery { voices.containsGeminiVoice(any()) } returns false
    }
    @After fun finish() { Dispatchers.resetMain() }
    private fun model(scope: CoroutineScope) = VoiceDesignViewModel(clients, assistant, voices, previews, personas, scope).apply {
        begin(); name("晚安旁白"); description("温暖清晰、从容自然的普通话女声")
    }

    @Test fun generatedVoiceOnlyEntersLibraryAfterConfirmationAndIsNeverDeletedAfterSave() = runTest {
        val vm = model(backgroundScope)
        vm.generate(); advanceUntilIdle()
        assertTrue(vm.state.value.canSave)
        coVerify(exactly = 0) { voices.saveDesignedVoice(any()) }
        vm.save(); vm.save(); runCurrent()
        assertEquals(12L, vm.state.value.savedId)
        vm.discard(); runCurrent()
        coVerify(exactly = 1) { voices.saveDesignedVoice(match { it.voiceId == "voice_one" && it.extraJson.contains("温暖清晰") }) }
        coVerify(exactly = 0) { client.deleteDesignedVoice("voice_one") }
    }

    @Test fun editingDescriptionRequiresRegenerationButFailedRetryKeepsPreviousCandidate() = runTest {
        val vm = model(backgroundScope)
        vm.generate(); advanceUntilIdle()
        vm.description("低沉一点的普通话女声")
        assertFalse(vm.state.value.canSave)
        vm.save()
        coVerify(exactly = 0) { voices.saveDesignedVoice(any()) }
        coEvery { client.designVoice(any()) } throws AiClientException.RateLimited()
        vm.generate(); advanceUntilIdle()
        assertEquals("voice_one", vm.state.value.candidate!!.voiceId)
        coVerify(exactly = 0) { client.deleteDesignedVoice(any()) }
        vm.discard(); runCurrent()
    }

    @Test fun missingPreviewRetriesTheExistingIdAndSaveFailureIsRetryable() = runTest {
        coEvery { client.designVoice(any()) } returns GeminiDesignedVoice("voice_one", null)
        coEvery { client.designedVoicePreview("voice_one") } returns audio
        val vm = model(backgroundScope)
        vm.generate(); advanceUntilIdle()
        assertFalse(vm.state.value.canSave)
        vm.fetchPreview(); advanceUntilIdle()
        assertTrue(vm.state.value.canSave)
        coEvery { voices.saveDesignedVoice(any()) } throws IllegalStateException("disk full")
        vm.save(); runCurrent()
        assertTrue(vm.state.value.canSave)
        coEvery { voices.saveDesignedVoice(any()) } returns 14
        vm.save(); runCurrent()
        assertEquals(14L, vm.state.value.savedId)
        coVerify(exactly = 1) { client.designVoice(any()) }
        coVerify(exactly = 0) { client.deleteDesignedVoice(any()) }
    }

    @Test fun closingDuringDatabaseCommitDoesNotDeleteASuccessfullySavedVoice() = runTest {
        val commit = CompletableDeferred<Long>()
        coEvery { voices.saveDesignedVoice(any()) } coAnswers { commit.await() }
        val vm = model(backgroundScope)
        vm.generate(); advanceUntilIdle()
        vm.save(); runCurrent()
        vm.discard(); runCurrent()
        commit.complete(19); runCurrent()
        assertEquals(19L, vm.state.value.savedId)
        coVerify(exactly = 0) { client.deleteDesignedVoice("voice_one") }
    }

    @Test fun aCandidateImportedElsewhereIsNotDeletedWhenEditorIsDiscarded() = runTest {
        val vm = model(backgroundScope)
        vm.generate(); advanceUntilIdle()
        coEvery { voices.containsGeminiVoice("voice_one") } returns true
        vm.discard(); runCurrent()
        coVerify(exactly = 0) { client.deleteDesignedVoice("voice_one") }
        coVerify(exactly = 0) { previews.remove("voice_one") }
    }

    @Test fun agentToolActionsCanChangeTheDraftAndGenerateWhileTheAgentIsWorking() = runTest {
        every { assistant.run(any(), any()) } answers {
            val actions = secondArg<VoiceDesignActions>()
            flow {
                actions.update(GeminiVoiceDesignRequest("悬疑旁白", "低沉克制的普通话男声", "male"))
                actions.generatePreview()
                emit(AgentEvent.Text("已生成一版，请试听。"))
            }
        }
        val vm = model(backgroundScope)
        vm.send("我想要适合悬疑故事的男声"); advanceUntilIdle()
        assertEquals("悬疑旁白", vm.state.value.name)
        assertEquals("male", vm.state.value.gender)
        assertEquals("voice_one", vm.state.value.candidate!!.voiceId)
        assertEquals(2, vm.state.value.chats.size)
        coVerify(exactly = 1) { client.designVoice(match { it.name == "悬疑旁白" && it.gender == "male" }) }
        coVerify(exactly = 0) { voices.saveDesignedVoice(any()) }
        vm.discard(); runCurrent()
    }
}
