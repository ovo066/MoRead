package com.mozhi.reader.core.stage

/** 只给独立旁白中写明冷暖的词着色，避免把情绪推断成原文。 */
internal object StageMoodInk {
    /** 同调留足呼吸，换调稍快响应，每章仍只点染少量词。 */
    const val SAME_TONE_GAP = 10
    const val TONE_SHIFT_GAP = 3
    const val CHAPTER_LIMIT = 12

    private val cold = Regex("冰冷|冰凉|冰寒|寒意|寒气|寒光|森寒|阴寒|阴冷|刺骨|彻骨|冷冽|凛冽|冰霜|寒风|冷光|幽蓝")
    private val warm = Regex("温暖|暖意|暖洋洋|暖融融|和煦|炽热|灼热|滚烫|火光|烛光|灯火|篝火|炉火|晚霞|夕阳|余晖|朝霞|霞光")
    private val negation = Regex("不|没|未|毫无|并不|并非")

    fun apply(beats: List<StageBeat>): List<StageBeat> {
        var lastTone: SpanStyle? = null
        var last = -SAME_TONE_GAP
        var count = 0
        return beats.mapIndexed { i, beat ->
            if (count >= CHAPTER_LIMIT || beat.kind != BeatKind.NARRATION || beat.embedded ||
                beat.containerId != null || beat.ink?.colorArgb != null
            ) return@mapIndexed beat

            val span = sequenceOf(cold to SpanStyle.COLD, warm to SpanStyle.WARM)
                .flatMap { (regex, style) ->
                    regex.findAll(beat.text).map { match ->
                        StageSpan(match.range.first, match.range.last + 1, setOf(style), source = StageSource.RULE)
                    }
                }
                .sortedBy { it.start }
                .firstOrNull { candidate ->
                    val gap = if (candidate.styles.single() == lastTone) SAME_TONE_GAP else TONE_SHIFT_GAP
                    val prefix = beat.text.substring((candidate.start - 3).coerceAtLeast(0), candidate.start)
                    i - last >= gap && !negation.containsMatchIn(prefix) &&
                        beat.spans.none { it.start < candidate.end && candidate.start < it.end }
                } ?: return@mapIndexed beat

            lastTone = span.styles.single()
            last = i
            count++
            beat.copy(spans = beat.spans + span)
        }
    }
}
