package com.mozhi.reader.feature.listen

import com.mozhi.reader.core.database.entity.ChapterEntity

data class ListenChapterItem(
    val chapter: ChapterEntity,
    val displayTitle: String
)

data class ListenChapterGroup(
    val title: String,
    val chapters: List<ListenChapterItem>,
    val headerChapterIndex: Int? = null
)

fun groupListenChapters(chapters: List<ChapterEntity>): List<ListenChapterGroup> {
    if (chapters.isEmpty()) return emptyList()
    val groups = mutableListOf<ListenChapterGroup>()
    var groupTitle: String? = null
    var headerChapterIndex: Int? = null
    var groupItems = mutableListOf<ListenChapterItem>()

    fun flush() {
        val title = groupTitle
        if (title != null && (groupItems.isNotEmpty() || headerChapterIndex != null)) {
            groups += ListenChapterGroup(title, groupItems, headerChapterIndex)
            groupItems = mutableListOf()
            headerChapterIndex = null
        }
    }

    chapters.forEach { chapter ->
        val parsed = parseVolumeTitle(chapter.title)
        if (parsed != null) {
            flush()
            groupTitle = parsed.groupTitle
            if (parsed.chapterTitle != null) {
                groupItems += ListenChapterItem(chapter, parsed.chapterTitle)
            } else {
                headerChapterIndex = chapter.chapterIndex
            }
        } else {
            if (groupTitle == null) groupTitle = "正文"
            groupItems += ListenChapterItem(chapter, chapter.title)
        }
    }
    flush()
    return groups
}

private fun parseVolumeTitle(title: String): ParsedVolumeTitle? {
    val trimmedTitle = title.trim()
    val match = VOLUME_PREFIX.find(trimmedTitle) ?: return null
    val volume = match.groupValues[1].trim()
    val remainder = match.groupValues[2].trim().trimStart('：', ':', '·', '-', '—').trim()
    return if (remainder.matchesChapterTitle()) {
        ParsedVolumeTitle(groupTitle = volume, chapterTitle = remainder)
    } else {
        ParsedVolumeTitle(groupTitle = trimmedTitle, chapterTitle = null)
    }
}

private data class ParsedVolumeTitle(
    val groupTitle: String,
    val chapterTitle: String?
)

private fun String.matchesChapterTitle(): Boolean = CHAPTER_PREFIX.containsMatchIn(this)

private val VOLUME_PREFIX = Regex(
    pattern = "^(第\\s*[0-9一二三四五六七八九十百千万零〇两]+\\s*卷|卷\\s*[0-9一二三四五六七八九十百千万零〇两]+|正文卷|Volume\\s+[0-9IVXLCDM]+|Book\\s+[0-9IVXLCDM]+|Part\\s+[0-9IVXLCDM]+)(.*)$",
    option = RegexOption.IGNORE_CASE
)

private val CHAPTER_PREFIX = Regex(
    pattern = "^(第\\s*[0-9一二三四五六七八九十百千万零〇两]+\\s*[章节回]|章节?\\s*[0-9一二三四五六七八九十百千万零〇两]+|Chapter\\s+[0-9IVXLCDM]+)",
    option = RegexOption.IGNORE_CASE
)
