package com.mozhi.reader.feature.settings

import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.core.database.entity.AiProviderAdapter
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ModelConfigurationTest {
    @Test fun aGatewayDoesNotOverrideTheActualModelBrand() {
        assertEquals(AiBrand.CLAUDE, modelBrand("anthropic/claude-sonnet-4"))
        assertEquals(AiBrand.DEEPSEEK, modelBrand("deepseek/deepseek-chat-v3"))
        assertEquals(AiBrand.QWEN, modelBrand("Qwen/Qwen3-32B"))
        assertEquals(AiBrand.META, modelBrand("llama3.1:8b"))
        assertEquals(AiBrand.OPENAI, modelBrand("openai/o3"))
        assertNull(modelBrand("my-private-model"))
        assertEquals(AiBrand.GEMINI, modelBrand("google/gemini-3.1-flash-lite"))
        assertEquals(AiBrand.MINIMAX, modelBrand("MiniMaxAI/MiniMax-M2.7"))
        assertEquals(AiBrand.ZHIPU, modelBrand("z-ai/glm-4.6"))
    }

    @Test fun providerIdentityUsesServiceNamesAndHostsIndependentlyOfTheProtocol() {
        assertEquals(AiBrand.VOLCENGINE, providerBrand(AiProviderAdapter.CUSTOM, "火山方舟"))
        assertEquals(AiBrand.SILICONFLOW, providerBrand(AiProviderAdapter.CUSTOM, "硅基流动"))
        assertEquals(AiBrand.VOLCENGINE, providerBrand(AiProviderAdapter.OPENAI, "我的接口", "https://ark.cn-beijing.volces.com/api/v3"))
        assertEquals(AiBrand.SILICONFLOW, providerBrand(AiProviderAdapter.CUSTOM, "我的接口", "https://api.siliconflow.cn/v1"))
        assertNull(providerBrand(AiProviderAdapter.CUSTOM, "private", "https://api.siliconflow.cn.example.com/v1"))
    }

    @Test fun parameterEditingPreservesVendorSettingsAndClearsConflictingBodyOverride() {
        val parameter = ModelParameter("temperature", "温度", "", 2.0)
        val old = """{"temperature":0.1,"max_tokens":2048,"headers":{"X-Test":"retained"},"body":{"temperature":0.8,"enable_thinking":true}}"""
        assertEquals("0.8", parameterValue(old, "temperature"))
        val next = withModelParameter(old, parameter, "0.5")
        val root = AiJson.parseToJsonElement(next).jsonObject
        assertEquals("0.5", parameterValue(next, "temperature"))
        assertEquals("2048", parameterValue(next, "max_tokens"))
        assertEquals("retained", root.getValue("headers").jsonObject.getValue("X-Test").jsonPrimitive.content)
        assertTrue(root.getValue("body").jsonObject.getValue("enable_thinking").jsonPrimitive.boolean)
        assertFalse("temperature" in root.getValue("body").jsonObject)
        assertNull(parameterValue(withModelParameter(next, parameter, ""), "temperature"))
    }

    @Test fun invalidNumbersAndBrokenJsonAreRejectedWithoutResettingSettings() {
        val tokens = ModelParameter("max_tokens", "最大长度", "", Int.MAX_VALUE.toDouble(), true)
        listOf("-1", "0", "1.5", "NaN", "Infinity", "2147483648").forEach {
            assertThrows(IllegalArgumentException::class.java) { withModelParameter("{}", tokens, it) }
        }
        assertThrows(IllegalArgumentException::class.java) { withModelParameter("broken", tokens, "42") }
    }
}
