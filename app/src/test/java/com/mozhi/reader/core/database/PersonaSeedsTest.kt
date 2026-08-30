package com.mozhi.reader.core.database

import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.database.entity.PersonaExampleDialog
import com.mozhi.reader.core.database.entity.encodeEnabledTools
import com.mozhi.reader.core.database.entity.encodeExampleDialogs
import com.mozhi.reader.core.database.entity.enabledTools
import com.mozhi.reader.core.database.entity.exampleDialogs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaSeedsTest {

    @Test
    fun templatesAreOneToolAssistantAndOneRoleplay() {
        assertEquals(2, PersonaSeeds.templates.size)
        assertEquals(1, PersonaSeeds.templates.count { it.isRoleplay })
        assertEquals(1, PersonaSeeds.templates.count { !it.isRoleplay })
        assertTrue(PersonaSeeds.templates.all { it.isBuiltIn })
        assertEquals(
            PersonaSeeds.templates.size,
            PersonaSeeds.templates.map { it.name }.distinct().size
        )
        PersonaSeeds.templates.forEach { persona ->
            assertTrue(persona.name.isNotBlank())
            assertTrue(persona.personality.isNotBlank())
            assertTrue(persona.speakingStyle.isNotBlank())
            assertTrue(persona.greeting.isNotBlank())
        }
    }

    @Test
    fun seedJsonFieldsParseBackThroughEntityHelpers() {
        PersonaSeeds.templates.forEach { persona ->
            val tools = persona.enabledTools()
            assertTrue("${persona.name} 工具白名单为空", tools.isNotEmpty())
            assertTrue(tools.contains("add_annotation"))
            val dialogs = persona.exampleDialogs()
            assertTrue("${persona.name} 缺少示例对话", dialogs.isNotEmpty())
            dialogs.forEach { dialog ->
                assertTrue(dialog.user.isNotBlank())
                assertTrue(dialog.assistant.isNotBlank())
            }
        }
    }

    @Test
    fun encodeHelpersRoundTrip() {
        val tools = listOf("search_book", "recall_memory")
        val dialogs = listOf(PersonaExampleDialog(user = "问：\"引号\"", assistant = "答\n换行"))
        val persona = testPersona(
            enabledToolsJson = encodeEnabledTools(tools),
            exampleDialogsJson = encodeExampleDialogs(dialogs)
        )
        assertEquals(tools, persona.enabledTools())
        assertEquals(dialogs, persona.exampleDialogs())
    }

    @Test
    fun malformedJsonDegradesToEmptyListsInsteadOfThrowing() {
        val persona = testPersona(
            enabledToolsJson = "not json",
            exampleDialogsJson = "{\"user\":\"缺外层数组\"}"
        )
        assertEquals(emptyList<String>(), persona.enabledTools())
        assertEquals(emptyList<PersonaExampleDialog>(), persona.exampleDialogs())
    }

    private fun testPersona(
        enabledToolsJson: String,
        exampleDialogsJson: String
    ) = PersonaEntity(
        name = "测试角色",
        personality = "测试人设",
        isRoleplay = false,
        enabledToolsJson = enabledToolsJson,
        exampleDialogsJson = exampleDialogsJson,
        createdAt = 0
    )
}
