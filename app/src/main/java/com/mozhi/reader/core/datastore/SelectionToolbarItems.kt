package com.mozhi.reader.core.datastore

/**
 * 选区工具栏的操作编号。第一排固定放最常用的五项，其余默认收进「更多」，
 * 用户可以在「阅读交互」里把任意可选项提到第一排。
 */
object SelectionToolbarItems {
    const val ANNOTATE = "annotate"
    const val COPY = "copy"
    const val DICTIONARY = "dictionary"
    const val TRANSLATE = "translate"
    const val ASK = "ask"
    const val ANALYZE = "analyze"
    const val PARAGRAPH = "paragraph"
    const val SPEAK = "speak"
    const val IMAGE = "image"
    const val EDIT = "edit"

    val PRIMARY = listOf(ANNOTATE, COPY, DICTIONARY, TRANSLATE, ASK)
    val OPTIONAL = listOf(ANALYZE, PARAGRAPH, SPEAK, IMAGE, EDIT)

    /** 第一排与「更多」各自的顺序；[editable] = false 时（正文不可编辑）去掉编辑。 */
    fun split(extras: Set<String>, editable: Boolean): Pair<List<String>, List<String>> {
        val optional = OPTIONAL.filter { editable || it != EDIT }
        return (PRIMARY + optional.filter { it in extras }) to optional.filterNot { it in extras }
    }
}
