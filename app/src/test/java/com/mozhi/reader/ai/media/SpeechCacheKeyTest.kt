package com.mozhi.reader.ai.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SpeechCacheKeyTest {
    @Test fun changingProviderDefaultVoiceInvalidatesSpeechWithoutExplicitVoice() {
        fun key(defaultVoice: String) = SpeechCacheKey.build(
            providerId = -1, providerBaseUrl = "https://api.elevenlabs.io/v1", providerExtraJson = "{}",
            modelId = -1, modelName = "eleven_multilingual_v2",
            modelExtraJson = """{"body":{"voice_id":"$defaultVoice"}}""",
            text = "你好", voiceId = null, speed = null, volume = null, pitch = null, format = null,
            emotion = null, instruction = null
        )
        assertEquals(key("voice-a"), key("voice-a"))
        assertNotEquals(key("voice-a"), key("voice-b"))
    }
    @Test
    fun identicalInputsProduceSameKeyAndVoiceChangesIt() {
        fun key(voice: String) = SpeechCacheKey.build(
            providerId = 1,
            providerBaseUrl = "https://example.test",
            providerExtraJson = "{}",
            modelId = 2,
            modelName = "tts-1",
            modelExtraJson = "{}",
            text = "你好",
            voiceId = voice,
            speed = 1f,
            volume = null,
            pitch = null,
            format = "mp3",
            emotion = "温柔",
            instruction = null
        )

        assertEquals(key("nova"), key("nova"))
        assertNotEquals(key("nova"), key("alloy"))
    }
}
