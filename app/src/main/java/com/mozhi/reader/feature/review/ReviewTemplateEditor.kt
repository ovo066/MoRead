package com.mozhi.reader.feature.review

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.feature.reader.*
import com.mozhi.reader.ui.theme.isDarkTheme
import com.mozhi.reader.ui.theme.ReadingReviewTheme
import com.mozhi.reader.ui.theme.LocalReadingReviewStyle
import com.mozhi.reader.ui.components.NoteStyleColorPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ReviewTemplateThumbnail(entry: ReviewEntry, style: ReviewCardStyle, options: ReviewExportOptions,
    label: String, selected: Boolean, onClick: () -> Unit) {
    val thumb by produceState<Bitmap?>(null, entry, style, options) {
        value = withContext(Dispatchers.Default) { runCatching { renderReviewCard(entry, style, options, width = 180) }.getOrNull() }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Box(Modifier.width(72.dp).height(104.dp).clip(MaterialTheme.shapes.medium)
            .border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick).padding(4.dp), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it.asImageBitmap(), "${label}模板", Modifier.fillMaxSize().clip(MaterialTheme.shapes.small), contentScale = ContentScale.Crop) }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ReviewTemplateEditor(initial: ReviewShareTemplate, entry: ReviewEntry, base: ReviewExportOptions,
    onDismiss: () -> Unit, onSave: suspend (ReviewShareTemplate) -> Unit) {
    ReadingReviewTheme {
    val settings = LocalReviewReaderSettings.current
    val dark = isDarkTheme()
    val scope = rememberCoroutineScope()
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var tab by remember { mutableIntStateOf(0) }
    var colorTarget by remember { mutableIntStateOf(0) }
    var colorExpanded by remember { mutableStateOf(false) }
    var rule by remember { mutableStateOf<ReaderSyntaxRule?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val css = remember(draft.css) { ReviewTemplateCss.parse(draft.css) }
    val options = reviewTemplateOptions(base, draft, settings, entry.book.id, dark)
    val preview by produceState<Result<Bitmap>?>(null, entry, options) {
        value = withContext(Dispatchers.Default) { runCatching { renderReviewCard(entry, ReviewCardStyle.PAPER, options, width = 540) } }
    }
    ReviewTemplateEditorFrame(listOf("外观", "CSS", "语法规则"), tab, { tab = it },
        onDismiss = { if (!saving) { if (draft != initial) discard = true else onDismiss() } },
        onSave = {
            saving = true; error = null
            scope.launch {
                try { onSave(draft.copy(name = draft.name.trim())); onDismiss() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "保存失败，请重试" }
                finally { saving = false }
            }
        }, saveEnabled = !saving && draft.name.isNotBlank() && css.declarations.errors.isEmpty() && preview?.isSuccess == true,
        preview = {
            Box(Modifier.fillMaxWidth().height(320.dp).padding(12.dp), contentAlignment = Alignment.Center) {
                preview?.getOrNull()?.let { Image(it.asImageBitmap(), "自定义模板预览", Modifier.fillMaxHeight()
                    .aspectRatio(it.width.toFloat() / it.height).clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Fit) }
                preview?.exceptionOrNull()?.let { Text(it.message ?: "预览失败", color = MaterialTheme.colorScheme.error) }
            }
        }) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        when (tab) {
            0 -> {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        ReviewWritingField(draft.name, { draft = draft.copy(name = it.take(30)) }, "给模板起个名字",
                            Modifier.weight(1f).testTag("review-template-name"), minLines = 1)
                        Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp), tint = LocalReadingReviewStyle.current.caption)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("纸色" to draft.backgroundArgb, "文字" to draft.textArgb, "点缀" to draft.accentArgb).forEachIndexed { index, (label, color) ->
                        Surface(onClick = { colorTarget = index }, shape = MaterialTheme.shapes.medium,
                            color = if (colorTarget == index) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            modifier = Modifier.weight(1f)) {
                            Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(30.dp).background(Color(color), CircleShape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                                Text(label, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                val color = when (colorTarget) { 0 -> draft.backgroundArgb; 1 -> draft.textArgb; else -> draft.accentArgb }
                ReviewTemplateColorPicker(color, { chosen -> chosen.let {
                    draft = when (colorTarget) {
                        0 -> draft.copy(backgroundArgb = it, css = draft.css.withoutStyleProperties("background", "background-color"))
                        1 -> draft.copy(textArgb = it, css = draft.css.withoutStyleProperties("color", "-webkit-text-fill-color"))
                        else -> draft.copy(accentArgb = it)
                    }
                } }, colorExpanded, { colorExpanded = !colorExpanded })
                Text("摘录字体", style = MaterialTheme.typography.titleSmall)
                ReviewTemplateFontPicker(draft.fontChoice, settings) { draft = draft.copy(fontChoice = it, css = draft.css.withoutStyleProperties("font-family")) }
            }
            1 -> {
                StyleCssField(draft.css, css.declarations.errors,
                    "background: #f2eee7;\ncolor: #49564f;\nfont-family: serif;\nfont-size: 1.1em;\nline-height: 1.7;\nletter-spacing: 0.03em;\npadding: 2em;",
                    "使用 CSS 声明，不需要选择器。支持配色、渐变、字体、对齐、边框、圆角、padding、margin-inline/top/bottom、行高和字距。em 以默认摘录字号为基准；语法规则可进一步美化匹配的文字。",
                    { draft = draft.copy(css = it) })
                if (settings.imageLibrary.isNotEmpty()) {
                    Text("图片背景", style = MaterialTheme.typography.titleSmall)
                    settings.imageLibrary.forEach { asset ->
                        TextButton(onClick = { draft = draft.copy(css = draft.css.withStyleProperty("background-image", "url(\"asset:${asset.id}\")")) }) { Text(asset.displayName) }
                    }
                }
            }
            2 -> SyntaxHighlightEditor(settings.copy(syntaxHighlightEnabled = draft.syntaxEnabled, syntaxHighlightRules = draft.syntaxRules), companionChatPalette(),
                { draft = draft.copy(syntaxEnabled = it) }, { rule = it },
                { rule = ReaderSyntaxRule(0, "", "“", "”", draft.accentArgb) })
        }
    }
    rule?.let { current ->
        SyntaxRuleEditorDialog(current, settings.fontLibrary, onDismiss = { rule = null }, onSave = { edited ->
            val saved = if (edited.id == 0L) edited.copy(id = (draft.syntaxRules.maxOfOrNull { it.id } ?: 0L) + 1) else edited
            draft = draft.copy(syntaxRules = if (draft.syntaxRules.any { it.id == saved.id }) draft.syntaxRules.map { if (it.id == saved.id) saved else it } else draft.syntaxRules + saved)
            rule = null
        }, onDelete = if (current.id == 0L) null else ({ draft = draft.copy(syntaxRules = draft.syntaxRules.filterNot { it.id == current.id }); rule = null }),
            readerSettings = settings, previewPalette = companionChatPalette())
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃这次修改？") },
        text = { Text("已保存的模板会保留。") }, confirmButton = { TextButton(onClick = onDismiss) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
    }
}

@Composable
private fun ReviewTemplateEditorFrame(tabs: List<String>, selectedTab: Int, onTabChange: (Int) -> Unit,
    onDismiss: () -> Unit, onSave: () -> Unit, saveEnabled: Boolean, preview: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    val scrolls = tabs.map { rememberScrollState() }
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    ReviewPageDialog("分享模板", onDismiss, tonalBackground = true, actions = {
        Button(onClick = onSave, enabled = saveEnabled, shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = LocalReadingReviewStyle.current.accent,
                contentColor = if (isDarkTheme()) MaterialTheme.colorScheme.background else Color.White),
            contentPadding = PaddingValues(horizontal = 20.dp), modifier = Modifier.height(42.dp)) { Text("保存", fontWeight = FontWeight.SemiBold) }
    }) {
        if (!keyboard) preview()
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = .6f), modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            Row(Modifier.padding(4.dp)) {
                tabs.forEachIndexed { index, name ->
                    Surface(onClick = { onTabChange(index) }, shape = CircleShape, modifier = Modifier.weight(1f).testTag("style-tab-$index"),
                        color = if (selectedTab == index) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent) {
                        Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrolls[selectedTab]).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
private fun ReviewTemplateColorPicker(value: Int, onChange: (Int) -> Unit, expanded: Boolean, onExpand: () -> Unit) {
    val colors = listOf(Color(0xFFF7F5EF), Color(0xFF495963), LocalReadingReviewStyle.current.accent,
        Color(0xFFBBCDBE), Color(0xFFC7ADB2), Color(0xFFD5C6A4), Color(0xFF252F37))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        colors.forEach { color ->
            val selected = color.toArgb() == value
            Box(Modifier.size(38.dp).clip(CircleShape).clickable { onChange(color.toArgb()) }
                .border(if (selected) 1.5.dp else 0.dp, if (selected) LocalReadingReviewStyle.current.toolbarInk else Color.Transparent, CircleShape)
                .padding(4.dp).background(color, CircleShape))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("#%06X".format(value and 0xFFFFFF), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick = onExpand, shape = CircleShape, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            Icon(Icons.Outlined.Palette, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text(if (expanded) "收起色板" else "自定义颜色")
        }
    }
    if (expanded) Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Box(Modifier.padding(16.dp)) { NoteStyleColorPalette(Color(value), { onChange(it.toArgb()) }) }
    }
}

@Composable
private fun ReviewTemplateFontPicker(choice: String, settings: ReaderSettings, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val choices = reviewFontChoices(settings).map { if (it.first.isEmpty()) "" to "跟随划线字体" else it }
    Box {
        Surface(onClick = { expanded = true }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Aa", style = MaterialTheme.typography.titleLarge, color = LocalReadingReviewStyle.current.accentText)
                Text(choices.firstOrNull { it.first == choice }?.second ?: "跟随划线字体", modifier = Modifier.weight(1f).padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
                Icon(Icons.Outlined.ExpandMore, "选择模板字体", tint = LocalReadingReviewStyle.current.toolbarInk)
            }
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            choices.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onChange(id); expanded = false }) }
        }
    }
}
