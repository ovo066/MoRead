package com.mozhi.reader.ai.audiobook

import com.mozhi.reader.feature.importer.EpubPackageInspector
import com.mozhi.reader.feature.importer.EpubLayoutDocumentParser
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Private EPUBs stay outside source control. The report contains counts and role labels, not book text. */
class AudiobookEpubFixtureTest {
    @Test fun realBooksPreserveAllTextAndExposeAttributionCoverage() {
        val paths = System.getenv("MOREAD_EPUB_FIXTURES") ?: System.getenv("MOREAD_EPUB_FIXTURE").orEmpty()
        assumeTrue("Configure a real EPUB fixture", paths.isNotBlank())
        val report = StringBuilder("book\tchapters\tcharacters\tdialogues\texplicitSpeakers\tuncertain\tlabels\n")
        paths.split(File.pathSeparator).filter(String::isNotBlank).forEach { path ->
            val file = File(path)
            assertTrue(file.isFile)
            val inspector = EpubPackageInspector()
            val pkg = inspector.inspect(file)
            val css = inspector.readStylesheets(file, pkg)
            val parser = EpubLayoutDocumentParser()
            var chapters = 0
            var chars = 0L
            var dialogues = 0
            var explicit = 0
            val names = mutableMapOf<String, Int>()
            ZipFile(file).use { zip ->
                pkg.spine.filter { it.linear && it.href != null }.forEach { spine ->
                    val resource = pkg.resources.firstOrNull { it.href.equals(spine.href, true) } ?: return@forEach
                    val entry = zip.getEntry(resource.archivePath) ?: return@forEach
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val text = parser.parseWithText(bytes, spine.index, requireNotNull(spine.href), css).text
                    val segments = DialogueRuleSegmenter.segment(text)
                    var end = 0
                    segments.forEach { segment ->
                        assertTrue(segment.startCharOffset >= end)
                        assertTrue(text.substring(end, segment.startCharOffset).all(Char::isWhitespace))
                        assertTrue(segment.endCharOffset <= text.length)
                        assertTrue(segment.endCharOffset > segment.startCharOffset)
                        end = segment.endCharOffset
                        if (segment.kind == AudiobookSegmentKind.DIALOGUE) {
                            dialogues++
                            if (segment.confidence >= .85f) {
                                explicit++
                                names[segment.roleName] = (names[segment.roleName] ?: 0) + 1
                            }
                        }
                    }
                    assertTrue(text.substring(end).all(Char::isWhitespace))
                    val targets = segments.indices.filter { segments[it].kind == AudiobookSegmentKind.DIALOGUE }.toSet()
                    val batches = buildAudiobookAttributionBatches(text, segments, targets, emptyMap())
                    assertEquals(targets, batches.flatMap { it.targetIndices }.toSet())
                    assertTrue(batches.all { it.targetIndices.size <= 18 })
                    chapters++
                    chars += text.length
                }
            }
            assertTrue(chapters > 0)
            report.append(listOf(file.name, chapters, chars, dialogues, explicit, dialogues - explicit,
                names.entries.sortedByDescending { it.value }.take(25).joinToString(";") { it.key+":"+it.value }).joinToString("\t")).append('\n')
        }
        val output = File(System.getenv("MOREAD_TTS_REPORT_DIR") ?: "build/outputs/tts-evaluation")
        output.mkdirs()
        File(output, "epub-attribution.tsv").writeText(report.toString())
    }
}
