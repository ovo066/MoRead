package com.mozhi.reader.feature.reader.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.mozhi.reader.feature.reader.engine.RenderPage

/** Shared pixel geometry for pagination, painting, selection, links and animation textures. */
data class SpreadGeometry(val paneWidth: Float, val paneHeight: Float, val gutterPx: Float) {
    val leafWidth: Float = ((paneWidth - gutterPx) / 2f).coerceAtLeast(0f)
    val leftOriginX: Float = 0f
    val rightOriginX: Float = leafWidth + gutterPx
    val spineX: Float = paneWidth / 2f
    val gutterRect: Rect get() = Rect(leafWidth, 0f, rightOriginX, paneHeight)

    fun originX(leaf: Leaf): Float = if (leaf == Leaf.LEFT) leftOriginX else rightOriginX
    fun leafAt(x: Float): Leaf? = when {
        x >= 0f && x < leafWidth -> Leaf.LEFT
        x >= rightOriginX && x < paneWidth -> Leaf.RIGHT
        else -> null
    }
    fun toLeafLocal(position: Offset, leaf: Leaf): Offset = position - Offset(originX(leaf), 0f)
    fun leafSrcRect(leaf: Leaf): Rect = Rect(originX(leaf), 0f, originX(leaf) + leafWidth, paneHeight)

    fun hitAt(position: Offset, pages: Pair<RenderPage, RenderPage>): PageHit? {
        if (position.y < 0f || position.y >= paneHeight) return null
        val leaf = leafAt(position.x) ?: return null
        val page = (if (leaf == Leaf.LEFT) pages.first else pages.second) as? RenderPage.Laid ?: return null
        return PageHit(leaf, page, toLeafLocal(position, leaf))
    }
}

enum class Leaf { LEFT, RIGHT }
data class PageHit(val leaf: Leaf, val page: RenderPage.Laid, val local: Offset)
