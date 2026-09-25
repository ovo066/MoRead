package com.mozhi.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.mozhi.reader.core.i18n.UiText

/** 在组合中按当前语言解析 [UiText]；读取 LocalConfiguration 以便语言变化时重组。 */
@Composable
@ReadOnlyComposable
fun UiText.asString(): String {
    LocalConfiguration.current
    return resolve(LocalContext.current.resources)
}
