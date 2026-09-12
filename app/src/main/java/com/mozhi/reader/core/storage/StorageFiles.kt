package com.mozhi.reader.core.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

data class StorageFile(val file: File, val relativePath: String, val bytes: Long, val modifiedAt: Long)

/** No symlink traversal. Counts on-disk files once, not references from books or settings. */
internal fun storageFiles(root: File, prefix: String): List<StorageFile> {
    if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return emptyList()
    val base = root.canonicalFile.toPath()
    return Files.walk(base).use { paths ->
        paths.iterator().asSequence()
            .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .map { path ->
                val file = path.toFile()
                StorageFile(file, "$prefix/${base.relativize(path).toString().replace('\\', '/')}", file.length(), file.lastModified())
            }.toList()
    }
}

internal const val STORAGE_CLEANUP_MIN_AGE_MS = 24L * 60 * 60 * 1000

internal fun isOldStorageFile(file: StorageFile, now: Long): Boolean =
    file.modifiedAt > 0 && now - file.modifiedAt >= STORAGE_CLEANUP_MIN_AGE_MS

internal fun isSafeTemporaryFile(file: StorageFile, now: Long, versionName: String): Boolean {
    if (!isOldStorageFile(file, now)) return false
    val path = file.relativePath
    // Do not sweep all cacheDir: it also contains active imports, playback and HTTP caches.
    return path.startsWith("cache/export/") ||
        (path.startsWith("cache/updates/") && file.file.extension == "apk" &&
            file.file.nameWithoutExtension.removePrefix("v") != versionName)
}

internal fun isOrphanStorageFile(
    file: StorageFile,
    bookIds: Set<Long>,
    conversationIds: Set<Long>,
    originalPaths: Set<String>,
    now: Long
): Boolean {
    if (!isOldStorageFile(file, now)) return false
    val segments = file.relativePath.split('/')
    if (segments.size < 3) return false
    if (segments[0] == "cache" && segments[1] == "agent-speech") {
        return segments[2].toLongOrNull()?.let { it !in bookIds } == true
    }
    if (segments[0] != "files") return false
    return when (segments[1]) {
        "books" -> file.file.canonicalPath !in originalPaths
        "book-text", "book-media", "book-layout", "speech-cache", "illustrations" ->
            segments[2].toLongOrNull()?.let { it !in bookIds } == true
        "attachments" -> segments[2].toLongOrNull()?.let { it !in conversationIds } == true
        else -> false // Library assets are user data, even if not currently selected.
    }
}

/** A preview is not a deletion permit for a changed file or a replaced symlink. */
internal fun deleteUnchangedStorageFile(entry: StorageFile): Boolean {
    val file = entry.file
    if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
    if (file.canonicalFile != file.absoluteFile || file.length() != entry.bytes || file.lastModified() != entry.modifiedAt) return false
    return file.delete()
}
