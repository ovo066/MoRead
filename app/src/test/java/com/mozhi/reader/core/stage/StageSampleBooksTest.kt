package com.mozhi.reader.core.stage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class StageSampleBooksTest {
    @Test fun `本地章节坐标与说话人覆盖率回归`() {
        val raw = System.getenv("MOREAD_STAGE_TEXT_DIRS")
        assumeTrue("未配置本地真书目录", !raw.isNullOrBlank())
        for (path in requireNotNull(raw).split(File.pathSeparator).filter { it.isNotBlank() }) {
            val directory = File(path)
            assertTrue("目录不存在: $path", directory.isDirectory)
            val files = directory.listFiles().orEmpty().filter { it.isFile && it.extension.equals("txt", true) }.sortedBy { it.name }
            assertTrue("目录没有 txt 章节: $path", files.isNotEmpty())
            val history = mutableListOf<String>()
            val counts = linkedMapOf<BeatKind, Int>()
            var dialogue = 0
            var attributed = 0
            for ((index, file) in files.withIndex()) {
                val text = file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
                val title = text.substringBefore('\n').trimEnd('\r')
                val body = text.substringAfter('\n', "")
                val script = StageCompiler.compile(index, title, body, StageCompileContext(history = history.takeLast(8)))
                StageAssertions.assertScript(body, script)
                for (beat in script.beats) {
                    counts[beat.kind] = (counts[beat.kind] ?: 0) + 1
                    if (beat.kind == BeatKind.DIALOGUE) { dialogue++; if (beat.speaker != null) attributed++ }
                }
                history += body
            }
            println("${directory.name}: chapters=${files.size}, kinds=$counts, attributed=$attributed/$dialogue (${if (dialogue == 0) 0.0 else attributed.toDouble() / dialogue})")
        }
    }
}
