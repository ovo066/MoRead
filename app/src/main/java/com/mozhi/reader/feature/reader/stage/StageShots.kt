package com.mozhi.reader.feature.reader.stage

import com.mozhi.reader.core.stage.BeatKind
import com.mozhi.reader.core.stage.StageBeat
import kotlin.math.ceil

/** 镜头类型：整页累积（旁白、对白、心声）、版式容器、版式特写（阶梯、叠句）、单拍特写（章名、字幕、拟声、系统、插图、海报……）。 */
internal enum class StageShotKind { FLOW, CONTAINER, FIGURE, SOLO }

/** 舞台上的一个镜头：同屏累积的一段拍子 [first, last]。 */
internal data class StageShot(val kind: StageShotKind, val first: Int, val last: Int, val containerId: Int? = null) {
    val key: String get() = "${kind.name}:$first"
    operator fun contains(index: Int): Boolean = index in first..last
}

/** 气泡挂在哪一侧；心声没有说话人时居中。 */
internal enum class StageSide { LEFT, RIGHT, CENTER }

/** 分页估算用的尺寸（像素）。中文按一字一格估行数，西文按 0.55 格。 */
internal data class StageMetrics(
    val widthPx: Float,
    val heightPx: Float,
    val proseFontPx: Float,
    val proseLinePx: Float,
    val paragraphGapPx: Float,
    val balloonFontPx: Float,
    val balloonLinePx: Float,
    /** 气泡左右内边距（估算可用宽度）与上下内边距加尾巴（估算高度）。 */
    val balloonPadHPx: Float,
    val balloonPadVPx: Float,
    val captionFontPx: Float,
    val captionLinePx: Float,
    val blockGapPx: Float,
    val indentEm: Float = 2f
)

internal class StagePlan(
    val shots: List<StageShot>,
    /** 每拍所在镜头的序号。 */
    private val shotOf: IntArray,
    /** 对白与心声气泡的左右。 */
    val sides: Map<Int, StageSide>,
    /** 这拍旁白另起一段（与上一拍不在同一原文段落）。 */
    val newParagraph: BooleanArray,
    /** 这拍旁白接着上一页没排完的段落：新页上不缩进。 */
    val carriesParagraph: BooleanArray
) {
    fun shotAt(index: Int): StageShot? = shotOf.getOrNull(index)?.let(shots::getOrNull)
    fun shotIndex(index: Int): Int = shotOf.getOrElse(index) { -1 }
}

/**
 * 把一章的拍子排成镜头：旁白、对白、心声在一页里累积，估算的高度到页高的 [PAGE_FILL] 就翻页；
 * 版式容器整组一个镜头；其余每拍一个特写镜头。纯函数，同样的输入总是同样的分页，回看与跳转时页不会变。
 */
internal object StagePlanner {
    /** 一页最多排到可用高度的这个比例；估算偏差由界面的贴底滚动兜住，不会遮字。 */
    const val PAGE_FILL = 0.94f
    /** 气泡最宽占内容区的比例。 */
    const val BALLOON_WIDTH = 0.82f
    private val FLOW_KINDS = setOf(BeatKind.NARRATION, BeatKind.DIALOGUE, BeatKind.THOUGHT, BeatKind.NOTE)

    fun flowable(beat: StageBeat): Boolean =
        beat.containerId == null && beat.overlayOn == null && beat.kind in FLOW_KINDS

    /** [promoted]：被提成特写的拍（海报单独成镜头，阶梯与叠句按组成一个镜头），即使是旁白也不进整页。 */
    fun plan(beats: List<StageBeat>, body: String, metrics: StageMetrics, single: Boolean, promoted: Set<Int> = emptySet()): StagePlan {
        val shots = mutableListOf<StageShot>()
        val newParagraph = BooleanArray(beats.size)
        val carries = BooleanArray(beats.size)
        val capacity = metrics.heightPx * PAGE_FILL
        fun flow(i: Int) = flowable(beats[i]) && i !in promoted
        var i = 0
        while (i < beats.size) {
            val beat = beats[i]
            val cid = beat.containerId ?: beat.overlayOn
            if (cid != null) {
                var j = i
                while (j + 1 < beats.size && (beats[j + 1].containerId ?: beats[j + 1].overlayOn) == cid) j++
                shots += StageShot(StageShotKind.CONTAINER, i, j, cid)
                i = j + 1
                continue
            }
            val group = beat.figureGroup?.takeIf { i in promoted }
            if (group != null) {
                var j = i
                while (j + 1 < beats.size && beats[j + 1].figureGroup == group && (j + 1) in promoted) j++
                shots += StageShot(StageShotKind.FIGURE, i, j)
                i = j + 1
                continue
            }
            if (!flow(i)) {
                shots += StageShot(StageShotKind.SOLO, i, i)
                i++
                continue
            }
            var pageStart = i
            var used = 0f
            var paraUnits = 0f
            var paraLines = 0f
            var j = i
            while (j < beats.size && flow(j)) {
                val b = beats[j]
                val prev = beats.getOrNull(j - 1)?.takeIf { j > i }
                val samePara = prev != null && sameParagraph(body, prev, b)
                // 同一原文段落里相邻的两句旁白排成一段；对白之后接回来的旁白另起一块但不缩进。
                val continues = samePara && prev?.kind == BeatKind.NARRATION && b.kind == BeatKind.NARRATION
                newParagraph[j] = b.kind == BeatKind.NARRATION && !samePara
                val cpl = (metrics.widthPx / metrics.proseFontPx).coerceAtLeast(4f)
                fun proseDelta(joinPage: Boolean): Float {
                    val start = if (joinPage) paraUnits else if (newParagraph[j]) metrics.indentEm else 0f
                    val lines = ceil((start + units(b.text)) / cpl)
                    return if (joinPage) (lines - paraLines) * metrics.proseLinePx else lines * metrics.proseLinePx + metrics.paragraphGapPx
                }
                var delta = if (b.kind == BeatKind.NARRATION) proseDelta(continues && j > pageStart) else blockHeight(b, metrics)
                val full = if (single) j > pageStart else used + delta > capacity && j > pageStart
                if (full) {
                    shots += StageShot(StageShotKind.FLOW, pageStart, j - 1)
                    pageStart = j
                    used = 0f
                    if (continues) carries[j] = true
                    delta = if (b.kind == BeatKind.NARRATION) proseDelta(false) else blockHeight(b, metrics)
                }
                if (b.kind == BeatKind.NARRATION) {
                    paraUnits = (if (continues && j > pageStart) paraUnits else if (newParagraph[j]) metrics.indentEm else 0f) + units(b.text)
                    paraLines = ceil(paraUnits / cpl)
                } else {
                    paraUnits = 0f
                    paraLines = 0f
                }
                used += delta
                j++
            }
            shots += StageShot(StageShotKind.FLOW, pageStart, j - 1)
            i = j
        }
        val shotOf = IntArray(beats.size)
        for ((n, shot) in shots.withIndex()) for (k in shot.first..shot.last) shotOf[k] = n
        return StagePlan(shots, shotOf, sides(beats, shots), newParagraph, carries)
    }

    /**
     * 气泡左右：认得说话人时同一人始终同一侧（主角在右）；认不得时按话轮左右交替，
     * 长对白拆出的续拍跟着前一段。整页镜头之间连续计算，遇到特写或容器重新开始。
     */
    fun sides(beats: List<StageBeat>, shots: List<StageShot>): Map<Int, StageSide> {
        val out = HashMap<Int, StageSide>()
        val bySpeaker = HashMap<String, StageSide>()
        var last: StageSide? = null
        for (shot in shots) {
            if (shot.kind != StageShotKind.FLOW) { bySpeaker.clear(); last = null; continue }
            for (k in shot.first..shot.last) {
                val b = beats[k]
                if (b.kind != BeatKind.DIALOGUE && b.kind != BeatKind.THOUGHT) continue
                val speaker = b.speaker?.takeIf { !it.unknown }
                val prev = beats.getOrNull(k - 1)
                val side = when {
                    b.kind == BeatKind.THOUGHT && speaker == null -> StageSide.CENTER
                    speaker?.isProtagonist == true -> StageSide.RIGHT
                    speaker != null && bySpeaker.containsKey(speaker.label) -> bySpeaker.getValue(speaker.label)
                    prev != null && prev.continued && prev.kind == b.kind && last != null -> last
                    else -> if (last == StageSide.LEFT) StageSide.RIGHT else StageSide.LEFT
                }
                if (speaker != null && side != StageSide.CENTER) bySpeaker.putIfAbsent(speaker.label, side)
                out[k] = side
                if (side != StageSide.CENTER) last = side
            }
        }
        return out
    }

    /** 原文里 [a] 与 [b] 之间没有换行：同一段。 */
    fun sameParagraph(body: String, a: StageBeat, b: StageBeat): Boolean {
        val ar = a.fullRange ?: return false
        val br = b.fullRange ?: return false
        if (br.start < ar.end) return false
        val nl = body.indexOf('\n', ar.end)
        return nl < 0 || nl >= br.start
    }

    private fun blockHeight(b: StageBeat, m: StageMetrics): Float {
        if (b.kind == BeatKind.NOTE) {
            val cpl = (m.widthPx / m.captionFontPx).coerceAtLeast(4f)
            return ceil(units(b.text) / cpl) * m.captionLinePx + m.blockGapPx
        }
        val inner = m.widthPx * BALLOON_WIDTH - 2 * m.balloonPadHPx
        val cpl = (inner / m.balloonFontPx).coerceAtLeast(4f)
        var h = ceil(units(b.text) / cpl) * m.balloonLinePx + m.balloonPadVPx + m.blockGapPx
        val captionCpl = (m.widthPx * 0.88f / m.captionFontPx).coerceAtLeast(4f)
        if (b.speaker != null) h += m.captionLinePx
        b.asideBefore?.let { h += ceil(units(it.text) / captionCpl) * m.captionLinePx }
        b.asideAfter?.let { h += ceil(units(it.text) / captionCpl) * m.captionLinePx }
        return h
    }

    /** 估算字宽：全角一格，半角 0.55 格。 */
    fun units(text: String): Float {
        var n = 0f
        for (c in text) n += if (c.code < 0x2E80) 0.55f else 1f
        return n
    }
}
