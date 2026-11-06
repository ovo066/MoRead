package com.mozhi.reader.core.stage

import org.junit.Assert.*
import org.junit.Test

class StageRevealTest {
    @Test fun `标点在当前字出现后追加停顿`() {
        val s = StageReveal.schedule("甲，乙。…丙", emptyList(), true, baseCharMillis = 10f)
        assertArrayEquals(intArrayOf(10, 20, 150, 160, 420, 540), s.startMillis)
        assertEquals(900, s.totalMillis)
        assertEquals(360, StageReveal.schedule("", emptyList(), true).totalMillis)
    }

    @Test fun `慢区间及速度倍率和起始等待`() {
        val slow = listOf(StageSpan(1, 2, setOf(SpanStyle.SLOW)))
        val s = StageReveal.schedule("甲乙丙", slow, true, 10f, 2f, 2f, 20)
        assertArrayEquals(intArrayOf(30, 58, 68), s.startMillis)
        assertEquals(428, s.totalMillis)
        val flat = StageReveal.schedule("甲乙丙", slow, false, 10f, startDelayMillis = 20)
        assertArrayEquals(intArrayOf(0, 48, 0), flat.startMillis)
        assertEquals(408, flat.totalMillis)
        assertEquals(360, StageReveal.schedule("甲，乙。", emptyList(), false).totalMillis)
    }

    @Test fun `代理对emoji与组合字符是一个字形簇`() {
        val s = StageReveal.schedule("甲😀e\u0301乙", emptyList(), true, 10f)
        assertEquals(listOf(0 until 1, 1 until 3, 3 until 5, 5 until 6), s.clusters)
        assertArrayEquals(intArrayOf(10, 20, 30, 40), s.startMillis)
        assertEquals(400, s.totalMillis)
    }

    @Test fun `自动停留使用UTF16字数并加慢速及章名等待`() {
        val beat = StageBeat(BeatKind.TITLE, "甲😀", null, pace = BeatPace.SLOW)
        assertEquals(2525L, StageReveal.autoHoldMillis(beat, 1f))
        assertEquals(1912L, StageReveal.autoHoldMillis(beat, 2f))
    }
}
