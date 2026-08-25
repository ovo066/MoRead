package com.mozhi.reader.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.database.entity.ChatBubbleShape
import com.mozhi.reader.core.database.entity.PersonaChatAppearance
import com.mozhi.reader.core.datastore.ReaderFontAsset
import java.io.File

/**
 * 角色外观派生出的气泡样式。放在 ui/components 是因为两处都要用：
 * 角色编辑页的实时预览与阅读侧真正的聊天气泡——而 feature 包之间不许互相 import。
 * 共用同一份派生逻辑，预览里看到的就一定是聊天页里出现的。
 */
data class ChatBubbleStyle(
    val container: Color,
    val content: Color,
    val border: BorderStroke?,
    val corner: Int
) {
    /**
     * 尖角朝向说话的一方，与常见 IM 一致。
     *
     * [isTail] 为 false 时四角全圆：一个人连发几条时，只有最后一条带尖角，
     * 中间几条圆着——这是 iMessage 的规则，也是让连发看起来像「一个人在说」
     * 而不是「几个人各说一句」的关键。
     */
    fun shape(fromUser: Boolean, isTail: Boolean = true) = when {
        !isTail -> RoundedCornerShape(corner.dp)
        fromUser -> RoundedCornerShape(corner.dp, corner.dp, 5.dp, corner.dp)
        else -> RoundedCornerShape(corner.dp, corner.dp, corner.dp, 5.dp)
    }
}

/** 形状语汇 → 具体的底色/描边/圆角。 */
@Composable
fun rememberChatBubbleStyle(
    appearance: PersonaChatAppearance,
    fromUser: Boolean,
    defaultUserContainer: Color = MaterialTheme.colorScheme.primary,
    defaultUserContent: Color = MaterialTheme.colorScheme.onPrimary,
    defaultAssistantContainer: Color = MaterialTheme.colorScheme.secondaryContainer,
    defaultAssistantContent: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    surfaceContent: Color = MaterialTheme.colorScheme.onSurface
): ChatBubbleStyle {
    val custom = if (fromUser) appearance.userColorArgb else appearance.assistantColorArgb
    val base = custom?.let(::Color)
        ?: if (fromUser) defaultUserContainer else defaultAssistantContainer
    val onBase = if (fromUser) defaultUserContent else defaultAssistantContent
    return when (appearance.shape) {
        ChatBubbleShape.ROUNDED -> ChatBubbleStyle(base, onBase, null, 16)
        // 描边与玻璃都要让背景透出来，所以文字色改用页面前景色而不是气泡的 on-color。
        ChatBubbleShape.OUTLINED -> ChatBubbleStyle(
            container = Color.Transparent,
            content = if (fromUser) base else surfaceContent,
            border = BorderStroke(1.dp, base.copy(alpha = 0.7f)),
            corner = 14
        )
        ChatBubbleShape.PAPER -> ChatBubbleStyle(base.copy(alpha = 0.94f), onBase, null, 6)
        ChatBubbleShape.GLASS -> ChatBubbleStyle(
            container = base.copy(alpha = 0.34f),
            content = surfaceContent,
            border = BorderStroke(1.dp, base.copy(alpha = 0.45f)),
            corner = 18
        )
    }
}

/** 按字体库 id 取字体；文件缺失或损坏一律返回 null（＝跟随应用字体），不抛异常。 */
@Composable
fun rememberChatFontFamily(fontId: String?, fonts: List<ReaderFontAsset>): FontFamily? {
    val path = fonts.firstOrNull { it.id == fontId }?.filePath ?: return null
    return remember(path) {
        File(path).takeIf(File::isFile)?.let { file ->
            runCatching { FontFamily(Font(file)) }.getOrNull()
        }
    }
}

/**
 * 把角色选定的字体与字号套到气泡内的一切文字上——包括 Markdown 渲染器，
 * 它读的是 MaterialTheme.typography，所以这里换主题比逐个 Text 传参可靠得多。
 *
 * 默认外观（字号 1×、跟随应用字体）直接透传，不额外建一层主题。
 */
@Composable
fun ChatTextStyling(
    appearance: PersonaChatAppearance,
    fontFamily: FontFamily?,
    content: @Composable () -> Unit
) {
    val scale = appearance.fontScale
    if (fontFamily == null && scale == 1f) {
        content()
        return
    }
    val base = MaterialTheme.typography
    val typography = remember(base, fontFamily, scale) {
        fun TextStyle.tuned(): TextStyle = copy(
            fontFamily = fontFamily ?: this.fontFamily,
            fontSize = fontSize * scale,
            lineHeight = if (lineHeight.isSpecified) lineHeight * scale else lineHeight
        )
        base.copy(
            titleMedium = base.titleMedium.tuned(),
            titleSmall = base.titleSmall.tuned(),
            bodyMedium = base.bodyMedium.tuned(),
            bodySmall = base.bodySmall.tuned(),
            labelSmall = base.labelSmall.tuned()
        )
    }
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        shapes = MaterialTheme.shapes,
        typography = typography,
        content = content
    )
}
