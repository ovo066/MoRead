package com.mozhi.reader.core.stage

import com.mozhi.reader.core.datastore.PublisherStyleMode
import com.mozhi.reader.core.epub.EpubLayoutDocumentParser
import com.mozhi.reader.core.epub.EpubPackageInspector
import com.mozhi.reader.core.epub.dom.EpubDomNode
import com.mozhi.reader.core.epub.style.EpubStyleResolver
import com.mozhi.reader.core.epub.style.StyledDomNode
import com.mozhi.reader.core.library.EpubStylesheetText
import com.mozhi.reader.feature.importer.TextEncodingDetector
import com.mozhi.reader.feature.importer.TxtChapterSplitter
import com.mozhi.reader.feature.importer.TxtTocRule
import java.io.File
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 本地诊断：把真书逐章编译成场记，写出镜头分布、成对符号被拆开、句首落标点、超长容器与精排 class 清单。
 * 只在设置报告目录 `MOREAD_STAGE_REPORT` 时运行，书籍内容只写进仓库外的报告：
 * - `MOREAD_EPUB_FIXTURES`：EPUB 路径（路径分隔符分隔）；
 * - `MOREAD_TXT_BOOKS`：放 TXT 原书的目录，按导入同一套编码探测与分章规则切章；
 * - `MOREAD_STAGE_CHAPTERS`：每本最多编译多少章；`MOREAD_STAGE_DUMP`：倒出逐拍清单的章号。
 */
class StageBookDiagnosticsTest {
    private val limit get() = System.getenv("MOREAD_STAGE_CHAPTERS")?.toIntOrNull() ?: 400
    private val dumpChapters get() = System.getenv("MOREAD_STAGE_DUMP")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: setOf(3)

    @Test fun `精排书演出诊断报告`() {
        val raw = System.getenv("MOREAD_EPUB_FIXTURES")
        val reportDir = System.getenv("MOREAD_STAGE_REPORT")
        assumeTrue("未配置本地 EPUB 与报告目录", !raw.isNullOrBlank() && !reportDir.isNullOrBlank())
        for (path in requireNotNull(raw).split(File.pathSeparator).filter(String::isNotBlank)) {
            val file = File(path)
            val diag = StageDiagnostics(file.nameWithoutExtension, dumpChapters)
            val inspector = EpubPackageInspector()
            val pkg = inspector.inspect(file)
            val css = inspector.readStylesheets(file, pkg)
            val sheets = css.entries.distinctBy { it.value }.map { EpubStylesheetText(it.key, it.value) }
            val parser = EpubLayoutDocumentParser()
            val classInventory = linkedMapOf<String, ClassInfo>()
            val history = ArrayDeque<String>()
            var chapters = 0
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
                    val title = parsed.document.documentTitle ?: ""
                    val script = StageCompiler.compile(chapters, title, text, StageCompileContext(history.toList(), book = layout))
                    diag.check(chapters) { StageAssertions.assertScript(text, script) }
                    runCatching {
                        val linked = parsed.document.stylesheetHrefs
                        val available = sheets + parsed.dom.embeddedStylesheets
                        val chosen = if (linked.isEmpty()) available else linked.mapNotNull { href -> available.firstOrNull { it.href.equals(href, true) } }.ifEmpty { available }
                        val styled = EpubStyleResolver(chosen, 360f, 720f, 16f, 0xFF000000.toInt(), PublisherStyleMode.RESPECT, documentHref = parsed.document.href)
                            .resolve(parsed.dom.bodyNode, parsed.dom.htmlNode)
                        collectClasses(styled, text, classInventory, chapters)
                    }
                    diag.add(chapters, title, text, script)
                    chapters++
                    history.addLast(text)
                    while (history.size > 6) history.removeFirst()
                }
            }
            diag.extra.appendLine("## 精排 class（出现次数 / 计算样式 / 样例）")
            for ((cls, info) in classInventory.entries.sortedByDescending { it.value.count }) {
                diag.extra.appendLine("${info.count}\t.$cls\t${info.style}\t[${info.sample}] ch${info.chapter}")
            }
            diag.write(File(requireNotNull(reportDir)))
            println(diag.summary())
        }
    }

    @Test fun `纯文本演出诊断报告`() {
        val dir = System.getenv("MOREAD_TXT_BOOKS")
        val reportDir = System.getenv("MOREAD_STAGE_REPORT")
        assumeTrue("未配置本地 TXT 目录与报告目录", !dir.isNullOrBlank() && !reportDir.isNullOrBlank())
        val rules = Json { ignoreUnknownKeys = true }
            .decodeFromString<List<TxtTocRule>>(File("src/main/assets/txtTocRule.json").readText())
            .sortedBy(TxtTocRule::serialNumber)
        val splitter = TxtChapterSplitter()
        val decoder = TextEncodingDetector()
        val only = System.getenv("MOREAD_TXT_ONLY")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
        val total = StageDiagnostics("_txt_total")
        val perBook = StringBuilder()
        val files = File(requireNotNull(dir)).listFiles().orEmpty().filter { it.isFile && it.extension.equals("txt", true) }
            .filter { only == null || it.nameWithoutExtension in only }.sortedBy { it.name }
        for (file in files) {
            val text = decoder.decode(file.readBytes()).text
            val split = splitter.chooseBest(text, rules)
            val diag = StageDiagnostics(file.nameWithoutExtension, dumpChapters)
            val history = ArrayDeque<String>()
            for ((n, chapter) in split.chapters.withIndex()) {
                if (n >= limit) break
                val body = chapter.content
                if (body.isBlank()) continue
                val script = StageCompiler.compile(n, chapter.title, body, StageCompileContext(history.toList()))
                diag.check(n) { StageAssertions.assertScript(body, script) }
                diag.add(n, chapter.title, body, script)
                total.add(-1, chapter.title, body, script)
                history.addLast(body)
                while (history.size > 6) history.removeFirst()
            }
            diag.write(File(requireNotNull(reportDir), "txt"))
            perBook.appendLine(diag.summary())
        }
        total.extra.appendLine("## 各书").append(perBook)
        total.write(File(requireNotNull(reportDir)))
        println(total.summary())
    }

    private class ClassInfo(val style: String, val sample: String, val chapter: Int) { var count = 0 }

    private fun collectClasses(node: StyledDomNode, text: String, out: MutableMap<String, ClassInfo>, chapter: Int) {
        val n = node.node
        if (n.classes.isNotEmpty() && n.tag != "#text") {
            val s = node.style
            val summary = buildList {
                if (s.fontFamilies.isNotEmpty()) add("font=${s.fontFamilies.joinToString("/")}")
                if (s.colorArgb != 0xFF000000.toInt()) add("color=#%06X".format(s.colorArgb and 0xFFFFFF))
                s.background.colorArgb?.let { add("bg=#%08X".format(it)) }
                s.background.imageHref?.let { add("bgimg=${it.substringAfterLast('/')}") }
                if (s.hasBorder()) add("border=${s.borderWidths.joinToString(",") { "%.0f".format(it) }}:${s.borderColors.firstOrNull()?.let { "#%06X".format(it and 0xFFFFFF) }}")
                if (s.fontWeight >= 600) add("bold")
                if (s.italic) add("italic")
                if (s.textAlign.name != "START" && s.textAlign.name != "JUSTIFY") add("align=${s.textAlign}")
                if (s.fontSizePx != 16f) add("size=%.2fem".format(s.fontSizePx / 16f))
            }.joinToString(" ")
            val range = textRange(n)
            val sample = range?.let { text.substring(it.first, minOf(it.last + 1, it.first + 40)).replace('\n', '⏎') }.orEmpty()
            for (c in n.classes) out.getOrPut("${n.tag}.$c") { ClassInfo(summary, sample, chapter) }.count++
        }
        node.children.forEach { collectClasses(it, text, out, chapter) }
    }

    private fun textRange(n: EpubDomNode): IntRange? {
        var a = Int.MAX_VALUE
        var b = -1
        fun walk(x: EpubDomNode) {
            if (x.textStart >= 0 && x.textEnd >= x.textStart) { a = minOf(a, x.textStart); b = maxOf(b, x.textEnd) }
            x.children.forEach(::walk)
        }
        walk(n)
        return if (b <= a) null else a until b
    }
}
