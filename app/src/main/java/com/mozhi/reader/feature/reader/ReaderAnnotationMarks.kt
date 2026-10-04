package com.mozhi.reader.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.mozhi.reader.core.database.entity.AnnotationColors
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.datastore.ChineseConversionMode
import com.mozhi.reader.core.library.ResolvedTextAnchor
import com.mozhi.reader.feature.reader.engine.ReaderAnnotationMark
import com.mozhi.reader.feature.reader.engine.ReaderContentController

/** Explicitly observe window publications; the controller itself is not Compose state. */
@Composable
internal fun rememberReaderAnnotationMarks(
    annotations: List<AnnotationEntity>,
    repliedAnnotationIds: Set<Long>,
    currentChapterIndex: Int,
    contentRevision: Int,
    conversionMode: ChineseConversionMode,
    controller: ReaderContentController,
    resolveRange: (AnnotationEntity) -> ResolvedTextAnchor?
): List<ReaderAnnotationMark> {
    val byChapter = remember(annotations) { annotations.groupBy { it.chapterIndex } }
    val sources = remember(controller, currentChapterIndex, contentRevision) {
        ((currentChapterIndex - 1)..(currentChapterIndex + 1)).associateWith(controller::chapterSource)
    }
    return sources.flatMap { (chapter, source) ->
        key(controller, chapter) {
            val chapterAnnotations = byChapter[chapter].orEmpty()
            val repliedIds = repliedAnnotationIds.intersect(chapterAnnotations.map { it.id }.toSet())
            // Page turns republish the window but do not repeat anchor matching for unchanged text.
            remember(chapterAnnotations, repliedIds, source, conversionMode, resolveRange) {
                if (source == null) emptyList() else chapterAnnotations.mapNotNull { annotation ->
                    val range = resolveRange(annotation) ?: return@mapNotNull null
                    ReaderAnnotationMark(
                        id = annotation.id,
                        chapterIndex = annotation.chapterIndex,
                        startCharOffset = range.start,
                        endCharOffset = range.end,
                        hasComment = annotation.note.isNotBlank() || annotation.id in repliedIds,
                        style = annotation.style,
                        colorTag = annotation.colorTag.ifBlank {
                            annotation.personaId?.let(AnnotationColors::forPersona).orEmpty()
                        }
                    )
                }
            }
        }
    }
}
