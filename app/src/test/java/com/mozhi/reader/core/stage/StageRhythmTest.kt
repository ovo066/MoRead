package com.mozhi.reader.core.stage

import org.junit.Assert.*
import org.junit.Test

class StageRhythmTest {
    private fun compile(
        body: String,
        rhythm: StageNarrationRhythm,
        knownNames: List<StageKnownName> = emptyList()
    ): StageScript = StageCompiler.compile(
        12, "测试章", body, StageCompileContext(knownNames = knownNames, narration = rhythm)
    ).also { assertRanges(body, it.beats) }

    private fun prose(body: String, rhythm: StageNarrationRhythm): List<StageBeat> =
        compile(body, rhythm).beats.filter { it.kind == BeatKind.NARRATION }

    private fun sentence(length: Int): String =
        "他在桌边逐页核对手中旧纸张上的每一行文字然后把它们依次收好".repeat(6).take(length - 1) + "。"

    @Test fun `逐句与核心默认编译结果完全相同`() {
        val body = "他愣住了。风停了。下一秒。\n“你先回去吧。”\n他把书放好。"
        val defaults = StageCompiler.compile(12, "测试章", body)
        assertEquals(defaults, compile(body, StageNarrationRhythm.SENTENCE))
        assertEquals(3, defaults.beats.count { it.kind == BeatKind.NARRATION && it.range!!.end <= body.indexOf('\n') })
        assertSame(defaults.beats, StageRhythm.merge(defaults.beats, body, StageNarrationRhythm.SENTENCE))
    }

    @Test fun `合句将同段三句短旁白合成一拍并覆盖原文范围`() {
        val body = "他愣住了。风停了。下一秒。"
        val beat = prose(body, StageNarrationRhythm.MERGE).single()
        assertEquals(body, beat.text)
        assertEquals(SourceRange(0, body.length), beat.range)
        assertEquals(beat.range, beat.fullRange)
    }

    @Test fun `合句不连接两句三十字旁白但允许七十字后接六字短句`() {
        val medium = sentence(30)
        assertEquals(listOf(medium, medium), prose(medium + medium, StageNarrationRhythm.MERGE).map { it.text })
        val long = sentence(70)
        val tiny = "他收起纸张。"
        assertEquals(6, tiny.length)
        for (body in listOf(long + tiny, tiny + long)) {
            val beat = prose(body, StageNarrationRhythm.MERGE).single()
            assertEquals(body, beat.text)
            assertEquals(SourceRange(0, body.length), beat.range)
        }
    }

    @Test fun `合句按累计长度贪心判断且不超过硬上限`() {
        val parts = listOf(sentence(30), sentence(6), sentence(30))
        assertEquals(listOf(parts.take(2).joinToString(""), parts.last()),
            prose(parts.joinToString(""), StageNarrationRhythm.MERGE).map { it.text })
        val exact = sentence(72) + sentence(8)
        assertEquals(listOf(exact), prose(exact, StageNarrationRhythm.MERGE).map { it.text })
        val over = listOf(sentence(73), sentence(8))
        assertEquals(over, prose(over.joinToString(""), StageNarrationRhythm.MERGE).map { it.text })
    }

    @Test fun `合句与整段都不跨换行或对白`() {
        val first = "他收起纸张。"
        val last = "她把书放好。"
        for (rhythm in listOf(StageNarrationRhythm.MERGE, StageNarrationRhythm.PARAGRAPH)) {
            for (newline in listOf("\n", "\r\n")) {
                assertEquals(listOf(first, last), prose(first + newline + last, rhythm).map { it.text })
            }
            val beats = compile("$first\n“你先回去吧。”\n$last", rhythm).beats.drop(1)
            assertEquals(listOf(BeatKind.NARRATION, BeatKind.DIALOGUE, BeatKind.NARRATION), beats.map { it.kind })
            assertEquals(listOf(first, last), beats.filter { it.kind == BeatKind.NARRATION }.map { it.text })
        }
    }

    @Test fun `带屏幕效果的旁白保持独立`() {
        val parts = listOf("他收起纸张。", "门外传来轰然巨响。", "他放下手中的书。")
        for (rhythm in listOf(StageNarrationRhythm.MERGE, StageNarrationRhythm.PARAGRAPH)) {
            val beats = prose(parts.joinToString(""), rhythm)
            assertEquals(parts, beats.map { it.text })
            assertEquals(ScreenEffect.SHAKE, beats[1].screen)
        }
    }

    @Test fun `整段将六句短旁白收为一拍并在一百六十字处封顶`() {
        val short = "他收起纸张。她合上书本。他走到门边。她把笔放下。他转过身去。她跟了上去。"
        assertEquals(listOf(short), prose(short, StageNarrationRhythm.PARAGRAPH).map { it.text })
        val part = sentence(30)
        val beats = prose(part.repeat(6), StageNarrationRhythm.PARAGRAPH)
        assertEquals(listOf(150, 30), beats.map { it.text.length })
        assertEquals(part.repeat(6), beats.joinToString("") { it.text })
        val exact = sentence(80).repeat(2)
        assertEquals(listOf(exact), prose(exact, StageNarrationRhythm.PARAGRAPH).map { it.text })
    }

    @Test fun `合并后的人名强调随文字移动且仍指向原名`() {
        val body = "他收起纸张。林晚走到门边。沈舟把书放好。"
        val names = listOf(StageKnownName("林晚"), StageKnownName("沈舟"))
        for (rhythm in listOf(StageNarrationRhythm.MERGE, StageNarrationRhythm.PARAGRAPH)) {
            val beat = compile(body, rhythm, names).beats.single { it.kind == BeatKind.NARRATION }
            val spans = beat.spans.filter { SpanStyle.NAME in it.styles }
            assertEquals(listOf("林晚", "沈舟"), spans.map { beat.text.substring(it.start, it.end) })
            for (span in spans) {
                assertEquals(body.indexOf(requireNotNull(span.nameKey)), span.start)
                assertEquals(StageSource.DOSSIER, span.source)
            }
        }
    }

    @Test fun `混合章节在三档节奏下范围仍单调且互不重叠`() {
        val body = "  😀他收起纸张。她把书放好。\n“你先回去吧。”\n门外传来轰然巨响。他走到门边。\n（他还要继续等吗？）"
        for (rhythm in StageNarrationRhythm.entries) {
            StageAssertions.assertScript(body, compile(body, rhythm))
        }
    }

    @Test fun `段内空白中文不加空格而ASCII边界只留一个空格`() {
        val cases = listOf(
            Triple("他收起纸张。", "她把书放好。", ""),
            Triple("Ready!", "他收起纸张。", " "),
            Triple("他收起纸张。", "Room 2。", " "),
            Triple("他记下编号2", "然后收起纸张。", " "),
            Triple("Ready!", "Go!", " ")
        )
        for ((first, last, separator) in cases) {
            val body = first + " \t  " + last
            val a = StageBeat(BeatKind.NARRATION, first, SourceRange(0, first.length))
            val start = body.length - last.length
            val b = StageBeat(BeatKind.NARRATION, last, SourceRange(start, body.length),
                spans = listOf(StageSpan(0, 1, setOf(SpanStyle.STRESS))))
            val merged = StageRhythm.merge(listOf(a, b), body, StageNarrationRhythm.MERGE).single()
            assertEquals(first + separator + last, merged.text)
            assertEquals(SourceRange(0, body.length), merged.fullRange)
            val span = merged.spans.single()
            assertEquals(first.length + separator.length, span.start)
            assertEquals(last.take(1), merged.text.substring(span.start, span.end))
        }
    }

    @Test fun `无原文空白时ASCII边界也不添空格`() {
        val body = "Ready!Go!"
        val beats = listOf(
            StageBeat(BeatKind.NARRATION, "Ready!", SourceRange(0, 6)),
            StageBeat(BeatKind.NARRATION, "Go!", SourceRange(6, body.length))
        )
        assertEquals(body, StageRhythm.merge(beats, body, StageNarrationRhythm.MERGE).single().text)
    }

    @Test fun `来源映射合并且后拍同名字段优先`() {
        val body = "他收起纸张。她把书放好。"
        val a = StageBeat(BeatKind.NARRATION, body.take(6), SourceRange(0, 6),
            sources = mapOf("pace" to StageSource.RULE, "ink" to StageSource.DOSSIER))
        val b = StageBeat(BeatKind.NARRATION, body.drop(6), SourceRange(6, body.length),
            sources = mapOf("pace" to StageSource.USER, "emotion" to StageSource.AUDIOBOOK))
        val beats = listOf(a, b)
        val merged = StageRhythm.merge(beats, body, StageNarrationRhythm.MERGE)
        assertEquals(mapOf("pace" to StageSource.USER, "ink" to StageSource.DOSSIER, "emotion" to StageSource.AUDIOBOOK),
            merged.single().sources)
        assertEquals(merged, StageRhythm.merge(beats, body, StageNarrationRhythm.MERGE))
        assertEquals("他收起纸张。", a.text)
        assertEquals(StageSource.RULE, a.sources["pace"])
    }

    @Test fun `效果容器提示语与不同元数据均阻断合并`() {
        val body = "他收起纸张。她把书放好。"
        val a = StageBeat(BeatKind.NARRATION, body.take(6), SourceRange(0, 6))
        val b = StageBeat(BeatKind.NARRATION, body.drop(6), SourceRange(6, body.length))
        val variants = listOf(
            b.copy(kind = BeatKind.THOUGHT), b.copy(containerId = 1), b.copy(embedded = true),
            b.copy(figure = StageFigure.POSTER), b.copy(figureGroup = 1),
            b.copy(screen = ScreenEffect.SHAKE), b.copy(enter = BeatEnter.FADE),
            b.copy(pace = BeatPace.SLOW), b.copy(ink = StageInk(bold = true)),
            b.copy(box = StageBox(radiusPx = 2f)), b.copy(emotion = StageEmotion.SAD),
            b.copy(intensity = 1), b.copy(instruction = "缓慢"), b.copy(overlayOn = 1),
            b.copy(speaker = StageSpeaker("林晚", 1f, StageSource.RULE)), b.copy(voice = DialogueVoice.QUOTE),
            b.copy(item = StageContainerItem(sender = "林晚")), b.copy(continued = true),
            b.copy(systemLabel = "提示"), b.copy(image = StageImage("picture.png")),
            b.copy(asideBefore = StageAside("他收起纸张。", SourceRange(0, 6))),
            b.copy(asideAfter = StageAside("她把书放好。", SourceRange(6, body.length))),
            b.copy(range = null), b.copy(range = SourceRange(5, body.length))
        )
        for (variant in variants) {
            assertEquals(listOf(a, variant), StageRhythm.merge(listOf(a, variant), body, StageNarrationRhythm.PARAGRAPH))
            assertEquals(listOf(variant, a), StageRhythm.merge(listOf(variant, a), body, StageNarrationRhythm.PARAGRAPH))
        }
    }

    @Test fun `原文缺字或换段时不能越过间隔合并`() {
        for (gap in listOf("\n", "\r", "\u2029", "遗漏的字")) {
            val first = "他收起纸张。"
            val last = "她把书放好。"
            val body = first + gap + last
            val beats = listOf(
                StageBeat(BeatKind.NARRATION, first, SourceRange(0, first.length)),
                StageBeat(BeatKind.NARRATION, last, SourceRange(first.length + gap.length, body.length))
            )
            assertEquals(beats, StageRhythm.merge(beats, body, StageNarrationRhythm.PARAGRAPH))
        }
        assertTrue(StageRhythm.merge(emptyList(), "", StageNarrationRhythm.MERGE).isEmpty())
    }

    private fun assertRanges(body: String, beats: List<StageBeat>) {
        val occupied = mutableListOf<SourceRange>()
        var previous = 0
        for (beat in beats) {
            val range = beat.range
            if (beat.kind == BeatKind.TITLE) assertNull(range)
            else {
                requireNotNull(range)
                assertTrue("${beat.kind}: $range", range.start >= previous && range.end <= body.length)
                previous = range.end
                occupied += range
                occupied += listOfNotNull(beat.asideBefore?.range, beat.asideAfter?.range)
            }
            beat.spans.forEach { assertTrue(it.start >= 0 && it.end >= it.start && it.end <= beat.text.length) }
        }
        occupied.sortBy { it.start }
        occupied.zipWithNext().forEach { (a, b) -> assertTrue("overlap: $a $b", a.end <= b.start) }
    }
}
