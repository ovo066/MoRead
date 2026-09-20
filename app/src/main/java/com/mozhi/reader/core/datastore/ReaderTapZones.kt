package com.mozhi.reader.core.datastore

/** Stable names are persisted; each reader surface resolves the same thirteen targets. */
enum class ReaderTapAction(val label: String) {
    NONE("无操作"), PREVIOUS_PAGE("上一页"), NEXT_PAGE("下一页"), MENU("菜单"),
    CONTENTS("目录 / 人物"), BOOKMARKS("书签"), TOGGLE_BOOKMARK("添加 / 移除书签"),
    SETTINGS("阅读设置"), PREVIOUS_CHAPTER("上一章"), NEXT_CHAPTER("下一章"), SEARCH("书内搜索"),
    TOGGLE_TRANSLATIONS("显示 / 隐藏译文"), ENGLISH_LEARNING("英语学习 / 词典")
}

data class ReaderTapZones(val actions: List<ReaderTapAction> = DEFAULT_ACTIONS) {
    fun actionAt(x: Float, y: Float, width: Float, height: Float): ReaderTapAction =
        actions.getOrElse(indexAt(x, y, width, height)) { ReaderTapAction.MENU }

    fun withAction(index: Int, action: ReaderTapAction) = copy(actions = actions.mapIndexed { i, old ->
        if (i == index) action else old
    })

    val hasMenu: Boolean get() = ReaderTapAction.MENU in actions

    companion object {
        // Body 0..8, header 9..10, footer 11..12. Insets belong to the host, not to this model.
        val DEFAULT_ACTIONS = List(9) { index -> when (index % 3) {
            0 -> ReaderTapAction.PREVIOUS_PAGE
            1 -> ReaderTapAction.MENU
            else -> ReaderTapAction.NEXT_PAGE
        } } + List(4) { ReaderTapAction.NONE }
        const val EDGE_FRACTION = 0.08f

        fun indexAt(x: Float, y: Float, width: Float, height: Float): Int {
            val nx = (x / width.coerceAtLeast(1f)).coerceIn(0f, 0.999999f)
            val ny = (y / height.coerceAtLeast(1f)).coerceIn(0f, 0.999999f)
            if (ny < EDGE_FRACTION) return 9 + (nx * 2).toInt()
            if (ny >= 1f - EDGE_FRACTION) return 11 + (nx * 2).toInt()
            val row = ((ny - EDGE_FRACTION) / (1f - 2 * EDGE_FRACTION) * 3).toInt().coerceIn(0, 2)
            return row * 3 + (nx * 3).toInt()
        }

        fun decode(raw: String?): ReaderTapZones? {
            if (raw.isNullOrBlank()) return null // retain the existing spread/scroll behavior until configured
            val entries = raw.split(',')
            if (entries.size != 13) return null
            val actions = entries.map { name -> ReaderTapAction.entries.firstOrNull { it.name == name } ?: return null }
            return ReaderTapZones(actions).takeIf { it.hasMenu }
        }
    }

    fun encode(): String = actions.joinToString(",") { it.name }
}
