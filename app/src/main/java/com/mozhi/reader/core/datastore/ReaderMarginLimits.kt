package com.mozhi.reader.core.datastore

object ReaderMarginLimits {
    /** 左右：-1.4 → 0dp，3 → 44dp（公式 14 + 10v）。 */
    const val HORIZONTAL_MIN = -1.4f
    const val HORIZONTAL_MAX = 3f
    /** 上下：0 为默认，3 → 额外 54dp（公式 18v）；受系统栏安全区和页眉文字限制，负值实际仅能收回约 4dp，因此不提供负值。 */
    const val VERTICAL_MIN = 0f
    const val VERTICAL_MAX = 3f

    fun horizontal(value: Float): Float =
        if (value.isFinite()) value.coerceIn(HORIZONTAL_MIN, HORIZONTAL_MAX) else 1f

    fun vertical(value: Float): Float =
        if (value.isFinite()) value.coerceIn(VERTICAL_MIN, VERTICAL_MAX) else 0f
}
