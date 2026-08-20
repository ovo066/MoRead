package com.mozhi.reader.core.library

import org.junit.Assert.assertEquals
import org.junit.Test

class EnginePolicyTest {
    @Test
    fun `推荐策略旁白系统角色 AI`() {
        val policy = AudiobookEnginePolicy.NARRATOR_SYSTEM_CHARACTERS_AI
        assertEquals(AudiobookEngine.SYSTEM, policy.engineFor(AudiobookRoleKind.NARRATOR.name))
        assertEquals(AudiobookEngine.AI, policy.engineFor(AudiobookRoleKind.CHARACTER.name))
    }

    @Test
    fun `全局策略覆盖所有角色`() {
        assertEquals(
            AudiobookEngine.SYSTEM,
            AudiobookEnginePolicy.ALL_SYSTEM.engineFor(AudiobookRoleKind.CHARACTER.name)
        )
        assertEquals(
            AudiobookEngine.AI,
            AudiobookEnginePolicy.ALL_AI.engineFor(AudiobookRoleKind.NARRATOR.name)
        )
    }
}
