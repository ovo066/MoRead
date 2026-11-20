package com.mozhi.reader.core.importer.convert

data class ConvertedBook(
    val title: String,
    val author: String = "",
    val language: String = "und",
    val chapters: List<ConvertedChapter>,
    val resources: List<ConvertedResource> = emptyList(),
    // 根元素的纸面样式也必须保留，不能只复制 body 的子节点。
    val htmlAttributes: Map<String, String> = emptyMap(),
    val bodyAttributes: Map<String, String> = emptyMap(),
    val coverHref: String? = null
)

data class ConvertedChapter(val title: String, val xhtmlBody: String, val inToc: Boolean = true)
data class ConvertedResource(val href: String, val mediaType: String, val bytes: ByteArray)

/** 纯 JVM 层只报告原因；界面层负责转换为本地化的 UiText。 */
class BookConversionException(val reason: Reason, detail: String? = null) :
    IllegalArgumentException(listOfNotNull(reason.description, detail).joinToString(": ")) {
    enum class Reason(val description: String) {
        SOURCE_TOO_LARGE("Source document exceeds 32 MiB"),
        IMAGE_TOO_LARGE("Embedded image exceeds 8 MiB"),
        EMPTY_DOCUMENT("Document contains no readable text"),
        DRM_PROTECTED("带 DRM 保护，无法导入"),
        UNSUPPORTED_FORMAT("不支持的电子书格式或压缩方式"),
        CORRUPT_FILE("电子书结构损坏"),
        MOBI_TOO_LARGE("MOBI source exceeds 256 MiB"),
        TEXT_TOO_LARGE("Decompressed text exceeds 64 MiB")
    }
}
