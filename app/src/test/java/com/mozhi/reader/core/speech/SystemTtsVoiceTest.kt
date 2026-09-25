package com.mozhi.reader.core.speech

import org.junit.Assert.*
import org.junit.Test

class SystemTtsVoiceTest {
    @Test fun voiceIdentityIncludesEngineAndPreservesUnicodeAndSeparators() {
        val voice = SystemTtsVoiceInfo("org.example.multitts", "苏晚:温柔/女声", "zh-CN")
        assertEquals(voice.enginePackage to voice.name, SystemTtsVoiceInfo.decode(voice.id))
        assertNotEquals(voice.id, voice.copy(enginePackage = "another.engine").id)
    }
    @Test fun roleVoiceOverridesEngineWithoutChangingTuning() {
        val original = TtsSettings(systemEnginePackage = "default.engine", systemVoiceName = "old", systemRate = 1.2f)
        val voice = SystemTtsVoiceInfo("local.engine", "角色一", "zh-CN")
        val resolved = original.withSystemVoiceId(voice.id)
        assertEquals("local.engine", resolved.systemEnginePackage)
        assertEquals("角色一", resolved.systemVoiceName)
        assertEquals(1.2f, resolved.systemRate)
        assertEquals(original, original.withSystemVoiceId("female-shaonv"))
        assertEquals(original, original.withSystemVoiceId(null))
    }
    @Test fun malformedIdsCannotOverrideTheSelectedEngine() {
        listOf("system-voice:", "system-voice:?:?", "system-voice:YQ:", "system-voice:YQ:Yg:extra").forEach {
            assertNull(SystemTtsVoiceInfo.decode(it))
        }
    }
}
