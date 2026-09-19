package com.mozhi.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.mozhi.reader.ui.theme.LocalMoReadColors
import com.mozhi.reader.ui.theme.MoReadTone
import com.mozhi.reader.ui.theme.SemanticSlot
import com.mozhi.reader.ui.theme.semanticColors

/**
 * 书架标签色。四个内置中文名映射到当前配色方案的标签色板，`#RRGGBB` 自定义值原样解析。
 *
 * 曾经在 `ShelfOrganizationComponents` 与 `TagManageScreen` 里各写一份硬编码四色，
 * 换配色方案时会有一处忘改；这里收成唯一来源。
 */
@Composable
@ReadOnlyComposable
fun shelfTagColor(tag: String): Color {
    val palette = LocalMoReadColors.current.tagPalette
    return when (tag) {
        "琥珀" -> palette[0]
        "青竹" -> palette[1]
        "黛蓝" -> palette[2]
        "绯红" -> palette[3]
        else -> runCatching { Color(android.graphics.Color.parseColor(tag)) }.getOrDefault(Color.Gray)
    }
}

/** 标签色板的取色顺序，供标签编辑页的色板展示使用。 */
@Composable
@ReadOnlyComposable
fun shelfTagPalette(): List<Color> = LocalMoReadColors.current.tagPalette

/** 按名字散列到一个语义色位：同一个角色在任何页面都得到同一种颜色。 */
@Composable
@ReadOnlyComposable
fun personaTone(name: String): MoReadTone {
    val slots = SemanticSlot.entries
    return semanticColors()[slots[personaPaletteIndex(name, slots.size)]]
}

/** 保留旧头像的散列分配，同时避免 Int.MIN_VALUE 取绝对值后仍为负数。 */
internal fun personaPaletteIndex(name: String, size: Int): Int = (kotlin.math.abs(name.hashCode().toLong()) % size).toInt()
