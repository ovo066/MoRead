package com.mozhi.reader.core.stage

private val forumOp = Regex("楼主|LZ|lz")
private val whitespace = Regex("\\s+")
private val forumOpLabel = Regex("^(楼主|LZ|lz)$")
private val sentenceEnd = Regex("[。！？!?…]$")
private val openingBracket = Regex("^[【\\[]")
private val trailingQuestion = Regex("[？?]$")
private val chatTime = Regex("\\d{1,2}[:：]\\d{2}")
private val letterEnd = Regex("^——|^—|^落款|^此致")
private val verseSeparator = Regex("[，、。；]")
private val verseLineText = Regex("^[一-龥，、。；]{4,16}$")
private val verseFunctionChars = Regex("[了的他她我你吗呢吧着啊呀么]")
/** 第三人称起头的行：信写完了，回到旁白。 */
private val thirdPersonStart = Regex("^(?:他们|她们|它们|他|她|它)")
/** 没有落款时，一封信最多收这么多行（含抬头）。 */
private const val UNSIGNED_LETTER_LINES = 7
private const val UNSIGNED_LETTER_LINE_CHARS = 120

internal data class StageFloorItem(val line: StageLine, val text: String)
internal data class StageFloor(val floor: String, val author: String, val time: String, val items: List<StageFloorItem>, val end: Int)
internal data class StageGroup(val type: String, val end: Int, val items: List<StageLine> = emptyList(), val head: List<StageLine> = emptyList(), val floors: List<StageFloor> = emptyList())
internal data class StageGroups(val groups: Map<Int, StageGroup>, val used: BooleanArray)

internal object StageGrouping {
    fun groupLines(lines: List<StageLine>): StageGroups = with(StageRules) {
        val used = BooleanArray(lines.size)
        val groups = linkedMapOf<Int, StageGroup>()
        fun mark(a: Int, b: Int) { for (k in a until b) used[k] = true }
        fun isLabel(l: StageLine) = l.type == "floorLabel" || l.type == "forum"
        var i = 0
        while (i < lines.size) {
            if (used[i] || !isLabel(lines[i])) { i++; continue }
            val labels = mutableListOf(i)
            var j = i + 1
            var since = 0
            while (j < lines.size && since <= 5) { if (isLabel(lines[j])) { labels += j; since = 0 } else since++; j++ }
            if (labels.size < 2) { i++; continue }
            val before = lines.subList(maxOf(0, i - 3), i).joinToString("") { it.text }
            if (!forumCue.containsMatchIn(before) && labels.none { forumOp.containsMatchIn(lines[it].floor) }) { i++; continue }
            val floors = labels.mapIndexed { n, k ->
                val l = lines[k]
                val nextLabel = labels.getOrNull(n + 1)
                val floor = l.floor.replace(whitespace, "")
                var author = if (l.type == "forum") l.author else ""
                var time = ""
                val items = mutableListOf<StageFloorItem>()
                if (l.type == "forum") items += StageFloorItem(l, l.body)
                var p = k + 1
                val bound = nextLabel ?: lines.size
                if (l.type == "floorLabel") {
                    if (forumOp.containsMatchIn(floor)) author = floor
                    val a = lines.getOrNull(p)
                    if (a != null && p < bound && forumOpLabel.matches(a.text)) { author = a.text; p++ }
                    else if (a != null && p < bound && !forumOp.containsMatchIn(floor) && a.text.length <= 12 && !sentenceEnd.containsMatchIn(a.text) && !timeLine.matches(a.text)) { author = a.text; p++ }
                    val tl = lines.getOrNull(p)
                    if (tl != null && p < bound && timeLine.matches(tl.text)) { time = tl.text; p++ }
                    val limit = nextLabel ?: minOf(lines.size, p + 1)
                    while (p < limit && items.size < 4) { items += StageFloorItem(lines[p], lines[p].text); p++ }
                }
                StageFloor(floor, author, time, items, maxOf(p, nextLabel ?: p))
            }
            val head = mutableListOf<Int>()
            for (k in i - 1 downTo maxOf(0, i - 2)) {
                val t = lines[k].text
                if (used[k] || t.length > 50 || pronounAny.containsMatchIn(t)) break
                if (openingBracket.containsMatchIn(t) || forumCue.containsMatchIn(t) || trailingQuestion.containsMatchIn(t)) head.add(0, k) else break
            }
            val start = head.firstOrNull() ?: i
            val end = floors.last().end
            mark(start, end)
            groups[start] = StageGroup("forum", end, head = head.map { lines[it] }, floors = floors)
            i = end
        }
        i = 0
        while (i < lines.size) {
            if (used[i] || lines[i].type !in listOf("chatline", "kv", "sticker")) { i++; continue }
            var j = i
            while (j < lines.size && !used[j] && lines[j].type in listOf("chatline", "kv", "sticker")) j++
            val run = lines.subList(i, j)
            val msgs = run.filter { it.type != "sticker" }
            val before = lines.subList(maxOf(0, i - 2), i).joinToString("") { it.text }
            val cue = chatCue.containsMatchIn(before) || msgs.any { chatTime.containsMatchIn(it.text) }
            val names = msgs.map { it.name }
            val avg = msgs.sumOf { it.content.length }.toDouble() / maxOf(1, msgs.size)
            if (msgs.size >= 3 && msgs.size == run.size && names.toSet().size == names.size && !cue && names.all { it.length <= 6 }) {
                mark(i, j); groups[i] = StageGroup("status", j, run)
            } else if (msgs.size >= 2 && cue && avg <= 40) { mark(i, j); groups[i] = StageGroup("chat", j, run) }
            i = j
        }
        i = 0
        while (i < lines.size) {
            if (used[i]) { i++; continue }
            val l = lines[i]
            val prev = lines.subList(maxOf(0, i - 2), i).joinToString("") { it.text }
            val opener = (l.type == "salutation" || (letterLine.containsMatchIn(l.text) && l.text.length <= 30 && !StageRules.quoteMarks.containsMatchIn(l.text))) && letterPrev.containsMatchIn(prev)
            if (!opener) { i++; continue }
            var end = -1
            for (k in i + 1 until minOf(lines.size, i + 15)) {
                if (used[k] || StageRules.quoteMarks.containsMatchIn(lines[k].text)) break
                if (letterEnd.containsMatchIn(lines[k].text)) {
                    end = k + 1
                    if (lines[k].text.startsWith("此致") && lines.getOrNull(k + 1)?.text?.length?.let { it <= 12 } == true) end = k + 2
                    break
                }
            }
            if (end < 0) {
                // 没写落款的信只收紧跟着的几行：遇到第三人称起头的叙述、超长段或空行就停，宁可短也不吞旁白。
                end = i + 1
                while (end < lines.size && !used[end] && lines[end].gap == 0 && end - i < UNSIGNED_LETTER_LINES &&
                    !StageRules.quoteMarks.containsMatchIn(lines[end].text) && !thirdPersonStart.containsMatchIn(lines[end].text) &&
                    lines[end].text.length <= UNSIGNED_LETTER_LINE_CHARS
                ) end++
            }
            if (end - i >= 2) { mark(i, end); groups[i] = StageGroup("letter", end, lines.subList(i, end)); i = end } else i++
        }
        fun clauses(l: StageLine) = l.text.split(verseSeparator).filter { it.isNotEmpty() }
        fun verseLine(l: StageLine): Boolean {
            if (l.type != "prose" || !verseLineText.matches(l.text) || verseFunctionChars.containsMatchIn(l.text)) return false
            val cl = clauses(l)
            return cl.isNotEmpty() && cl.all { it.length == cl[0].length } && cl[0].length in 4..7
        }
        i = 0
        while (i < lines.size) {
            if (used[i] || !verseLine(lines[i])) { i++; continue }
            var j = i + 1
            while (j < lines.size && !used[j] && verseLine(lines[j]) && lines[j].gap == 0 && lines[j].text.length == lines[i].text.length && lines[j].text != lines[j - 1].text) j++
            val n = j - i
            if (n >= 4 || (n >= 2 && clauses(lines[i]).size >= 2)) { mark(i, j); groups[i] = StageGroup("verse", j, lines.subList(i, j)) }
            i = j
        }
        StageGroups(groups, used)
    }
}
