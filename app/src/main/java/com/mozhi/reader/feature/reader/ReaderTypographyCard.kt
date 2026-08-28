package com.mozhi.reader.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.feature.reader.render.ReaderPageStyle
import kotlin.math.roundToInt

/** 悬浮卡片里的分类。一张卡片装下四类，靠顶部胶囊切换，不再是四张要来回退的二级页。 */
private enum class TypographyCardTab(val label: String) {
    BODY("正文"),
    TITLE("标题"),
    PAGE("页面"),
    CHROME("页眉页脚")
}

/**
 * 排版细调悬浮卡片。
 *
 * 为什么不做成半屏弹层里的又一张二级页：这一类设置**每改一格都要立刻看重排结果**，
 * 而半屏弹层从下沿吃掉半个屏幕，正文只剩上半屏、还正好是刚翻过去的旧内容。
 * 悬浮卡片居中浮着，上下都留着正文，并且可以按住顶部手柄挪开正在盯的那几行。
 *
 * 卡片渲染在阅读页自己的窗口里（不是 Dialog），因此没有遮罩、也不抢焦点。
 */
@Composable
internal fun ReaderTypographyCard(
    visible: Boolean,
    settings: ReaderSettings,
    palette: ReaderPalette,
    actions: ReaderTypographyActions,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    // 位置留在 ReaderScreen 的生命周期里：关掉再打开还在上次挪到的地方。
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    var tab by remember { mutableStateOf(TypographyCardTab.BODY) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)) + scaleIn(tween(190), initialScale = 0.94f),
        exit = fadeOut(tween(120)) + scaleOut(tween(140), targetScale = 0.96f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { boxSize = it }
                // 点卡片以外的任何地方＝收起。没有遮罩，所以这层透明面板就是「外部」。
                .pointerInput(Unit) { detectTapGestures { onClose() } }
        ) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 400.dp)
                    .onSizeChanged { cardSize = it }
                    .shadow(22.dp, RoundedCornerShape(26.dp), clip = false)
                    // 吞掉落在卡片上的点击，否则会穿到外层的「点外部收起」。
                    .pointerInput(Unit) { detectTapGestures { } },
                shape = RoundedCornerShape(26.dp),
                // 玻璃层本身留了 5% 透明度，压在深色纸上会透出一层幽灵正文（实测 9/255，
                // 深底上看得很清楚）。卡片是要读数值的面板，先与纸色合成成不透明再画。
                color = palette.glassStrong.compositeOver(palette.background),
                contentColor = palette.onBackground,
                border = BorderStroke(1.dp, palette.glassBorder)
            ) {
                Column {
                    TypographyCardHeader(
                        palette = palette,
                        onBack = onBack,
                        onClose = onClose,
                        onDrag = { delta ->
                            val maxX = ((boxSize.width - cardSize.width) / 2f).coerceAtLeast(0f)
                            val maxY = ((boxSize.height - cardSize.height) / 2f).coerceAtLeast(0f)
                            offset = Offset(
                                x = (offset.x + delta.x).coerceIn(-maxX, maxX),
                                y = (offset.y + delta.y).coerceIn(-maxY, maxY)
                            )
                        }
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TypographyCardTab.entries.forEach { entry ->
                            SegChip(
                                text = entry.label,
                                selected = tab == entry,
                                palette = palette,
                                modifier = Modifier.weight(1f)
                            ) { tab = entry }
                        }
                    }
                    HorizontalDivider(
                        color = palette.glassBorder,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Column(
                        modifier = Modifier
                            .heightIn(max = 340.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        when (tab) {
                            TypographyCardTab.BODY -> BodyTypographySection(settings, palette, actions)
                            TypographyCardTab.TITLE -> TitleTypographySection(settings, palette, actions)
                            TypographyCardTab.PAGE -> PageMarginSection(settings, palette, actions)
                            TypographyCardTab.CHROME -> ChromeMarginSection(settings, palette, actions)
                        }
                    }
                }
            }
        }
    }
}

/** 顶部：拖动手柄 + 返回 / 标题 / 关闭。手柄与标题行整块都能拖，按钮自己吃掉点击不误触发拖动。 */
@Composable
private fun TypographyCardHeader(
    palette: ReaderPalette,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onDrag: (Offset) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(34.dp)
                    .height(4.dp)
                    .background(palette.muted.copy(alpha = 0.35f), CircleShape)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CardHeaderButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                description = "返回排版面板",
                palette = palette,
                onClick = onBack
            )
            Text(
                text = "排版",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            )
            CardHeaderButton(
                icon = Icons.Outlined.Close,
                description = "收起排版",
                palette = palette,
                onClick = onClose
            )
        }
    }
}

@Composable
private fun CardHeaderButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = palette.onBackground
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier
                .padding(8.dp)
                .size(18.dp)
        )
    }
}

@Composable
private fun BodyTypographySection(
    settings: ReaderSettings,
    palette: ReaderPalette,
    actions: ReaderTypographyActions
) {
    SheetRow(label = "EPUB 样式", palette = palette) {
        com.mozhi.reader.core.datastore.PublisherStyleMode.entries.forEach { mode ->
            SegChip(
                text = when (mode) {
                    com.mozhi.reader.core.datastore.PublisherStyleMode.RESPECT -> "尊重"
                    com.mozhi.reader.core.datastore.PublisherStyleMode.SMART -> "融合"
                    com.mozhi.reader.core.datastore.PublisherStyleMode.TAKE_OVER -> "接管"
                },
                selected = settings.publisherStyleMode == mode,
                palette = palette,
                modifier = Modifier.weight(1f)
            ) { actions.onPublisherStyleModeChange(mode) }
        }
    }
    Text(
        text = when (settings.publisherStyleMode) {
            com.mozhi.reader.core.datastore.PublisherStyleMode.RESPECT -> "保留原书字号、行距、间距与装饰"
            com.mozhi.reader.core.datastore.PublisherStyleMode.SMART -> "以你的字号、行距和段距为基准，保留原书比例"
            com.mozhi.reader.core.datastore.PublisherStyleMode.TAKE_OVER -> "统一正文排版，仍保留粗斜体、对齐与 ruby"
        },
        style = MaterialTheme.typography.labelSmall,
        color = palette.muted
    )
    TypographyStepper(
        label = "字号",
        valueText = "" + (ReaderPageStyle.BASE_CONTENT_SP * settings.fontScale).roundToInt() + " sp",
        value = settings.fontScale,
        range = 0.75f..2f,
        step = 0.05f,
        palette = palette,
        onValueChange = actions.onFontScaleChange
    )
    TypographyStepper(
        label = "行间距",
        valueText = String.format(java.util.Locale.ROOT, "%.2f×", settings.lineHeight),
        value = settings.lineHeight,
        range = 1f..2.2f,
        step = 0.05f,
        palette = palette,
        onValueChange = actions.onLineHeightChange
    )
    TypographyStepper(
        label = "段落间距",
        valueText = String.format(java.util.Locale.ROOT, "%.2f em", settings.paragraphSpacingEm),
        value = settings.paragraphSpacingEm,
        range = 0f..1.5f,
        step = 0.05f,
        palette = palette,
        onValueChange = actions.onParagraphSpacingChange
    )
    TypographyStepper(
        label = "字间距",
        valueText = String.format(java.util.Locale.ROOT, "%.2f em", settings.letterSpacingEm),
        value = settings.letterSpacingEm,
        range = -0.05f..0.2f,
        step = 0.01f,
        palette = palette,
        onValueChange = actions.onLetterSpacingChange
    )
    TypographyStepper(
        label = "段首缩进",
        valueText = String.format(java.util.Locale.ROOT, "%.1f 字", settings.firstLineIndentEm),
        value = settings.firstLineIndentEm,
        range = 0f..4f,
        step = 0.25f,
        palette = palette,
        onValueChange = actions.onFirstLineIndentChange
    )
    SheetRow(label = "字重", palette = palette) {
        listOf(300, 400, 500, 600, 700).forEach { weight ->
            SegChip(
                text = weight.toString(),
                selected = settings.fontWeight == weight,
                palette = palette,
                modifier = Modifier.weight(1f)
            ) { actions.onFontWeightChange(weight) }
        }
    }
    SheetRow(label = "对齐", palette = palette) {
        SegChip(
            text = "两端",
            selected = settings.textJustification,
            palette = palette,
            modifier = Modifier.weight(1f)
        ) { actions.onTextJustificationChange(true) }
        SegChip(
            text = "左对齐",
            selected = !settings.textJustification,
            palette = palette,
            modifier = Modifier.weight(1f)
        ) { actions.onTextJustificationChange(false) }
    }
}

@Composable
private fun TitleTypographySection(
    settings: ReaderSettings,
    palette: ReaderPalette,
    actions: ReaderTypographyActions
) {
    TypographyStepper(
        label = "标题比例",
        valueText = String.format(java.util.Locale.ROOT, "%.2f×", settings.titleScale),
        value = settings.titleScale,
        range = 1f..2f,
        step = 0.05f,
        palette = palette,
        onValueChange = actions.onTitleScaleChange
    )
    TypographyStepper(
        label = "标题上距",
        valueText = spacingLineText(settings.titleTopSpacing),
        value = settings.titleTopSpacing,
        range = 0f..3f,
        step = 0.1f,
        palette = palette,
        onValueChange = actions.onTitleTopSpacingChange
    )
    TypographyStepper(
        label = "标题下距",
        valueText = spacingLineText(settings.titleBottomSpacing),
        value = settings.titleBottomSpacing,
        range = 0f..3f,
        step = 0.1f,
        palette = palette,
        onValueChange = actions.onTitleBottomSpacingChange
    )
}

@Composable
private fun PageMarginSection(
    settings: ReaderSettings,
    palette: ReaderPalette,
    actions: ReaderTypographyActions
) {
    TypographyStepper(
        label = "左边距",
        valueText = horizontalMarginText(settings.pageMarginLeft),
        value = settings.pageMarginLeft,
        range = 0f..2f,
        step = 0.1f,
        palette = palette,
        onValueChange = actions.onPageMarginLeftChange
    )
    TypographyStepper(
        label = "右边距",
        valueText = horizontalMarginText(settings.pageMarginRight),
        value = settings.pageMarginRight,
        range = 0f..2f,
        step = 0.1f,
        palette = palette,
        onValueChange = actions.onPageMarginRightChange
    )
    TypographyStepper(
        label = "上边距",
        valueText = verticalMarginText(settings.pageMarginTop),
        value = settings.pageMarginTop,
        range = 0f..2f,
        step = 0.1f,
        palette = palette,
        onValueChange = actions.onPageMarginTopChange
    )
    TypographyStepper(
        label = "下边距",
        valueText = verticalMarginText(settings.pageMarginBottom),
        value = settings.pageMarginBottom,
        range = 0f..2f,
        step = 0.1f,
        palette = palette,
        onValueChange = actions.onPageMarginBottomChange
    )
}

@Composable
private fun ChromeMarginSection(
    settings: ReaderSettings,
    palette: ReaderPalette,
    actions: ReaderTypographyActions
) {
    AdvancedSwitchRow(
        "显示页眉",
        "章节标题",
        settings.showHeader,
        palette,
        actions.onShowHeaderChange
    )
    TypographyStepper(
        label = "页眉上距",
        valueText = chromeMarginText(settings.headerMarginTop),
        value = settings.headerMarginTop,
        range = 0f..2f,
        step = 0.1f,
        palette = palette,
        onValueChange = actions.onHeaderMarginTopChange
    )
    HorizontalDivider(color = palette.glassBorder)
    AdvancedSwitchRow(
        "显示页脚",
        "页码、进度、时间与电量",
        settings.showFooter,
        palette,
        actions.onShowFooterChange
    )
    TypographyStepper(
        label = "页脚下距",
        valueText = chromeMarginText(settings.footerMarginBottom),
        value = settings.footerMarginBottom,
        range = 0f..2f,
        step = 0.1f,
        palette = palette,
        onValueChange = actions.onFooterMarginBottomChange
    )
}
