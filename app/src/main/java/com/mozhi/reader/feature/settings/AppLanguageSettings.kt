package com.mozhi.reader.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mozhi.reader.R
import com.mozhi.reader.core.i18n.AppLanguage
import com.mozhi.reader.core.i18n.AppLocales
import com.mozhi.reader.ui.components.MoReadBlock
import com.mozhi.reader.ui.components.MoReadSegmented

/**
 * 界面语言选择。选择后由系统（13+）或 [AppLocales]（12 及以下）重建 Activity，
 * 这里不持有状态：每次配置变化重新读取当前语言。
 */
@Composable
internal fun AppLanguageBlock() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val selected = remember(configuration) { AppLocales.selected(context) }
    val labels = mapOf(
        AppLanguage.SYSTEM to stringResource(R.string.settings_language_system),
        AppLanguage.SIMPLIFIED_CHINESE to stringResource(R.string.language_name_simplified_chinese),
        AppLanguage.ENGLISH to stringResource(R.string.language_name_english)
    )
    MoReadBlock(
        title = stringResource(R.string.settings_language),
        subtitle = stringResource(R.string.settings_language_hint)
    ) {
        MoReadSegmented(
            options = AppLanguage.entries,
            selected = selected,
            onSelect = { language -> context.findActivity()?.let { AppLocales.select(it, language) } },
            label = { labels.getValue(it) }
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
