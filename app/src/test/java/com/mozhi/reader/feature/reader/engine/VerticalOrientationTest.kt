package com.mozhi.reader.feature.reader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerticalOrientationTest {
    @Test
    fun `ideographs and kana stand upright`() {
        assertEquals(VerticalOrientation.U, VerticalOrientation.of('国'.code))
        assertEquals(VerticalOrientation.U, VerticalOrientation.of('か'.code))
        assertEquals(VerticalOrientation.U, VerticalOrientation.of(0x20BB7)) // 𠮷, CJK Ext-B
    }

    @Test
    fun `latin letters, digits and dashes lie sideways`() {
        assertEquals(VerticalOrientation.R, VerticalOrientation.of('A'.code))
        assertEquals(VerticalOrientation.R, VerticalOrientation.of('7'.code))
        assertEquals(VerticalOrientation.R, VerticalOrientation.of('—'.code))
        assertEquals(VerticalOrientation.R, VerticalOrientation.of('…'.code))
    }

    @Test
    fun `cjk punctuation uses vertical alternates`() {
        assertEquals(VerticalOrientation.TU, VerticalOrientation.of('，'.code))
        assertEquals(VerticalOrientation.TU, VerticalOrientation.of('。'.code))
        assertEquals(VerticalOrientation.TU, VerticalOrientation.of('、'.code))
        assertEquals(VerticalOrientation.TR, VerticalOrientation.of('「'.code))
        assertEquals(VerticalOrientation.TR, VerticalOrientation.of('（'.code))
        assertEquals(VerticalOrientation.TR, VerticalOrientation.of('“'.code))
    }

    @Test
    fun `presentation forms cover fullwidth punctuation`() {
        assertEquals("\uFE10", VerticalForms.of("，"))
        assertEquals("\uFE12", VerticalForms.of("。"))
        assertEquals("\uFE41", VerticalForms.of("「"))
        assertEquals("\uFE35", VerticalForms.of("（"))
        assertNull(VerticalForms.of("国"))
        assertNull(VerticalForms.of("，。"))
    }
}
