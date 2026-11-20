package com.mozhi.reader.core.stage

import org.junit.Assert.*
import org.junit.Test

class StageMoodInkTest {
    private fun beat(text: String = "他走过长廊。", kind: BeatKind = BeatKind.NARRATION) =
        StageBeat(kind, text, SourceRange(100, 100 + text.length))

    @Test fun `旁白只标第一处冷词且坐标在拍内`() {
        val input = beat("屋里冰冷刺骨。")
        val result = StageMoodInk.apply(listOf(input)).single()
        assertEquals(listOf(StageSpan(2, 4, setOf(SpanStyle.COLD), source = StageSource.RULE)), result.spans)
        assertEquals(input.copy(spans = result.spans), result)
        assertTrue(input.spans.isEmpty())
    }

    @Test fun `冷暖混写按原文顺序只取一处`() {
        for ((text, style) in listOf("温暖的屋里放着冰冷的石头。" to SpanStyle.WARM, "冰凉的石头映着火光。" to SpanStyle.COLD)) {
            assertEquals(listOf(StageSpan(0, 2, setOf(style))), StageMoodInk.apply(listOf(beat(text))).single().spans)
        }
    }

    @Test fun `否定词在前三字内跳过且不泛化单字冷暖`() {
        for (text in listOf("一点也不冷", "并不温暖", "不冰冷", "没暖意", "未觉冰凉", "毫无寒意", "并非灼热", "不再觉冰冷", "冷静地望着热闹的人群。")) {
            assertTrue(text, StageMoodInk.apply(listOf(beat(text))).single().spans.isEmpty())
        }
        assertEquals(setOf(SpanStyle.COLD), StageMoodInk.apply(listOf(beat("不知何处的冰冷。"))).single().spans.single().styles)
    }

    @Test fun `前一个命中被否定后继续找合格词`() {
        val result = StageMoodInk.apply(listOf(beat("并不温暖，石壁冰冷。"))).single()
        assertEquals(listOf(StageSpan(7, 9, setOf(SpanStyle.COLD))), result.spans)
    }

    @Test fun `对白心声容器系统与已有整拍颜色都不加`() {
        for (kind in BeatKind.entries.filter { it != BeatKind.NARRATION }) {
            val input = beat("冰冷的风，温暖的灯。", kind)
            assertEquals(listOf(input), StageMoodInk.apply(listOf(input)))
        }
        assertTrue(StageMoodInk.apply(listOf(beat("好冷", BeatKind.DIALOGUE))).single().spans.isEmpty())
        val input = beat("屋里冰冷。")
        for (blocked in listOf(input.copy(embedded = true), input.copy(containerId = 0), input.copy(ink = StageInk(colorArgb = 0)))) {
            assertEquals(listOf(blocked), StageMoodInk.apply(listOf(blocked)))
        }
        assertEquals(1, StageMoodInk.apply(listOf(input.copy(ink = StageInk(bold = true)))).single().spans.size)
    }

    @Test fun `任何已有样式与命中相交时都优先保留`() {
        val input = beat("冰冷的石壁。")
        val existing = SpanStyle.entries.map { StageSpan(1, 2, setOf(it), source = StageSource.USER) } +
            StageSpan(1, 2, emptySet(), ink = StageInk(bold = true))
        for (span in existing) {
            val styled = input.copy(spans = listOf(span))
            assertEquals(listOf(styled), StageMoodInk.apply(listOf(styled)))
        }
    }

    @Test fun `人名重叠后改取下一处且相邻区间不算重叠`() {
        val name = StageSpan(0, 2, setOf(SpanStyle.NAME), nameKey = "寒光")
        val result = StageMoodInk.apply(listOf(beat("寒光走过冰冷的石壁。").copy(spans = listOf(name)))).single()
        assertEquals(listOf(name, StageSpan(4, 6, setOf(SpanStyle.COLD))), result.spans)
        val adjacent = StageSpan(0, 2, setOf(SpanStyle.TERM))
        assertEquals(listOf(adjacent, StageSpan(2, 4, setOf(SpanStyle.COLD))),
            StageMoodInk.apply(listOf(beat("屋里冰冷。").copy(spans = listOf(adjacent)))).single().spans)
    }

    @Test fun `拍内坐标沿用UTF16下标`() {
        val text = "\uD83C\uDF19冰凉的石壁。"
        assertEquals(listOf(StageSpan(2, 4, setOf(SpanStyle.COLD))), StageMoodInk.apply(listOf(beat(text))).single().spans)
    }

    @Test fun `同调十拍才再标且跳过的命中不重置间隔`() {
        for (text in listOf("屋里冰冷。", "屋里温暖。")) {
            val output = StageMoodInk.apply(List(22) { beat(text) })
            assertEquals(listOf(0, 10, 20), output.indices.filter { output[it].spans.isNotEmpty() })
        }
    }

    @Test fun `换调三拍可标且换回也按实际上一调计算`() {
        val input = List(10) { i -> beat(if (i == 0 || i in 4..6) "屋里冰冷。" else "炉火温暖。") }
        val output = StageMoodInk.apply(input)
        assertEquals(listOf(0, 3, 6, 9), output.indices.filter { output[it].spans.isNotEmpty() })
        assertEquals(listOf(SpanStyle.COLD, SpanStyle.WARM, SpanStyle.COLD, SpanStyle.WARM),
            output.flatMap { it.spans }.map { it.styles.single() })
    }

    @Test fun `间隔计入非旁白与无命中的拍`() {
        val input = listOf(beat("屋里冰冷。"), beat("温暖。", BeatKind.DIALOGUE), beat(), beat("炉火温暖。"))
        val output = StageMoodInk.apply(input)
        assertEquals(listOf(0, 3), output.indices.filter { output[it].spans.isNotEmpty() })
    }

    @Test fun `先出现的同调尚未到间隔时仍可取后面的换调词`() {
        val input = listOf(beat("屋里冰冷。"), beat(), beat(), beat("冰冷的石壁映着火光。"))
        assertEquals(listOf(StageSpan(7, 9, setOf(SpanStyle.WARM))), StageMoodInk.apply(input).last().spans)
    }

    @Test fun `每章上限十二处且下次调用重新计数`() {
        val input = List(180) { beat(if (it / 3 % 2 == 0) "屋里冰冷。" else "炉火温暖。") }
        val output = StageMoodInk.apply(input)
        assertEquals((0..33 step 3).toList(), output.indices.filter { output[it].spans.isNotEmpty() })
        assertEquals(12, output.sumOf { it.spans.size })
        assertEquals(1, StageMoodInk.apply(listOf(beat("屋里冰冷。"))).single().spans.size)
    }

    @Test fun `克制档过滤纯冷暖而其它档位包括减少动态均保留`() {
        for (text in listOf("屋里冰冷。", "炉火温暖。")) {
            val beats = StageMoodInk.apply(listOf(beat(text)))
            for (reduceMotion in listOf(false, true)) {
                assertTrue(StageEffectBudget.apply(beats, StageIntensity.RESTRAINED, reduceMotion).single().spans.isEmpty())
                for (intensity in listOf(StageIntensity.STANDARD, StageIntensity.LAVISH)) {
                    assertEquals(beats.single().spans, StageEffectBudget.apply(beats, intensity, reduceMotion).single().spans)
                }
            }
        }
    }

    @Test fun `冷暖在其它档位占强调名额而克制档过滤后留给其它强调`() {
        val cold = StageSpan(0, 2, setOf(SpanStyle.COLD))
        val warm = StageSpan(2, 4, setOf(SpanStyle.WARM))
        val stress = StageSpan(4, 6, setOf(SpanStyle.STRESS))
        val slow = StageSpan(6, 8, setOf(SpanStyle.SLOW))
        val input = listOf(beat("冰冷火光脚步慢行。").copy(spans = listOf(cold, warm, stress, slow)))
        assertEquals(listOf(stress, slow), StageEffectBudget.apply(input, StageIntensity.RESTRAINED).single().spans)
        for (intensity in listOf(StageIntensity.STANDARD, StageIntensity.LAVISH)) {
            assertEquals(listOf(cold, warm), StageEffectBudget.apply(input, intensity).single().spans)
        }
    }

    @Test fun `克制档保留混合样式与空样式的原有预算行为`() {
        val mood = StageSpan(0, 2, setOf(SpanStyle.COLD, SpanStyle.WARM))
        val name = StageSpan(0, 2, setOf(SpanStyle.NAME, SpanStyle.COLD), nameKey = "寒光")
        val term = StageSpan(2, 4, setOf(SpanStyle.TERM, SpanStyle.WARM))
        val book = StageSpan(4, 6, emptySet(), ink = StageInk(bold = true))
        val mixed = StageSpan(6, 8, setOf(SpanStyle.COLD, SpanStyle.STRESS))
        val empty = StageSpan(8, 9, emptySet())
        val extra = StageSpan(0, 2, setOf(SpanStyle.SLOW))
        val spans = listOf(mood, name, term, book, mixed, empty, extra)
        val input = listOf(beat("寒光炉火石壁脚步声。").copy(spans = spans))
        assertEquals(listOf(name, term, book, mixed, empty), StageEffectBudget.apply(input, StageIntensity.RESTRAINED).single().spans)
    }

    @Test fun `结果确定且不修改输入`() {
        val input = List(80) { i -> beat(when (i % 4) { 0 -> "屋里冰冷。"; 1 -> "并不温暖。"; 2 -> "炉火温暖。"; else -> "他走过长廊。" }) }
        val snapshot = input.map { it.copy(spans = it.spans.toList()) }
        val first = StageMoodInk.apply(input)
        assertEquals(first, StageMoodInk.apply(input))
        assertEquals(snapshot, input)
        assertTrue(StageMoodInk.apply(emptyList()).isEmpty())
    }
}
