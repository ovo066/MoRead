package com.mozhi.reader.core.datastore

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PendingReaderFont(
    val cachePath: String,
    val originalFileName: String,
    val detectedName: String,
    val extension: String
)

/** 统一承接阅读页选择器与系统“其他应用打开”的字体导入。 */
@Singleton
class ReaderFontImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: ReaderSettingsRepository
) {
    suspend fun supports(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val mime = runCatching { context.contentResolver.getType(uri) }
            .getOrNull()
            ?.lowercase()
        if (mime in SUPPORTED_MIME_TYPES || mime?.startsWith("font/") == true) {
            return@withContext true
        }
        val name = runCatching { queryDisplayName(uri) }.getOrNull()
            ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
        name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS
    }

    suspend fun prepare(uri: Uri): PendingReaderFont = withContext(Dispatchers.IO) {
        val originalName = queryDisplayName(uri) ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
        val extension = originalName.substringAfterLast('.', "ttf")
            .lowercase()
            .takeIf { it in SUPPORTED_EXTENSIONS }
            ?: extensionForMime(context.contentResolver.getType(uri))
        val directory = pendingDirectory()
        val target = File(directory, "${UUID.randomUUID()}.$extension")
        try {
            context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_FONT_BYTES) { "字体文件超过 64 MB" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("无法读取字体文件")
            require(target.length() > 0L) { "字体文件为空" }
            // Android 字体栈做最终可用性校验；name 表解析失败仍可回退文件名。
            Typeface.createFromFile(target)
            val fallbackName = originalName.substringBeforeLast('.').ifBlank { "自定义字体" }
            PendingReaderFont(
                cachePath = target.absolutePath,
                originalFileName = originalName.ifBlank { target.name },
                detectedName = SfntFontNameReader.read(target) ?: fallbackName,
                extension = extension
            )
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    suspend fun confirm(pending: PendingReaderFont, customName: String): ReaderFontAsset =
        withContext(Dispatchers.IO) {
            val source = checkedPendingFile(pending)
            val displayName = customName.trim().take(48).ifBlank { pending.detectedName }
            val directory = File(context.filesDir, "reader-custom").apply { mkdirs() }.canonicalFile
            val id = UUID.randomUUID().toString()
            val destination = File(directory, "font-$id.${pending.extension}")
            try {
                if (!source.renameTo(destination)) source.copyTo(destination, overwrite = false)
                Typeface.createFromFile(destination)
                val asset = ReaderFontAsset(
                    id = id,
                    displayName = displayName,
                    filePath = destination.absolutePath,
                    originalFileName = pending.originalFileName,
                    importedAt = System.currentTimeMillis()
                )
                settingsRepository.addCustomFont(asset, select = true)
                if (source.exists()) source.delete()
                asset
            } catch (error: Throwable) {
                destination.delete()
                throw error
            }
        }

    suspend fun rename(fontId: String, displayName: String) {
        settingsRepository.renameCustomFont(fontId, displayName)
    }

    suspend fun delete(font: ReaderFontAsset) = withContext(Dispatchers.IO) {
        settingsRepository.removeCustomFont(font.id)
        val directory = File(context.filesDir, "reader-custom").canonicalFile
        deleteOwnedReaderAsset(font.filePath, directory)
    }

    suspend fun discard(pending: PendingReaderFont) = withContext(Dispatchers.IO) {
        runCatching { checkedPendingFile(pending).delete() }
    }

    private fun checkedPendingFile(pending: PendingReaderFont): File {
        val root = pendingDirectory().canonicalFile
        val file = File(pending.cachePath).canonicalFile
        require(file.isFile && file.parentFile == root) { "待导入字体已失效" }
        return file
    }

    private fun pendingDirectory(): File =
        File(context.cacheDir, "reader-font-import").apply { mkdirs() }

    private fun deleteOwnedReaderAsset(path: String?, root: File) {
        path?.let(::File)?.takeIf(File::exists)?.canonicalFile
            ?.takeIf { it.parentFile == root }
            ?.delete()
    }

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }

    private fun extensionForMime(mime: String?): String = when (mime?.lowercase()) {
        "font/otf", "application/x-font-opentype" -> "otf"
        "font/collection" -> "ttc"
        else -> "ttf"
    }

    private companion object {
        const val MAX_FONT_BYTES = 64L * 1024 * 1024
        val SUPPORTED_EXTENSIONS = setOf("ttf", "otf", "ttc")
        val SUPPORTED_MIME_TYPES = setOf(
            "font/ttf",
            "font/otf",
            "font/collection",
            "font/sfnt",
            "application/font-sfnt",
            "application/x-font-ttf",
            "application/x-font-truetype",
            "application/vnd.ms-opentype",
            "application/x-font-opentype"
        )
    }
}

/** 读取 OpenType/TrueType `name` 表，优先完整名称，再回退字体家族名。 */
internal object SfntFontNameReader {
    fun read(file: File): String? = runCatching {
        RandomAccessFile(file, "r").use { input ->
            val fontOffset = firstFontOffset(input)
            input.seek(fontOffset + 4)
            val tableCount = input.readUnsignedShort().coerceAtMost(MAX_TABLES)
            input.skipBytes(6)
            var nameOffset = -1L
            var nameLength = 0L
            repeat(tableCount) {
                val tag = input.readInt()
                input.skipBytes(4)
                val offset = input.readInt().toLong() and UINT_MASK
                val length = input.readInt().toLong() and UINT_MASK
                if (tag == NAME_TAG) {
                    nameOffset = offset
                    nameLength = length
                }
            }
            require(nameOffset >= 0 && nameLength >= 6 && nameOffset + nameLength <= input.length())
            input.seek(nameOffset)
            input.readUnsignedShort() // format
            val count = input.readUnsignedShort().coerceAtMost(MAX_NAME_RECORDS)
            val stringsOffset = input.readUnsignedShort()
            val records = ArrayList<NameRecord>(count)
            repeat(count) {
                records += NameRecord(
                    platform = input.readUnsignedShort(),
                    encoding = input.readUnsignedShort(),
                    language = input.readUnsignedShort(),
                    nameId = input.readUnsignedShort(),
                    length = input.readUnsignedShort(),
                    offset = input.readUnsignedShort()
                )
            }
            records.asSequence()
                .filter { it.nameId == FULL_NAME_ID || it.nameId == FAMILY_NAME_ID }
                .filter { it.length in 1..MAX_NAME_BYTES }
                .sortedByDescending(::score)
                .mapNotNull { record ->
                    val start = nameOffset + stringsOffset + record.offset
                    if (start < nameOffset || start + record.length > nameOffset + nameLength) {
                        return@mapNotNull null
                    }
                    input.seek(start)
                    val bytes = ByteArray(record.length)
                    input.readFully(bytes)
                    decode(record, bytes)
                }
                .firstOrNull()
        }
    }.getOrNull()

    private fun firstFontOffset(input: RandomAccessFile): Long {
        require(input.length() >= 12)
        input.seek(0)
        return if (input.readInt() == TTC_TAG) {
            input.skipBytes(4)
            require(input.readInt() > 0)
            input.readInt().toLong() and UINT_MASK
        } else {
            0L
        }
    }

    private fun score(record: NameRecord): Int =
        (if (record.nameId == FULL_NAME_ID) 100 else 70) +
            (if (record.platform == WINDOWS_PLATFORM || record.platform == UNICODE_PLATFORM) 20 else 0) +
            when (record.language) {
                SIMPLIFIED_CHINESE_LANGUAGE -> 15
                ENGLISH_LANGUAGE, 0 -> 10
                else -> 0
            }

    private fun decode(record: NameRecord, bytes: ByteArray): String? {
        val charset = when (record.platform) {
            UNICODE_PLATFORM, WINDOWS_PLATFORM -> Charsets.UTF_16BE
            MACINTOSH_PLATFORM -> runCatching { Charset.forName("x-MacRoman") }.getOrDefault(Charsets.ISO_8859_1)
            else -> Charsets.UTF_8
        }
        return bytes.toString(charset)
            .replace('\u0000', ' ')
            .replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(48)
            .takeIf(String::isNotBlank)
    }

    private data class NameRecord(
        val platform: Int,
        val encoding: Int,
        val language: Int,
        val nameId: Int,
        val length: Int,
        val offset: Int
    )

    private const val NAME_TAG = 0x6E616D65
    private const val TTC_TAG = 0x74746366
    private const val UINT_MASK = 0xFFFF_FFFFL
    private const val UNICODE_PLATFORM = 0
    private const val MACINTOSH_PLATFORM = 1
    private const val WINDOWS_PLATFORM = 3
    private const val ENGLISH_LANGUAGE = 0x0409
    private const val SIMPLIFIED_CHINESE_LANGUAGE = 0x0804
    private const val FAMILY_NAME_ID = 1
    private const val FULL_NAME_ID = 4
    private const val MAX_TABLES = 512
    private const val MAX_NAME_RECORDS = 4_096
    private const val MAX_NAME_BYTES = 2_048
}
