package com.mozhi.reader.feature.reader.engine.epub

import com.mozhi.reader.core.epub.style.EpubShadow
import com.mozhi.reader.core.epub.style.EpubStyle
import com.mozhi.reader.core.epub.style.StyledDomNode

/**
 * `writing-mode: vertical-rl` without a second layout engine.
 *
 * The chapter is laid out by the ordinary horizontal engine in a *frame* rotated 90° clockwise:
 * frame lines are the physical columns, frame x runs down the page and frame y runs from the right
 * edge to the left. The renderer rotates the canvas back and stands CJK glyphs, images and
 * background art upright again, so line breaking, justification, kinsoku, floats, pagination and
 * text.mz anchors are all shared with horizontal books.
 *
 * CSS box properties are physical, so they are remapped once before layout:
 *
 * | frame  | physical |      | frame corner | physical corner |
 * |--------|----------|------|--------------|-----------------|
 * | top    | right    |      | top-left     | top-right       |
 * | right  | bottom   |      | top-right    | bottom-right    |
 * | bottom | left     |      | bottom-right | bottom-left     |
 * | left   | top      |      | bottom-left  | top-left        |
 *
 * `width`/`height` swap. Logical values (text-align, text-indent, float/clear line-left/right,
 * vertical-align) already mean the right thing in the frame. Backgrounds keep their physical
 * size/position because the renderer paints them in physical space.
 */
internal object EpubVerticalFrame {

    fun toFrame(root: StyledDomNode): StyledDomNode =
        root.copy(style = toFrame(root.style), children = root.children.map(::toFrame))

    fun toFrame(style: EpubStyle): EpubStyle = style.copy(
        appliedProperties = style.appliedProperties.mapTo(HashSet(style.appliedProperties.size)) {
            PROPERTY_NAMES[it] ?: it
        },
        marginTop = style.marginRight,
        marginRight = style.marginBottom,
        marginBottom = style.marginLeft,
        marginLeft = style.marginTop,
        paddingTop = style.paddingRight,
        paddingRight = style.paddingBottom,
        paddingBottom = style.paddingLeft,
        paddingLeft = style.paddingTop,
        width = style.height,
        height = style.width,
        minWidth = style.minHeight,
        minHeight = style.minWidth,
        maxWidth = style.maxHeight,
        maxHeight = style.maxWidth,
        borderWidths = sides(style.borderWidths),
        borderColors = sides(style.borderColors),
        borderStyles = sides(style.borderStyles),
        borderRadii = listOf(style.borderRadii[1], style.borderRadii[2], style.borderRadii[3], style.borderRadii[0]),
        boxShadows = style.boxShadows.map(::shadowToFrame),
        insets = sides(style.insets),
        // 物理 (tx, ty) 在帧里是 (ty, -tx)；百分比各自仍指向同一条物理边。
        translateX = style.translateY,
        translateY = when (val x = style.translateX) {
            is com.mozhi.reader.core.epub.style.ResolvedLength.Px -> com.mozhi.reader.core.epub.style.ResolvedLength.Px(-x.value)
            is com.mozhi.reader.core.epub.style.ResolvedLength.Percent -> com.mozhi.reader.core.epub.style.ResolvedLength.Percent(-x.value)
            else -> x
        }
    )

    /** Physical (dx, dy) becomes frame (dy, -dx): physical x = W - frame y, physical y = frame x. */
    private fun shadowToFrame(shadow: EpubShadow) = shadow.copy(offsetXPx = shadow.offsetYPx, offsetYPx = -shadow.offsetXPx)

    /** Physical top/right/bottom/left lists become frame top(=right)/right(=bottom)/bottom(=left)/left(=top). */
    private fun <T> sides(physical: List<T>): List<T> = listOf(physical[1], physical[2], physical[3], physical[0])

    private val PROPERTY_NAMES: Map<String, String> = buildMap {
        for (prefix in listOf("margin", "padding")) {
            put("$prefix-right", "$prefix-top")
            put("$prefix-bottom", "$prefix-right")
            put("$prefix-left", "$prefix-bottom")
            put("$prefix-top", "$prefix-left")
        }
        put("right", "top")
        put("bottom", "right")
        put("left", "bottom")
        put("top", "left")
        put("width", "height")
        put("height", "width")
        put("min-width", "min-height")
        put("min-height", "min-width")
        put("max-width", "max-height")
        put("max-height", "max-width")
    }
}
