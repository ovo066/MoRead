package com.mozhi.reader.feature.reader.engine

/** Immutable provenance attached to a rendered bitmap, never inferred from a later controller state. */
data class ReaderVisibleReadSnapshot(
    val layoutGeneration: Int,
    val chapterIndex: Int,
    val pages: List<RenderPage.Laid>,
    val displayEnd: Int,
    val source: ReaderChapterSource
)
