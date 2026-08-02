package com.mozhi.reader.feature.importer

import kotlinx.serialization.Serializable

@Serializable
data class TxtTocRule(
    val id: Long,
    val enable: Boolean,
    val name: String,
    val rule: String,
    val example: String = "",
    val serialNumber: Int = 0
)

data class TxtChapter(
    val index: Int,
    val title: String,
    val content: String,
    val startOffset: Int,
    val endOffset: Int
) {
    val charCount: Int get() = content.length
}

data class TxtSplitResult(
    val rule: TxtTocRule?,
    val chapters: List<TxtChapter>,
    val score: Double,
    val usedFallback: Boolean = false
)

data class DetectedText(
    val charsetName: String,
    val text: String
)

data class ImportProgress(
    val message: String,
    val completed: Int = 0,
    val total: Int = 0
) {
    val fraction: Float?
        get() = total.takeIf { it > 0 }?.let { completed.coerceIn(0, it).toFloat() / it }
}
