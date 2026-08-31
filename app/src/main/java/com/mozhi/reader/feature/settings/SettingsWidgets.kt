package com.mozhi.reader.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.mozhi.reader.ui.components.MoReadBlock
import com.mozhi.reader.ui.components.MoReadRow
import com.mozhi.reader.ui.components.MoReadRowDivider
import com.mozhi.reader.ui.components.MoReadSection
import com.mozhi.reader.ui.components.MoReadSwitchRow

/**
 * 设置页组件的兼容层。
 *
 * 实现已经上移到 `ui/components/MoReadSection.kt`——feature 包之间不许互相 import，
 * 而角色编辑页、TTS 页、Provider 页同样需要这套版式。这里只留一层薄转调，
 * 于是所有还在用旧名字的二级页一行不改就继承了新的素面卡视觉。
 *
 * 新写的页面请直接用 `MoReadSection` / `MoReadRow` 那一套，不要再走这些别名。
 */
@Composable
fun SettingsGroup(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) = MoReadSection(modifier = modifier, title = title, icon = icon, content = content)

@Composable
fun SettingsRowDivider() = MoReadRowDivider()

@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) = MoReadRow(
    title = title,
    modifier = modifier,
    icon = icon,
    subtitle = subtitle,
    onClick = onClick,
    trailing = trailing
)

@Composable
fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) = MoReadSwitchRow(
    title = title,
    checked = checked,
    onCheckedChange = onCheckedChange,
    modifier = modifier,
    icon = icon,
    subtitle = subtitle,
    enabled = enabled
)

@Composable
fun SettingsBlock(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) = MoReadBlock(modifier = modifier, title = title, subtitle = subtitle, content = content)
