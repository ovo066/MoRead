package com.mozhi.reader.feature.reader.engine.epub

import com.mozhi.reader.feature.importer.EpubLayoutDocumentParser
import com.mozhi.reader.feature.importer.ParsedEpubLayoutChapter
import java.io.File

internal object FineLayoutFixtures {
    val names = listOf(
        "01-percent-box",
        "02-inline-emoji",
        "03-bg-paint",
        "04-chat-bubbles",
        "05-table",
        "06-dialog-cards",
        "07-footnote",
        "08-headers",
        "09-log-page",
        "10-selectors",
        "11-ruby-span",
        "12-fullpage-image"
    )

    fun stylesheets(name: String): Map<String, String> = buildMap {
        put("OEBPS/Text/main.css", resource("main.css").decodeToString())
        if (name == "09-log-page") {
            put("OEBPS/Text/log.css", resource("log.css").decodeToString())
        }
    }

    fun parse(name: String): ParsedEpubLayoutChapter {
        val xhtml = resource("$name.xhtml")
        val stylesheets = stylesheets(name)
        return EpubLayoutDocumentParser().parseWithText(
            bytes = xhtml,
            chapterIndex = names.indexOf(name),
            href = "OEBPS/Text/$name.xhtml",
            stylesheets = stylesheets
        )
    }

    fun snapshotFile(relativePath: String): File {
        val resourceRoot = sequenceOf(
            File("src/test/resources"),
            File("app/src/test/resources")
        ).firstOrNull(File::isDirectory)
            ?: error("Cannot locate app/src/test/resources from ${File(".").absolutePath}")
        return File(resourceRoot, "epub-finelayout/snapshots/$relativePath")
    }

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("epub-finelayout/$name")) {
            "Missing EPUB fine-layout fixture: $name"
        }.use { it.readBytes() }
}
