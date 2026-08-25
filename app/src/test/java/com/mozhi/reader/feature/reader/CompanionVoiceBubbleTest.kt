package com.mozhi.reader.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CompanionVoiceBubbleTest {
    @Test
    fun waveformIsStableAndTextDependent() {
        assertEquals(deterministicWaveform("同一句"), deterministicWaveform("同一句"))
        assertNotEquals(deterministicWaveform("第一句"), deterministicWaveform("第二句"))
        assertEquals(24, deterministicWaveform("长度").size)
    }
}
