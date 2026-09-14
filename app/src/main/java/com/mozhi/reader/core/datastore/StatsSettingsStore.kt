package com.mozhi.reader.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

enum class StatsWidget(val title: String, val description: String) {
    HEATMAP("阅读热力", "查看阅读的频率与分布"),
    CALENDAR("阅读月历", "用书籍封面拼出每一天的阅读足迹"),
    TREND("阅读趋势", "所选周期内的阅读时长变化"),
    HOURS("阅读时间段", "一天中，什么时候读得最多"),
    TIMELINE("阅读时间线", "看见每本书的阅读日期与连续跨度"),
    BOOKS("阅读排行", "这个周期里读得最多的书"),
    TAGS("标签云", "从书籍标签看阅读偏好"),
    AUTHORS("作者云", "与哪些作者相处得最多")
}

data class StatsWidgets(
    val order: List<StatsWidget> = StatsWidget.entries.toList(),
    val hidden: Set<StatsWidget> = emptySet()
) {
    val visible: List<StatsWidget> get() = order.filterNot { it in hidden }
}

@Singleton
class StatsSettingsStore @Inject constructor(private val dataStore: DataStore<Preferences>) {
    val widgets = dataStore.data.map(::read)

    suspend fun setVisible(widget: StatsWidget, visible: Boolean) = dataStore.edit { preferences ->
        val hidden = read(preferences).hidden.toMutableSet()
        if (visible) hidden.remove(widget) else hidden.add(widget)
        preferences[HIDDEN] = hidden.mapTo(mutableSetOf()) { it.name }
    }

    suspend fun move(widget: StatsWidget, direction: Int) = dataStore.edit { preferences ->
        val order = read(preferences).order.toMutableList()
        val from = order.indexOf(widget)
        val to = (from + direction.coerceIn(-1, 1)).coerceIn(order.indices)
        order.removeAt(from)
        order.add(to, widget)
        preferences[ORDER] = order.joinToString(",") { it.name }
    }

    suspend fun reset() = dataStore.edit { it.remove(ORDER); it.remove(HIDDEN) }

    private fun read(preferences: Preferences): StatsWidgets {
        val known = StatsWidget.entries.associateBy { it.name }
        val order = preferences[ORDER].orEmpty().split(',').mapNotNull(known::get).distinct()
        return StatsWidgets(order + StatsWidget.entries.filterNot { it in order },
            preferences[HIDDEN].orEmpty().mapNotNull(known::get).toSet())
    }

    private companion object {
        val ORDER = stringPreferencesKey("stats_widget_order")
        val HIDDEN = stringSetPreferencesKey("stats_hidden_widgets")
    }
}
