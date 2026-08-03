package com.mozhi.reader.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mozhi.reader.ui.theme.AccentPreset
import com.mozhi.reader.ui.theme.AppearanceSettings
import com.mozhi.reader.ui.theme.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ReaderTheme {
    SYSTEM,
    LIGHT,
    DARK,
    PAPER,
    EYE_CARE,
    /** Pure-black OLED theme, 深空. */
    AMOLED,
    /** Pale celadon, 青简. */
    MIST
}

enum class PageMode {
    PAGINATED,
    SCROLL
}

enum class PageTurnAnimation {
    SIMULATION,
    COVER,
    SLIDE,
    NONE
}

enum class ReaderFont {
    SYSTEM,
    SERIF,
    SANS_SERIF,
    MONOSPACE
}

enum class ShelfLayout {
    GRID,
    LIST
}

data class ReaderSettings(
    val fontScale: Float = 1f,
    val font: ReaderFont = ReaderFont.SYSTEM,
    val lineHeight: Float = 1.55f,
    val pageMargin: Float = 1f,
    val theme: ReaderTheme = ReaderTheme.SYSTEM,
    val pageMode: PageMode = PageMode.PAGINATED,
    val pageTurnAnimation: PageTurnAnimation = PageTurnAnimation.SIMULATION,
    val shelfLayout: ShelfLayout = ShelfLayout.GRID,
    val keepScreenOn: Boolean = false,
    /** 用户保存的自定义主题预设。 */
    val customThemes: List<CustomReaderTheme> = emptyList(),
    /** 非空表示自定义主题生效，覆盖 [theme]；选内置主题时清空。 */
    val activeCustomThemeId: Long? = null
)

/** 当前生效的自定义主题；id 悬空（预设已删）按未启用处理。 */
fun ReaderSettings.activeCustomTheme(): CustomReaderTheme? =
    activeCustomThemeId?.let { id -> customThemes.firstOrNull { it.id == id } }

@Singleton
class ReaderSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val settings: Flow<ReaderSettings> = dataStore.data.map { preferences ->
        ReaderSettings(
            fontScale = preferences[Keys.FontScale] ?: 1f,
            font = preferences[Keys.Font]
                ?.let { runCatching { ReaderFont.valueOf(it) }.getOrNull() }
                ?: ReaderFont.SYSTEM,
            lineHeight = preferences[Keys.LineHeight] ?: 1.55f,
            pageMargin = preferences[Keys.PageMargin] ?: 1f,
            theme = preferences[Keys.Theme]
                ?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
                ?: ReaderTheme.SYSTEM,
            // 2026-08-03 滚动模式回归：以章节为单位的连续滚动（上下滑动翻页）。
            pageMode = preferences[Keys.PageMode]
                ?.let { runCatching { PageMode.valueOf(it) }.getOrNull() }
                ?: PageMode.PAGINATED,
            pageTurnAnimation = preferences[Keys.PageTurnAnimation]
                ?.let { runCatching { PageTurnAnimation.valueOf(it) }.getOrNull() }
                ?: PageTurnAnimation.SIMULATION,
            shelfLayout = preferences[Keys.ShelfLayout]
                ?.let { runCatching { ShelfLayout.valueOf(it) }.getOrNull() }
                ?: ShelfLayout.GRID,
            keepScreenOn = preferences[Keys.KeepScreenOn] ?: false,
            customThemes = CustomReaderThemeCodec.decode(preferences[Keys.CustomThemes]),
            activeCustomThemeId = preferences[Keys.ActiveCustomThemeId]
        )
    }

    /** 外观偏好独立成流：MainActivity 只关心这三项，不必因字号变化重组整棵树。 */
    val appearance: Flow<AppearanceSettings> = dataStore.data.map { preferences ->
        AppearanceSettings(
            themeMode = preferences[Keys.ThemeMode]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            accent = preferences[Keys.AccentPreset]
                ?.let { runCatching { AccentPreset.valueOf(it) }.getOrNull() }
                ?: AccentPreset.Default,
            customAccentArgb = preferences[Keys.AccentCustomArgb]
        )
    }

    suspend fun setThemeMode(value: ThemeMode) {
        dataStore.edit { it[Keys.ThemeMode] = value.name }
    }

    /** 选中预设即清掉自定义色，否则自定义会一直盖住预设。 */
    suspend fun setAccentPreset(value: AccentPreset) {
        dataStore.edit {
            it[Keys.AccentPreset] = value.name
            it.remove(Keys.AccentCustomArgb)
        }
    }

    suspend fun setCustomAccent(argb: Int) {
        dataStore.edit { it[Keys.AccentCustomArgb] = argb }
    }

    suspend fun setFontScale(value: Float) {
        dataStore.edit { it[Keys.FontScale] = value.coerceIn(0.75f, 2f) }
    }

    suspend fun setFont(value: ReaderFont) {
        dataStore.edit { it[Keys.Font] = value.name }
    }

    suspend fun setLineHeight(value: Float) {
        dataStore.edit { it[Keys.LineHeight] = value.coerceIn(1f, 2.2f) }
    }

    suspend fun setPageMargin(value: Float) {
        dataStore.edit { it[Keys.PageMargin] = value.coerceIn(0f, 2f) }
    }

    /** 选内置主题即退出自定义主题，两者互斥（与强调色预设/自定义同款语义）。 */
    suspend fun setTheme(value: ReaderTheme) {
        dataStore.edit {
            it[Keys.Theme] = value.name
            it.remove(Keys.ActiveCustomThemeId)
        }
    }

    /** 保存（新建或覆盖）自定义主题并立即应用；返回落盘的 id。 */
    suspend fun saveCustomTheme(theme: CustomReaderTheme): Long {
        var assigned = theme.id
        dataStore.edit { preferences ->
            val existing = CustomReaderThemeCodec.decode(preferences[Keys.CustomThemes])
            if (assigned == 0L) assigned = (existing.maxOfOrNull { it.id } ?: 0L) + 1
            val updated = existing.filterNot { it.id == assigned } + theme.copy(id = assigned)
            preferences[Keys.CustomThemes] = CustomReaderThemeCodec.encode(updated)
            preferences[Keys.ActiveCustomThemeId] = assigned
        }
        return assigned
    }

    suspend fun deleteCustomTheme(id: Long) {
        dataStore.edit { preferences ->
            val remaining = CustomReaderThemeCodec.decode(preferences[Keys.CustomThemes])
                .filterNot { it.id == id }
            preferences[Keys.CustomThemes] = CustomReaderThemeCodec.encode(remaining)
            if (preferences[Keys.ActiveCustomThemeId] == id) {
                preferences.remove(Keys.ActiveCustomThemeId)
            }
        }
    }

    suspend fun selectCustomTheme(id: Long) {
        dataStore.edit { it[Keys.ActiveCustomThemeId] = id }
    }

    suspend fun setPageMode(value: PageMode) {
        dataStore.edit { it[Keys.PageMode] = value.name }
    }

    suspend fun setPageTurnAnimation(value: PageTurnAnimation) {
        dataStore.edit { it[Keys.PageTurnAnimation] = value.name }
    }

    suspend fun setShelfLayout(value: ShelfLayout) {
        dataStore.edit { it[Keys.ShelfLayout] = value.name }
    }

    suspend fun setKeepScreenOn(value: Boolean) {
        dataStore.edit { it[Keys.KeepScreenOn] = value }
    }

    /** 当前伴读角色（personas.id）；null = 未选择，界面按第一个角色处理。 */
    val activePersonaId: Flow<Long?> = dataStore.data.map { it[Keys.ActivePersonaId] }

    suspend fun setActivePersonaId(personaId: Long?) {
        dataStore.edit { preferences ->
            if (personaId == null) {
                preferences.remove(Keys.ActivePersonaId)
            } else {
                preferences[Keys.ActivePersonaId] = personaId
            }
        }
    }

    /** 伴读输入区的 AI 建议回复；默认开启，关闭后不再发起建议生成请求。 */
    val suggestionRepliesEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.SuggestionReplies] ?: true }

    suspend fun setSuggestionRepliesEnabled(value: Boolean) {
        dataStore.edit { it[Keys.SuggestionReplies] = value }
    }

    /** 显示 AI 批注：关闭后角色划线与「评」标记不再渲染，书籍详情仍可回顾。默认开。 */
    val showAiAnnotations: Flow<Boolean> =
        dataStore.data.map { it[Keys.ShowAiAnnotations] ?: true }

    suspend fun setShowAiAnnotations(value: Boolean) {
        dataStore.edit { it[Keys.ShowAiAnnotations] = value }
    }

    /** 即划即改：记住上次划线样式（AnnotationStyle wire 值），一击直接复用。 */
    val lastAnnotationStyle: Flow<String> =
        dataStore.data.map { it[Keys.LastAnnotationStyle] ?: "HIGHLIGHT" }

    /** 上次划线颜色（AnnotationColors 色名）。 */
    val lastAnnotationColor: Flow<String> =
        dataStore.data.map { it[Keys.LastAnnotationColor] ?: "amber" }

    suspend fun setLastAnnotationInk(style: String, colorTag: String) {
        dataStore.edit {
            it[Keys.LastAnnotationStyle] = style
            it[Keys.LastAnnotationColor] = colorTag
        }
    }

    private object Keys {
        val FontScale = floatPreferencesKey("reader_font_scale")
        val Font = stringPreferencesKey("reader_font")
        val LineHeight = floatPreferencesKey("reader_line_height")
        val PageMargin = floatPreferencesKey("reader_page_margin")
        val Theme = stringPreferencesKey("reader_theme")
        val PageMode = stringPreferencesKey("reader_page_mode")
        val PageTurnAnimation = stringPreferencesKey("reader_page_turn_animation")
        val ShelfLayout = stringPreferencesKey("shelf_layout")
        val KeepScreenOn = booleanPreferencesKey("keep_screen_on")
        val ThemeMode = stringPreferencesKey("app_theme_mode")
        val AccentPreset = stringPreferencesKey("accent_preset")
        val AccentCustomArgb = intPreferencesKey("accent_custom_argb")
        val ActivePersonaId = longPreferencesKey("active_persona_id")
        val SuggestionReplies = booleanPreferencesKey("companion_suggestion_replies")
        val ShowAiAnnotations = booleanPreferencesKey("companion_show_ai_annotations")
        val LastAnnotationStyle = stringPreferencesKey("reader_annotation_last_style")
        val LastAnnotationColor = stringPreferencesKey("reader_annotation_last_color")
        val CustomThemes = stringPreferencesKey("reader_custom_themes")
        val ActiveCustomThemeId = longPreferencesKey("reader_active_custom_theme_id")
    }
}
