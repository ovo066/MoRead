package com.mozhi.reader.core.epub.dom

/**
 * 在持久化 DOM 里定位 `href#fragment` 指向的正文范围。
 *
 * 只有文本/图片叶子带 text.mz 坐标；元素本身是 -1。因此目标元素的范围取其后代叶子的最小起点
 * 与最大终点；`<a id="x"></a>` 这类空锚点没有后代叶子时，退到文档序里它之后的第一个叶子起点
 * （零长度范围），与浏览器把视口滚到锚点处的行为一致。找不到 id 返回 null，由调用方决定回退。
 */
object EpubDomFragmentLocator {
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
