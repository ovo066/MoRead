package com.mozhi.reader.core.stage

import com.mozhi.reader.core.epub.EpubLayoutDocumentParser
import com.mozhi.reader.core.epub.EpubPackageInspector
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 本地精排 EPUB 回归：真解析器 → 演出结构读取 → 编译，断言坐标不变式，并打印各镜头 / 容器的覆盖。
 * 读取 `MOREAD_EPUB_FIXTURES`（路径分隔符分隔）；未设置时跳过，书籍内容不进入仓库，只打印计数。
 */
class StageEpubBooksTest {
    @Test fun `精排书结构复用与视觉覆盖回归`() {
        val raw = System.getenv("MOREAD_EPUB_FIXTURES")
        assumeTrue("未配置本地 EPUB", !raw.isNullOrBlank())
        val limit = System.getenv("MOREAD_STAGE_CHAPTERS")?.toIntOrNull() ?: 400
        for (path in requireNotNull(raw).split(File.pathSeparator).filter(String::isNotBlank)) {
            val file = File(path)
            assertTrue(file.isFile)
            val inspector = EpubPackageInspector()
            val pkg = inspector.inspect(file)
            val css = inspector.readStylesheets(file, pkg)
            val parser = EpubLayoutDocumentParser()
            val kinds = sortedMapOf<BeatKind, Int>()
            val containers = sortedMapOf<StageContainerType, Int>()
            val screens = sortedMapOf<ScreenEffect, Int>()
            var beatsTotal = 0
            var plain = 0
            var titleArt = 0
            var breakArt = 0
            var chapters = 0
            var styled = 0
            val history = ArrayDeque<String>()
            ZipFile(file).use { zip ->
                for (spine in pkg.spine.filter { it.linear && it.href != null }) {
                    if (chapters >= limit) break
                    val resource = pkg.resources.firstOrNull { it.href.equals(spine.href, true) } ?: continue
                    val entry = zip.getEntry(resource.archivePath) ?: continue
                    val parsed = parser.parseWithText(zip.getInputStream(entry).use { it.readBytes() }, spine.index, requireNotNull(spine.href), css)
                    val text = parsed.text
                    if (text.isBlank()) continue
                    val images = parsed.images.map { StageBookImageSource(it.charOffset, it.href) }
                    val layout = StageBookLayoutReader.read(parsed.dom.bodyNode, text, images)
                    val script = StageCompiler.compile(chapters, parsed.document.documentTitle ?: "", text, StageCompileContext(history.toList(), book = layout))
                    StageAssertions.assertScript(text, script)
                    chapters++
                    if (script.titleArt != null) titleArt++
                    if (script.breakArt != null) breakArt++
                    for (b in script.beats) {
                        beatsTotal++
                        kinds[b.kind] = (kinds[b.kind] ?: 0) + 1
                        b.screen?.let { screens[it] = (screens[it] ?: 0) + 1 }
                        if (b.spans.any { s -> s.styles.any { it != SpanStyle.NAME } }) styled++
                        if ((b.kind == BeatKind.NARRATION || b.kind == BeatKind.DIALOGUE) && b.screen == null && b.containerId == null && b.spans.isEmpty() && b.enter == null) plain++
                    }
                    for (c in script.containers) containers[c.type] = (containers[c.type] ?: 0) + 1
                    history.addLast(text)
                    while (history.size > 6) history.removeFirst()
                }
            }
            println("${file.nameWithoutExtension.take(12)}: chapters=$chapters beats=$beatsTotal plain=${"%.1f".format(plain * 100.0 / beatsTotal.coerceAtLeast(1))}% styled=$styled titleArt=$titleArt breakArt=$breakArt")
            println("  kinds=$kinds")
            println("  containers=$containers screens=$screens")
        }
    }
}
