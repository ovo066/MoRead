package com.mozhi.reader.ai.audiobook

import com.mozhi.reader.core.database.entity.AudiobookRoleEntity
import com.mozhi.reader.core.library.AudiobookRoleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudiobookRoleMatcherTest {
    private val roles = listOf(
        AudiobookRoleEntity(
            id = 1, bookId = 7, name = "旁白", kind = AudiobookRoleKind.NARRATOR.name
        ),
        AudiobookRoleEntity(
            id = 2, bookId = 7, name = "苏晚", aliases = "晚晚,苏小姐",
            kind = AudiobookRoleKind.CHARACTER.name
        )
    )

    @Test
    fun `角色说明和别名仍能匹配到真实角色`() {
        assertEquals(2L, resolveAudiobookRole(roles, "苏晚（女主）")?.id)
        assertEquals(2L, resolveAudiobookRole(roles, "苏小姐")?.id)
    }

    @Test
    fun `泛化对白标签不会误配人物`() {
        assertNull(resolveAudiobookRole(roles, "对白"))
    }
}
