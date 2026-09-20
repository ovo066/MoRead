package com.mozhi.reader.feature.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.feature.reader.engine.ChapterTypesetter
import com.mozhi.reader.feature.reader.engine.RenderPage
import com.mozhi.reader.feature.reader.render.PageBitmapRenderer
import com.mozhi.reader.feature.reader.render.ReaderPageStyle
import com.mozhi.reader.ui.components.NoteStyleColorPalette
import java.util.Locale

@Composable
internal fun ReaderTitleStyleEditor(settings: ReaderSettings, palette: ReaderPalette,
    onDismiss: () -> Unit, actions: ReaderLayoutActions,
    initialName: String? = null, onSaveNamed: ((String, ReaderTitleStyle) -> Unit)? = null) {
    var draft by remember { mutableStateOf(settings.titleStyle) }
    var name by remember { mutableStateOf(initialName.orEmpty()) }
    var tab by remember { mutableStateOf(0) }
    var colorTarget by remember { mutableStateOf(0) }
    var customColor by remember { mutableStateOf(false) }
    val css = remember(draft.css) { ReaderStyleCss.parse(draft.css, title = true) }
    val resolved = draft.resolved()
    val controls = palette.copy(onBackground = MaterialTheme.colorScheme.onSurface,
        muted = MaterialTheme.colorScheme.onSurfaceVariant, glass = MaterialTheme.colorScheme.surfaceContainer,
        glassBorder = MaterialTheme.colorScheme.outlineVariant, accent = MaterialTheme.colorScheme.primary)
    ReaderStyleEditor("标题样式", "TXT 章首 · EPUB 保留原书精排", listOf("字体", "布局", "装饰", "CSS"), tab,
        { tab = it }, onDismiss, {
            if (onSaveNamed != null) {
                // A reusable style captures inherited dimensions too, so another theme cannot
                // silently change its title size or the chapter-opening whitespace.
                var savedCss = draft.css
                if (css.sizeEm == null) savedCss = savedCss.withStyleProperty("font-size", em(settings.titleScale))
                if (css.topEm == null) savedCss = savedCss.withStyleProperty("margin-top", em(settings.titleTopSpacing * settings.lineHeight))
                if (css.bottomEm == null) savedCss = savedCss.withStyleProperty("margin-bottom", em(settings.titleBottomSpacing * settings.lineHeight))
                onSaveNamed(name.trim(), draft.copy(css = savedCss, presetId = null))
            } else actions.onTitleStyleChange(draft.copy(presetId = null))
            onDismiss()
        }, css.errors.isEmpty() && (onSaveNamed == null || name.isNotBlank()),
        preview = { TitleStylePreview(settings.copy(titleStyle = draft), palette) }) {
        when (tab) {
            0 -> {
                if (onSaveNamed != null) StyleEditorSection("样式名称") {
                    OutlinedTextField(name, { name = it.take(30) }, label = { Text("为这套样式命名") },
                        singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                }
                StyleEditorSection("从一种风格开始", "预设同时调整字体、留白和装饰，也可以继续微调。") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { draft = ReaderTitleStyle(font = ReaderSyntaxFont.SERIF, bold = false,
                            css = "font-size: 1.65em;\nmargin-top: 5em;\nmargin-bottom: 6em;\ntext-align: left;") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) { Text("留白章首") }
                        OutlinedButton(onClick = { draft = ReaderTitleStyle(alignment = ReaderTitleAlignment.CENTER,
                            borderWidthEm = .05f, paddingEm = .8f, cornerRadiusEm = .3f) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) { Text("边框章首") }
                    }
                }
                StyleEditorSection("标题字体") {
                    StyleFontPicker(resolved.font, resolved.fontAssetId, settings.fontLibrary) { font, id ->
                        draft = draft.copy(font = font, fontAssetId = id, css = draft.css.withoutStyleProperties("font-family"))
                    }
                    SyntaxRuleSwitch("加粗标题", resolved.bold) { draft = draft.copy(bold = it, css = draft.css.withoutStyleProperties("font-weight")) }
                    TextButton(onClick = actions.onImportTitleFont) { Text("＋ 导入字体") }
                }
                TextButton(onClick = { draft = ReaderTitleStyle() }) { Text("恢复默认标题样式") }
            }
            1 -> {
                StyleEditorSection("位置与留白", "尺寸以正文字号为基准，1 em 等于一个正文字符的高度。") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderTitleAlignment.entries.forEach { align ->
                            FilterChip(resolved.alignment == align, { draft = draft.copy(alignment = align, css = draft.css.withoutStyleProperties("text-align")) },
                                label = { Text(when (align) { ReaderTitleAlignment.START -> "居左"; ReaderTitleAlignment.CENTER -> "居中"; ReaderTitleAlignment.END -> "居右" }) })
                        }
                    }
                    TitleDimension("标题字号", css.sizeEm ?: settings.titleScale, .75f..3f, .05f, controls) { draft = draft.copy(css = draft.css.withStyleProperty("font-size", em(it))) }
                    TitleDimension("上方留白", css.topEm ?: settings.titleTopSpacing * settings.lineHeight, 0f..12f, .25f, controls) { draft = draft.copy(css = draft.css.withStyleProperty("margin-top", em(it))) }
                    TitleDimension("下方留白", css.bottomEm ?: settings.titleBottomSpacing * settings.lineHeight, 0f..12f, .25f, controls) { draft = draft.copy(css = draft.css.withStyleProperty("margin-bottom", em(it))) }
                    TitleDimension("水平内收", resolved.insetEm, 0f..8f, .1f, controls) { draft = draft.copy(insetEm = it, css = draft.css.withoutStyleProperties("margin-inline")) }
                }
            }
            2 -> {
                StyleEditorSection("配色") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("文字", "边框", "底色").forEachIndexed { index, label ->
                            FilterChip(colorTarget == index, { colorTarget = index; customColor = false }, label = { Text(label) })
                        }
                    }
                    val color = when (colorTarget) { 0 -> resolved.colorArgb; 1 -> resolved.borderColorArgb; else -> resolved.backgroundArgb }
                    fun changeColor(value: Int?) {
                        draft = when (colorTarget) {
                            0 -> draft.copy(colorArgb = value, css = draft.css.withoutStyleProperties("color"))
                            1 -> draft.copy(borderColorArgb = value, css = draft.css.withoutStyleProperties("border-color"))
                            else -> draft.copy(backgroundArgb = value, css = draft.css.withoutStyleProperties("background-color"))
                        }
                    }
                    StyleColorChoices(color, palette.onBackground.toArgb(), if (colorTarget == 2) "透明" else "跟随主题",
                        onChange = ::changeColor, expanded = customColor, onExpand = { customColor = !customColor })
                }
                StyleEditorSection("边框") {
                    TitleDimension("线条粗细", resolved.borderWidthEm, 0f..0.5f, .05f, controls) { draft = draft.copy(borderWidthEm = it, css = draft.css.withoutStyleProperties("border-width")) }
                    TitleDimension("框内留白", resolved.paddingEm, 0f..3f, .1f, controls) { draft = draft.copy(paddingEm = it, css = draft.css.withoutStyleProperties("padding")) }
                    TitleDimension("圆角大小", resolved.cornerRadiusEm, 0f..3f, .1f, controls) { draft = draft.copy(cornerRadiusEm = it, css = draft.css.withoutStyleProperties("border-radius")) }
                }
                StyleEditorSection("图片装饰", "图片铺满标题框，透明 PNG 可用作花边。") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(resolved.imageAssetId == null, { draft = draft.copy(imageAssetId = null, css = draft.css.withStyleBackgroundImage(null)) }, label = { Text("无图片") }) }
                        items(settings.imageLibrary, key = { it.id }) { image ->
                            BackgroundChoice(image.displayName, image.filePath, resolved.imageAssetId == image.id, controls) { draft = draft.copy(imageAssetId = image.id, css = draft.css.withStyleBackgroundImage(image.id)) }
                        }
                    }
                    TextButton(onClick = actions.onImportTitleImage) { Text("＋ 导入装饰图片") }
                }
            }
            else -> {
                StyleCssField(draft.css, css.errors,
                "font-family: serif;\nfont-size: 1.65em;\nmargin-top: 5em;\nmargin-bottom: 6em;\ncolor: #685248;",
                "无需选择器或大括号。支持字体、颜色、对齐、font-size、margin-top / bottom / inline、padding、border-width / color / radius。长度使用 em。\n$STYLE_GRADIENT_HELP") { draft = draft.copy(css = it) }
                StyleCssImages(settings.imageLibrary, draft.css, { draft = draft.copy(css = it) }, actions.onImportTitleImage)
            }
        }
    }
}

private fun em(value: Float) = String.format(Locale.ROOT, "%.2fem", value)

@Composable
private fun TitleDimension(label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, palette: ReaderPalette, onChange: (Float) -> Unit) {
    TypographyStepper(label, em(value), value, range, step, palette, onValueChange = onChange)
}

/** Preview uses the same measured layout and bitmap renderer as the book. */
@Composable
internal fun TitleStylePreview(settings: ReaderSettings, palette: ReaderPalette, height: androidx.compose.ui.unit.Dp = 176.dp) {
    val bitmap = remember(settings, palette) {
        val style = ReaderPageStyle.resolve(settings.copy(showHeader = false, showFooter = false), palette, Density(1f), 360, 220, 0f, 0f)
        val chapter = ChapterTypesetter(style.spec, style.measure).typeset(0, "第1章  春江月夜", "春江潮水连海平，海上明月共潮生。\n沿着河岸缓缓前行，远处灯火渐渐亮起。")
        val renderer = PageBitmapRenderer(style)
        renderer.render(RenderPage.Laid(0, chapter.title, 0, chapter.pages.size, chapter.pages.first()), null, 0f, "", 100).also { renderer.release() }
    }
    Image(bitmap.asImageBitmap(), "章节标题排版预览", Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp).height(height).clip(RoundedCornerShape(12.dp)))
}
