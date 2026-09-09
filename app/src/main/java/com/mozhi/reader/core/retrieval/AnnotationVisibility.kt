package com.mozhi.reader.core.retrieval

import com.mozhi.reader.core.database.entity.AnnotationEntity

/** The same boundary guards render markers, discussion targets, prompts, tools and notices. */
object AnnotationVisibility {
    /** Shared SQL equivalent for count-only tools, without materializing note/quote/media payloads. */
    const val SQL_PREDICATE = """
        (:wholeBook OR personaId IS NULL OR
          (sourceScopeChapterIndex IS NOT NULL AND sourceScopeCharOffset IS NOT NULL AND
           sourceScopeChapterIndex >= 0 AND sourceScopeCharOffset >= 0 AND
           (sourceScopeChapterIndex < :maxChapter OR
            (sourceScopeChapterIndex = :maxChapter AND sourceScopeCharOffset <= :maxOffset))) OR
          (sourceScopeChapterIndex IS NULL AND sourceScopeCharOffset IS NULL AND
           chapterIndex >= 0 AND startCharOffset >= 0 AND endCharOffset > startCharOffset AND
           length(trim(selectedText, :whitespace)) > 0 AND
           (chapterIndex < :maxChapter OR (chapterIndex = :maxChapter AND startCharOffset <= :maxOffset))))
    """
    val sqlWhitespace: String = buildString {
        for (char in Char.MIN_VALUE..Char.MAX_VALUE) if (char.isWhitespace()) append(char)
    }

    fun isVisible(
        annotation: AnnotationEntity,
        scope: ReadingScope,
        spoilerProtection: Boolean = true
    ): Boolean {
        if (!spoilerProtection || scope.isWholeBook || annotation.personaId == null) return true
        val sourceChapter = annotation.sourceScopeChapterIndex
        val sourceEnd = annotation.sourceScopeCharOffset
        if (sourceChapter != null || sourceEnd != null) {
            return sourceChapter != null && sourceEnd != null && sourceChapter >= 0 && sourceEnd >= 0 &&
                scope.allowsPosition(sourceChapter, sourceEnd)
        }
        // Legacy rows have exact persisted selection coordinates, rather than source provenance.
        // Never estimate from an anchor ratio or treat missing/invalid coordinates as permission.
        return annotation.chapterIndex >= 0 && annotation.startCharOffset >= 0 &&
            annotation.endCharOffset > annotation.startCharOffset && annotation.selectedText.isNotBlank() &&
            scope.allowsPosition(annotation.chapterIndex, annotation.startCharOffset)
    }

    /** Fail closed while independent Room image/annotation observers deliver a transaction. */
    fun isIllustrationVisible(
        image: com.mozhi.reader.core.database.entity.IllustrationEntity,
        annotations: List<AnnotationEntity>,
        scope: ReadingScope,
        spoilerProtection: Boolean = true
    ): Boolean {
        val sources = annotations.filter {
            com.mozhi.reader.core.library.AnnotationMedia.decode(it.mediaJson).illustrationId == image.id
        }
        if (sources.isEmpty() && image.imagePath.contains("/annotations/")) return false
        if (!spoilerProtection || scope.isWholeBook) return true
        if (sources.isNotEmpty()) return sources.any { isVisible(it, scope, spoilerProtection) }
        if (image.createdByPersonaId == null) return true
        // Chat-requested/legacy AI pictures use the same conservative anchored fallback as old notes.
        // Missing/negative anchors never become permission to reveal an unrelated AI picture.
        val chapter = image.chapterIndex ?: return false
        val offset = image.charOffset ?: return false
        return chapter >= 0 && offset >= 0 && scope.allowsPosition(chapter, offset)
    }

    fun firstVisible(
        annotations: List<AnnotationEntity>,
        ids: List<Long>,
        scope: ReadingScope,
        spoilerProtection: Boolean = true
    ): AnnotationEntity? = ids.asSequence().mapNotNull { id -> annotations.firstOrNull { it.id == id } }
        .firstOrNull { isVisible(it, scope, spoilerProtection) }
}
