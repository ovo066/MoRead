package com.mozhi.reader.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSettingsTest {
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