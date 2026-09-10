package com.mozhi.reader.feature.importer

import com.mozhi.reader.core.library.EpubLayoutPackage
import com.mozhi.reader.core.library.EpubResourcePath
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Lazy fallback for Readium misses: one archive/index per import, never one scan per image. */
internal class EpubArchiveImageReader(
    private val file: File?,
    private val layoutPackage: EpubLayoutPackage?,
    private val openZip: (File) -> ZipFile = ::ZipFile
) : Closeable {
    private var zip: ZipFile? = null
    private var entries: Map<String, ZipEntry>? = null
    private var closed = false
    private val aliases by lazy {
        buildMap {
            layoutPackage?.resources?.forEach { resource ->
                val wanted = resource.archivePath.lowercase(Locale.ROOT)
                (EpubResourcePath.packageAliases(resource.href, layoutPackage.packageDocumentPath) +
                    EpubResourcePath.packageAliases(resource.archivePath, layoutPackage.packageDocumentPath))
                    .forEach { putIfAbsent(it.lowercase(Locale.ROOT), wanted) }
            }
        }
    }

    fun read(href: String, maxBytes: Int): ByteArray? {
        if (closed || maxBytes <= 0) return null
        val archiveFile = file ?: return null
        if (zip == null && !archiveFile.isFile) return null
        val normalized = EpubResourcePath.normalize(href)?.lowercase(Locale.ROOT) ?: return null
        return runCatching {
            val archive = zip ?: openZip(archiveFile).also { zip = it }
            val index = entries ?: buildMap {
                archive.entries().asSequence().filterNot(ZipEntry::isDirectory).forEach { entry ->
                    putIfAbsent(entry.name.replace('\\', '/').removePrefix("./").lowercase(Locale.ROOT), entry)
                }
            }.also { entries = it }
            val entry = index[aliases[normalized] ?: normalized] ?: return null
            if (entry.size > maxBytes) return null
            archive.getInputStream(entry).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }.getOrNull()
    }

    override fun close() {
        closed = true
        zip?.close()
        zip = null
        entries = null
    }
}
