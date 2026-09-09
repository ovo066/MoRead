package com.mozhi.reader.feature.reader.engine

/** Frozen source and destination ink for one atomic page/spread turn. */
data class ReaderTurnSnapshot(
    val forward: Boolean,
    val sourcePages: List<RenderPage>,
    val targetPages: List<RenderPage>,
    val navigationGeneration: Int,
    val sourceGeneration: Int,
    val environmentGeneration: Int,
    val spread: Boolean
)
