package com.mozhi.reader.core.datastore

import java.util.Locale
import kotlin.math.round
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 页眉页脚上能放的内容；名字会持久化，只增不改。顺序即同一槽位内的显示顺序。 */
enum class ReaderChromeItem { BOOK_TITLE, CHAPTER_TITLE, CHAPTER_PAGE, CHAPTER_PERCENT, BOOK_PERCENT, TIME, BATTERY }

enum class ReaderChromeSlot {
    HEADER_LEFT, HEADER_CENTER, HEADER_RIGHT, FOOTER_LEFT, FOOTER_CENTER, FOOTER_RIGHT;

    val isHeader: Boolean get() = this == HEADER_LEFT || this == HEADER_CENTER || this == HEADER_RIGHT
}

data class ReaderChromeLayout(
    val slots: Map<ReaderChromeSlot, List<ReaderChromeItem>> = DEFAULT_SLOTS,
    /** 状态栏可见（没开「完全沉浸」）时不画时间与电量：系统状态栏里已经有了。 */
    val hideClockWithStatusBar: Boolean = false
) {
    /** 槽位里的内容，按枚举顺序、去重；没配置的槽位是空表。 */
    fun items(slot: ReaderChromeSlot): List<ReaderChromeItem> =
        ReaderChromeItem.entries.filter { it in slots[slot].orEmpty() }

    /** 把 [items] 放进 [slot]：同一项只能在一个槽位里，放进来就从其它槽位移走；结果按枚举顺序去重。 */
    fun with(slot: ReaderChromeSlot, items: List<ReaderChromeItem>): ReaderChromeLayout {
        val selected = ReaderChromeItem.entries.filter { it in items }
        val claimed = selected.toMutableSet()
        return copy(slots = ReaderChromeSlot.entries.associateWith { current ->
            if (current == slot) selected else this.items(current).filter { claimed.add(it) }
        })
    }

    /** 绘制时实际显示的内容：开了 hideClockWithStatusBar 且状态栏可见时去掉 TIME、BATTERY。 */
    fun visibleItems(slot: ReaderChromeSlot, statusBarVisible: Boolean): List<ReaderChromeItem> =
        items(slot).filterNot {
            hideClockWithStatusBar && statusBarVisible &&
                (it == ReaderChromeItem.TIME || it == ReaderChromeItem.BATTERY)
        }

    /** 三个页眉槽位在当前状态下都没东西可画。 */
    fun headerEmpty(statusBarVisible: Boolean): Boolean = ReaderChromeSlot.entries
        .filter { it.isHeader }.all { visibleItems(it, statusBarVisible).isEmpty() }

    fun footerEmpty(statusBarVisible: Boolean): Boolean = ReaderChromeSlot.entries
        .filterNot { it.isHeader }.all { visibleItems(it, statusBarVisible).isEmpty() }

    companion object {
        /** 默认还原现在的样子：页眉左章名；页脚左「本章页码」，右「全书进度 · 时间 + 电池」。 */
        val DEFAULT_SLOTS: Map<ReaderChromeSlot, List<ReaderChromeItem>> = ReaderChromeSlot.entries.associateWith {
            when (it) {
                ReaderChromeSlot.HEADER_LEFT -> listOf(ReaderChromeItem.CHAPTER_TITLE)
                ReaderChromeSlot.FOOTER_LEFT -> listOf(ReaderChromeItem.CHAPTER_PAGE)
                ReaderChromeSlot.FOOTER_RIGHT -> listOf(ReaderChromeItem.BOOK_PERCENT, ReaderChromeItem.TIME, ReaderChromeItem.BATTERY)
                else -> emptyList()
            }
        }
        val DEFAULT: ReaderChromeLayout = ReaderChromeLayout()
        private val json = Json

        /** null、空串、损坏的 JSON → DEFAULT；未知的槽位名或内容名丢掉其余照读；同一项出现在多个槽位时保留第一个。 */
        fun decode(raw: String?): ReaderChromeLayout {
            if (raw.isNullOrBlank()) return DEFAULT
            val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return DEFAULT
            val encodedSlots = root["slots"] as? JsonObject ?: return DEFAULT
            val claimed = mutableSetOf<ReaderChromeItem>()
            val decoded = mutableMapOf<ReaderChromeSlot, List<ReaderChromeItem>>()
            // 按 JSON 中的槽位先后认领内容；一个字段损坏不影响其它槽位。
            encodedSlots.forEach { (name, value) ->
                val slot = ReaderChromeSlot.entries.firstOrNull { it.name == name } ?: return@forEach
                val values = value as? JsonArray ?: return@forEach
                val known = values.mapNotNull { element ->
                    val itemName = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ReaderChromeItem.entries.firstOrNull { it.name == itemName }
                }.toSet()
                decoded[slot] = ReaderChromeItem.entries.filter { it in known && claimed.add(it) }
            }
            return ReaderChromeLayout(
                slots = decoded.toMap(),
                hideClockWithStatusBar = (root["hideClockWithStatusBar"] as? JsonPrimitive)?.booleanOrNull ?: false
            )
        }

        fun encode(value: ReaderChromeLayout): String {
            val claimed = mutableSetOf<ReaderChromeItem>()
            return buildJsonObject {
                put("version", 1)
                put("hideClockWithStatusBar", value.hideClockWithStatusBar)
                put("slots", buildJsonObject {
                    ReaderChromeSlot.entries.filter { it in value.slots }.forEach { slot ->
                        put(slot.name, JsonArray(value.items(slot).filter { claimed.add(it) }.map { JsonPrimitive(it.name) }))
                    }
                })
            }.toString()
        }
    }
}

/** 绘制一页页眉页脚时用到的数据。 */
data class ReaderChromeInfo(
    val bookTitle: String,
    val chapterTitle: String,
    /** 本章页码文字（如「3 / 12 页」「本章完」「加载中」），由绘制层按资源字符串算好。 */
    val pageLabel: String,
    /** 本章进度 0..1；不知道（加载中、滚动模式未排版）时为 null。 */
    val chapterProgress: Float?,
    /** 全书进度 0..1。 */
    val bookProgress: Float,
    val timeText: String
)

object ReaderChromeText {
    /** 一个槽位要画的文字：各项用「 · 」连接；BATTERY 不出字（由绘制层画电池图标）；拿不到值的项跳过。 */
    fun text(items: List<ReaderChromeItem>, info: ReaderChromeInfo): String = items.mapNotNull { item ->
        when (item) {
            ReaderChromeItem.BOOK_TITLE -> info.bookTitle.takeIf { it.isNotBlank() }
            ReaderChromeItem.CHAPTER_TITLE -> info.chapterTitle.takeIf { it.isNotBlank() }
            ReaderChromeItem.CHAPTER_PAGE -> info.pageLabel.takeIf { it.isNotBlank() }
            ReaderChromeItem.CHAPTER_PERCENT -> info.chapterProgress?.takeIf { it.isFinite() }?.let {
                String.format(Locale.ROOT, "%d%%", round(it * 100).toInt())
            }
            ReaderChromeItem.BOOK_PERCENT -> info.bookProgress.takeIf { it.isFinite() }?.let {
                String.format(Locale.ROOT, "%.1f%%", it * 100)
            }
            ReaderChromeItem.TIME -> info.timeText.takeIf { it.isNotBlank() }
            ReaderChromeItem.BATTERY -> null
        }
    }.joinToString(" · ")

    fun hasBattery(items: List<ReaderChromeItem>): Boolean = ReaderChromeItem.BATTERY in items
}
