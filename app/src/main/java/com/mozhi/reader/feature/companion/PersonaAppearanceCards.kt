package com.mozhi.reader.feature.companion

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mozhi.reader.core.database.entity.ChatBubbleShape
import com.mozhi.reader.core.database.entity.PersonaChatAppearance
import com.mozhi.reader.core.datastore.ReaderFontAsset
import com.mozhi.reader.core.datastore.ReaderImageAsset
import com.mozhi.reader.ui.components.rememberChatBubbleStyle
import com.mozhi.reader.ui.components.rememberChatFontFamily
import java.io.File
import kotlin.math.roundToInt

/**
 * 聊天外观编辑：预览卡常驻在最上面，任何一项改动都立刻反映在它身上——
 * 让用户「先看到再决定」，而不是保存、退出、进聊天页才发现配色难看。
 */
@Composable
internal fun AppearanceCard(
    appearance: PersonaChatAppearance,
    personaName: String,
    images: List<ReaderImageAsset>,
    fonts: List<ReaderFontAsset>,
    onChange: ((PersonaChatAppearance) -> PersonaChatAppearance) -> Unit,
    onReset: () -> Unit
) {
    // 外层卡由调用方的 MoReadSection 提供，这里只出内容，免得卡中套卡。
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ChatAppearancePreview(
            appearance = appearance,
            personaName = personaName,
            images = images,
            fonts = fonts
        )
        if (!appearance.isDefault) {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onReset) {
                    Icon(
                        Icons.Outlined.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text("恢复默认", Modifier.padding(start = 4.dp))
                }
            }
        }

                AppearanceField(label = "气泡样式") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChatBubbleShape.entries.forEach { shape ->
                            FilterChip(
                                selected = appearance.shape == shape,
                                onClick = { onChange { it.copy(bubbleShape = shape.wire) } },
                                label = { Text(shape.label) }
                            )
                        }
                    }
                }

                AppearanceField(label = "聊天背景") {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BackgroundThumb(
                            label = "无",
                            path = null,
                            selected = appearance.backgroundImageId == null,
                            onClick = { onChange { it.copy(backgroundImageId = null) } }
                        )
                        images.forEach { image ->
                            BackgroundThumb(
                                label = image.displayName,
                                path = image.filePath,
                                selected = appearance.backgroundImageId == image.id,
                                onClick = { onChange { it.copy(backgroundImageId = image.id) } }
                            )
                        }
                    }
                    if (images.isEmpty()) {
                        Text(
                            "图片库还是空的。到「设置 → 图片库」导入后，这里和阅读背景可以共用同一批图。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (appearance.backgroundImageId != null) {
                    AppearanceField(
                        label = "背景蒙版 ${(appearance.backgroundDim * 100).roundToInt()}%"
                    ) {
                        Slider(
                            value = appearance.backgroundDim,
                            onValueChange = { value -> onChange { it.copy(backgroundDim = value) } },
                            valueRange = 0f..1f
                        )
                        Text(
                            "盖得越重，气泡文字越清楚；盖得越轻，背景图越明显。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                AppearanceField(label = "聊天字体") {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = appearance.fontId == null,
                            onClick = { onChange { it.copy(fontId = null) } },
                            label = { Text("跟随应用") }
                        )
                        fonts.forEach { font ->
                            FilterChip(
                                selected = appearance.fontId == font.id,
                                onClick = { onChange { it.copy(fontId = font.id) } },
                                label = { Text(font.displayName) }
                            )
                        }
                    }
                }

        AppearanceField(
            label = "字号 ${(appearance.fontScale * 100).roundToInt()}%"
        ) {
            Slider(
                value = appearance.fontScale,
                onValueChange = { value -> onChange { it.copy(fontScale = value) } },
                valueRange = PersonaChatAppearance.MIN_FONT_SCALE..
                    PersonaChatAppearance.MAX_FONT_SCALE
            )
        }
    }
}

@Composable
private fun AppearanceField(
    label: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

@Composable
private fun BackgroundThumb(
    label: String,
    path: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp)
    ) {
        Surface(
            modifier = Modifier.size(64.dp, 44.dp).clickable(onClick = onClick),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = if (selected) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                null
            }
        ) {
            if (path != null) {
                AsyncImage(
                    model = File(path),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** 两条假气泡的实时预览；背景、字体、字号、形状与真实聊天页走同一套派生逻辑。 */
@Composable
private fun ChatAppearancePreview(
    appearance: PersonaChatAppearance,
    personaName: String,
    images: List<ReaderImageAsset>,
    fonts: List<ReaderFontAsset>
) {
    val backgroundPath = images
        .firstOrNull { it.id == appearance.backgroundImageId }
        ?.filePath
        ?.takeIf { File(it).isFile }
    val fontFamily = rememberChatFontFamily(appearance.fontId, fonts)
    Surface(
        modifier = Modifier.fillMaxWidth().height(168.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box {
            backgroundPath?.let { path ->
                AsyncImage(
                    model = File(path),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = appearance.backgroundDim)
                        )
                )
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "预览",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PreviewBubble(
                    text = personaName.ifBlank { "角色" } + "：这一段你怎么看？",
                    fromUser = false,
                    appearance = appearance,
                    fontFamily = fontFamily
                )
                PreviewBubble(
                    text = "我觉得他其实早就知道了。",
                    fromUser = true,
                    appearance = appearance,
                    fontFamily = fontFamily
                )
            }
        }
    }
}

@Composable
private fun PreviewBubble(
    text: String,
    fromUser: Boolean,
    appearance: PersonaChatAppearance,
    fontFamily: FontFamily?
) {
    val style = rememberChatBubbleStyle(appearance, fromUser)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = style.container,
            contentColor = style.content,
            shape = style.shape(fromUser),
            border = style.border
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = fontFamily,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize * appearance.fontScale,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
            )
        }
    }
}

