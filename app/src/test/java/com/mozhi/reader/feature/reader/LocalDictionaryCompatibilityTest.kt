package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.dictionary.MdictReader
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** User-owned dictionaries stay outside the repository; only diagnostic HTML goes in build/. */
class LocalDictionaryCompatibilityTest {
    @Test fun configuredDictionaryHasReadableRenderedEntries() {
        val file = System.getenv("MOREAD_MDX_FIXTURE")?.let(::File)
        assumeTrue("Set MOREAD_MDX_FIXTURE to check a local dictionary", file?.isFile == true)
        val reader = MdictReader(file!!)
        val output = File("build/reports/dictionary-local").apply { mkdirs() }
        for (word in listOf("apple", "book", "hello", "world", "run")) {
            val definition = reader.definition(word)
            assertNotNull("Missing $word", definition)
            val parsed = org.jsoup.Jsoup.parse(definition!!)
            println("$word: chars=${definition.length}, text=${parsed.body().text().length}, scripts=${parsed.select("script").size}, links=${parsed.select("link").map { it.attr("href") }}, bodyStyle=${parsed.body().attr("style")}")
            assertTrue(dictionaryPlainText(definition).isNotBlank())
            File(output, "$word-source.html").writeText(definition)
            for (dark in listOf(false, true)) File(output, "$word-${if (dark) "dark" else "light"}.html").writeText(dictionaryHtml(definition, dark))
        }
    }
}
