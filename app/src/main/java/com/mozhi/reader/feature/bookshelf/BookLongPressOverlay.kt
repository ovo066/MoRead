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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.outlined.Checklist
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.mozhi.reader.R
import com.mozhi.reader.ui.MoReadLayoutPolicy
import com.mozhi.reader.ui.rememberMoReadWindowWidth
import com.mozhi.reader.ui.theme.MoReadSpacing
import com.mozhi.reader.ui.components.safeTopInset
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookReadState
import com.mozhi.reader.core.database.entity.isPinned
import com.mozhi.reader.core.database.entity.readState
import com.mozhi.reader.core.library.BookReadSpan
import com.mozhi.reader.core.library.readFraction
import com.mozhi.reader.core.library.readPercent
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.MoReadMenuDivider

/** 被长按的书 + 它在根坐标系里的位置，浮层照这个位置把书「浮起来」。 */
internal data class BookLongPressTarget(
    val book: BookEntity,
    val bounds: Rect
)

/**
 * 长按书籍的悬浮操作层：压暗（书架内容那侧同时虚化）、被按的书原地浮起，菜单按书在
 * 屏幕上的位置自适应选择四个方向，必要时覆盖部分封面。坐标相对书架内容容器，
 * 菜单始终避开系统栏，优先避让导航舱，空间不足时由顶层窗口覆盖它；短屏可滚动。
 */
@Composable
internal fun BookLongPressOverlay(
    target: BookLongPressTarget,
    readSpan: BookReadSpan?,
    rootSize: IntSize,
    contentPadding: PaddingValues,
    onDismiss: () -> Unit,
    onSetReadState: (BookReadState?) -> Unit,
    onEditDetails: () -> Unit,
    onChangeCover: () -> Unit,
    onTogglePinned: () -> Unit,
    onStartSelection: () -> Unit,
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

    if (rootSize.width <= 0 || rootSize.height <= 0) return
    val layoutDirection = LocalLayoutDirection.current
    val bottomReserve = MoReadLayoutPolicy.rootBottomPaddingDp(rememberMoReadWindowWidth()).dp
    val topInset = maxOf(contentPadding.calculateTopPadding(), safeTopInset())
    val systemSafeBounds = with(density) {
        val left = (contentPadding.calculateLeftPadding(layoutDirection) + MoReadSpacing.l).toPx()
        val top = (topInset + MoReadSpacing.l).toPx()
        Rect(
            left, top,
            maxOf(left + 1f, rootSize.width - (contentPadding.calculateRightPadding(layoutDirection) + MoReadSpacing.l).toPx()),
            maxOf(top + 1f, rootSize.height - (contentPadding.calculateBottomPadding() + MoReadSpacing.l).toPx())
        )
    }
    val dockSafeBounds = systemSafeBounds.copy(bottom = maxOf(
        systemSafeBounds.top + 1f,
        rootSize.height - with(density) { (contentPadding.calculateBottomPadding() + bottomReserve).toPx() }
    ))
    val gapPx = with(density) { MoReadSpacing.m.toPx() }
    val menuWidthPx = minOf(with(density) { MENU_WIDTH.toPx() }, systemSafeBounds.width)
    val previewWidthPx = with(density) {
        (systemSafeBounds.width - menuWidthPx - gapPx)
            .coerceIn(MIN_PREVIEW_WIDTH.toPx(), PREVIEW_WIDTH.toPx())
            .coerceAtMost(systemSafeBounds.width)
    }
    val previewWidth = with(density) { previewWidthPx.toDp() }
    val menuWidth = with(density) { menuWidthPx.toDp() }
    val maxHeight = with(density) { systemSafeBounds.height.toDp() }
    // Prefer leaving the dock visible. If that makes the group cramped, the popup may cover it.
    val placementBounds = if (previewHeight + menuHeight + gapPx <= dockSafeBounds.height) {
        dockSafeBounds
    } else systemSafeBounds
    val placement = placeBookMenu(
        target.bounds, placementBounds,
        Size(previewWidthPx, previewHeight.toFloat()),
        Size(menuWidthPx, menuHeight.toFloat()), gapPx
    )

    // A separate popup window sits above the app-root dock; local zIndex cannot cross that layer.
    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, clippingEnabled = false)
    ) {
        Box(
            modifier = Modifier
                .size(with(density) { rootSize.width.toDp() }, with(density) { rootSize.height.toDp() })
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
                readSpan = readSpan,
                modifier = Modifier
                    .width(previewWidth)
                    .heightIn(max = maxHeight)
                    .offset { IntOffset(placement.preview.left.toInt(), placement.preview.top.toInt()) }
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
                    .width(menuWidth)
                    .heightIn(max = maxHeight)
                    .offset { IntOffset(placement.menu.left.toInt(), placement.menu.top.toInt()) }
                    .onSizeChanged { menuHeight = it.height }
                    .graphicsLayer {
                        alpha = enter.value
                        val scale = 0.9f + 0.1f * enter.value
                        scaleX = scale
                        scaleY = scale
                        // 菜单从贴近书的那条边展开，方向感跟着位置走。
                        transformOrigin = TransformOrigin(
                            ((placement.preview.center.x - placement.menu.left) / menuWidthPx).coerceIn(0f, 1f),
                            ((placement.preview.center.y - placement.menu.top) / menuHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
                        )
                    },
                shape = RoundedCornerShape(22.dp),
                shadowElevation = 18.dp
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = MoReadSpacing.xs)) {
                    BookActionRow(
                        text = stringResource(if (state == BookReadState.FINISHED) R.string.book_action_unfinish else R.string.book_action_finish),
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
                            text = stringResource(R.string.book_action_unread),
                            icon = Icons.Outlined.RadioButtonUnchecked,
                            onClick = {
                                onDismiss()
                                onSetReadState(BookReadState.UNREAD)
                            }
                        )
                    }
                    BookActionRow(
                        text = stringResource(if (state == BookReadState.SHELVED) R.string.book_action_unshelve else R.string.book_action_shelve),
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
                        text = stringResource(R.string.book_action_edit),
                        icon = Icons.Outlined.Edit,
                        onClick = {
                            onDismiss()
                            onEditDetails()
                        }
                    )
                    BookActionRow(
                        text = stringResource(R.string.book_action_cover),
                        icon = Icons.Outlined.Image,
                        onClick = {
                            onDismiss()
                            onChangeCover()
                        }
                    )
                    BookActionRow(
                        text = stringResource(if (book.isPinned) R.string.book_action_unpin else R.string.book_action_pin),
                        icon = Icons.Outlined.PushPin,
                        onClick = {
                            onDismiss()
                            onTogglePinned()
                        }
                    )
                    BookActionRow(
                        text = stringResource(R.string.book_action_select),
                        icon = Icons.Outlined.Checklist,
                        onClick = onStartSelection
                    )
                    MoReadMenuDivider()
                    BookActionRow(
                        text = stringResource(R.string.book_action_remove),
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
}

/** 浮起的书：封面 + 书名 + 当前阅读状态，让用户确认自己按中的是哪一本。 */
@Composable
private fun BookLongPressPreview(
    book: BookEntity,
    state: BookReadState,
    readSpan: BookReadSpan?,
    modifier: Modifier = Modifier
) {
    FrostedSurface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 20.dp
    ) {
        Column(
            modifier = Modifier.padding(MoReadSpacing.s),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MoReadSpacing.s)
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
                text = state.caption(book, readSpan),
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
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = MoReadSpacing.l, vertical = MoReadSpacing.m),
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

@Composable
internal fun BookReadState.caption(book: BookEntity, readSpan: BookReadSpan?): String = when (this) {
    BookReadState.UNREAD -> pluralStringResource(R.plurals.book_state_unread_chapters, book.totalChapters, book.totalChapters)
    BookReadState.READING -> stringResource(R.string.book_state_reading_progress, readPercent(readFraction(book, readSpan)))
    BookReadState.FINISHED -> stringResource(R.string.book_state_finished)
    BookReadState.SHELVED -> stringResource(R.string.book_state_shelved)
}

@Composable
internal fun BookReadState.label(): String = stringResource(when (this) {
    BookReadState.UNREAD -> R.string.book_state_unread
    BookReadState.READING -> R.string.book_state_reading
    BookReadState.FINISHED -> R.string.book_state_finished
    BookReadState.SHELVED -> R.string.book_state_shelved
})

/** 浮层压暗强度；虚化不可用（API < 31）时加重，靠对比度托住浮层。 */
internal val SCRIM_ALPHA = if (android.os.Build.VERSION.SDK_INT >= 31) 0.26f else 0.44f
private val MIN_PREVIEW_WIDTH = 96.dp
private val PREVIEW_WIDTH = 142.dp
private val MENU_WIDTH = 218.dp
