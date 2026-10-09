package com.mozhi.reader.core.datastore

import com.mozhi.reader.feature.reader.AgentExecutionStep
import com.mozhi.reader.feature.reader.AgentStepState
import com.mozhi.reader.feature.reader.processStepGroups
import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionProcessDisplayTest {

    @Test fun `defaults are compact with every detail visible`() {
        val value = CompanionProcessDisplay.decode(null)
        assertEquals(CompanionProcessMode.COMPACT, value.mode)
        assertEquals(CompanionProcessDisplay(), CompanionProcessDisplay.decode("not json"))
    }

    @Test fun `round trips and tolerates unknown fields`() {
        val value = CompanionProcessDisplay(CompanionProcessMode.DETAILED, showReasoning = false, showResults = false, expandWhileStreaming = true)
        assertEquals(value, CompanionProcessDisplay.decode(CompanionProcessDisplay.encode(value)))
        assertEquals(CompanionProcessMode.HIDDEN, CompanionProcessDisplay.decode("""{"mode":"HIDDEN","future":1}""").mode)
    }

    @Test fun `parallel steps are grouped the same way the loop ran them`() {
        fun step(id: String, tool: String) = AgentExecutionStep(id, tool, tool, AgentStepState.SUCCEEDED)
        val steps = listOf(step("1", "search_book"), step("2", "grep_book"), step("3", "write_note"), step("4", "search_book"))
        assertEquals(listOf(listOf("1", "2"), listOf("3"), listOf("4")), processStepGroups(steps).map { group -> group.map { it.callId } })
    }
}
