package com.mozhi.reader.core.datastore


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

enum class WidePageLayout { SINGLE, DUAL }

enum class PageMode {
    PAGINATED,
    SCROLL
}

enum class PageTurnAnimation {
    SIMULATION,
    MODERN_SIMULATION,
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

/** EPUB 出版商样式与用户排版的合成策略。 */
enum class PublisherStyleMode {
    /** 尽量按原书 CSS 渲染，仅保留阅读器页面安全边距。 */
    RESPECT,
    /** 用户字号/行距/段距作为基准，原书比例与装饰继续保留。 */
    SMART,
    /** 去掉出版商可替换的字号、行距、间距与色板，统一使用用户排版。 */
    TAKE_OVER
}

enum class ShelfLayout {
    GRID,
    LIST
}

/**
 * 阅读页亮度的「跟随系统」哨兵值。
 *
 * 取 -1f 是为了和 `WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE` 对齐——
 * 写回窗口属性时可以原样赋值，不必在两套语义之间来回换算。
 */
const val FOLLOW_SYSTEM_BRIGHTNESS = -1f

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
    /** EPUB 出版商 CSS 的处理方式，默认智能融合。 */
    val publisherStyleMode: PublisherStyleMode = PublisherStyleMode.SMART,
    /** 旧版统一边距；保留用于迁移，运行时请使用四边独立值。 */
    val pageMargin: Float = 1f,
    /** 阅读正文左侧边距，范围见 [ReaderMarginLimits]。 */
    val pageMarginLeft: Float = 1f,
    /** 阅读正文右侧边距，范围见 [ReaderMarginLimits]。 */
    val pageMarginRight: Float = 1f,
    /** 阅读正文上侧边距，范围见 [ReaderMarginLimits]。 */
    val pageMarginTop: Float = 0f,
    /** 阅读正文下侧边距，范围见 [ReaderMarginLimits]。 */
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
    val titleStyle: ReaderTitleStyle = ReaderTitleStyle(),
    val titleStylePresets: List<ReaderTitleStylePreset> = ReaderTitleStylePresetCodec.defaults,
    val textJustification: Boolean = true,
    val showHeader: Boolean = true,
    val showFooter: Boolean = true,
    /** 全局页眉页脚分项布局，不随阅读主题切换。 */
    val chromeLayout: ReaderChromeLayout = ReaderChromeLayout.DEFAULT,
    val theme: ReaderTheme = ReaderTheme.SYSTEM,
    val pageMode: PageMode = PageMode.PAGINATED,
    val autoRead: AutoReadSettings = AutoReadSettings(),
    val widePageLayout: WidePageLayout = WidePageLayout.SINGLE,
    val companionSidePaneEnabled: Boolean = false,
    val pageTurnAnimation: PageTurnAnimation = PageTurnAnimation.SIMULATION,
    val modernBackTextOpacity: Float = .18f,
    val modernCurlRadiusScale: Float = 1f,
    val shelfLayout: ShelfLayout = ShelfLayout.GRID,
    val shelfGridColumns: Int = 0,
    val shelfBookOrder: List<Long> = emptyList(),
    val shelfBookOrderReadAnchor: Long = 0L,
    val readingOrderAffectsShelf: Boolean = true,
    val keepScreenOn: Boolean = false,
    /** 阅读页默认隐藏系统状态栏；离开阅读页时恢复。 */
    val immersiveReading: Boolean = true,
    /** 按键翻页总开关；保留原偏好键以兼容已有音量键设置。 */
    val volumeKeysPageTurn: Boolean = false,
    val physicalKeyBindings: List<ReaderKeyBinding> = ReaderKeyBindings.DEFAULT,
    val tapZones: ReaderTapZones? = null,
    val englishLearningEnabled: Boolean = false,
    val englishBionicEnabled: Boolean = false,
    val bilingualBooks: Set<Long> = emptySet(),
    /** 把已生成的插图排进正文（像出版书的插图）的书；不在这里的书仍是点开查看。 */
    val embeddedArtBooks: Set<Long> = emptySet(),
    /** 选区工具栏里额外放到第一排的操作（[SelectionToolbarItems.OPTIONAL] 中的 id）；其余收进「更多」。 */
    val selectionToolbarExtras: Set<String> = emptySet(),
    /** 按书设置的学习语言（[com.mozhi.reader.core.dictionary.LearningLanguage.code]）；没有记录 = 自动识别。 */
    val learningLanguages: Map<Long, String> = emptyMap(),
    /** 段落对照的译文语言（[com.mozhi.reader.core.dictionary.TranslationTarget.code]）。 */
    val translationTarget: String = com.mozhi.reader.core.dictionary.TranslationTarget.ZH_HANS.code,
    val wordAnnotationMode: com.mozhi.reader.core.dictionary.WordAnnotationMode = com.mozhi.reader.core.dictionary.WordAnnotationMode.INLINE,
    val vocabulary: List<com.mozhi.reader.core.dictionary.VocabularyWord> = emptyList(),
    /**
     * 阅读页窗口亮度，0..1；[FOLLOW_SYSTEM_BRIGHTNESS] 表示跟随系统。
     * 只作用于阅读页这一个窗口，不改系统全局亮度，退出阅读页即还原。
     */
    val screenBrightness: Float = FOLLOW_SYSTEM_BRIGHTNESS,
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
    val builtinThemeTypography: Map<String, CustomReaderTheme> = emptyMap(),
    /** 非空表示自定义主题生效，覆盖 [theme]；选内置主题时清空。 */
    val activeCustomThemeId: Long? = null,
    /**
     * 日夜自动切换：开启后深色模式下改用夜间槽的配色与背景图。默认关闭——
     * 老配置只有一套纸色，默默换成两套等于替用户改了外观。
     */
    val dayNightThemeAuto: Boolean = false,
    /** 夜间槽的内置主题。 */
    val nightTheme: ReaderTheme = ReaderTheme.DARK,
    /** 夜间槽的自定义主题；语义同 [activeCustomThemeId]。 */
    val nightActiveCustomThemeId: Long? = null,
    /** 夜间槽的背景图。 */
    val nightSelectedBackgroundImageId: String? = null,
    /** 夜间槽的背景图不透明度。 */
    val nightBackgroundImageOpacity: Float = 0.28f,
    /** 用户主动启用的逐书主题；键为 books.id。 */
    val bookThemes: Map<Long, BookReaderTheme> = emptyMap(),
    val bookChineseConversions: Map<Long, ChineseConversionMode> = emptyMap(),
    /** Empty follows the book/default reading font; built-in name or font:<asset id> overrides review quotes only. */
    val reviewFont: String = "",
    val reviewPreferences: ReadingReviewPreferences = ReadingReviewPreferences(),
    val reviewShareTemplates: List<ReviewShareTemplate> = emptyList()
)

/** 当前生效的自定义主题；id 悬空（预设已删）按未启用处理。 */
fun ReaderSettings.activeCustomTheme(): CustomReaderTheme? =
    activeCustomThemeId?.let { id -> customThemes.firstOrNull { it.id == id } }
