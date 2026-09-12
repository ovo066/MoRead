package com.mozhi.reader.feature.bookdetail

import com.mozhi.reader.core.database.entity.AnnotationEntity
import org.junit.Assert.*
import org.junit.Test

class AnnotationIndexTest {
    private fun annotation(id: Long, persona: Long? = null) = AnnotationEntity(id = id, bookId = 7,
        personaId = persona, chapterIndex = 0, startCharOffset = 0, endCharOffset = 4,
        selectedText = "原文片段", createdAt = id)

    @Test fun originIsAuthorNotColorStyleOrWhetherAiWasProactive() {
        val mine = annotation(1).copy(style = "WAVY", colorTag = "red", note = "AI 也可以这样想")
        val aiManual = annotation(2, 9)
        val aiAutomatic = annotation(3, 10).copy(proactiveJobId = 4)
        val rows = listOf(mine, aiManual, aiAutomatic)
        assertEquals(listOf(mine), filterAnnotationIndex(rows, AnnotationIndexSource.USER, 9))
        assertEquals(listOf(aiManual, aiAutomatic), filterAnnotationIndex(rows, AnnotationIndexSource.AI))
        assertEquals(listOf(aiManual), filterAnnotationIndex(rows, AnnotationIndexSource.AI, 9))
        assertEquals(rows, filterAnnotationIndex(rows, AnnotationIndexSource.ALL, 9))
    }

    @Test fun deletedOrMisleadingPersonaNamesNeverBecomeUserAuthorship() {
        assertEquals("我的划线", annotationAuthorLabel(annotation(1), emptyMap()))
        assertEquals("AI · 已删除角色 #99", annotationAuthorLabel(annotation(2, 99), emptyMap()))
        assertEquals("AI · 我的划线", annotationAuthorLabel(annotation(3, 2), mapOf(2L to "我的划线")))
    }
}
