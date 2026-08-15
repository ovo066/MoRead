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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
    MONOSPACE,
    CUSTOM
}

enum class ShelfLayout {
    GRID,
    LIST
}

data class ReaderSettings(
    val fontScale: Float = 1f,
    val font: ReaderFont = ReaderFont.SYSTEM,
    /** 已导入到应用私有目录的 TTF/OTF 文件。 */
    val customFontPath: String? = null,
    /** 从字体 name 表自动识别、且允许用户在导入时改写的显示名。 */
    val customFontName: String? = null,
    /** 所有已导入字体；正文与语法规则均通过稳定 id 引用。 */
    val fontLibrary: List<ReaderFontAsset> = emptyList(),
    val selectedCustomFontId: String? = null,
    /** 正文字重；Android Typeface 的常用有效区间为 100..900。 */
    val fontWeight: Int = 400,
    val lineHeight: Float = 1.55f,
    /** 旧版统一边距；保留用于迁移，运行时请使用四边独立值。 */
    val pageMargin: Float = 1f,
    /** 阅读正文左侧边距，0..2 对应紧凑到宽松。 */
    val pageMarginLeft: Float = 1f,
    /** 阅读正文右侧边距，0..2 对应紧凑到宽松。 */
    val pageMarginRight: Float = 1f,
    /** 阅读正文上侧边距，0..2 对应紧凑到宽松。 */
    val pageMarginTop: Float = 0f,
    /** 阅读正文下侧边距，0..2 对应紧凑到宽松。 */
    val pageMarginBottom: Float = 0f,
    /** 页眉相对默认位置继续向下偏移，0..2。 */
    val headerMarginTop: Float = 0f,
    /** 页脚相对默认位置继续向上偏移，0..2。 */
    val footerMarginBottom: Float = 0f,
    /** TextPaint 的 em 字间距。 */
    val letterSpacingEm: Float = 0f,
    /** 段后距，以正文字号 em 为单位。 */
    val paragraphSpacingEm: Float = 0.55f,
    /** 段首缩进，以全角字符宽度为单位；不向正文注入空格。 */
    val firstLineIndentEm: Float = 2f,
    /** 章节标题相对正文字号。 */
    val titleScale: Float = 1.35f,
    /** 章节标题上方留白，以正文行高为单位。 */
    val titleTopSpacing: Float = 0.4f,
    /** 章节标题与正文之间留白，以正文行高为单位。 */
    val titleBottomSpacing: Float = 1f,
    val textJustification: Boolean = true,
    val showHeader: Boolean = true,
    val showFooter: Boolean = true,
    val theme: ReaderTheme = ReaderTheme.SYSTEM,
    val pageMode: PageMode = PageMode.PAGINATED,
    val pageTurnAnimation: PageTurnAnimation = PageTurnAnimation.SIMULATION,
    val shelfLayout: ShelfLayout = ShelfLayout.GRID,
    val keepScreenOn: Boolean = false,
    /** 阅读页默认隐藏系统状态栏；离开阅读页时恢复。 */
    val immersiveReading: Boolean = true,
    /** 音量加=上一页、音量减=下一页；仅阅读页消费按键。 */
    val volumeKeysPageTurn: Boolean = false,
    /** 已导入到应用私有目录的阅读背景图片。 */
    val backgroundImagePath: String? = null,
    /** 可供阅读背景、书籍封面等位置复用的图片资产。 */
    val imageLibrary: List<ReaderImageAsset> = emptyList(),
    val selectedBackgroundImageId: String? = null,
    /** 背景图片在主题底色之上的不透明度。 */
    val backgroundImageOpacity: Float = 0.28f,
    val syntaxHighlightEnabled: Boolean = false,
    val syntaxHighlightRules: List<ReaderSyntaxRule> = ReaderSyntaxHighlighter.DEFAULT_RULES,
    /** 正文清洗/替换规则；规则本身可复用，应用范围由阅读页工具决定。 */
    val textReplacementRules: List<ReaderTextReplacementRule> = emptyList(),
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
        val legacyPath = preferences[Keys.CustomFontPath]?.takeIf(String::isNotBlank)
        val legacyName = preferences[Keys.CustomFontName]?.takeIf(String::isNotBlank)
        val fontLibrary = ReaderFontLibraryCodec.includeLegacy(
            ReaderFontLibraryCodec.decode(preferences[Keys.FontLibrary]),
            legacyPath,
            legacyName
        )
        val selectedFontId = preferences[Keys.SelectedCustomFontId]
            ?.takeIf { id -> fontLibrary.any { it.id == id } }
            ?: legacyPath?.let { path -> fontLibrary.firstOrNull { it.filePath == path }?.id }
            ?: fontLibrary.firstOrNull()?.id
        val selectedFont = fontLibrary.firstOrNull { it.id == selectedFontId }
        val legacyBackgroundPath = preferences[Keys.BackgroundImagePath]
            ?.takeIf(String::isNotBlank)
        val imageLibrary = ReaderImageLibraryCodec.includeLegacyBackground(
            ReaderImageLibraryCodec.decode(preferences[Keys.ImageLibrary]),
            legacyBackgroundPath
        )
        val selectedBackgroundId = preferences[Keys.SelectedBackgroundImageId]
            ?.takeIf { id -> imageLibrary.any { it.id == id } }
            ?: legacyBackgroundPath?.let { path ->
                imageLibrary.firstOrNull { it.filePath == path }?.id
            }
        val selectedBackground = imageLibrary.firstOrNull { it.id == selectedBackgroundId }
        ReaderSettings(
            fontScale = preferences[Keys.FontScale] ?: 1f,
            font = preferences[Keys.Font]
                ?.let { runCatching { ReaderFont.valueOf(it) }.getOrNull() }
                ?: ReaderFont.SYSTEM,
            customFontPath = selectedFont?.filePath,
            customFontName = selectedFont?.displayName,
            fontLibrary = fontLibrary,
            selectedCustomFontId = selectedFontId,
            fontWeight = (preferences[Keys.FontWeight] ?: 400).coerceIn(300, 700),
            lineHeight = (preferences[Keys.LineHeight] ?: 1.55f).coerceIn(1f, 2.2f),
            pageMargin = (preferences[Keys.PageMargin] ?: 1f).coerceIn(0f, 2f),
            pageMarginLeft = (preferences[Keys.PageMarginLeft]
                ?: preferences[Keys.PageMargin]
                ?: 1f).coerceIn(0f, 2f),
            pageMarginRight = (preferences[Keys.PageMarginRight]
                ?: preferences[Keys.PageMargin]
                ?: 1f).coerceIn(0f, 2f),
            pageMarginTop = (preferences[Keys.PageMarginTop] ?: 0f).coerceIn(0f, 2f),
            pageMarginBottom = (preferences[Keys.PageMarginBottom] ?: 0f).coerceIn(0f, 2f),
            headerMarginTop = (preferences[Keys.HeaderMarginTop] ?: 0f).coerceIn(0f, 2f),
            footerMarginBottom = (preferences[Keys.FooterMarginBottom] ?: 0f).coerceIn(0f, 2f),
            letterSpacingEm = (preferences[Keys.LetterSpacingEm] ?: 0f).coerceIn(-0.05f, 0.2f),
            paragraphSpacingEm = (preferences[Keys.ParagraphSpacingEm] ?: 0.55f).coerceIn(0f, 1.5f),
            firstLineIndentEm = (preferences[Keys.FirstLineIndentEm] ?: 2f).coerceIn(0f, 4f),
            titleScale = (preferences[Keys.TitleScale] ?: 1.35f).coerceIn(1f, 2f),
            titleTopSpacing = (preferences[Keys.TitleTopSpacing] ?: 0.4f).coerceIn(0f, 3f),
            titleBottomSpacing = (preferences[Keys.TitleBottomSpacing] ?: 1f).coerceIn(0f, 3f),
            textJustification = preferences[Keys.TextJustification] ?: true,
            showHeader = preferences[Keys.ShowHeader] ?: true,
            showFooter = preferences[Keys.ShowFooter] ?: true,
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
            immersiveReading = preferences[Keys.ImmersiveReading] ?: true,
            volumeKeysPageTurn = preferences[Keys.VolumeKeysPageTurn] ?: false,
            backgroundImagePath = selectedBackground?.filePath ?: legacyBackgroundPath,
            imageLibrary = imageLibrary,
            selectedBackgroundImageId = selectedBackgroundId,
            backgroundImageOpacity = (preferences[Keys.BackgroundImageOpacity] ?: 0.28f)
                .coerceIn(0.05f, 1f),
            syntaxHighlightEnabled = preferences[Keys.SyntaxHighlightEnabled] ?: false,
            syntaxHighlightRules = ReaderSyntaxRuleCodec.decode(preferences[Keys.SyntaxHighlightRules]),
            textReplacementRules = ReaderTextReplacementRuleCodec.decode(
                preferences[Keys.TextReplacementRules]
            ),
            customThemes = CustomReaderThemeCodec.decode(preferences[Keys.CustomThemes]),
            activeCustomThemeId = preferences[Keys.ActiveCustomThemeId]
        )
    }

    /**
     * 进程级热缓存：阅读页首帧要同步拿到真实纸色与排版，冷读 DataStore 会先用
     * 默认值画一帧再跳变（进场转场中途背景变色）。Eagerly 随单例构造预热；
     * 进程冷启动瞬间取到默认值属可接受回落。需要「等到真实值」的写路径仍用
     * [settings].first()。
     */
    val cachedSettings: StateFlow<ReaderSettings> = settings.stateIn(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        started = SharingStarted.Eagerly,
        initialValue = ReaderSettings()
    )

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

    suspend fun addCustomFont(font: ReaderFontAsset, select: Boolean = true) {
        dataStore.edit { preferences ->
            val existing = fontLibraryFrom(preferences)
            preferences[Keys.FontLibrary] = ReaderFontLibraryCodec.encode(
                existing.filterNot { it.id == font.id || it.filePath == font.filePath } + font
            )
            preferences.remove(Keys.CustomFontPath)
            preferences.remove(Keys.CustomFontName)
            if (select) {
                preferences[Keys.SelectedCustomFontId] = font.id
                preferences[Keys.Font] = ReaderFont.CUSTOM.name
            }
        }
    }

    suspend fun selectCustomFont(id: String) {
        dataStore.edit { preferences ->
            require(fontLibraryFrom(preferences).any { it.id == id }) { "字体不存在或已删除" }
            preferences[Keys.SelectedCustomFontId] = id
            preferences[Keys.Font] = ReaderFont.CUSTOM.name
        }
    }

    suspend fun renameCustomFont(id: String, displayName: String) {
        val name = displayName.trim().take(48)
        require(name.isNotBlank()) { "字体名称不能为空" }
        dataStore.edit { preferences ->
            val fonts = fontLibraryFrom(preferences)
            require(fonts.any { it.id == id }) { "字体不存在或已删除" }
            preferences[Keys.FontLibrary] = ReaderFontLibraryCodec.encode(
                fonts.map { if (it.id == id) it.copy(displayName = name) else it }
            )
            preferences.remove(Keys.CustomFontPath)
            preferences.remove(Keys.CustomFontName)
        }
    }

    suspend fun removeCustomFont(id: String) {
        dataStore.edit { preferences ->
            val remaining = fontLibraryFrom(preferences).filterNot { it.id == id }
            preferences[Keys.FontLibrary] = ReaderFontLibraryCodec.encode(remaining)
            preferences.remove(Keys.CustomFontPath)
            preferences.remove(Keys.CustomFontName)
            if (preferences[Keys.SelectedCustomFontId] == id) {
                preferences.remove(Keys.SelectedCustomFontId)
                if (preferences[Keys.Font] == ReaderFont.CUSTOM.name) {
                    preferences[Keys.Font] = ReaderFont.SYSTEM.name
                }
            }
            val rules = ReaderSyntaxRuleCodec.decode(preferences[Keys.SyntaxHighlightRules])
            val repaired = rules.map { rule ->
                if (rule.fontAssetId == id) {
                    rule.copy(font = ReaderSyntaxFont.INHERIT, fontAssetId = null)
                } else {
                    rule
                }
            }
            if (repaired != rules) {
                preferences[Keys.SyntaxHighlightRules] = ReaderSyntaxRuleCodec.encode(repaired)
            }
        }
    }

    suspend fun setCustomFont(path: String?, displayName: String? = null) {
        if (path.isNullOrBlank()) {
            setFont(ReaderFont.SYSTEM)
            return
        }
        addCustomFont(
            ReaderFontAsset(
                id = ReaderFontLibraryCodec.legacyId(path),
                displayName = displayName?.trim()?.takeIf { it.isNotBlank() } ?: "已导入字体",
                filePath = path
            )
        )
    }

    /** 兼容旧调用；新导入流程应同时传入自动识别的名称。 */
    suspend fun setCustomFontPath(path: String?) = setCustomFont(path)

    suspend fun renameCustomFont(displayName: String) {
        val current = settings.first().selectedCustomFontId ?: return
        renameCustomFont(current, displayName)
    }

    suspend fun setFontWeight(value: Int) {
        dataStore.edit { it[Keys.FontWeight] = value.coerceIn(300, 700) }
    }

    suspend fun setLineHeight(value: Float) {
        dataStore.edit { it[Keys.LineHeight] = value.coerceIn(1f, 2.2f) }
    }

    suspend fun setPageMargin(value: Float) {
        val safe = value.coerceIn(0f, 2f)
        dataStore.edit {
            it[Keys.PageMargin] = safe
            it[Keys.PageMarginLeft] = safe
            it[Keys.PageMarginRight] = safe
            it[Keys.PageMarginTop] = safe
            it[Keys.PageMarginBottom] = safe
        }
    }

    suspend fun setPageMarginLeft(value: Float) {
        dataStore.edit { it[Keys.PageMarginLeft] = value.coerceIn(0f, 2f) }
    }

    suspend fun setPageMarginRight(value: Float) {
        dataStore.edit { it[Keys.PageMarginRight] = value.coerceIn(0f, 2f) }
    }

    suspend fun setPageMarginTop(value: Float) {
        dataStore.edit { it[Keys.PageMarginTop] = value.coerceIn(0f, 2f) }
    }

    suspend fun setPageMarginBottom(value: Float) {
        dataStore.edit { it[Keys.PageMarginBottom] = value.coerceIn(0f, 2f) }
    }

    suspend fun setHeaderMarginTop(value: Float) {
        dataStore.edit { it[Keys.HeaderMarginTop] = value.coerceIn(0f, 2f) }
    }

    suspend fun setFooterMarginBottom(value: Float) {
        dataStore.edit { it[Keys.FooterMarginBottom] = value.coerceIn(0f, 2f) }
    }

    suspend fun setLetterSpacingEm(value: Float) {
        dataStore.edit { it[Keys.LetterSpacingEm] = value.coerceIn(-0.05f, 0.2f) }
    }

    suspend fun setParagraphSpacingEm(value: Float) {
        dataStore.edit { it[Keys.ParagraphSpacingEm] = value.coerceIn(0f, 1.5f) }
    }

    suspend fun setFirstLineIndentEm(value: Float) {
        dataStore.edit { it[Keys.FirstLineIndentEm] = value.coerceIn(0f, 4f) }
    }

    suspend fun setTitleScale(value: Float) {
        dataStore.edit { it[Keys.TitleScale] = value.coerceIn(1f, 2f) }
    }

    suspend fun setTitleTopSpacing(value: Float) {
        dataStore.edit { it[Keys.TitleTopSpacing] = value.coerceIn(0f, 3f) }
    }

    suspend fun setTitleBottomSpacing(value: Float) {
        dataStore.edit { it[Keys.TitleBottomSpacing] = value.coerceIn(0f, 3f) }
    }

    suspend fun setTextJustification(value: Boolean) {
        dataStore.edit { it[Keys.TextJustification] = value }
    }

    suspend fun setShowHeader(value: Boolean) {
        dataStore.edit { it[Keys.ShowHeader] = value }
    }

    suspend fun setShowFooter(value: Boolean) {
        dataStore.edit { it[Keys.ShowFooter] = value }
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

    suspend fun setImmersiveReading(value: Boolean) {
        dataStore.edit { it[Keys.ImmersiveReading] = value }
    }

    suspend fun setVolumeKeysPageTurn(value: Boolean) {
        dataStore.edit { it[Keys.VolumeKeysPageTurn] = value }
    }

    suspend fun setBackgroundImagePath(path: String?) {
        dataStore.edit { preferences ->
            if (path.isNullOrBlank()) {
                preferences.remove(Keys.BackgroundImagePath)
                preferences.remove(Keys.SelectedBackgroundImageId)
            } else {
                val images = imageLibraryFrom(preferences)
                val image = images.firstOrNull { it.filePath == path }
                    ?: ReaderImageAsset(
                        id = ReaderImageLibraryCodec.legacyId(path),
                        displayName = "原有阅读背景",
                        filePath = path
                    ).also { legacy ->
                        preferences[Keys.ImageLibrary] = ReaderImageLibraryCodec.encode(images + legacy)
                    }
                preferences[Keys.SelectedBackgroundImageId] = image.id
                preferences.remove(Keys.BackgroundImagePath)
            }
        }
    }

    suspend fun addReaderImage(image: ReaderImageAsset, selectAsBackground: Boolean = false) {
        dataStore.edit { preferences ->
            val existing = imageLibraryFrom(preferences)
            preferences[Keys.ImageLibrary] = ReaderImageLibraryCodec.encode(
                existing.filterNot { it.id == image.id || it.filePath == image.filePath } + image
            )
            if (selectAsBackground) {
                preferences[Keys.SelectedBackgroundImageId] = image.id
                preferences.remove(Keys.BackgroundImagePath)
            }
        }
    }

    suspend fun selectBackgroundImage(id: String) {
        dataStore.edit { preferences ->
            require(imageLibraryFrom(preferences).any { it.id == id }) { "图片不存在或已删除" }
            preferences[Keys.SelectedBackgroundImageId] = id
            preferences.remove(Keys.BackgroundImagePath)
        }
    }

    suspend fun renameReaderImage(id: String, displayName: String) {
        val name = displayName.trim().take(48)
        require(name.isNotBlank()) { "图片名称不能为空" }
        dataStore.edit { preferences ->
            val images = imageLibraryFrom(preferences)
            require(images.any { it.id == id }) { "图片不存在或已删除" }
            val activeId = activeBackgroundId(preferences, images)
            preferences[Keys.ImageLibrary] = ReaderImageLibraryCodec.encode(
                images.map { if (it.id == id) it.copy(displayName = name) else it }
            )
            if (activeId != null) preferences[Keys.SelectedBackgroundImageId] = activeId
            preferences.remove(Keys.BackgroundImagePath)
        }
    }

    suspend fun removeReaderImage(id: String) {
        dataStore.edit { preferences ->
            val images = imageLibraryFrom(preferences)
            val activeId = activeBackgroundId(preferences, images)
            val remaining = images.filterNot { it.id == id }
            preferences[Keys.ImageLibrary] = ReaderImageLibraryCodec.encode(remaining)
            if (activeId == id) {
                preferences.remove(Keys.SelectedBackgroundImageId)
            } else if (activeId != null) {
                preferences[Keys.SelectedBackgroundImageId] = activeId
            }
            preferences.remove(Keys.BackgroundImagePath)
        }
    }

    suspend fun setBackgroundImageOpacity(value: Float) {
        dataStore.edit { it[Keys.BackgroundImageOpacity] = value.coerceIn(0.05f, 1f) }
    }

    suspend fun setSyntaxHighlightEnabled(value: Boolean) {
        dataStore.edit { it[Keys.SyntaxHighlightEnabled] = value }
    }

    suspend fun saveSyntaxHighlightRule(rule: ReaderSyntaxRule) {
        dataStore.edit { preferences ->
            val existing = ReaderSyntaxRuleCodec.decode(preferences[Keys.SyntaxHighlightRules])
            val id = rule.id.takeIf { it != 0L } ?: ((existing.maxOfOrNull { it.id } ?: 0L) + 1)
            preferences[Keys.SyntaxHighlightRules] = ReaderSyntaxRuleCodec.encode(
                existing.filterNot { it.id == id } + rule.copy(id = id)
            )
        }
    }

    suspend fun deleteSyntaxHighlightRule(id: Long) {
        dataStore.edit { preferences ->
            val remaining = ReaderSyntaxRuleCodec.decode(preferences[Keys.SyntaxHighlightRules])
                .filterNot { it.id == id }
            preferences[Keys.SyntaxHighlightRules] = ReaderSyntaxRuleCodec.encode(remaining)
        }
    }

    suspend fun saveTextReplacementRule(rule: ReaderTextReplacementRule) {
        dataStore.edit { preferences ->
            val existing = ReaderTextReplacementRuleCodec.decode(preferences[Keys.TextReplacementRules])
            val id = rule.id.takeIf { it != 0L } ?: ((existing.maxOfOrNull { it.id } ?: 0L) + 1L)
            val saved = rule.copy(id = id)
            val next = existing.toMutableList()
            val index = next.indexOfFirst { it.id == id }
            if (index >= 0) next[index] = saved else next += saved
            preferences[Keys.TextReplacementRules] = ReaderTextReplacementRuleCodec.encode(next)
        }
    }

    suspend fun deleteTextReplacementRule(id: Long) {
        dataStore.edit { preferences ->
            val existing = ReaderTextReplacementRuleCodec.decode(preferences[Keys.TextReplacementRules])
            preferences[Keys.TextReplacementRules] = ReaderTextReplacementRuleCodec.encode(
                existing.filterNot { it.id == id }
            )
        }
    }

    private fun fontLibraryFrom(preferences: Preferences): List<ReaderFontAsset> =
        ReaderFontLibraryCodec.includeLegacy(
            ReaderFontLibraryCodec.decode(preferences[Keys.FontLibrary]),
            preferences[Keys.CustomFontPath],
            preferences[Keys.CustomFontName]
        )

    private fun imageLibraryFrom(preferences: Preferences): List<ReaderImageAsset> =
        ReaderImageLibraryCodec.includeLegacyBackground(
            ReaderImageLibraryCodec.decode(preferences[Keys.ImageLibrary]),
            preferences[Keys.BackgroundImagePath]
        )

    private fun activeBackgroundId(
        preferences: Preferences,
        images: List<ReaderImageAsset>
    ): String? = preferences[Keys.SelectedBackgroundImageId]
        ?.takeIf { id -> images.any { it.id == id } }
        ?: preferences[Keys.BackgroundImagePath]?.let { path ->
            images.firstOrNull { it.filePath == path }?.id
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

    /** 伴读默认只读取用户已读范围；关闭后可检索整本书。 */
    val companionSpoilerProtectionEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.CompanionSpoilerProtection] ?: true }

    suspend fun setCompanionSpoilerProtectionEnabled(value: Boolean) {
        dataStore.edit { it[Keys.CompanionSpoilerProtection] = value }
    }

    /**
     * 伴读记忆的范围开关（Memory 2.0 第 5 节）。**默认全开**：记忆是陪伴感的地基，
     * 且改动前的行为本就是跨书召回，默认关闭属于行为回退。偏好纯问答的用户关掉总开关即可。
     */
    val companionMemorySettings: Flow<CompanionMemorySettings> = dataStore.data.map { preferences ->
        CompanionMemorySettings(
            longTermEnabled = preferences[Keys.CompanionLongTermMemory] ?: true,
            crossBookEnabled = preferences[Keys.CompanionCrossBookMemory] ?: true,
            crossBookChatSearchEnabled = preferences[Keys.CompanionCrossBookChatSearch] ?: true
        )
    }

    suspend fun setCompanionLongTermMemory(value: Boolean) {
        dataStore.edit { it[Keys.CompanionLongTermMemory] = value }
    }

    suspend fun setCompanionCrossBookMemory(value: Boolean) {
        dataStore.edit { it[Keys.CompanionCrossBookMemory] = value }
    }

    suspend fun setCompanionCrossBookChatSearch(value: Boolean) {
        dataStore.edit { it[Keys.CompanionCrossBookChatSearch] = value }
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
        val CustomFontPath = stringPreferencesKey("reader_custom_font_path")
        val CustomFontName = stringPreferencesKey("reader_custom_font_name")
        val FontLibrary = stringPreferencesKey("reader_font_library")
        val SelectedCustomFontId = stringPreferencesKey("reader_selected_custom_font_id")
        val FontWeight = intPreferencesKey("reader_font_weight")
        val LineHeight = floatPreferencesKey("reader_line_height")
        val PageMargin = floatPreferencesKey("reader_page_margin")
        val PageMarginLeft = floatPreferencesKey("reader_page_margin_left")
        val PageMarginRight = floatPreferencesKey("reader_page_margin_right")
        val PageMarginTop = floatPreferencesKey("reader_page_margin_top")
        val PageMarginBottom = floatPreferencesKey("reader_page_margin_bottom")
        val HeaderMarginTop = floatPreferencesKey("reader_header_margin_top")
        val FooterMarginBottom = floatPreferencesKey("reader_footer_margin_bottom")
        val LetterSpacingEm = floatPreferencesKey("reader_letter_spacing_em")
        val ParagraphSpacingEm = floatPreferencesKey("reader_paragraph_spacing_em")
        val FirstLineIndentEm = floatPreferencesKey("reader_first_line_indent_em")
        val TitleScale = floatPreferencesKey("reader_title_scale")
        val TitleTopSpacing = floatPreferencesKey("reader_title_top_spacing")
        val TitleBottomSpacing = floatPreferencesKey("reader_title_bottom_spacing")
        val TextJustification = booleanPreferencesKey("reader_text_justification")
        val ShowHeader = booleanPreferencesKey("reader_show_header")
        val ShowFooter = booleanPreferencesKey("reader_show_footer")
        val Theme = stringPreferencesKey("reader_theme")
        val PageMode = stringPreferencesKey("reader_page_mode")
        val PageTurnAnimation = stringPreferencesKey("reader_page_turn_animation")
        val ShelfLayout = stringPreferencesKey("shelf_layout")
        val KeepScreenOn = booleanPreferencesKey("keep_screen_on")
        val ImmersiveReading = booleanPreferencesKey("reader_immersive_reading")
        val VolumeKeysPageTurn = booleanPreferencesKey("reader_volume_keys_page_turn")
        val BackgroundImagePath = stringPreferencesKey("reader_background_image_path")
        val ImageLibrary = stringPreferencesKey("reader_image_library")
        val SelectedBackgroundImageId = stringPreferencesKey("reader_selected_background_image_id")
        val BackgroundImageOpacity = floatPreferencesKey("reader_background_image_opacity")
        val SyntaxHighlightEnabled = booleanPreferencesKey("reader_syntax_highlight_enabled")
        val SyntaxHighlightRules = stringPreferencesKey("reader_syntax_highlight_rules")
        val TextReplacementRules = stringPreferencesKey("reader_text_replacement_rules")
        val ThemeMode = stringPreferencesKey("app_theme_mode")
        val AccentPreset = stringPreferencesKey("accent_preset")
        val AccentCustomArgb = intPreferencesKey("accent_custom_argb")
        val ActivePersonaId = longPreferencesKey("active_persona_id")
        val SuggestionReplies = booleanPreferencesKey("companion_suggestion_replies")
        val CompanionSpoilerProtection = booleanPreferencesKey("companion_spoiler_protection")
        val ShowAiAnnotations = booleanPreferencesKey("companion_show_ai_annotations")
        val CompanionLongTermMemory = booleanPreferencesKey("companion_long_term_memory")
        val CompanionCrossBookMemory = booleanPreferencesKey("companion_cross_book_memory")
        val CompanionCrossBookChatSearch = booleanPreferencesKey("companion_cross_book_chat_search")
        val LastAnnotationStyle = stringPreferencesKey("reader_annotation_last_style")
        val LastAnnotationColor = stringPreferencesKey("reader_annotation_last_color")
        val CustomThemes = stringPreferencesKey("reader_custom_themes")
        val ActiveCustomThemeId = longPreferencesKey("reader_active_custom_theme_id")
    }
}
