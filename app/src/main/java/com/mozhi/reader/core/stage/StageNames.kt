package com.mozhi.reader.core.stage

import kotlin.math.roundToInt

private val adverbEnding = Regex("^(.{2,}?)[一-龥]{1,6}地$")
private val transliteratedName = Regex("([一-龥]{1,8})·([一-龥]{1,10})")
private val allHan = Regex("^[一-龥]+$")
private val leadingAsidePunctuation = Regex("^[，,。\\s]+")

internal data class StageNameHit(val name: String, val at: Int)
internal data class StageSpeakerMatch(val name: String, val confidence: Float)

internal object StageNames {
    fun stripAdverbs(value: String): String {
        var s = value
        while (s.length > 1) {
            val suffix = StageRules.adverbSuffix.firstOrNull { s.length > it.length && s.endsWith(it) }
            if (suffix != null) { s = s.dropLast(suffix.length); continue }
            val m = adverbEnding.find(s)
            if (m != null && m.groupValues[1].length >= 2) s = m.groupValues[1] else break
        }
        return s
    }

    private fun speakerClauseBefore(text: String, verbIndex: Int): String {
        var k = verbIndex
        while (k > 0 && text[k - 1] !in "，,。！？!?；;：:“”「」\"\n\t ") k--
        return text.substring(k, verbIndex)
    }

    fun speechStarts(text: String): List<String> = with(StageRules) {
        val out = mutableListOf<String>()
        for (m in verb.findAll(text)) {
            var clause = speakerClauseBefore(text, m.range.first)
            if (clause.isBlank()) {
                var k = m.range.first
                while (k > 0 && text[k - 1] !in "。！？!?\n“”「」") k--
                clause = text.substring(k, m.range.first)
            }
            var st = clause.replaceFirst(connective, "")
            perception.find(st)?.let { st = st.substring(it.value.length).replaceFirst(connective, "") }
            if (st.length < 2 || pronounStart.containsMatchIn(st) || indefinite.containsMatchIn(st.take(4)) || st[0] in clauseStopFirst) continue
            out += st
        }
        out
    }

    fun mineNames(texts: List<String>): LinkedHashMap<String, Int> = with(StageRules) {
        val names = linkedMapOf<String, Int>()
        val byTail = linkedMapOf<String, MutableList<String>>()
        for (text in texts) for (m in transliteratedName.findAll(text)) {
            val head = m.groupValues[1]
            var tail = m.groupValues[2]
            nameTailStop.find(tail)?.let { tail = tail.take(it.range.first) }
            tail = tail.replace(nameTitleTail, "")
            if (tail.isNotEmpty() && head.length >= 2) byTail.getOrPut(tail) { mutableListOf() }.add(head)
        }
        for ((tail, heads) in byTail) {
            if (heads.size < 2) continue
            var k = 2
            while (k < 4) {
                val ext = linkedMapOf<Char, Int>()
                var total = 0
                for (h in heads) if (h.length > k) { val c = h[h.length - k - 1]; ext[c] = (ext[c] ?: 0) + 1; total++ }
                val top = ext.maxByOrNull { it.value }
                if (total >= 2 && top != null && top.value.toDouble() / heads.size >= .75 && top.key !in clauseStopFirst && top.key !in "着向的了看对和与给把被让") k++ else break
            }
            val counts = linkedMapOf<String, Int>()
            for (h in heads) { val head = h.takeLast(k); counts[head] = (counts[head] ?: 0) + 1 }
            val best = counts.maxByOrNull { it.value } ?: continue
            val head = best.key
            val c = best.value
            if (c < 2 || head[0] in clauseStopFirst) continue
            names["$head·$tail"] = c
            val n = texts.sumOf { count(it, head) }
            if (head.length >= 2 && n >= c + 2) names[head] = (names[head] ?: 0) + n
        }
        val counts = linkedMapOf<String, Int>()
        val next = linkedMapOf<String, LinkedHashMap<Char, Int>>()
        for (st in texts.flatMap(::speechStarts)) for (length in 2..minOf(4, st.length)) {
            val p = st.take(length)
            if (!allHan.matches(p)) break
            counts[p] = (counts[p] ?: 0) + 1
            if (st.length > length) {
                val nx = next.getOrPut(p) { linkedMapOf() }
                nx[st[length]] = (nx[st[length]] ?: 0) + 1
            }
        }
        fun sentenceStarts(p: String): Int {
            var n = 0
            for (text in texts) {
                var k = text.indexOf(p)
                while (k >= 0) {
                    if (k == 0 || text[k - 1] in "。！？!?\n") n++
                    k = text.indexOf(p, k + p.length)
                }
            }
            return n
        }
        val accepted = mutableListOf<String>()
        for (length in 2..4) for ((p, c) in counts) {
            if (p.length != length || c < 3 || p in notName || p in notNameExtra || p in notSubject) continue
            if (accepted.any { p.startsWith(it) }) continue
            val top = next[p]?.values?.maxOrNull()
            if (top != null && top.toDouble() / c >= .75 && top >= 3) continue
            if (sentenceStarts(p) < maxOf(1, (c * .1).roundToInt())) continue
            accepted += p
            names[p] = (names[p] ?: 0) + c * 2
        }
        names
    }

    fun namesIn(text: String, names: Iterable<String>): List<StageNameHit> {
        val hits = mutableListOf<StageNameHit>()
        for (n in names) {
            if (n.isEmpty()) continue
            var k = text.indexOf(n)
            while (k >= 0) { hits += StageNameHit(n, k); k = text.indexOf(n, k + n.length) }
        }
        hits.sortWith(compareBy<StageNameHit> { it.at }.thenByDescending { it.name.length })
        var end = -1
        return hits.filter { if (it.at >= end) { end = it.at + it.name.length; true } else false }
    }

    fun inferSpeaker(aside: StageAside?, names: List<String>, isHead: Boolean): StageSpeakerMatch? = with(StageRules) {
        if (aside == null) return null
        val t = aside.text.replace(leadingAsidePunctuation, "")
        val lastVerb = verb.findAll(t).lastOrNull()
        if (lastVerb != null && (isHead || lastVerb.range.first < 12)) {
            val clause = speakerClauseBefore(t, lastVerb.range.first)
            if (indefinite.containsMatchIn(clause)) return null
            val inClause = namesIn(clause, names)
            if (inClause.isNotEmpty()) return StageSpeakerMatch(inClause.last().name, .9f)
            if (pronounStart.containsMatchIn(clause.replaceFirst(connective, ""))) return null
        }
        val cut = maxOf(t.lastIndexOf('。'), t.lastIndexOf('！'), t.lastIndexOf('？'))
        val sentence = if (isHead && cut >= 0 && cut < t.length - 1) t.substring(cut + 1) else t
        var s = sentence.replaceFirst(connective, "")
        perception.find(s)?.let { s = s.substring(it.value.length).replaceFirst(connective, "") }
        if (pronounStart.containsMatchIn(s) || indefinite.containsMatchIn(s.take(6))) return null
        val first = namesIn(s.take(12), names).firstOrNull()
        if (first != null && first.at == 0) return StageSpeakerMatch(first.name, if (lastVerb != null) .85f else .72f)
        null
    }
}
