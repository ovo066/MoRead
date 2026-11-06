package com.mozhi.reader.core.stage

import java.io.File
import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class StageSceneCoverageTest {
    @Test fun `本地真书场景覆盖与命中抽样`() {
        val raw = System.getenv("MOREAD_STAGE_TEXT_DIRS")
        assumeTrue("未配置本地真书目录", !raw.isNullOrBlank())
        for (path in requireNotNull(raw).split(File.pathSeparator).filter { it.isNotBlank() }) {
            val directory = File(path)
            assertTrue(directory.isDirectory)
            val files = directory.listFiles().orEmpty().filter { it.isFile && it.extension.equals("txt", true) }.sortedBy { it.name }
            assertTrue(files.isNotEmpty())
            val history = ArrayDeque<String>()
            val axes = listOf(StageSetting.entries, StageTime.entries, StageLight.entries, StageMood.entries, StageWeatherCue.entries)
            val counts = axes.map { axis -> axis.associateTo(linkedMapOf()) { it.toString() to 0 } }
            val hits = StageSetting.entries.associateWith { mutableListOf<String>() }
            val seen = IntArray(StageSetting.entries.size)
            val random = Random(1701)
            var beats = 0; var switches = 0
            for ((index, file) in files.withIndex()) {
                val text = file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
                val body = text.substringAfter('\n', "")
                val script = StageCompiler.compile(index, text.substringBefore('\n').trimEnd('\r'), body, StageCompileContext(history = history.toList()))
                beats += script.beats.size
                switches += (script.scenes.size - 1).coerceAtLeast(0)
                var at = 0
                for (i in script.beats.indices) {
                    while (at + 1 < script.scenes.size && script.scenes[at + 1].fromBeat <= i) at++
                    val scene = script.scenes.getOrNull(at) ?: continue
                    val values = listOf(scene.setting, scene.time, scene.light, scene.mood, scene.weather)
                    for ((axis, value) in values.withIndex()) if (value != null) counts[axis][value.toString()] = (counts[axis][value.toString()] ?: 0) + 1
                    // 只抽实际引起布景改变的原文，不能把持续期间的无关句当作命中句。
                    if (scene.fromBeat == i && scene.setting != null && (at == 0 || script.scenes[at - 1].setting != scene.setting)) {
                        val b = script.beats[i]
                        if (b.kind == BeatKind.TITLE) continue
                        val s = scene.setting
                        val sample = listOfNotNull(b.asideBefore?.text, b.text, b.asideAfter?.text).joinToString("").take(40)
                        val n = ++seen[s.ordinal]
                        val bag = hits.getValue(s)
                        if (bag.size < 3) bag += sample else { val replace = random.nextInt(n); if (replace < 3) bag[replace] = sample }
                    }
                }
                history.addLast(body)
                if (history.size > 8) history.removeFirst()
            }
            fun percent(n: Int) = String.format(Locale.ROOT, "%.2f%%", n * 100.0 / beats)
            println("SCENE ${directory.name}: chapters=${files.size}, beats=$beats, switches=$switches")
            for (axis in counts.indices) println("  ${listOf("setting", "time", "light", "mood", "weather")[axis]} total=${percent(counts[axis].values.sum())} ${counts[axis].mapValues { percent(it.value) }}")
            for ((setting, samples) in hits) println("  HIT $setting: ${samples.joinToString(" | ")}")
        }
    }
}
