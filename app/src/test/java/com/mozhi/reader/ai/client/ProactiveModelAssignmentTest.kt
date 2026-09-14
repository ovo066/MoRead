package com.mozhi.reader.ai.client

import com.mozhi.reader.ai.provider.AiProviderRepository
import com.mozhi.reader.core.database.dao.AiProviderDao
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.datastore.GlobalPromptPresetStore
import io.mockk.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ProactiveModelAssignmentTest {
    private val dao = mockk<AiProviderDao>()
    private val repository = mockk<AiProviderRepository>()
    private val globalPresets = mockk<GlobalPromptPresetStore>()
    private val factory = AiClientFactory(dao, repository, mockk(), mockk(), mockk(), OkHttpClient(), globalPresets)
    private val provider = AiProviderEntity(1, "Test", "https://example.invalid", "test-key", AiProviderType.CHAT, createdAt = 0)
    init {
        coEvery { dao.getProvider(1) } returns provider
        every { repository.apiKeyFor(provider) } returns "test-only-key"
        coEvery { dao.getModel(7) } returns AiModelEntity(7, 1, "cheap-model", createdAt = 0)
        coEvery { dao.getModel(8) } returns AiModelEntity(8, 1, "annotation-model", createdAt = 0)
    }

    @Test fun unassignedProactiveRoleUsesCheapWithoutChatPresets() = runTest {
        coEvery { dao.getAssignment(ModelRole.PROACTIVE_ANNOTATION) } returns null
        coEvery { dao.getAssignment(ModelRole.CHEAP) } returns ModelAssignmentEntity(ModelRole.CHEAP, 7)
        assertEquals("cheap-model", factory.forRole(ModelRole.PROACTIVE_ANNOTATION).modelName)
        coVerify(exactly = 0) { globalPresets.current() }
        coVerify(exactly = 0) { dao.getAssignment(ModelRole.CHAT) }
    }

    @Test fun explicitAssignmentWinsAndItsErrorsDoNotSilentlyFallBack() = runTest {
        coEvery { dao.getAssignment(ModelRole.PROACTIVE_ANNOTATION) } returns ModelAssignmentEntity(ModelRole.PROACTIVE_ANNOTATION, 8)
        assertEquals("annotation-model", factory.forRole(ModelRole.PROACTIVE_ANNOTATION).modelName)
        every { repository.apiKeyFor(provider) } returns null
        assertTrue(runCatching { factory.forRole(ModelRole.PROACTIVE_ANNOTATION) }.exceptionOrNull() is AiClientException.MissingKey)
        coVerify(exactly = 0) { dao.getAssignment(ModelRole.CHEAP) }
    }

    @Test fun missingCheapAlsoProducesTheConfigurationError() = runTest {
        coEvery { dao.getAssignment(any()) } returns null
        assertTrue(runCatching { factory.forRole(ModelRole.PROACTIVE_ANNOTATION) }.exceptionOrNull() is AiClientException.NotConfigured)
    }
}
