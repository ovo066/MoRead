package com.mozhi.reader.core.stage

private val softInstruction = Regex("低声|轻声")
private val tremblingInstruction = Regex("颤抖|哽咽")
private val slowInstruction = Regex("缓慢|句末短停")

object StageExpressions {
    fun of(emotion: StageEmotion?, intensity: Int, instruction: String?): StageExpression {
        val ins = instruction.orEmpty()
        val softer = softInstruction.containsMatchIn(ins)
        val louder = ins.contains("高声")
        var tremble = tremblingInstruction.containsMatchIn(ins)
        var pace = 1f
        var enter: BeatEnter? = null
        var screen: ScreenEffect? = null
        var bolder = false
        var subdued = false
        var pop = false
        var warm = false
        if (softer) { enter = BeatEnter.WHISPER; pace *= 1.25f }
        if (ins.contains("急促")) pace *= .6f
        if (slowInstruction.containsMatchIn(ins)) pace *= 1.4f
        if (tremble) pace *= 1.15f
        when (emotion) {
            StageEmotion.ANGRY -> { if (intensity >= 2) { bolder = true; enter = BeatEnter.SLAM }; if (intensity >= 3) screen = ScreenEffect.SHAKE }
            StageEmotion.SAD -> { pace *= 1.3f; subdued = true }
            StageEmotion.FEAR -> { tremble = true; if (intensity >= 2) screen = ScreenEffect.VIGNETTE }
            StageEmotion.SURPRISE -> pop = true
            StageEmotion.HAPPY -> { enter = enter ?: BeatEnter.RISE; warm = true }
            StageEmotion.DISGUST -> subdued = true
            else -> Unit
        }
        return StageExpression(enter, screen, pace, softer, louder, bolder, tremble, pop, subdued, warm)
    }
}

object StageEffectBudget {
    private val strongScreen = setOf(ScreenEffect.SHAKE, ScreenEffect.FLASH, ScreenEffect.HEARTBEAT)

    /**
     * 版式特写的限频：克制档一个都不提；海报是整屏的朱砂大字，必须稀少——每章至多一张，章首几拍不提（刚演过章名卡）。
     * 只提对得最紧的句子（[StageFigures.posterStrength] 为 3：两半同位同字，如「粗看离谱，细看合理」），或 AI 导演逐字核对过的金句；
     * 只是字数对得上的四四句（「面料垂软，领口松散」）分不出好坏，交给 AI 导演挑。华丽档每章可到两张。
     * 候选里先挑 AI 金句，同分时取靠后的（情绪多半在后面攒起来）。阶梯与叠句是原文自己的节奏，不限频。
     */
    fun figures(beats: List<StageBeat>, intensity: StageIntensity): Set<Int> {
        if (intensity == StageIntensity.RESTRAINED) return emptySet()
        val out = HashSet<Int>()
        for ((i, b) in beats.withIndex()) if (b.figure == StageFigure.LADDER || b.figure == StageFigure.STACK) out += i
        val limit = if (intensity == StageIntensity.LAVISH) POSTER_LIMIT_LAVISH else POSTER_LIMIT
        val minStrength = 3
        val size = beats.size.coerceAtLeast(1)
        fun director(i: Int) = beats[i].sources["figure"] == StageSource.DIRECTOR
        fun score(i: Int): Float {
            val b = beats[i]
            val director = if (b.sources["figure"] == StageSource.DIRECTOR) 10f else 0f
            return director + StageFigures.posterStrength(b.text) * 2f + i.toFloat() / size
        }
        val candidates = beats.indices.filter {
            beats[it].figure == StageFigure.POSTER && it >= POSTER_SKIP_HEAD &&
                (director(it) || StageFigures.posterStrength(beats[it].text) >= minStrength)
        }.sortedByDescending(::score)
        val posters = mutableListOf<Int>()
        for (i in candidates) {
            if (posters.size >= limit) break
            if (posters.none { kotlin.math.abs(it - i) < POSTER_GAP }) posters += i
        }
        return out + posters
    }

    /** 每章至多几张海报（标准 / 华丽），彼此至少隔多少拍，章首跳过几拍。 */
    const val POSTER_LIMIT = 1
    const val POSTER_LIMIT_LAVISH = 2
    const val POSTER_GAP = 40
    const val POSTER_SKIP_HEAD = 3
    private val strongEnter = setOf(BeatEnter.SLAM, BeatEnter.GLITCH)
    private val motionEnter = strongEnter + setOf(BeatEnter.RISE, BeatEnter.TYPE, BeatEnter.WHISPER)
    private val defaultType = setOf(BeatKind.NARRATION, BeatKind.DIALOGUE, BeatKind.THOUGHT, BeatKind.SYSTEM, BeatKind.CAPTION)

    /** 返回与 [beats] 一一对应的最终效果。 */
    fun apply(beats: List<StageBeat>, intensity: StageIntensity, reduceMotion: Boolean = false): List<StagePresentation> {
        val gap = if (intensity == StageIntensity.LAVISH) 3 else 6
        val restrained = intensity == StageIntensity.RESTRAINED || reduceMotion
        var last = -1_000_000_000
        return beats.mapIndexed { i, b ->
            val x = StageExpressions.of(b.emotion, b.intensity, b.instruction)
            var enter = b.enter ?: x.enter ?: BeatEnter.TYPE.takeIf { b.kind in defaultType }
            var screen = b.screen ?: x.screen
            var blocked: ScreenEffect? = null
            if (screen in strongScreen) {
                if (restrained || enter in strongEnter || i - last < gap) { blocked = screen; screen = null } else last = i
            }
            if (restrained && enter in motionEnter) enter = BeatEnter.FADE
            var n = 0
            // 人名、专有名词与书里自带的样式不算效果，不占两处的名额。
            val spans = b.spans.filter { it.ink != null || SpanStyle.NAME in it.styles || SpanStyle.TERM in it.styles || ++n <= 2 }
            StagePresentation(enter, screen, x, x.paceMultiplier * when (b.pace) { BeatPace.SLOW -> 1.35f; BeatPace.FAST -> .7f; else -> 1f }, spans, blocked)
        }
    }
}
