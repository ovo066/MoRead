package com.mozhi.reader.core.stage

/**
 * 氛围线索：旁白与对白里写明了的声光与情绪，换成屏幕效果和行内强调。
 *
 * 只认原文直接写出的词（「轰然」「一片漆黑」「心跳」），不推断；屏幕效果仍由
 * [StageEffectBudget] 统一限频，这里只给倾向。规则产出的字段都标 [StageSource.RULE]，
 * 有声书或用户给过的字段不覆盖。
 */
internal object StageAmbience {
    private val impact = Regex("轰然|轰的一声|轰隆|巨响|爆炸|炸开|炸裂|崩塌|坍塌|震动|剧震|地动山摇|一拳砸|狠狠砸|撞在|撞上|轰在")
    private val light = Regex("闪电|电光|雷光|刺目|刺眼的光|白光|强光|光芒大盛|金光大作|一道亮光|骤亮")
    private val dark = Regex("一片漆黑|漆黑一片|陷入黑暗|黑暗中|灯灭|熄灭了|伸手不见五指|夜色吞没|陷入沉默|死一般的寂静|鸦雀无声")
    private val heart = Regex("心跳|心脏狂跳|心脏猛地|心口一紧|怦怦|砰砰直跳|屏住呼吸|屏息")
    private val chill = Regex("寒意|毛骨悚然|头皮发麻|脊背发凉|后背发凉|浑身发冷|阴冷|诡异|恐怖|尖叫|惨叫|血迹|鲜血|尸体")
    private val stutter = Regex("([\\u4e00-\\u9fa5])、\\1|([\\u4e00-\\u9fa5])……\\2")
    private val shout = Regex("[！!]{2,}|[！!][？?]|[？?][！!]")
    private val trailingEllipsis = Regex("(……|…|——)[”」\"]?$")
    private val repeatedOno = Regex("([轰砰咚嘭啪哗嗖叮咔嚓噗嗡哒滴答])\\1{1,}")

    fun apply(beats: List<StageBeat>): List<StageBeat> = beats.map { beat ->
        when (beat.kind) {
            BeatKind.NARRATION -> narration(beat)
            BeatKind.DIALOGUE, BeatKind.THOUGHT -> speech(beat)
            else -> beat
        }
    }

    private fun narration(beat: StageBeat): StageBeat {
        if (beat.embedded || beat.screen != null) return beat
        val t = beat.text
        val screen = when {
            impact.containsMatchIn(t) -> ScreenEffect.SHAKE
            light.containsMatchIn(t) -> ScreenEffect.FLASH
            heart.containsMatchIn(t) -> ScreenEffect.HEARTBEAT
            dark.containsMatchIn(t) -> ScreenEffect.DIM
            chill.containsMatchIn(t) -> ScreenEffect.VIGNETTE
            else -> null
        }
        val spans = beat.spans + onoSpans(t)
        if (screen == null && spans.size == beat.spans.size) return beat
        val sources = if (screen != null) beat.sources + ("screen" to StageSource.RULE) else beat.sources
        return beat.copy(screen = screen, spans = spans, sources = sources)
    }

    private fun speech(beat: StageBeat): StageBeat {
        val t = beat.text
        var spans = beat.spans + onoSpans(t)
        var pace = beat.pace
        var enter = beat.enter
        // 「我、我……」这类结巴：抖字。
        stutter.find(t)?.let { m -> spans = spans + StageSpan(m.range.first, m.range.last + 1, setOf(SpanStyle.SHAKE)) }
        // 惊叹叠用：句末加粗放大（只给最后一个小句）。
        shout.findAll(t).lastOrNull()?.let { m ->
            val clauseStart = t.lastIndexOfAny(charArrayOf('，', '。', ',', '、'), m.range.first - 1).let { if (it < 0) 0 else it + 1 }
            if (m.range.last + 1 - clauseStart in 2..14) spans = spans + StageSpan(clauseStart, m.range.last + 1, setOf(SpanStyle.SHOUT))
        }
        if (pace == null && trailingEllipsis.containsMatchIn(t)) pace = BeatPace.SLOW
        if (enter == null && beat.instruction?.contains("低声") != true && t.length <= 8 && shout.containsMatchIn(t)) enter = BeatEnter.SLAM
        if (spans.size == beat.spans.size && pace == beat.pace && enter == beat.enter) return beat
        return beat.copy(spans = spans.distinctBy { it.start to it.end }, pace = pace, enter = enter)
    }

    private fun onoSpans(t: String): List<StageSpan> =
        repeatedOno.findAll(t).take(1).map { StageSpan(it.range.first, it.range.last + 1, setOf(SpanStyle.SHAKE)) }.toList()
}
