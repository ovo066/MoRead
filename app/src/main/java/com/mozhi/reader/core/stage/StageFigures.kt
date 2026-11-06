package com.mozhi.reader.core.stage

/**
 * 版式特写：短促有力的独立短句做海报，连续的问句排成阶梯，起头或收尾相同的一串短句叠成一列。
 * 只做候选标记；真正提成特写由界面按强度档限频（[StageEffectBudget.figures]）。字一个不改。
 */
enum class StageFigure { POSTER, LADDER, STACK }

object StageFigures {
    private val punctuation = Regex("[\\s，。！？；：、,.!?;:…—～~“”「」『』\"'（）()《》【】［］]")
    private val couplet = Regex("^([^，,。！？!?]{2,7})[，,]([^，,。！？!?]{2,7})[！!。]?$")
    /** 问句、省略、引号书名号、西文数字、音译名的间隔号：都不是对联式的句子。 */
    private val noise = Regex("[？?…“”「」『』《》\"A-Za-z0-9·]")
    /** 口语里的常用双音词：「现在想想，有点后悔」字数对得上，但只是一句心里话。 */
    private val everyday = Regex("现在|有点|已经|然后|但是|可是|因为|所以|如果|只是|还是|就是|不过|其实|一下|一点|觉得|知道|什么|怎么|这样|那样|自己|大家|突然|忽然|一边|开始|没有|不是|可以|应该|一样")
    /** 口语虚词、语气词与人称：带这些的是说话，不是对句。 */
    private val casual = Regex("[了吗呢吧啊呀哦嗯哈嘛哪么我你他她它们这那的就都也还又很太真呗啦咱俺]")
    private val questioned = Regex("[？?][”」』\"]?$")

    private fun core(text: String) = text.replace(punctuation, "")

    /** 海报的字数范围（不算标点）：三三到四四。 */
    const val POSTER_MIN = 6
    const val POSTER_MAX = 8
    private const val LADDER_STEP_MAX = 30
    private const val STACK_LINE_MAX = 22

    fun apply(beats: List<StageBeat>, body: String): List<StageBeat> {
        val out = beats.toMutableList()
        fun standalone(i: Int): Boolean {
            val r = beats[i].fullRange ?: return false
            val lineStart = body.lastIndexOf('\n', (r.start - 1).coerceAtLeast(0)).let { if (it < 0 || r.start == 0) 0 else it + 1 }
            val lineEnd = body.indexOf('\n', r.end).let { if (it < 0) body.length else it }
            return body.substring(lineStart, r.start).isBlank() && body.substring(r.end, lineEnd).isBlank()
        }
        fun free(b: StageBeat) = b.containerId == null && b.overlayOn == null && b.text.isNotEmpty()
        fun core(text: String) = text.replace(punctuation, "")

        // 阶梯：至少三句连着的问句。
        var i = 0
        while (i < beats.size) {
            var j = i
            while (j < beats.size && free(beats[j]) && (beats[j].kind == BeatKind.NARRATION || beats[j].kind == BeatKind.THOUGHT) &&
                questioned.containsMatchIn(beats[j].text) && core(beats[j].text).length in 2..LADDER_STEP_MAX
            ) j++
            if (j - i >= 3) {
                for (k in i until j) out[k] = out[k].copy(figure = StageFigure.LADDER, figureGroup = i)
                i = j
            } else i++
        }

        // 叠句：至少三段独立短句，起头或收尾的两个字以上相同。
        i = 0
        while (i < beats.size) {
            fun eligible(k: Int) = k < beats.size && out[k].figure == null && free(beats[k]) && beats[k].kind == BeatKind.NARRATION &&
                standalone(k) && core(beats[k].text).length in 3..STACK_LINE_MAX
            if (!eligible(i)) { i++; continue }
            var j = i + 1
            while (eligible(j)) j++
            // 在这一串里贪心地找共享前缀或后缀（两字以上）的连续段，每段至少三句。
            val texts = (i until j).map { core(beats[it].text) }
            var s = i
            while (s + 2 < j) {
                var e = s + 1
                while (e < j && shared(texts.subList(s - i, e - i + 1)) >= 2) e++
                if (e - s >= 3) {
                    for (k in s until e) out[k] = out[k].copy(figure = StageFigure.STACK, figureGroup = s)
                    s = e
                } else s++
            }
            i = j
        }

        // 海报：只给真正撑得起一整屏朱砂大字的句子——独立成段的旁白里，三三或四四的对仗句。
        // 对白、惊叹（「什么！」「天下第一！」）、带人名的叙述（「傅文斐垂下眼，转头看向怀姣。」）一律不提；
        // 是否真的提成海报还要过 [StageEffectBudget.figures] 的强度档与每章限额。
        for ((k, b) in beats.withIndex()) {
            if (out[k].figure != null || !free(b) || !standalone(k) || b.kind != BeatKind.NARRATION) continue
            if (b.spans.any { SpanStyle.NAME in it.styles }) continue
            if (posterStrength(b.text) > 0) out[k] = out[k].copy(figure = StageFigure.POSTER)
        }
        return out
    }

    /**
     * 海报的分量：0 不够格；2 四四/三三对仗（「潮水摇摆，青山不动。」）；3 两半还在同一位置用了同一个字、
     * 对得更紧（「粗看离谱，细看合理。」「见一面，少一面。」）。不含口语虚词与人称、西文数字、省略号与引号。
     */
    fun posterStrength(text: String): Int {
        val c = core(text)
        if (c.length !in POSTER_MIN..POSTER_MAX) return 0
        if (noise.containsMatchIn(text) || casual.containsMatchIn(c) || everyday.containsMatchIn(c)) return 0
        val pair = couplet.find(text.trim())?.groupValues ?: return 0
        val a = core(pair[1])
        val b = core(pair[2])
        if (a.length != b.length || a.length !in 3..4 || a == b) return 0
        val echoes = a.indices.count { a[it] == b[it] }
        return if (echoes >= 1) 3 else 2
    }

    /** 一组短句共享的前缀或后缀长度（取较长的那个）。 */
    fun shared(texts: List<String>): Int {
        if (texts.size < 2) return 0
        val shortest = texts.minOf { it.length }
        var prefix = 0
        while (prefix < shortest && texts.all { it[prefix] == texts[0][prefix] }) prefix++
        var suffix = 0
        while (suffix < shortest && texts.all { it[it.length - 1 - suffix] == texts[0][texts[0].length - 1 - suffix] }) suffix++
        // 整句相同不算叠句；共享部分必须留出变化的字。
        val best = maxOf(prefix, suffix)
        return if (best >= shortest) 0 else best
    }
}
