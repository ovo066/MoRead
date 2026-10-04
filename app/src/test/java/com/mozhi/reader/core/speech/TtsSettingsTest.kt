package com.mozhi.reader.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSettingsTest {
    @Test fun mimoAndFishPresetsUseNativeEndpointsAndControls() {
        assertEquals("https://api.xiaomimimo.com/v1", TtsApiProvider.XIAOMI_MIMO.defaultBaseUrl())
        assertEquals("mimo-v2.5-tts", TtsApiProvider.XIAOMI_MIMO.defaultModel())
        assertEquals("https://api.fish.audio/v1", TtsApiProvider.FISH_AUDIO.defaultBaseUrl())
        assertEquals("s2.1-pro", TtsApiProvider.FISH_AUDIO.defaultModel())
        val mimo = TtsSettings(aiProvider = TtsApiProvider.XIAOMI_MIMO, aiBaseUrl = "https://minimax-proxy.test/v1")
        assertTrue(mimo.aiUsesStyleControls)
        assertTrue(mimo.aiSupportsPitch)
        assertFalse(mimo.aiIsMiniMax)
        val fish = mimo.copy(aiProvider = TtsApiProvider.FISH_AUDIO)
        assertTrue(fish.aiSupportsVolume)
        assertFalse(fish.aiSupportsPitch)
        assertFalse(fish.aiIsMiniMax)
        assertNotEquals(TtsSettingsStore.apiKeyAlias(mimo.aiProvider), TtsSettingsStore.apiKeyAlias(fish.aiProvider))
    }
    @Test
    fun `GMI preset uses request queue defaults`() {
        assertEquals("https://console.gmicloud.ai", TtsApiProvider.GMI_CLOUD.defaultBaseUrl())
        assertEquals("minimax-tts-speech-2.8-hd", TtsApiProvider.GMI_CLOUD.defaultModel())

        val settings = TtsSettings(
            aiProvider = TtsApiProvider.GMI_CLOUD,
            aiBaseUrl = TtsApiProvider.GMI_CLOUD.defaultBaseUrl()
        )
        assertTrue(settings.aiIsGmiCloud)
        assertFalse(settings.aiIsMiniMax)
    }

    @Test
    fun `GMI base URL switches compatible preset to request queue`() {
        val settings = TtsSettings(
            aiProvider = TtsApiProvider.OPENAI_COMPAT,
            aiBaseUrl = "https://console.gmicloud.ai"
        )
        assertTrue(settings.aiIsGmiCloud)
        assertFalse(settings.aiIsMiniMax)
    }
}
