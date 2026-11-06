package com.mozhi.reader.feature.importer

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Repairs only the app's import copy. The source fingerprint still identifies the original file. */
internal object EpubArchiveNormalizer {
    private const val MIMETYPE = "mimetype"
    private val epubMime = "application/epub+zip".toByteArray(Charsets.US_ASCII)

    fun normalize(file: File, openZip: (File) -> ZipFile = ::ZipFile, checkActive: () -> Unit = {}): Boolean {
        if (!hasDuplicateEntries(file, openZip, checkActive)) return false
        val temporary = File.createTempFile("epub-normalized-", ".epub", file.absoluteFile.parentFile)
        try {
            ZipInputStream(file.inputStream().buffered()).use { input ->
                ZipOutputStream(temporary.outputStream().buffered()).use { output ->
                    // OCF requires a single, uncompressed mimetype as the first entry.
                    output.putNextEntry(ZipEntry(MIMETYPE).apply {
                        method = ZipEntry.STORED
                        size = epubMime.size.toLong()
                        crc = CRC32().apply { update(epubMime) }.value
                    })
                    output.write(epubMime)
                    output.closeEntry()
                    val resources = HashMap<String, Pair<Long, ByteArray>>()
                    var mimetypes = 0
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        checkActive()
                        val entry = input.nextEntry ?: break
                        if (entry.name == MIMETYPE) {
                            // Never choose between conflicting duplicate contents.
                            val content = ByteArray(epubMime.size + 1)
                            var count = 0
                            while (count < content.size) {
                                val read = input.read(content, count, content.size - count)
                                if (read < 0) break
                                count += read
                            }
                            require(count == epubMime.size && content.copyOf(count).contentEquals(epubMime)) {
                                "EPUB 包含内容冲突的重复 mimetype"
                            }
                            mimetypes++
                        } else {
                            val previous = resources[entry.name]
                            if (previous == null) {
                                output.putNextEntry(ZipEntry(entry.name).apply {
                                    method = entry.method
                                    time = entry.time
                                    extra = entry.extra
                                    comment = entry.comment
                                    if (method == ZipEntry.STORED) {
                                        size = entry.size
                                        crc = entry.crc
                                    }
                                })
                            }
                            val digest = MessageDigest.getInstance("SHA-256")
                            var size = 0L
                            while (true) {
                                checkActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                digest.update(buffer, 0, count)
                                size += count
                                if (previous == null) output.write(buffer, 0, count)
                            }
                            val hash = digest.digest()
                            if (previous == null) {
                                output.closeEntry()
                                resources[entry.name] = size to hash
                            } else {
                                require(previous.first == size && MessageDigest.isEqual(previous.second, hash)) {
                                    "EPUB 包含内容冲突的重复资源：${entry.name}"
                                }
                            }
                        }
                        input.closeEntry()
                    }
                    require(mimetypes > 0) { "EPUB 缺少 mimetype" }
                }
            }
            checkActive()
            // A failed/cancelled repair leaves the import copy intact and removes the temporary file.
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            return true
        } finally {
            temporary.delete()
        }
    }

    private fun hasDuplicateEntries(file: File, openZip: (File) -> ZipFile, checkActive: () -> Unit): Boolean = try {
        openZip(file).use { zip ->
            val names = HashSet<String>()
            var duplicate = false
            zip.entries().asSequence().forEach { entry ->
                checkActive()
                if (!names.add(entry.name)) {
                    duplicate = true
                }
            }
            duplicate
        }
    } catch (error: ZipException) {
        // Some Android ZIP implementations reject duplicates before exposing the directory.
        // Inspect local entries instead of depending on system/version-specific error text.
        // Healthy archives keep the cheap central-directory scan and are never rewritten.
        val duplicates = try {
            hasDuplicateLocalEntries(file, checkActive)
        } catch (streamError: IOException) {
            error.addSuppressed(streamError)
            throw error
        }
        if (!duplicates) throw error
        true
    }

    private fun hasDuplicateLocalEntries(file: File, checkActive: () -> Unit): Boolean =
        ZipInputStream(file.inputStream().buffered()).use { input ->
            val names = HashSet<String>()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                checkActive()
                val entry = input.nextEntry ?: return@use false
                if (!names.add(entry.name)) return@use true
                // Drain with cancellation checks; closeEntry alone can inflate a large resource
                // without returning control to a cancelled import.
                while (true) {
                    checkActive()
                    if (input.read(buffer) < 0) break
                }
                input.closeEntry()
            }
            @Suppress("UNREACHABLE_CODE") false
        }
}
