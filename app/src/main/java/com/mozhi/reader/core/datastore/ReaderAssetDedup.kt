package com.mozhi.reader.core.datastore

import com.mozhi.reader.core.importer.SourceFingerprint
import com.mozhi.reader.core.importer.SourceFingerprints
import java.io.File

/**
 * 字体库、图片库的重复检测：先比已记录的指纹；旧条目没有指纹时，只对字节数相同的文件补算，
 * 绝大多数情况下一次哈希都不用算。
 */
internal object ReaderAssetDedup {
    fun <T> find(assets: List<T>, fingerprint: SourceFingerprint, filePath: (T) -> String, sha256: (T) -> String): T? {
        assets.firstOrNull { sha256(it) == fingerprint.sha256 && File(filePath(it)).isFile }?.let { return it }
        return assets.firstOrNull { asset ->
            if (sha256(asset).isNotBlank()) return@firstOrNull false
            val file = File(filePath(asset))
            file.isFile && file.length() == fingerprint.size &&
                runCatching { SourceFingerprints.of(file).sha256 }.getOrNull() == fingerprint.sha256
        }
    }
}
