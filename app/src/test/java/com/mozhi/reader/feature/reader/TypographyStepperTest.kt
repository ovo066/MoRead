package com.mozhi.reader.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/** 胶囊步进器的取值规则：两端不越界、按一下只走一格、拖动落点对齐网格。 */
class TypographyStepperTest {

    @Test
    fun `fraction maps range ends to zero and one`() {
        assertEquals(0f, typographyFraction(0f, 0f..2f), 1e-4f)
        assertEquals(1f, typographyFraction(2f, 0f..2f), 1e-4f)
        assertEquals(0.5f, typographyFraction(1f, 0f..2f), 1e-4f)
    }

    @Test
    fun `fraction clamps values outside the range`() {
        assertEquals(0f, typographyFraction(-3f, 0f..2f), 1e-4f)
        assertEquals(1f, typographyFraction(9f, 0f..2f), 1e-4f)
    }

    @Test
    fun `fraction of a degenerate range is zero instead of dividing by zero`() {
        assertEquals(0f, typographyFraction(1f, 1f..1f), 1e-4f)
    }

    @Test
    fun `drag position snaps to the step grid`() {
        // 0.5 落在 1.0，步长 0.25 的网格上最近的点就是 1.0
        assertEquals(1f, typographyValueAt(0.5f, 0f..2f, 0.25f), 1e-4f)
        // 0.6 → 原始 1.2，最近网格点 1.25
        assertEquals(1.25f, typographyValueAt(0.6f, 0f..2f, 0.25f), 1e-4f)
    }

    @Test
    fun `drag beyond the ends clamps to the ends`() {
        assertEquals(0f, typographyValueAt(-2f, 0f..2f, 0.25f), 1e-4f)
        assertEquals(2f, typographyValueAt(5f, 0f..2f, 0.25f), 1e-4f)
    }

    @Test
    fun `nudging from a grid point moves exactly one step`() {
        assertEquals(0.15f, steppedTypographyValue(0.10f, 0f..2f, 0.05f, 1), 1e-4f)
        assertEquals(0.05f, steppedTypographyValue(0.10f, 0f..2f, 0.05f, -1), 1e-4f)
    }

    @Test
    fun `nudging from an off-grid value lands on the neighbouring grid point`() {
        // 老设置留下的 0.13：＋ 应该到 0.15 而不是越过它跳到 0.20
        assertEquals(0.15f, steppedTypographyValue(0.13f, 0f..2f, 0.05f, 1), 1e-4f)
        assertEquals(0.10f, steppedTypographyValue(0.13f, 0f..2f, 0.05f, -1), 1e-4f)
    }

    @Test
    fun `nudging never leaves the range`() {
        assertEquals(2f, steppedTypographyValue(2f, 0f..2f, 0.05f, 1), 1e-4f)
        assertEquals(0f, steppedTypographyValue(0f, 0f..2f, 0.05f, -1), 1e-4f)
    }

    @Test
    fun `nudging works on ranges that do not start at zero`() {
        assertEquals(1.05f, steppedTypographyValue(1f, 1f..2.2f, 0.05f, 1), 1e-4f)
        assertEquals(1f, steppedTypographyValue(1.04f, 1f..2.2f, 0.05f, -1), 1e-4f)
    }

    @Test
    fun `nudging works on ranges that start below zero`() {
        assertEquals(0f, steppedTypographyValue(-0.01f, -0.05f..0.2f, 0.01f, 1), 1e-4f)
        assertEquals(-0.05f, steppedTypographyValue(-0.05f, -0.05f..0.2f, 0.01f, -1), 1e-4f)
    }

    @Test
    fun `value and fraction round trip on grid points`() {
        val range = 0.75f..2f
        val step = 0.05f
        var value = range.start
        while (value <= range.endInclusive) {
            val back = typographyValueAt(typographyFraction(value, range), range, step)
            assertEquals(value, back, 1e-3f)
            value += step
        }
    }
}
