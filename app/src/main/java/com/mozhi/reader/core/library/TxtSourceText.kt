package com.mozhi.reader.core.library

/** Rule-based TXT splitting stores the heading separately from the body. */
internal fun reconstructTxtSource(chapters: List<EditableChapterDraft>): String = buildString {
    chapters.sortedBy { it.index }.forEachIndexed { index, chapter ->
        if (index > 0) append("\n\n")
        val title = chapter.title.trim()
        if (title.isNotEmpty() && chapter.body.lineSequence().firstOrNull()?.trim() != title) {
            append(title).append('\n')
        }
        append(chapter.body)
    }
}
