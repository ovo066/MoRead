package com.mozhi.reader.feature.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.feature.reader.engine.ChapterTypesetter
import com.mozhi.reader.feature.reader.engine.RenderPage
import com.mozhi.reader.feature.reader.render.PageBitmapRenderer
import com.mozhi.reader.feature.reader.render.ReaderPageStyle

@Composable
internal fun SyntaxHighlightEditor(settings: ReaderSettings, palette: ReaderPalette,
    onEnabledChange: (Boolean) -> Unit, onEdit: (ReaderSyntaxRule) -> Unit, onAdd: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(18.dp), color = palette.accent.copy(alpha = .08f)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("让文字有层次", style = MaterialTheme.typography.titleSmall)
                    Text("为对话、书名或自定义片段设置不同样式", style = MaterialTheme.typography.bodySmall, color = palette.muted)
                }
                Switch(settings.syntaxHighlightEnabled, onEnabledChange, colors = SwitchDefaults.colors(checkedTrackColor = palette.accent))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${settings.syntaxHighlightRules.size} 条规则", style = MaterialTheme.typography.labelMedium, color = palette.muted, modifier = Modifier.weight(1f))
            TextButton(onClick = onAdd) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Text("添加规则") }
        }
        settings.syntaxHighlightRules.forEach { rule ->
            val declarations = remember(rule.css) { ReaderStyleCss.parse(rule.css) }
            Surface(onClick = { onEdit(rule) }, color = palette.glass.copy(alpha = .65f), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(40.dp).background(Color(declarations.color ?: rule.colorArgb).copy(alpha = .12f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Text(if (rule.matchMode == ReaderSyntaxMatchMode.REGEX) ".*" else "Aa", color = Color(declarations.color ?: rule.colorArgb), style = MaterialTheme.typography.titleMedium)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(rule.name, style = MaterialTheme.typography.titleSmall)
                        Text(if (rule.matchMode == ReaderSyntaxMatchMode.REGEX) rule.pattern else "${rule.startDelimiter} 内容 ${rule.endDelimiter}",
                            style = MaterialTheme.typography.bodySmall, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(if (rule.enabled) "已启用" else "已停用", style = MaterialTheme.typography.labelSmall, color = if (rule.enabled) palette.accent else palette.muted)
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = palette.muted, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (settings.syntaxHighlightRules.isEmpty()) Text("添加一条规则，从对话或书名开始。", style = MaterialTheme.typography.bodyMedium, color = palette.muted)
    }
}

@Composable
internal fun SyntaxRuleEditorDialog(initial: ReaderSyntaxRule, fontLibrary: List<ReaderFontAsset>,
    onDismiss: () -> Unit, onSave: (ReaderSyntaxRule) -> Unit, onDelete: (() -> Unit)?,
    readerSettings: ReaderSettings = ReaderSettings(), previewPalette: ReaderPalette? = null, onImportImage: () -> Unit = {}) {
    var draft by remember { mutableStateOf(initial) }
    var tab by remember { mutableStateOf(0) }
    var customColor by remember { mutableStateOf(false) }
    var backgroundPicker by remember { mutableStateOf(false) }
    var sample by remember { mutableStateOf(
        if (initial.matchMode == ReaderSyntaxMatchMode.DELIMITED && initial.startDelimiter.isNotEmpty() && initial.endDelimiter.isNotEmpty())
            "他轻声说：${initial.startDelimiter}今晚的月色真美。${initial.endDelimiter}\n我合上书，继续向前。"
        else "他轻声说：“今晚的月色真美。”\n我合上《春江月夜》，继续向前。") }
    val css = remember(draft.css) { ReaderStyleCss.parse(draft.css) }
    val regexError = remember(draft.pattern) { if (draft.pattern.isBlank()) "请输入正则表达式" else runCatching { Regex(draft.pattern) }.exceptionOrNull()?.let { "表达式格式有误，请检查括号和转义符。" } }
    val valid = when (draft.matchMode) {
        ReaderSyntaxMatchMode.DELIMITED -> draft.startDelimiter.isNotEmpty() && draft.endDelimiter.isNotEmpty()
        ReaderSyntaxMatchMode.REGEX -> regexError == null && draft.pattern.length <= 256
    }
    val palette = previewPalette ?: readerPalette(readerSettings.theme, false, MaterialTheme.colorScheme.primary)
    ReaderStyleEditor(if (initial.id == 0L) "添加高亮规则" else "编辑高亮规则", "匹配文字 · 定义专属样式",
        listOf("匹配", "样式", "CSS"), tab, { tab = it }, onDismiss,
        { onSave(draft.copy(name = draft.name.trim().ifBlank { "高亮规则" }, pattern = draft.pattern.trim())) }, valid && css.errors.isEmpty(),
        preview = { SyntaxStylePreview(sample, draft, readerSettings.copy(fontLibrary = fontLibrary), palette) }) {
        when (tab) {
            0 -> {
                StyleEditorSection("规则信息") {
                    OutlinedTextField(draft.name, { draft = draft.copy(name = it.take(20)) }, label = { Text("规则名称") },
                        singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                    SyntaxRuleSwitch("启用这条规则", draft.enabled) { draft = draft.copy(enabled = it) }
                }
                StyleEditorSection("匹配方式") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderSyntaxMatchMode.entries.forEach { mode ->
                            FilterChip(draft.matchMode == mode, { draft = draft.copy(matchMode = mode) }, label = { Text(if (mode == ReaderSyntaxMatchMode.DELIMITED) "成对符号" else "正则表达式") })
                        }
                    }
                    if (draft.matchMode == ReaderSyntaxMatchMode.DELIMITED) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(draft.startDelimiter, { draft = draft.copy(startDelimiter = it.take(8)) }, label = { Text("开始符号") }, singleLine = true,
                                shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f))
                            OutlinedTextField(draft.endDelimiter, { draft = draft.copy(endDelimiter = it.take(8)) }, label = { Text("结束符号") }, singleLine = true,
                                shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f))
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(listOf("“" to "”", "「" to "」", "《" to "》", "【" to "】")) { (start, end) ->
                                SuggestionChip(onClick = { draft = draft.copy(startDelimiter = start, endDelimiter = end); sample = "他翻开书页：${start}春江潮水连海平${end}，仿佛看见远方的月色。" }, label = { Text("$start $end") })
                            }
                        }
                        SyntaxRuleSwitch("符号本身也着色", draft.includeDelimiters) { draft = draft.copy(includeDelimiters = it) }
                    } else {
                        OutlinedTextField(draft.pattern, { draft = draft.copy(pattern = it.take(256)) }, label = { Text("正则表达式") },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            isError = draft.pattern.isNotEmpty() && regexError != null,
                            supportingText = { Text(if (draft.pattern.isNotEmpty() && regexError != null) regexError else "多行匹配，对完整命中的文本应用样式") },
                            minLines = 2, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                        SyntaxRuleSwitch("忽略大小写", draft.ignoreCase) { draft = draft.copy(ignoreCase = it) }
                    }
                }
                StyleEditorSection("试一段文字", "在这里测试匹配范围，上方预览会立即更新。") {
                    OutlinedTextField(sample, { sample = it.take(500) }, minLines = 3, maxLines = 5, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("syntax-sample"))
                }
                onDelete?.let { TextButton(onClick = it, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除这条规则") } }
            }
            1 -> {
                StyleEditorSection("文字颜色") {
                    StyleColorChoices(css.color ?: draft.colorArgb, palette.accent.toArgb(), null, { value -> value?.let {
                        draft = draft.copy(colorArgb = it, css = draft.css.withoutStyleProperties("color"))
                    } }, customColor, { customColor = !customColor })
                }
                StyleEditorSection("字体与字形") {
                    StyleFontPicker(css.font ?: draft.font, css.fontAssetId ?: draft.fontAssetId, fontLibrary) { font, id ->
                        draft = draft.copy(font = font, fontAssetId = id, css = draft.css.withoutStyleProperties("font-family"))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(css.bold ?: draft.bold, { draft = draft.copy(bold = !(css.bold ?: draft.bold), css = draft.css.withoutStyleProperties("font-weight")) }, label = { Text("粗体") })
                        FilterChip(css.italic ?: draft.italic, { draft = draft.copy(italic = !(css.italic ?: draft.italic), css = draft.css.withoutStyleProperties("font-style")) }, label = { Text("斜体") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        fun decoration(underline: Boolean, strike: Boolean) { draft = draft.copy(underline = underline, strikethrough = strike, css = draft.css.withoutStyleProperties("text-decoration")) }
                        FilterChip(css.underline ?: draft.underline, { decoration(!(css.underline ?: draft.underline), css.strike ?: draft.strikethrough) }, label = { Text("下划线") })
                        FilterChip(css.strike ?: draft.strikethrough, { decoration(css.underline ?: draft.underline, !(css.strike ?: draft.strikethrough)) }, label = { Text("删除线") })
                    }
                }
                StyleEditorSection("文字底色", "用浅色底纹强调重点片段。") {
                    val background = css.background ?: draft.backgroundArgb
                    SyntaxRuleSwitch("显示底色", background != null) { draft = draft.copy(backgroundArgb = if (it) 0x33D06B42 else null, css = draft.css.withoutStyleProperties("background-color")) }
                    if (background != null) StyleColorChoices(background or 0xff000000.toInt(), palette.accent.toArgb(), null, { value ->
                        draft = draft.copy(backgroundArgb = value?.let { Color(it).copy(alpha = .24f).toArgb() }, css = draft.css.withoutStyleProperties("background-color"))
                    }, backgroundPicker, { backgroundPicker = !backgroundPicker })
                }
            }
            else -> {
                StyleCssField(draft.css, css.errors, "color: #ab5575;\nfont-style: italic;\ntext-decoration: underline;",
                "无需选择器或大括号。支持 color、background-color、font-family、font-weight、font-style 和 text-decoration。字体可填写 serif、sans-serif、monospace 或 asset:字体ID。\n$STYLE_GRADIENT_HELP") { draft = draft.copy(css = it) }
                StyleCssImages(readerSettings.imageLibrary, draft.css, { draft = draft.copy(css = it) }, onImportImage)
            }
        }
    }
}

@Composable
private fun SyntaxStylePreview(sample: String, rule: ReaderSyntaxRule, settings: ReaderSettings, palette: ReaderPalette) {
    val matches = remember(sample, rule) { ReaderSyntaxHighlighter.spans(sample, listOf(rule)).count { !it.delimiterGlyphsOnly } }
    val bitmap = remember(sample, rule, settings, palette) {
        val style = ReaderPageStyle.resolve(settings.copy(syntaxHighlightEnabled = true, syntaxHighlightRules = listOf(rule),
            showHeader = false, showFooter = false, pageMarginTop = .3f, pageMarginBottom = .3f, fontScale = 1.05f), palette, Density(1f), 360, 145, 0f, 0f)
        val chapter = ChapterTypesetter(style.spec, style.measure).typeset(0, "", sample.ifBlank { "输入一段文字，查看高亮效果。" })
        val renderer = PageBitmapRenderer(style)
        renderer.render(RenderPage.Laid(0, chapter.title, 0, chapter.pages.size, chapter.pages.first()), null, 0f, "", 100).also { renderer.release() }
    }
    Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
        Image(bitmap.asImageBitmap(), "语法高亮效果预览", Modifier.fillMaxWidth().height(116.dp))
        Text(if (!rule.enabled) "规则已停用" else if (matches == 0) "未匹配 · 可修改下方示例文字" else "已匹配 $matches 处",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
    }
}

@Composable
internal fun SyntaxRuleSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

internal fun ReaderSyntaxFont.shortLabel(): String = when (this) {
    ReaderSyntaxFont.INHERIT -> "跟随正文"
    ReaderSyntaxFont.SYSTEM -> "系统"
    ReaderSyntaxFont.SERIF -> "宋体"
    ReaderSyntaxFont.SANS_SERIF -> "黑体"
    ReaderSyntaxFont.MONOSPACE -> "等宽"
    ReaderSyntaxFont.CUSTOM -> "导入字体"
}
