package com.mozhi.reader.core.stage

private val numericQuote = Regex("^\\d{1,3}$")
private val quietSpeech = Regex("低声|轻声|小声|喃喃|悄声|耳语|压低")
private val angrySpeech = Regex("怒|吼|喝道|厉声|咆哮|骂")
private val sadSpeech = Regex("哭|哽咽|泣")
private val tremblingSpeech = Regex("颤声|发抖|颤抖")
private val surprisedSpeech = Regex("惊|愕|失声")
private val happySpeech = Regex("笑道|笑着|大笑|笑了笑")
private val emphaticExclamation = Regex("[！!]{2,}|！？|\\?!|？！")
/** 引号里的句末标点：有它就是整句话，不是词。 */
private val innerSentenceEnd = Regex("[。！？!?]")
private val colonEnd = Regex("[：:]\\s*$")
private const val OPENERS = "“「『（(【［[《〈‘"
private const val CLOSERS = "”」』）)】］]》〉’"
private const val TRAIL = "」』”’\"）)】］]》〉"
/** 括号或引号开着超过这么多字仍没收口，就当它是错排的孤立符号，照常按句切。 */
private const val STRAY_OPEN = 120

internal data class StageQuote(val start: Int, val end: Int, val bracket: Boolean = false, val open: Boolean = false)
internal data class StageTextPart(val text: String, val start: Int, val end: Int)
internal data class StageEmotionHint(val emotion: StageEmotion? = null, val intensity: Int = 0, val instruction: String? = null)

internal object StageQuotes {
    /** 对白引号与方括号（传音、系统音）的配对；方括号按嵌套层数找收口，引号取最近的收口。 */
    private val quotePairs = mapOf('“' to '”', '「' to '」', '[' to ']', '【' to '】', '［' to '］', '"' to '"')
    private val bracketOpeners = setOf('[', '【', '［')
    /** 紧跟在短引号后面、说明它是句中词语的标点。 */
    private const val INLINE_FOLLOWERS = "，,、；;？！?!：:"
    private const val CLAUSE_FOLLOWERS = "，,、；;"

    fun findQuotes(t: String): List<StageQuote> = with(StageRules) {
        val result = mutableListOf<StageQuote>()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '”') {
                // 反着用的引号（”我要回去了。“、”抱歉。”）：行首或说话提示后面的 ” 配后面的 “ 或 ”。
                val j = t.indexOf('“', i + 1).takeIf { it > i + 1 } ?: t.indexOf('”', i + 1).takeIf { it > i + 1 } ?: -1
                if (j > 0 && (i == 0 || speechBefore.containsMatchIn(t.take(i)))) {
                    if (!inlineQuote(t, i, j, t.substring(i + 1, j), false)) result += StageQuote(i, j + 1)
                    i = j + 1
                    continue
                }
                i++
                continue
            }
            val j = matchClose(t, i)
            if (j < 0 && (c == '“' || c == '「') && (i == 0 || speechBefore.containsMatchIn(t.take(i))) && t.length - i > 3) {
                result += StageQuote(i, t.length, open = true); break
            }
            if (j > i + 1) {
                val inner = t.substring(i + 1, j)
                val bracket = c in bracketOpeners
                if (bracket && (numericQuote.matches(inner) || stickerWords.containsMatchIn(inner) || (inner.length <= 6 && !StageRules.sentencePunctuation.containsMatchIn(inner)))) { i++; continue }
                if (inlineQuote(t, i, j, inner, bracket)) { i = j + 1; continue }
                result += StageQuote(i, j + 1, bracket)
                i = j
            }
            i++
        }
        result
    }

    /** [i] 处开口符号的收口位置；方括号允许嵌套（［副本［冥婚］］），没有收口返回 -1。 */
    private fun matchClose(t: String, i: Int): Int {
        val open = t[i]
        val close = quotePairs[open] ?: return -1
        if (open == '“') {
            // 引号里套引号（叫一个“啸啸”的人）：按层数配；层数对不上时退回最近的收口，
            // 再认中英混排的 "，最后认把开口当收口用的“……。“。
            var depth = 0
            for (k in i until t.length) {
                when (t[k]) {
                    '“' -> depth++
                    '”' -> { depth--; if (depth == 0) return k }
                }
            }
            t.indexOf('”', i + 1).let { if (it >= 0) return it }
            t.indexOf('"', i + 1).let { if (it >= 0) return it }
            return t.indexOf('“', i + 1)
        }
        if (open !in bracketOpeners) {
            val j = t.indexOf(close, i + 1)
            return if (j < 0 && open == '"') t.indexOf('”', i + 1) else j
        }
        var depth = 0
        for (k in i until t.length) {
            when (t[k]) {
                open -> depth++
                close -> { depth--; if (depth == 0) return k }
            }
        }
        return -1
    }

    /**
     * 句中的引号词：「“xx”的」、并列的「“惊悚”、“恐怖”」、「叫一声“爸”，」「名曰“元坤壤精”，」「“小丑”？」。
     * 这些是词或转述，拆成对白会把一句话从中间切开，下一拍就以逗号开头；留在叙述里，引号照原文显示。
     */
    private fun inlineQuote(t: String, i: Int, j: Int, inner: String, bracket: Boolean): Boolean = with(StageRules) {
        val after = t.substring(j + 1)
        val next = after.firstOrNull()
        val wordy = inner.length <= 10 && !sentencePunctuation.containsMatchIn(inner)
        if (wordy && termAfter.containsMatchIn(after)) return true
        if (!bracket && i > 0 && !speechBefore.containsMatchIn(t.take(i)) && inner.length <= 8 && !sentencePunctuation.containsMatchIn(inner)) return true
        if (inner.length <= 12 && (next == '、' || t.getOrNull(i - 1) == '、')) return true
        // 书名号、括号里的引号（《“我好难过”单亲父亲求助》）：是标题或注释的一部分。
        if (t.getOrNull(i - 1)?.let { it in "《（(〈「『【［" } == true || next?.let { it in "》）)〉" } == true) return true
        // 引号里本身有句末标点（不算末尾那一个）：是一整句话。
        val core = inner.trimEnd('…', '—', '～', '~', '。', '！', '？', '!', '?')
        if (next == null || innerSentenceEnd.containsMatchIn(core)) return false
        if (inner.length <= 12 && next in INLINE_FOLLOWERS) return true
        val before = clauseBefore(t, i)
        return before.isNotEmpty() && !colonEnd.containsMatchIn(before) && inner.length <= 30 && next in CLAUSE_FOLLOWERS
    }

    /** 引号前、同一句里的叙述（上一个句末标点之后）。 */
    private fun clauseBefore(t: String, i: Int): String {
        var k = i
        while (k > 0 && t[k - 1] !in "。！？!?；;…\n") k--
        return t.substring(k, i).trim()
    }

    /**
     * 按句切开：句末标点在引号或括号里面时不切（「（小声跟吕耳语。吕红着脸……）」是一句），
     * 收口符号跟着前一句走；开口超过 [STRAY_OPEN] 字仍没收口，当它是错排的孤立符号。
     */
    fun splitSentences(text: String, base: Int, max: Int): List<StageTextPart> {
        val out = mutableListOf<StageTextPart>()
        fun push(from: Int, until: Int) {
            var a = from
            var b = until
            while (a < b && text[a].isWhitespace()) a++
            while (b > a && text[b - 1].isWhitespace()) b--
            if (b <= a) return
            // 整段括起来的旁注（「（……。……）」）多给一些长度，尽量不从括号中间切开。
            val bracketed = text[a] in OPENERS && (text[b - 1] in CLOSERS || (b - 2 > a && text[b - 2] in CLOSERS))
            if (b - a > (if (bracketed) max * 7 / 4 else max)) {
                val cut = cutNear(text, a, b)
                if (cut > 0) { push(a, cut); push(cut, b); return }
            }
            out += StageTextPart(text.substring(a, b), base + a, base + b)
        }
        var s = 0
        var i = 0
        var depth = 0
        var openedAt = -1
        while (i < text.length) {
            val c = text[i]
            if (c in OPENERS) { if (depth == 0) openedAt = i; depth++; i++; continue }
            if (c in CLOSERS) { if (depth > 0) depth--; i++; continue }
            val ellipsisEnd = c == '…' && (i + 1 >= text.length || (if (text[i + 1] == '…') i + 2 >= text.length || text[i + 2] in StageRules.closers else text[i + 1] in StageRules.closers))
            if (c !in "。！？!?；;" && !ellipsisEnd) { i++; continue }
            if (depth > 0 && i - openedAt <= STRAY_OPEN) { i++; continue }
            depth = 0
            var e = i + 1
            while (e < text.length && (text[e] in "。！？!?；;…" || text[e] in TRAIL)) e++
            // 「??，」「吗？」后面紧跟逗号或冒号：不是句末（多半是丢了字形的表情或嵌在句中的问句）。
            if (e < text.length && text[e] in "，,、；;：:") { i = e; continue }
            push(s, e); s = e; i = e
        }
        if (s < text.length) push(s, text.length)
        return out
    }

    /** 超长句的切点：先找中点附近的句末标点，再找逗号；优先括号与引号外面的位置，收口符号留在前半。 */
    private fun cutNear(text: String, a: Int, b: Int): Int {
        val depth = IntArray(b - a + 1)
        var d = 0
        for (k in a until b) {
            depth[k - a] = d
            when (text[k]) {
                in OPENERS -> d++
                in CLOSERS -> if (d > 0) d--
            }
        }
        depth[b - a] = d
        val mid = a + (b - a) / 2
        for (outside in listOf(true, false)) for (marks in listOf("。！？!?；;", "，,、：:")) {
            for (dist in 0 until (b - a) / 2) {
                for (k in intArrayOf(mid + dist, mid - dist)) {
                    if (k <= a + 4 || k >= b - 4 || text[k - 1] !in marks) continue
                    var cut = k
                    while (cut < b && text[cut] in TRAIL) cut++
                    // 收口符号后紧跟的逗号顿号也留在前半，后半不能以逗号起头。
                    while (cut < b && text[cut] in "，,、；;：:") cut++
                    if (outside && depth[cut - a] > 0) continue
                    if (cut < b - 2) return cut
                }
            }
        }
        return -1
    }

    fun chunkDialogue(inner: String, base: Int, max: Int): List<StageTextPart> {
        if (inner.length <= max) return listOf(StageTextPart(inner, base, base + inner.length))
        val out = mutableListOf<StageTextPart>()
        for (s in splitSentences(inner, base, max)) {
            val last = out.lastOrNull()
            if (last != null && s.end - last.start <= max) out[out.lastIndex] = StageTextPart(inner.substring(last.start - base, s.end - base), last.start, s.end) else out += s
        }
        return out
    }

    fun emotionFrom(asides: List<StageAside?>, quote: String): StageEmotionHint {
        val t = asides.joinToString("") { it?.text.orEmpty() }
        var emotion: StageEmotion? = null
        var intensity = 0
        var instruction: String? = null
        if (quietSpeech.containsMatchIn(t)) instruction = "低声"
        when {
            angrySpeech.containsMatchIn(t) -> { emotion = StageEmotion.ANGRY; intensity = 2 }
            sadSpeech.containsMatchIn(t) -> { emotion = StageEmotion.SAD; intensity = 2 }
            tremblingSpeech.containsMatchIn(t) -> instruction = "颤抖"
            surprisedSpeech.containsMatchIn(t) -> { emotion = StageEmotion.SURPRISE; intensity = 2 }
            happySpeech.containsMatchIn(t) -> { emotion = StageEmotion.HAPPY; intensity = 1 }
        }
        if (emphaticExclamation.containsMatchIn(quote)) { emotion = emotion ?: StageEmotion.SURPRISE; intensity = maxOf(intensity, 2); instruction = instruction ?: "高声" }
        return StageEmotionHint(emotion, intensity, instruction)
    }
}
