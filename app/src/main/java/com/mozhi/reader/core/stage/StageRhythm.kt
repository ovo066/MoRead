package com.mozhi.reader.core.stage

/** 旁白节奏：逐句保留原拍，合句收拢短旁白，整段在同一原文段落内尽量合并；枚举名用于持久化。 */
enum class StageNarrationRhythm { SENTENCE, MERGE, PARAGRAPH }

/** 只收拢同段、同样式的普通旁白；原文坐标与行内强调跟着文字一起合并。 */
object StageRhythm {
    /** 四十字以内一拍，减少短句连续点按而保留停顿。 */
    const val MERGE_SOFT = 40
    /** 八字以内视为短尾句，允许接在较长旁白前后。 */
    const val TINY = 8
    /** 短句补齐后最多八十字，与普通旁白的单拍长度上限一致。 */
    const val MERGE_HARD = 80
    /** 整段最多一百六十字，避免长段落挤满一屏。 */
    const val PARAGRAPH_MAX = 160

    fun merge(beats: List<StageBeat>, body: String, rhythm: StageNarrationRhythm): List<StageBeat> {
        if (rhythm == StageNarrationRhythm.SENTENCE || beats.size < 2) return beats
        val out = mutableListOf<StageBeat>()
        var current = beats.first()
        for (next in beats.drop(1)) {
            val joined = mergePair(current, next, body, rhythm)
            if (joined != null) current = joined
            else {
                out += current
                current = next
            }
        }
        out += current
        return out
    }

    private fun mergePair(a: StageBeat, b: StageBeat, body: String, rhythm: StageNarrationRhythm): StageBeat? {
        if (!plain(a) || !plain(b)) return null
        // 文字、坐标、行内强调与来源按规则合并，其余元数据（包括情绪强度、说话人与续拍标记）必须一致。
        if (a.copy(text = b.text, range = b.range, spans = b.spans, sources = b.sources) != b) return null
        val ar = a.range ?: return null
        val br = b.range ?: return null
        if (br.start < ar.end || br.end > body.length) return null
        val gap = body.substring(ar.end, br.start)
        if (gap.any { !it.isWhitespace() || it == '\n' || it == '\r' || it == '\u2029' }) return null
        val total = a.text.length + b.text.length
        val fits = when (rhythm) {
            StageNarrationRhythm.SENTENCE -> false
            StageNarrationRhythm.MERGE -> total <= MERGE_SOFT ||
                ((a.text.length <= TINY || b.text.length <= TINY) && total <= MERGE_HARD)
            StageNarrationRhythm.PARAGRAPH -> total <= PARAGRAPH_MAX
        }
        if (!fits) return null
        // 可打印的非空格 ASCII 字符正好涵盖字母、数字与标点；中文句子相接不添空格。
        val separator = if (gap.isNotEmpty() &&
            (a.text.lastOrNull()?.let { it in '!'..'~' } == true || b.text.firstOrNull()?.let { it in '!'..'~' } == true)
        ) " " else ""
        val shift = a.text.length + separator.length
        return a.copy(
            text = a.text + separator + b.text,
            range = SourceRange(ar.start, br.end),
            spans = a.spans + b.spans.map { it.copy(start = it.start + shift, end = it.end + shift) },
            sources = a.sources + b.sources
        )
    }

    private fun plain(beat: StageBeat): Boolean = beat.kind == BeatKind.NARRATION &&
        beat.containerId == null && !beat.embedded && beat.figure == null && beat.figureGroup == null &&
        beat.screen == null && beat.enter == null &&
        // fullRange 由主体与提示语计算；带提示语的拍保留原样，普通旁白合并 range 即得到 fullRange 的并集。
        beat.asideBefore == null && beat.asideAfter == null
}
