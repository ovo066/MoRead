package com.mozhi.reader.core.readium

import android.net.Uri
import java.io.File
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.TransformingResource

/**
 * Android Uri treats a colon anywhere in a relative path as a scheme separator. Readium 3.3
 * also leaves colons unescaped when indexing ZIP entries, so otherwise valid EPUB resources
 * disappear from both the manifest and the archive. Expose escaped URLs to Readium while
 * leaving the original ZIP, entry names, IDs and document text intact.
 */
internal class EpubUriContainer private constructor(
    private val delegate: Container<Resource>,
    override val entries: Set<Url>
) : Container<Resource> by delegate {
    override fun iterator(): Iterator<Url> = entries.iterator()

    override fun get(url: Url): Resource? {
        val resource = delegate[url] ?: return null
        val extension = url.path?.substringAfterLast('.', "")?.lowercase()
        if (extension !in MARKUP_EXTENSIONS) return resource
        return object : TransformingResource(resource) {
            override suspend fun transform(data: Try<ByteArray, ReadError>): Try<ByteArray, ReadError> =
                data.map(::escapeMarkupReferences)
        }
    }

    companion object {
        private val MARKUP_EXTENSIONS = setOf("opf", "ncx", "xml", "xhtml", "html", "htm")
        private val REFERENCE_ATTRIBUTES = setOf("href", "src", "xlink:href", "full-path", "uri", "xml:base")
        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

        fun wrap(container: Container<Resource>, file: File): Container<Resource> {
            val names = ZipFile(file).use { zip ->
                zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
            }
            if (names.none { ':' in it }) return container
            val entries = names.mapNotNull { name -> Url(Uri.encode(name, "/")) }.toSet()
            return EpubUriContainer(container, entries)
        }

        internal fun escapeReference(value: String): String {
            val href = value.trim()
            if (SCHEME.containsMatchIn(href) || href.startsWith("//")) return value
            if (':' !in href) return value
            // Uri also misidentifies a colon in a relative fragment/query. Percent encoding
            // preserves the decoded target ID and query while avoiding that ambiguity.
            return href.replace(":", "%3A")
        }

        private fun escapeMarkupReferences(bytes: ByteArray): ByteArray {
            val document = bytes.inputStream().use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
            var changed = false
            document.getAllElements().forEach { element ->
                element.attributes().forEach { attribute ->
                    if (attribute.key.lowercase() in REFERENCE_ATTRIBUTES) {
                        val escaped = escapeReference(attribute.value)
                        if (escaped != attribute.value) {
                            attribute.setValue(escaped)
                            changed = true
                        }
                    }
                }
            }
            if (!changed) return bytes
            document.outputSettings().prettyPrint(false).charset(Charsets.UTF_8)
            document.charset(Charsets.UTF_8)
            return document.outerHtml().toByteArray(Charsets.UTF_8)
        }
    }
}
