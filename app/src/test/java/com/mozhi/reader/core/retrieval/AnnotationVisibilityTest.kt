package com.mozhi.reader.core.retrieval

import com.mozhi.reader.core.database.entity.AnnotationEntity
import org.junit.Assert.*
import org.junit.Test

class AnnotationVisibilityTest {
    private val row = AnnotationEntity(id = 1, bookId = 1, personaId = 2, chapterIndex = 3,
        startCharOffset = 20, endCharOffset = 30, selectedText = "原文", createdAt = 0,
        sourceScopeChapterIndex = 3, sourceScopeCharOffset = 80)
    @Test fun sourceEndNotQuoteStartControlsVisibility() {
        assertFalse(AnnotationVisibility.isVisible(row, ReadingScope.upto(3, 79)))
        assertTrue(AnnotationVisibility.isVisible(row, ReadingScope.upto(3, 80)))
        assertFalse(AnnotationVisibility.isVisible(row, ReadingScope.upto(2, 999)))
    }
    @Test fun usersAndDisabledProtectionStayVisible() {
        assertTrue(AnnotationVisibility.isVisible(row.copy(personaId = null), ReadingScope.upto(0, 0)))
        assertTrue(AnnotationVisibility.isVisible(row, ReadingScope.upto(0, 0), false))
        assertTrue(AnnotationVisibility.isVisible(row, ReadingScope.WholeBook))
    }
    @Test fun legacyUsesExactAnchorAndInvalidAnchorsStayHidden() {
        val legacy = row.copy(sourceScopeChapterIndex = null, sourceScopeCharOffset = null)
        assertFalse(AnnotationVisibility.isVisible(legacy, ReadingScope.upto(3, 19)))
        assertTrue(AnnotationVisibility.isVisible(legacy, ReadingScope.upto(3, 20)))
        assertFalse(AnnotationVisibility.isVisible(legacy.copy(startCharOffset = -1), ReadingScope.upto(4, 0)))
        assertFalse(AnnotationVisibility.isVisible(legacy.copy(selectedText = ""), ReadingScope.upto(4, 0)))
        assertFalse(AnnotationVisibility.isVisible(row.copy(sourceScopeCharOffset = null), ReadingScope.upto(4, 0)))
    }
    @Test fun generatedImageIsHiddenUntilItsVisibleAnnotationArrives() {
        val image = com.mozhi.reader.core.database.entity.IllustrationEntity(id = 7, bookId = 1,
            prompt = "hidden", imagePath = "/files/illustrations/1/annotations/a.png", createdAt = 0)
        val attached = row.copy(mediaJson = com.mozhi.reader.core.library.AnnotationMedia(illustrationId = 7).encode())
        assertFalse(AnnotationVisibility.isIllustrationVisible(image, emptyList(), ReadingScope.upto(3, 80)))
        assertFalse(AnnotationVisibility.isIllustrationVisible(image, listOf(attached), ReadingScope.upto(3, 79)))
        assertTrue(AnnotationVisibility.isIllustrationVisible(image, listOf(attached), ReadingScope.upto(3, 80)))
        assertTrue(AnnotationVisibility.isIllustrationVisible(image, listOf(attached), ReadingScope.WholeBook))
    }
    @Test fun unlinkedLegacyAiImageFailsClosedButUserImageRemainsVisible() {
        val image = com.mozhi.reader.core.database.entity.IllustrationEntity(id = 7, bookId = 1,
            prompt = "无来源边界", imagePath = "/files/illustrations/1/old.png", createdAt = 0, createdByPersonaId = 2)
        assertFalse(AnnotationVisibility.isIllustrationVisible(image, emptyList(), ReadingScope.upto(3, 80)))
        assertTrue(AnnotationVisibility.isIllustrationVisible(image.copy(createdByPersonaId = null), emptyList(), ReadingScope.upto(3, 80)))
        assertTrue(AnnotationVisibility.isIllustrationVisible(image, emptyList(), ReadingScope.WholeBook))
        val attached = row.copy(mediaJson = com.mozhi.reader.core.library.AnnotationMedia(illustrationId = 7).encode())
        assertTrue(AnnotationVisibility.isIllustrationVisible(image, listOf(attached), ReadingScope.upto(3, 80)))
    }
    @Test fun chatRequestedAiImageUsesValidAnchorUnderDefaultSpoilerProtection() {
        // GenerateIllustrationTool persists persona + chapter/offset, without an annotation link.
        val image = com.mozhi.reader.core.database.entity.IllustrationEntity(id = 7, bookId = 1,
            prompt = "聊天请求配图", imagePath = "/files/illustrations/1/chat.png", createdAt = 0,
            createdByPersonaId = 2, chapterIndex = 3, charOffset = 80)
        assertFalse(AnnotationVisibility.isIllustrationVisible(image, emptyList(), ReadingScope.upto(3, 79)))
        assertTrue(AnnotationVisibility.isIllustrationVisible(image, emptyList(), ReadingScope.upto(3, 80)))
        assertFalse(AnnotationVisibility.isIllustrationVisible(image, emptyList(), ReadingScope.upto(2, 999)))
        assertFalse(AnnotationVisibility.isIllustrationVisible(image.copy(charOffset = -1), emptyList(), ReadingScope.upto(4, 0)))
        assertFalse(AnnotationVisibility.isIllustrationVisible(image.copy(chapterIndex = null), emptyList(), ReadingScope.upto(4, 0)))
        val detached = image.copy(imagePath = "/files/illustrations/1/annotations/pending.png")
        assertFalse(AnnotationVisibility.isIllustrationVisible(detached, emptyList(), ReadingScope.upto(4, 0)))
        assertFalse(AnnotationVisibility.isIllustrationVisible(detached, emptyList(), ReadingScope.WholeBook))
        assertFalse(AnnotationVisibility.isIllustrationVisible(detached, emptyList(), ReadingScope.WholeBook, false))
    }

    @Test fun noticeCannotTargetUnreadRows() {
        assertNull(AnnotationVisibility.firstVisible(listOf(row), listOf(1), ReadingScope.upto(3, 79)))
        assertEquals(row, AnnotationVisibility.firstVisible(listOf(row), listOf(1), ReadingScope.upto(3, 80)))
    }
}
