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

class TranslationModelAssignmentTest {
    private val dao = mockk<AiProviderDao>()
    private val repository = mockk<AiProviderRepository>()
    private val presets = mockk<GlobalPromptPresetStore>()
    private val factory = AiClientFactory(dao, repository, mockk(), mockk(), mockk(), OkHttpClient(), presets)
    private val provider = AiProviderEntity(1, "Test", "https://example.invalid", "test-key", AiProviderType.CHAT, createdAt = 0)
    init {
        coEvery { dao.getProvider(1) } returns provider
        every { repository.apiKeyFor(provider) } returns "test-only-key"
        coEvery { dao.getModel(7) } returns AiModelEntity(7, 1, "chat-model", createdAt = 0)
        coEvery { dao.getModel(8) } returns AiModelEntity(8, 1, "translation-model", createdAt = 0)
    }

    @Test fun unassignedTranslationUsesMainChatModelWithoutConversationPresets() = runTest {
        coEvery { dao.getAssignment(ModelRole.TRANSLATION) } returns null
        coEvery { dao.getAssignment(ModelRole.CHAT) } returns ModelAssignmentEntity(ModelRole.CHAT, 7)
        assertEquals("chat-model", factory.forRole(ModelRole.TRANSLATION).modelName)
        coVerify(exactly = 0) { presets.current() }
        coVerify(exactly = 0) { dao.getAssignment(ModelRole.CHEAP) }
    }

    @Test fun explicitTranslationAssignmentWinsAndErrorsNeverSwitchProviderSilently() = runTest {
        coEvery { dao.getAssignment(ModelRole.TRANSLATION) } returns ModelAssignmentEntity(ModelRole.TRANSLATION, 8)
        assertEquals("translation-model", factory.forRole(ModelRole.TRANSLATION).modelName)
        every { repository.apiKeyFor(provider) } returns null
        assertTrue(runCatching { factory.forRole(ModelRole.TRANSLATION) }.exceptionOrNull() is AiClientException.MissingKey)
        coVerify(exactly = 0) { dao.getAssignment(ModelRole.CHAT) }
    }

    @Test fun incompatibleOrMissingModelGivesConfigurationError() = runTest {
        coEvery { dao.getAssignment(any()) } returns null
        assertTrue(runCatching { factory.forRole(ModelRole.TRANSLATION) }.exceptionOrNull() is AiClientException.NotConfigured)
        coEvery { dao.getAssignment(ModelRole.TRANSLATION) } returns ModelAssignmentEntity(ModelRole.TRANSLATION, 8)
        coEvery { dao.getModel(8) } returns AiModelEntity(8, 1, "vector", type = AiModelType.EMBEDDING, createdAt = 0)
        assertTrue(runCatching { factory.forRole(ModelRole.TRANSLATION) }.exceptionOrNull() is AiClientException.NotConfigured)
    }
}
