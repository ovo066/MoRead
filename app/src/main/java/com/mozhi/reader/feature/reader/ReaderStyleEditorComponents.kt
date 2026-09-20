package com.mozhi.reader.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.ReaderFontAsset
import com.mozhi.reader.core.datastore.ReaderSyntaxFont
import com.mozhi.reader.ui.components.FontPreviewChoice
import com.mozhi.reader.ui.components.NoteStyleColorPalette

/** Shared structure leaves the primary action and tab navigation visible while forms scroll. */
@Composable
internal fun ReaderStyleEditor(
    title: String, subtitle: String, tabs: List<String>, selectedTab: Int,
    onTabChange: (Int) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit,
    saveEnabled: Boolean, preview: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit
) {
    var previewExpanded by remember { mutableStateOf(true) }
    // Each page keeps its own anchor when switching tabs.
    val scrollStates = tabs.map { rememberScrollState() }
    ReaderFullscreenDialog(onDismiss) {
        val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "取消编辑") }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge)
                        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = onSave, enabled = saveEnabled, contentPadding = PaddingValues(horizontal = 20.dp)) { Text("保存") }
                }
                Surface(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))) {
                    Column {
                        Surface(onClick = { previewExpanded = !previewExpanded }, color = MaterialTheme.colorScheme.surface) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("实时预览", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                Text(if (previewExpanded) "收起" else "展开", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(if (previewExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                            }
                        }
                        AnimatedVisibility(previewExpanded && !keyboardVisible) { preview() }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tabs.forEachIndexed { index, label ->
                        val selected = selectedTab == index
                        Surface(onClick = { onTabChange(index) }, modifier = Modifier.weight(1f).testTag("style-tab-$index"),
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
                            Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                Text(label, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollStates[selectedTab]).padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
            }
        }
    }
}

@Composable
internal fun StyleEditorSection(title: String, caption: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            content()
        }
    }
}

@Composable
internal fun StyleFontPicker(font: ReaderSyntaxFont, assetId: String?, library: List<ReaderFontAsset>, onChange: (ReaderSyntaxFont, String?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ReaderSyntaxFont.entries.filter { it != ReaderSyntaxFont.CUSTOM }) { candidate ->
            FilterChip(font == candidate, { onChange(candidate, null) }, label = { Text(candidate.shortLabel()) })
        }
        items(library, key = { it.id }) { asset ->
            FontPreviewChoice(asset, font == ReaderSyntaxFont.CUSTOM && assetId == asset.id, onClick = { onChange(ReaderSyntaxFont.CUSTOM, asset.id) })
        }
    }
}

@Composable
internal fun StyleCssField(css: String, errors: List<String>, example: String, help: String, onChange: (String) -> Unit) {
    StyleEditorSection("自定义 CSS", "声明会覆盖相同的外观选项；留空则使用界面设置。") {
        OutlinedTextField(css, { onChange(it.take(4000)) }, modifier = Modifier.fillMaxWidth().testTag("style-css"),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            minLines = 7, maxLines = 12, shape = RoundedCornerShape(14.dp),
            placeholder = { Text(example, fontFamily = FontFamily.Monospace) },
            isError = errors.isNotEmpty(),
            supportingText = if (errors.isNotEmpty()) ({ Text(errors.joinToString("\n")) }) else null)
        Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (css.isNotBlank()) TextButton(onClick = { onChange("") }) { Text("清空 CSS") }
            else TextButton(onClick = { onChange(example) }) { Text("填入示例") }
        }
    }
}

/** Editing a visible control takes precedence over the same property in a previous CSS preset. */
internal fun String.withoutStyleProperties(vararg properties: String): String {
    val remaining = replace(Regex("/\\*[\\s\\S]*?\\*/"), "").split(';')
        .filter { entry -> entry.substringBefore(':').trim().lowercase() !in properties }
        .map(String::trim).filter(String::isNotEmpty)
    return remaining.joinToString(";\n", postfix = if (remaining.isEmpty()) "" else ";")
}

internal fun String.withStyleProperty(property: String, value: String): String =
    withoutStyleProperties(property).trim().let { if (it.isBlank() || it == ";") "$property: $value;" else "$it\n$property: $value;" }

@Composable
internal fun StyleColorChoices(value: Int?, fallback: Int, resetLabel: String?, onChange: (Int?) -> Unit,
    expanded: Boolean, onExpand: () -> Unit) {
    val swatches = listOf(0xff353330, 0xff98634d, 0xffab5575, 0xff5c7399, 0xff50796b, 0xffa48642, 0xffe4ddd1).map(Long::toInt)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 3.dp)) {
        items(swatches) { color ->
            Box(Modifier.size(36.dp).border(if (color == value) 2.dp else 1.dp,
                if (color == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                CircleShape).padding(4.dp).background(Color(color), CircleShape)
                .clickable { onChange(color) }.semantics { contentDescription = "颜色 #${color.toUInt().toString(16).takeLast(6)}" },
                contentAlignment = Alignment.Center) {
                if (color == value) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = if (Color(color).luminance() > .5f) Color.Black else Color.White)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (resetLabel != null) TextButton(onClick = { onChange(null) }) { Text(resetLabel) }
        else Text(value?.let { "#${it.toUInt().toString(16).takeLast(6).uppercase()}" } ?: "默认", style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = onExpand) { Text(if (expanded) "收起取色器" else "自定义颜色") }
    }
    AnimatedVisibility(expanded) { NoteStyleColorPalette(Color(value ?: fallback), { onChange(it.toArgb()) }) }
}

/** Insert managed image references without asking the user to find an internal asset ID. */
@Composable
internal fun StyleCssImages(images: List<com.mozhi.reader.core.datastore.ReaderImageAsset>,
    css: String, onChange: (String) -> Unit, onImport: () -> Unit) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    StyleEditorSection("CSS 图片背景", "选择图片会插入 background-image；居中铺满显示。透明 PNG 可叠在底色上。") {
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(images.size) { index ->
                val asset = images[index]
                SuggestionChip(onClick = { focus.clearFocus(); onChange(css.withStyleBackgroundImage(asset.id)) },
                    modifier = Modifier.testTag("style-css-image-${asset.id}"), label = { Text(asset.displayName) })
            }
        }
        TextButton(onClick = onImport) { Text("＋ 导入背景图片") }
    }
}

internal const val STYLE_GRADIENT_HELP = "渐变背景：background: linear-gradient(90deg, #fbe4df, #dce8f5);\n渐变文字：background: linear-gradient(90deg, #c65f76, #567bce); background-clip: text; color: transparent;\n也可用 color: linear-gradient(...) 单独设置文字渐变，与图片背景搭配。支持 deg 角度、to right 等方向、2～16 个颜色及 0%～100% 色标；颜色支持十六进制和 rgb / rgba。每处匹配在当前页共用渐变，换行不逐字重置。"

internal fun String.withStyleBackgroundImage(id: String?): String {
    val parsed = com.mozhi.reader.core.datastore.ReaderStyleCss.parse(this)
    var value = withoutStyleProperties("background-clip", "-webkit-background-clip")
    // Replacing a text-clipped background must also remove its transparent text fallback.
    if (parsed.clipText && parsed.color == 0) value = value.withoutStyleProperties("color", "-webkit-text-fill-color")
    return value.withStyleProperty("background-image", id?.let { "url(\"asset:$it\")" } ?: "none")
}
