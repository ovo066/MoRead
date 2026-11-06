package com.mozhi.reader.core.stage

import com.mozhi.reader.core.epub.style.EpubDisplay
import com.mozhi.reader.core.epub.style.EpubStyle
import com.mozhi.reader.core.epub.style.ResolvedLength
import com.mozhi.reader.core.epub.style.StyledDomNode
import kotlin.math.abs

/** 书里给一段文字定的样子：书内嵌字体的族名（小写）、颜色、粗细、斜体、相对正文的字号。 */
data class StageInk(
    val fontFamily: String? = null,
    val colorArgb: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val sizeEm: Float? = null
) {
    val isEmpty: Boolean get() = fontFamily == null && colorArgb == null && !bold && !italic && sizeEm == null
}

/** 书里给一块内容画的框：底色、边框、圆角与底图（本地路径），以及框里文字的样子。 */
data class StageBox(
    val backgroundArgb: Int? = null,
    val borderArgb: Int? = null,
    val borderWidthPx: Float = 0f,
    val radiusPx: Float = 0f,
    val dashed: Boolean = false,
    val backgroundImage: String? = null,
    val ink: StageInk? = null
)

data class StageBookInk(val start: Int, val end: Int, val ink: StageInk, val block: Boolean)
data class StageBookBox(val start: Int, val end: Int, val box: StageBox)

/** 精排书的样式线索：坐标都是章内 UTF-16 原文下标。 */
data class StageBookStyles(
    val inks: List<StageBookInk> = emptyList(),
    val boxes: List<StageBookBox> = emptyList(),
    /** 章名的字体与颜色（书里的章名样式）。 */
    val titleInk: StageInk? = null
)

/**
 * 从已经算好级联样式的 DOM 里读出「和正文不一样」的地方：换了内嵌字体、换了颜色、加粗、斜体、
 * 字号明显不同的文字，以及画了底色或边框的块。正文本身的样式不记——舞台正文用阅读页的字。
 * 盖住大半章的大框是整页底纹，不当成「一块内容」。
 */
object StageBookStyleReader {
    private val blockTags = setOf("p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "section", "aside", "dd", "dt", "td", "table", "ul", "ol")
    private val blockDisplays = setOf(EpubDisplay.BLOCK, EpubDisplay.LIST_ITEM, EpubDisplay.FLEX, EpubDisplay.GRID, EpubDisplay.TABLE, EpubDisplay.TABLE_CELL, EpubDisplay.INLINE_BLOCK)
    /** 框盖住超过这个比例的正文，就是整页底纹。 */
    private const val PAGE_FRAME = 0.6f
    private const val SIZE_DELTA = 0.12f

    private data class Body(val family: String?, val color: Int, val bold: Boolean, val italic: Boolean, val sizePx: Float)

    fun read(root: StyledDomNode, text: String, embeddedFamilies: Set<String>, imagePath: (String) -> String?, themeTextArgb: Int): StageBookStyles {
        val embedded = embeddedFamilies.map { it.lowercase() }.toSet()
        val body = baseline(root, embedded, themeTextArgb)
        val inks = mutableListOf<StageBookInk>()
        val boxes = mutableListOf<StageBookBox>()
        var titleInk: StageInk? = null

        fun ink(s: EpubStyle): StageInk {
            val family = firstEmbedded(s, embedded)?.takeIf { it != body.family }
            val color = s.colorArgb.takeIf { distinct(it, body.color) && distinct(it, themeTextArgb) }
            val ratio = if (body.sizePx > 0f) s.fontSizePx / body.sizePx else 1f
            return StageInk(
                fontFamily = family,
                colorArgb = color,
                bold = s.fontWeight >= 600 && !body.bold,
                italic = s.italic && !body.italic,
                sizeEm = ratio.takeIf { abs(it - 1f) >= SIZE_DELTA }?.coerceIn(0.7f, 1.8f)
            )
        }

        fun walk(n: StyledDomNode, parentInk: StageInk) {
            val node = n.node
            if (node.tag == "#text") return
            val range = range(n)
            val s = n.style
            val own = ink(s)
            if (range != null && node.tag != "body" && node.tag != "html") {
                val block = node.tag in blockTags || s.display in blockDisplays
                if (own != parentInk && !own.isEmpty) inks += StageBookInk(range.first, range.last + 1, own, block)
                if (block && s.hasDecoration() && (range.last + 1 - range.first) < text.length * PAGE_FRAME) {
                    boxes += StageBookBox(range.first, range.last + 1, box(s, own, imagePath))
                }
                if (titleInk == null && node.tag.length == 2 && node.tag[0] == 'h' && range.first < TITLE_WINDOW && !own.isEmpty) titleInk = own
            }
            n.children.forEach { walk(it, own) }
        }
        walk(root, StageInk())
        return StageBookStyles(inks.sortedBy { it.start }, boxes.sortedBy { it.start }, titleInk)
    }

    private fun box(s: EpubStyle, ink: StageInk, imagePath: (String) -> String?): StageBox {
        val side = s.borderWidths.indices.maxByOrNull { s.borderWidths[it] } ?: 0
        val width = s.borderWidths.maxOrNull() ?: 0f
        val radius = (s.borderRadii.firstOrNull() as? ResolvedLength.Px)?.value ?: 0f
        return StageBox(
            backgroundArgb = s.background.colorArgb ?: s.background.gradient?.stops?.firstOrNull()?.colorArgb,
            borderArgb = if (width > 0f) s.borderColors.getOrNull(side) ?: s.colorArgb else null,
            borderWidthPx = width,
            radiusPx = radius,
            dashed = s.borderStyles.any { it == "dashed" || it == "dotted" },
            backgroundImage = s.background.imageHref?.let(imagePath),
            ink = ink.takeIf { !it.isEmpty }
        )
    }

    /** 正文的样子：按字数取最多的那种（字体、颜色、粗斜、字号）。 */
    private fun baseline(root: StyledDomNode, embedded: Set<String>, themeTextArgb: Int): Body {
        val weights = HashMap<Body, Int>()
        fun walk(n: StyledDomNode, parent: EpubStyle?) {
            val node = n.node
            if (node.tag == "#text") {
                val s = parent ?: n.style
                val length = (node.textEnd - node.textStart).coerceAtLeast(0)
                if (length > 0) {
                    val key = Body(firstEmbedded(s, embedded) ?: s.fontFamilies.firstOrNull()?.lowercase(), s.colorArgb, s.fontWeight >= 600, s.italic, s.fontSizePx)
                    weights[key] = (weights[key] ?: 0) + length
                }
                return
            }
            n.children.forEach { walk(it, n.style) }
        }
        walk(root, null)
        return weights.maxByOrNull { it.value }?.key ?: Body(null, themeTextArgb, false, false, root.style.fontSizePx)
    }

    private fun firstEmbedded(s: EpubStyle, embedded: Set<String>): String? =
        s.fontFamilies.map { it.trim().trim('"', '\'').lowercase() }.firstOrNull { it in embedded }

    /** 两种颜色看得出差别（忽略透明度，按通道最大差）。 */
    private fun distinct(a: Int, b: Int): Boolean {
        val dr = abs((a ushr 16 and 0xFF) - (b ushr 16 and 0xFF))
        val dg = abs((a ushr 8 and 0xFF) - (b ushr 8 and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return maxOf(dr, dg, db) > 24
    }

    private fun range(n: StyledDomNode): IntRange? {
        var a = Int.MAX_VALUE
        var b = -1
        fun walk(x: StyledDomNode) {
            val node = x.node
            if (node.textStart >= 0 && node.textEnd >= node.textStart) { a = minOf(a, node.textStart); b = maxOf(b, node.textEnd) }
            x.children.forEach(::walk)
        }
        walk(n)
        return if (b <= a) null else a until b
    }

    private const val TITLE_WINDOW = 300
}
