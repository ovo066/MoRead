package com.mozhi.reader.core.stage

import java.text.BreakIterator
import kotlin.math.roundToInt

data class RevealSchedule(
    /** 每个字形簇在展示文字里的半开区间；IntRange 用 until 构造。 */
    val clusters: List<IntRange>,
    val startMillis: IntArray,
    val totalMillis: Int
)

object StageReveal {
    fun schedule(text: String, spans: List<StageSpan>, typing: Boolean, baseCharMillis: Float = 46f, paceMultiplier: Float = 1f, speed: Float = 1f, startDelayMillis: Int = 0): RevealSchedule {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        val clusters = mutableListOf<IntRange>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) { clusters += start until end; start = end; end = iterator.next() }
        val safeSpeed = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        val ratio = paceMultiplier.toDouble() / safeSpeed
        val base = baseCharMillis * ratio
        var d = startDelayMillis.toDouble()
        val starts = IntArray(clusters.size)
        for ((i, range) in clusters.withIndex()) {
            val slow = spans.any { range.first >= it.start && range.first < it.end && SpanStyle.SLOW in it.styles }
            if (typing || slow) d += base * if (slow) 2.8 else 1.0
            starts[i] = if (typing || slow) d.roundToInt() else 0
            if (typing) {
                val c = text.substring(range.first, range.last + 1)
                if (c in listOf("，", "、", ",", "：", ":")) d += 120 * ratio
                else if (c in listOf("。", "！", "？", "!", "?", "；", ";")) d += 250 * ratio
                else if (c == "…") d += 110 * ratio
            }
        }
        return RevealSchedule(clusters, starts, (d + 360).roundToInt())
    }

    fun autoHoldMillis(beat: StageBeat, speed: Float): Long {
        val safeSpeed = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        return ((1000.0 + beat.text.length * 75) / safeSpeed + (if (beat.pace == BeatPace.SLOW) 700 else 0) + (if (beat.kind == BeatKind.TITLE) 600 else 0)).toLong()
    }
}
