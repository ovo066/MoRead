package com.mozhi.reader.core.importer.lan

import java.util.Locale

/**
 * 上传文件名的清洗与去重。传书服务把文件直接落进应用私有目录，因此文件名是最直接的
 * 攻击面：路径穿越（`../../databases/moread.db`）、控制字符、Windows 保留名一律在这里挡掉。
 */
object LanUploadNaming {

    /** 与导入管线支持的格式保持一致；其余类型宁可拒收也不落盘。 */
    val ALLOWED_EXTENSIONS = setOf("txt", "epub")

    const val MAX_NAME_CHARS = 120

    /**
     * 清洗上传文件名。返回 null 表示不接受这个文件（扩展名不支持或清洗后没有内容）。
     * 中日韩字符、空格与常见标点保留；控制字符、路径分隔符与保留字符替换为下划线。
     */
    fun sanitize(rawName: String?): String? {
        val name = rawName.orEmpty()
            // 兼容 Windows 客户端可能发来的整段路径：只取最后一节。
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        if (name.isEmpty()) return null

        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in ALLOWED_EXTENSIONS) return null

        val stem = name.substringBeforeLast('.')
            .map { character ->
                when {
                    character.isISOControl() -> '_'
                    character in FORBIDDEN_CHARS -> '_'
                    else -> character
                }
            }
            .joinToString("")
            // 全是点或空格的名字（`.` `..` `   `）在各平台都是雷，直接清空后由下面兜底。
            .trim()
            .trim('.')
            .take(MAX_NAME_CHARS)
            .trim()

        val safeStem = stem.ifBlank { "未命名" }
        if (safeStem.uppercase(Locale.ROOT) in RESERVED_STEMS) return "_$safeStem.$extension"
        return "$safeStem.$extension"
    }

    /** 同名文件按 `名称 (2).txt` 递增，避免后来的上传静默覆盖先到的文件。 */
    fun uniqueName(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        val stem = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "")
        var index = 2
        while (true) {
            val candidate = if (extension.isEmpty()) "$stem ($index)" else "$stem ($index).$extension"
            if (candidate !in taken) return candidate
            index++
        }
    }

    // 空格不在此列：书名里带空格很常见，Android 文件系统也完全接受。
    private val FORBIDDEN_CHARS = charArrayOf(
        '/', '\\', ':', '*', '?', '"', '<', '>', '|'
    )

    private val RESERVED_STEMS = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )
}
