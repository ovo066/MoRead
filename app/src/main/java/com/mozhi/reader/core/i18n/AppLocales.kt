package com.mozhi.reader.core.i18n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * 应用内可选的界面语言。[tag] 为 null 表示跟随系统。
 *
 * 新增语言时同步三处：这里的枚举、`res/xml/locales_config.xml`、对应的 `values-xx/strings.xml`。
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    SIMPLIFIED_CHINESE("zh-CN"),
    ENGLISH("en");

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            if (tag.isNullOrBlank()) SYSTEM else fromLocale(Locale.forLanguageTag(tag))

        fun fromLocale(locale: Locale?): AppLanguage = when (locale?.language) {
            "zh" -> SIMPLIFIED_CHINESE
            "en" -> ENGLISH
            else -> SYSTEM
        }
    }
}

/**
 * 应用级语言切换，不依赖 AppCompat。
 *
 * - Android 13+：交给系统的 per-app language（[LocaleManager]），系统负责持久化、重建 Activity，
 *   也能在系统设置「应用语言」里改；`locales_config.xml` 声明可选项。
 * - Android 8–12：选择存在私有 SharedPreferences（启动时同步读，不能等 DataStore），
 *   Activity 在 attachBaseContext 用 [wrap] 包一层配置，Application 资源用 [applyToApplication] 覆盖，
 *   让 ViewModel / Worker / 通知里经 Application Context 取的字符串也跟着变。
 */
object AppLocales {
    private const val PREFS = "app_locale"
    private const val KEY_TAG = "language_tag"

    fun selected(context: Context): AppLanguage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) AppLanguage.SYSTEM else AppLanguage.fromLocale(locales[0])
        } else {
            AppLanguage.fromTag(legacyTag(context))
        }

    /** 切换语言；新语言经 Activity 重建生效，调用方不需要自己刷新界面。 */
    fun select(activity: Activity, language: AppLanguage) {
        if (selected(activity) == language) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
            return
        }
        // commit 而非 apply：紧接着的 recreate 会在 attachBaseContext 里同步读取。
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TAG, language.tag).commit()
        applyToApplication(activity.applicationContext)
        activity.recreate()
    }

    /** Android 12 及以下给 Activity 的 base context 套上所选语言；13+ 原样返回。 */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = legacyTag(base) ?: return base
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(configuration)
    }

    /**
     * Android 12 及以下覆盖 Application 资源与默认 Locale。启动时和系统配置变化后
     * （系统会把 Application 资源重置回系统语言）都要调用一次。
     */
    @Suppress("DEPRECATION")
    fun applyToApplication(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val locales = legacyTag(context)?.let(LocaleList::forLanguageTags)
            ?: Resources.getSystem().configuration.locales
        LocaleList.setDefault(locales)
        val resources = context.applicationContext.resources
        if (resources.configuration.locales == locales) return
        val configuration = Configuration(resources.configuration)
        configuration.setLocales(locales)
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    private fun legacyTag(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null)
            ?.takeIf { it.isNotBlank() }
}
