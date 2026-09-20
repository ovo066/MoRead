package com.mozhi.reader.core.epub.dom

/**
 * 在持久化 DOM 里定位 `href#fragment` 指向的正文范围。
 *
 * 只有文本/图片叶子带 text.mz 坐标；元素本身是 -1。因此目标元素的范围取其后代叶子的最小起点
 * 与最大终点；`<a id="x"></a>` 这类空锚点没有后代叶子时，退到文档序里它之后的第一个叶子起点
 * （零长度范围），与浏览器把视口滚到锚点处的行为一致。找不到 id 返回 null，由调用方决定回退。
 */
object EpubDomFragmentLocator {
    /** Preview the note containing an inline marker, without changing its navigation anchor. */
    fun previewRange(body: EpubDomNode, fragment: String): IntRange? {
        val id = fragment.trim().removePrefix("#").takeIf(String::isNotEmpty) ?: return null
        val path = mutableListOf<EpubDomNode>()
        fun find(node: EpubDomNode): Boolean {
            path += node
            if (node.id == id || node.children.any(::find)) return true
            path.removeAt(path.lastIndex)
            return false
        }
        if (!find(body)) return null
        fun range(node: EpubDomNode): IntRange? {
            var start = Int.MAX_VALUE
            var end = -1
            fun collect(current: EpubDomNode) {
                if (current.textStart in 0 until current.textEnd) {
                    start = minOf(start, current.textStart)
                    end = maxOf(end, current.textEnd)
                }
                current.children.forEach(::collect)
            }
            collect(node)
            return if (end >= 0) start until end else null
        }
        // A semantic note can contain several paragraphs. Do not include adjacent notes.
        path.asReversed().firstOrNull { node ->
            node.attributes["epub:type"].orEmpty().split(Regex("\\s+")).any { it == "footnote" || it == "endnote" } ||
                node.attributes["role"] in setOf("doc-footnote", "doc-endnote")
        }?.let { note -> range(note)?.let { return it } }
        path.asReversed().firstOrNull { it.tag.lowercase() in PREVIEW_BLOCKS }
            ?.let { block -> range(block)?.let { return it } }
        val anchor = locate(body, fragment) ?: return null
        if (!anchor.isEmpty()) return anchor
        // Standalone empty anchors before a paragraph have no containing text block.
        fun followingBlock(node: EpubDomNode): IntRange? {
            if (node.tag.lowercase() in PREVIEW_BLOCKS) {
                range(node)?.takeIf { it.first == anchor.first }?.let { return it }
            }
            return node.children.firstNotNullOfOrNull(::followingBlock)
        }
        return followingBlock(body) ?: anchor
    }

    private val PREVIEW_BLOCKS = setOf("p", "li", "dd", "dt", "div", "aside", "section", "blockquote", "td", "th", "h1", "h2", "h3", "h4", "h5", "h6")

    fun locate(body: EpubDomNode, fragment: String): IntRange? {
        val id = fragment.trim().removePrefix("#").takeIf(String::isNotEmpty) ?: return null
        var targetFound = false
        var start = Int.MAX_VALUE
        var end = -1
        var following: Int? = null
        fun walk(node: EpubDomNode, insideTarget: Boolean) {
            if (following != null) return
            val within = insideTarget || (!targetFound && node.id == id).also { if (it) targetFound = true }
            val anchored = node.textStart in 0 until node.textEnd
            if (anchored) {
                if (within) {
                    if (node.textStart < start) start = node.textStart
                    if (node.textEnd > end) end = node.textEnd
                } else if (targetFound && end < 0) {
                    following = node.textStart
                    return
                }
            }
            node.children.forEach { walk(it, within) }
        }
        walk(body, false)
        if (!targetFound) return null
        if (end >= 0) return start until end
        val next = following ?: return null
        return next until next
    }
}
