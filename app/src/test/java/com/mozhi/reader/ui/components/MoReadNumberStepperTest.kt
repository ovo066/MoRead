package com.mozhi.reader.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoReadNumberStepperTest {
    @Test fun typedDraftIsClampedAndSkipsNoOps() {
        assertEquals(12, committedNumber("12", 3, 1..99))
        assertEquals(99, committedNumber("250", 3, 1..99))
        assertEquals(1, committedNumber("0", 3, 1..99))
        // 与当前值相同、清空、非数字：都视为放弃编辑，不写库。
        assertNull(committedNumber("3", 3, 1..99))
        assertNull(committedNumber("", 3, 1..99))
        assertNull(committedNumber("", null, 1..99))
        assertNull(committedNumber("abc", 3, 1..99))
        // 在「不限」状态下填数字即回到有限值。
        assertEquals(8, committedNumber("8", null, 1..99))
    }

    @Test fun nudgesClampAndLeaveUnlimitedAtTheRememberedValue() {
        assertEquals(4, steppedNumber(3, 3, 1, 0..10))
        assertEquals(10, steppedNumber(10, 10, 1, 0..10))
        assertEquals(0, steppedNumber(0, 0, -1, 0..10))
        assertEquals(6, steppedNumber(null, 6, 1, 0..10))
        assertEquals(6, steppedNumber(null, 6, -1, 0..10))
        assertEquals(10, steppedNumber(null, 40, 1, 0..10))
    }
}
