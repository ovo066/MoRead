package com.mozhi.reader.core.stage

import java.io.File

/**
 * 真书诊断的统计器：按章喂入原文与场记，汇总镜头分布、句首落标点、成对符号被拆开、书信与超长容器，
 * 并可按章倒出逐拍清单。只给本地诊断用，书籍内容只写进仓库外的报告。
 */
internal class StageDiagnostics(private val name: String, private val dumpChapters: Set<Int> = emptySet()) {
    private val closers = "」』”’）)》】］]，。、！？；：,.!?;:"
    private val continuation = "，、；：,;:"
    private val pairs = listOf('「' to '」', '『' to '』', '“' to '”', '《' to '》', '【' to '】', '（' to '）', '［' to '］', '[' to ']')
    val kinds = sortedMapOf<BeatKind, Int>()
    val containers = sortedMapOf<StageContainerType, Int>()
    val closerSamples = mutableListOf<String>()
    var closerCount = 0
    /** 句首是逗号、顿号、分号、冒号：一定是把一句话从中间切开了。 */
    var continuationCount = 0
    val pairSamples = mutableListOf<String>()
    var pairCount = 0
    val letterSamples = mutableListOf<String>()
    val bigContainers = mutableListOf<String>()
    var dialogue = 0
    var attributed = 0
    var asideBefore = 0
    var asideAfter = 0
    var beatsTotal = 0
    var chapters = 0
    private val dumps = StringBuilder()
    val failures = mutableListOf<String>()

    /** 不变式失败只记下来，不中断整本书的诊断。 */
    fun check(index: Int, block: () -> Unit) {
        try { block() } catch (e: AssertionError) { if (failures.size < 40) failures += "ch$index ${e.message?.take(240)}" }
    }
    val extra = StringBuilder()

    fun add(index: Int, title: String, body: String, script: StageScript) {
        chapters++
        val lineStarts = IntArray(body.count { it == '\n' } + 1)
        run {
            var k = 1
            for ((i, c) in body.withIndex()) if (c == '\n') lineStarts[k++] = i + 1
        }
        fun lineOf(offset: Int): String {
            var lo = 0
            var hi = lineStarts.size - 1
            while (lo < hi) { val mid = (lo + hi + 1) / 2; if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1 }
            val start = lineStarts[lo]
            val end = body.indexOf('\n', start).let { if (it < 0) body.length else it }
            return body.substring(start, end).trim()
        }
        fun lineStartOf(offset: Int): Int {
            var lo = 0
            var hi = lineStarts.size - 1
            while (lo < hi) { val mid = (lo + hi + 1) / 2; if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1 }
            var s = lineStarts[lo]
            while (s < body.length && body[s].isWhitespace() && body[s] != '\n') s++
            return s
        }
        for (b in script.beats) {
            beatsTotal++
            kinds[b.kind] = (kinds[b.kind] ?: 0) + 1
            if (b.kind == BeatKind.DIALOGUE) {
                dialogue++
                if (b.speaker != null) attributed++
                if (b.asideBefore != null) asideBefore++
                if (b.asideAfter != null) asideAfter++
            }
            val r = b.range ?: continue
            val t = b.text
            if (b.containerId != null || t.isEmpty()) continue
            val atLineStart = r.start <= lineStartOf(r.start) + 1
            if (t[0] in closers && !t.startsWith("——") && !atLineStart) {
                closerCount++
                if (t[0] in continuation) continuationCount++
                if (closerSamples.size < 80) closerSamples += "ch$index ${b.kind} [${t.take(50)}] ⟵ ${lineOf(r.start).take(100)}"
            }
            val line = lineOf(r.start)
            val unbalanced = pairs.any { (o, c) -> t.count { it == o } != t.count { it == c } } &&
                pairs.all { (o, c) -> line.count { it == o } == line.count { it == c } }
            if (unbalanced && !b.continued && b.kind == BeatKind.NARRATION) {
                pairCount++
                if (pairSamples.size < 80) pairSamples += "ch$index ${b.kind} [${t.take(60)}] ⟵ ${line.take(110)}"
            }
        }
        for (c in script.containers) {
            containers[c.type] = (containers[c.type] ?: 0) + 1
            val items = script.beats.filter { it.containerId == c.id }
            if (items.isEmpty()) continue
            if (c.type == StageContainerType.LETTER && letterSamples.size < 60) {
                val before = script.beats.getOrNull(script.beats.indexOfFirst { it.containerId == c.id } - 1)?.text?.take(30)
                letterSamples += "ch$index LETTER×${items.size}: ${items.first().text.take(30)} … ${items.last().text.take(30)} (prev: $before)"
            }
            if (items.size > 12 && bigContainers.size < 60) bigContainers += "ch$index ${c.type}×${items.size} fromBook=${c.fromBook}: ${items.first().text.take(30)} … ${items.last().text.take(30)}"
        }
        if (index in dumpChapters) {
            dumps.appendLine("### 第 $index 章 $title（${script.beats.size} 拍）")
            for ((i, b) in script.beats.withIndex()) {
                val who = b.speaker?.label?.let { "【$it】" }.orEmpty()
                val aside = listOfNotNull(b.asideBefore?.text?.let { "前:$it" }, b.asideAfter?.text?.let { "后:$it" }).joinToString(" ")
                val tags = listOfNotNull(
                    b.containerId?.let { "c$it" }, b.item?.sender?.let { "from=$it" }, b.item?.letterRole?.name,
                    b.screen?.name, b.enter?.name, b.emotion?.name, b.voice?.takeIf { it != DialogueVoice.QUOTE }?.name
                ).joinToString(",")
                dumps.appendLine("%3d %-9s %s%s %s %s".format(i, b.kind, who, b.text.take(80), if (aside.isEmpty()) "" else "| $aside", if (tags.isEmpty()) "" else "{$tags}"))
            }
        }
    }

    fun summary(): String =
        "$name: chapters=$chapters beats=$beatsTotal failures=${failures.size} closers=$closerCount continuation=$continuationCount pairs=$pairCount letters=${containers[StageContainerType.LETTER] ?: 0}"

    fun write(dir: File) {
        val out = StringBuilder()
        out.appendLine("# $name chapters=$chapters beats=$beatsTotal")
        out.appendLine("kinds=$kinds")
        out.appendLine("containers=$containers")
        out.appendLine("dialogue=$dialogue attributed=$attributed (${"%.1f".format(attributed * 100.0 / dialogue.coerceAtLeast(1))}%) asideBefore=$asideBefore asideAfter=$asideAfter")
        out.appendLine()
        out.appendLine("## 不变式失败 ${failures.size}")
        failures.forEach(out::appendLine)
        out.appendLine()
        out.appendLine("## 句首落标点 $closerCount（其中逗号顿号起头 $continuationCount）")
        closerSamples.forEach(out::appendLine)
        out.appendLine()
        out.appendLine("## 成对符号被拆开 $pairCount")
        pairSamples.forEach(out::appendLine)
        out.appendLine()
        out.appendLine("## 书信 ${containers[StageContainerType.LETTER] ?: 0}")
        letterSamples.forEach(out::appendLine)
        out.appendLine()
        out.appendLine("## 超长容器")
        bigContainers.forEach(out::appendLine)
        out.appendLine()
        out.append(extra)
        out.appendLine()
        out.append(dumps)
        dir.mkdirs()
        File(dir, "$name.txt").writeText(out.toString(), Charsets.UTF_8)
    }
}
