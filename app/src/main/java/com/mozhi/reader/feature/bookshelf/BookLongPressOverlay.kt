package com.mozhi.reader.feature.bookshelf

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookReadState
import com.mozhi.reader.core.database.entity.isPinned
import com.mozhi.reader.core.database.entity.readState
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.MoReadMenuDivider

/** 被长按的书 + 它在根坐标系里的位置，浮层照这个位置把书「浮起来」。 */
internal data class BookLongPressTarget(
    val book: BookEntity,
    val bounds: Rect
)

/**
 * 长按书籍的悬浮操作层：压暗（书架内容那侧同时虚化）、被按的书原地浮起，菜单按书在
 * 屏幕上的位置自适应落在下方或上方。整层由 [BookshelfScreen] 叠在内容之上，自身不受
 * contentPadding 约束，所以坐标一律用根坐标系。
 */
@Composable
internal fun BookLongPressOverlay(
    target: BookLongPressTarget,
    rootSize: IntSize,
    onDismiss: () -> Unit,
    onSetReadState: (BookReadState?) -> Unit,
    onEditDetails: () -> Unit,
    onChangeCover: () -> Unit,
    onTogglePinned: () -> Unit,
    onDelete: () -> Unit
) {
    val density = LocalDensity.current
    val book = target.book
    val state = book.readState()
    val enter = remember { Animatable(0f) }
    // 两块各自量高，位置才能算准；量到之前保持透明，别让它跳一帧。
    var previewHeight by remember { mutableIntStateOf(0) }
    var menuHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(previewHeight, menuHeight) {
        if (previewHeight > 0 && menuHeight > 0 && enter.value < 1f) {
            enter.animateTo(1f, tween(190))
        }
    }

    val previewWidthPx = with(density) { PREVIEW_WIDTH.toPx() }
    val menuWidthPx = with(density) { MENU_WIDTH.toPx() }
    val marginPx = with(density) { 16.dp.toPx() }
    val gapPx = with(density) { 12.dp.toPx() }

    // 横向：两块都对齐被按项的中心，贴着原位置长出来；越界才往里收。
    fun clampLeft(width: Float): Float =
        (target.bounds.center.x - width / 2f)
            .coerceIn(marginPx, (rootSize.width - width - marginPx).coerceAtLeast(marginPx))

    // 纵向：优先「书在原位、菜单在下方」；下方放不下就翻到上方；都放不下才整组上移。
    val maxBottom = rootSize.height - marginPx
    var previewTop = target.bounds.top
    var menuTop = previewTop + previewHeight + gapPx
    if (menuTop + menuHeight > maxBottom) {
        val above = previewTop - gapPx - menuHeight
        if (above >= marginPx) {
            menuTop = above
        } else {
            val group = previewHeight + gapPx + menuHeight
            previewTop = (maxBottom - group).coerceAtLeast(marginPx)
            menuTop = previewTop + previewHeight + gapPx
        }
    }
    previewTop = previewTop.coerceIn(
        marginPx,
        (maxBottom - previewHeight).coerceAtLeast(marginPx)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA * enter.value))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    ) {
        BookLongPressPreview(
            book = book,
            state = state,
            modifier = Modifier
                .width(PREVIEW_WIDTH)
                .offset { IntOffset(clampLeft(previewWidthPx).toInt(), previewTop.toInt()) }
                .onSizeChanged { previewHeight = it.height }
                .graphicsLayer {
                    alpha = enter.value
                    // 从书原本的大小长起来，落点就是它自己的位置。
                    val scale = 0.9f + 0.1f * enter.value
                    scaleX = scale
                    scaleY = scale
                }
        )

        FrostedSurface(
            modifier = Modifier
                .width(MENU_WIDTH)
                .offset { IntOffset(clampLeft(menuWidthPx).toInt(), menuTop.toInt()) }
                .onSizeChanged { menuHeight = it.height }
                .graphicsLayer {
                    alpha = enter.value
                    val scale = 0.9f + 0.1f * enter.value
                    scaleX = scale
                    scaleY = scale
                    // 菜单从贴近书的那条边展开，方向感跟着位置走。
                    transformOrigin = TransformOrigin(
                        0.5f,
                        if (menuTop >= previewTop) 0f else 1f
                    )
                },
            shape = RoundedCornerShape(22.dp),
            shadowElevation = 18.dp
        ) {
            Column(modifier = Modifier.padding(vertical = 5.dp)) {
                BookActionRow(
                    text = if (state == BookReadState.FINISHED) "取消已读完" else "标为已读完",
                    icon = Icons.Outlined.TaskAlt,
                    onClick = {
                        onDismiss()
                        onSetReadState(
                            if (state == BookReadState.FINISHED) null else BookReadState.FINISHED
                        )
                    }
                )
                if (state != BookReadState.UNREAD) {
                    BookActionRow(
                        text = "标为未读",
                        icon = Icons.Outlined.RadioButtonUnchecked,
                        onClick = {
                            onDismiss()
                            onSetReadState(BookReadState.UNREAD)
                        }
                    )
                }
                BookActionRow(
                    text = if (state == BookReadState.SHELVED) "取消搁置" else "标为搁置",
                    icon = Icons.Outlined.Schedule,
                    onClick = {
                        onDismiss()
                        onSetReadState(
                            if (state == BookReadState.SHELVED) null else BookReadState.SHELVED
                        )
                    }
                )
                MoReadMenuDivider()
                BookActionRow(
                    text = "编辑详情",
                    icon = Icons.Outlined.Edit,
                    onClick = {
                        onDismiss()
                        onEditDetails()
                    }
                )
                BookActionRow(
                    text = "修改封面",
                    icon = Icons.Outlined.Image,
                    onClick = {
                        onDismiss()
                        onChangeCover()
                    }
                )
                BookActionRow(
                    text = if (book.isPinned) "取消置顶" else "置顶书架",
                    icon = Icons.Outlined.PushPin,
                    onClick = {
                        onDismiss()
                        onTogglePinned()
                    }
                )
                MoReadMenuDivider()
                BookActionRow(
                    text = "移除书架",
                    icon = Icons.Outlined.Delete,
                    destructive = true,
                    onClick = {
                        onDismiss()
                        onDelete()
                    }
                )
            }
        }
    }
}

/** 浮起的书：封面 + 书名 + 当前阅读状态，让用户确认自己按中的是哪一本。 */
@Composable
private fun BookLongPressPreview(
    book: BookEntity,
    state: BookReadState,
    modifier: Modifier = Modifier
) {
    FrostedSurface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 20.dp
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CompactBookArtwork(
                book = book,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.69f)
                    .clip(RoundedCornerShape(10.dp))
            )
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = state.caption(book),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BookActionRow(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    destructive: Boolean = false
) {
    val tint = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(19.dp)
        )
    }
}

internal fun BookReadState.caption(book: BookEntity): String = when (this) {
    BookReadState.UNREAD -> "未读 · ${book.totalChapters} 章"
    BookReadState.READING -> "在读 · ${(readProgress(book) * 100).toInt()}%"
    BookReadState.FINISHED -> "已读完"
    BookReadState.SHELVED -> "已搁置"
}

internal fun BookReadState.label(): String = when (this) {
    BookReadState.UNREAD -> "未读"
    BookReadState.READING -> "在读"
    BookReadState.FINISHED -> "已读完"
    BookReadState.SHELVED -> "搁置"
}

/** 浮层压暗强度；虚化不可用（API < 31）时加重，靠对比度托住浮层。 */
internal val SCRIM_ALPHA = if (android.os.Build.VERSION.SDK_INT >= 31) 0.26f else 0.44f
private val PREVIEW_WIDTH = 142.dp
private val MENU_WIDTH = 218.dp
