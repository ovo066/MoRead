package com.mozhi.reader.core.library

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.zip.ZipFile
import java.util.zip.CRC32

/** A local EPUB entry, carried through layout as an image source without extracting it. */
data class EpubArchiveAsset(val archive: File, val entry: String) {
    fun encode(): String = PREFIX + URLEncoder.encode(archive.absolutePath, "UTF-8") + "!/" +
        URLEncoder.encode(entry, "UTF-8")

    companion object {
        private const val PREFIX = "epub-zip:"

        fun parse(source: String): EpubArchiveAsset? {
            if (!source.startsWith(PREFIX)) return null
            val separator = source.indexOf("!/", PREFIX.length)
            if (separator < 0) return null
            return runCatching {
                val archive = File(URLDecoder.decode(source.substring(PREFIX.length, separator), "UTF-8"))
                val entry = URLDecoder.decode(source.substring(separator + 2), "UTF-8")
                require(archive.isAbsolute && entry.isNotBlank())
                EpubArchiveAsset(archive, entry)
            }.getOrNull()
        }
    }
}

/** Small owner-scoped pool: ZipFile keeps its central directory for all images on nearby pages. */
class EpubArchivePool(private val capacity: Int = 2) : Closeable {
    private data class Handle(val size: Long, val modified: Long, val zip: ZipFile)
    private val handles = LinkedHashMap<String, Handle>(4, .75f, true)

    @Synchronized
    fun read(asset: EpubArchiveAsset, maxBytes: Int = MAX_IMAGE_BYTES): ByteArray? = runCatching {
        if (maxBytes <= 0 || !asset.archive.isFile) return null
        val file = asset.archive
        val previous = handles[file.absolutePath]
        val handle = if (previous != null && previous.size == file.length() && previous.modified == file.lastModified()) {
            previous
        } else {
            handles.remove(file.absolutePath)?.zip?.close()
            Handle(file.length(), file.lastModified(), ZipFile(file)).also { handles[file.absolutePath] = it }
        }
        while (handles.size > capacity.coerceAtLeast(1)) {
            val oldest = handles.entries.first()
            handles.remove(oldest.key)
            oldest.value.zip.close()
        }
        val entry = handle.zip.getEntry(asset.entry) ?: return null
        if (entry.isDirectory || entry.size > maxBytes) return null
        handle.zip.getInputStream(entry).use { input ->
            val output = ByteArrayOutputStream(entry.size.coerceIn(0, 64 * 1024).toInt())
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val crc = CRC32()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > maxBytes) return null
                output.write(buffer, 0, count)
                crc.update(buffer, 0, count)
            }
            if (entry.crc >= 0 && entry.crc != crc.value) return null
            output.toByteArray()
        }
    }.getOrNull()

    @Synchronized
    override fun close() {
        handles.values.forEach { runCatching { it.zip.close() } }
        handles.clear()
    }

    companion object { const val MAX_IMAGE_BYTES = 30 * 1024 * 1024 }
}

/** Store app-owned sources relative to filesDir so backup/restore does not freeze an install path. */
internal fun File.portableArchivePath(filesDir: File): String =
    if (canonicalFile.toPath().startsWith(filesDir.canonicalFile.toPath())) {
        relativeTo(filesDir).invariantSeparatorsPath
    } else absolutePath

internal fun resolveArchivePath(filesDir: File, path: String): File? = runCatching {
    val file = File(path)
    if (file.isAbsolute) file.takeIf(File::isFile)
    else File(filesDir, path).canonicalFile.takeIf {
        it.toPath().startsWith(filesDir.canonicalFile.toPath()) && it.isFile
    }
}.getOrNull()
