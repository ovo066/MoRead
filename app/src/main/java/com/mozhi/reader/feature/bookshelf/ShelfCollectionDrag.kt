package com.mozhi.reader.feature.bookshelf

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.database.entity.BookEntity
import kotlin.math.abs

data class ShelfDropTarget(
    val entryKey: String,
    val bookId: Long?,
    val collectionId: Long?
)

data class ShelfDropRegion(
    val target: ShelfDropTarget,
    val bounds: Rect
)

internal fun ShelfEntry.dropTarget(): ShelfDropTarget = when (this) {
    is ShelfEntry.Book -> ShelfDropTarget(key, book.id, null)
    is ShelfEntry.Collection -> ShelfDropTarget(key, null, collection.id)
}

enum class ShelfDropPlacement { BEFORE, MERGE, AFTER }

data class ShelfDrop(
    val target: ShelfDropTarget,
    val placement: ShelfDropPlacement
)

private fun findShelfDropRegion(
    pointer: Offset,
    sourceBookId: Long,
    regions: List<ShelfDropRegion>,
    horizontal: Boolean
): ShelfDropRegion? {
    regions.firstOrNull {
        it.target.bookId == sourceBookId && it.bounds.contains(pointer)
    }?.let { return it }
    regions.firstOrNull {
        it.target.bookId != sourceBookId && it.bounds.contains(pointer)
    }?.let { return it }
    if (!horizontal) return null
    val row = regions.filter { pointer.y >= it.bounds.top && pointer.y < it.bounds.bottom }
    if (row.isEmpty() ||
        pointer.x < row.minOf { it.bounds.left } ||
        pointer.x > row.maxOf { it.bounds.right }
    ) return null
    return row.minWithOrNull(
        compareBy<ShelfDropRegion> { abs(pointer.x - it.bounds.center.x) }
            .thenBy { if (it.target.bookId == sourceBookId) 0 else 1 }
    )
}

internal data class ShelfDragResult(
    val source: BookEntity,
    val drop: ShelfDrop?,
    val showLongPressMenu: Boolean
)

fun findShelfDrop(
    pointer: Offset,
    sourceBookId: Long,
    regions: List<ShelfDropRegion>,
    horizontal: Boolean,
    allowMerge: Boolean
): ShelfDrop? {
    val region = findShelfDropRegion(pointer, sourceBookId, regions, horizontal) ?: return null
    if (region.target.bookId == sourceBookId) return null
    val direct = region.bounds.contains(pointer)
    val fraction = if (horizontal) {
        (pointer.x - region.bounds.left) / region.bounds.width
    } else {
        (pointer.y - region.bounds.top) / region.bounds.height
    }
    val placement = when {
        !direct -> if (pointer.x < region.bounds.center.x) {
            ShelfDropPlacement.BEFORE
        } else {
            ShelfDropPlacement.AFTER
        }
        !allowMerge && fraction < 0.5f -> ShelfDropPlacement.BEFORE
        !allowMerge -> ShelfDropPlacement.AFTER
        fraction < 0.25f -> ShelfDropPlacement.BEFORE
        fraction > 0.75f -> ShelfDropPlacement.AFTER
        else -> ShelfDropPlacement.MERGE
    }
    return ShelfDrop(region.target, placement)
}

fun shelfEdgeScrollDirection(
    dragPointerY: Float,
    viewport: Rect
): Int = when {
    dragPointerY <= viewport.top + viewport.height * 0.10f -> -1
    dragPointerY >= viewport.top + viewport.height * 0.90f -> 1
    else -> 0
}

@Stable
internal class ShelfCollectionDragState {
    private data class Registration(val owner: Any, val region: ShelfDropRegion)

    var sourceBook by mutableStateOf<BookEntity?>(null)
        private set
    var dragBounds by mutableStateOf(Rect.Zero)
        private set
    var activeDrop by mutableStateOf<ShelfDrop?>(null)
        private set
    var autoScrollDirection by mutableStateOf(0)
        private set

    private val regions = mutableStateMapOf<String, Registration>()
    private var pointer = Offset.Zero
    private var dragActivated = false
    private var movedOneCoverDistance = false
    private var sourceBounds = Rect.Zero
    private var dragOffset = Offset.Zero
    private var horizontal = true
    private var allowMerge = true
    private var viewport = Rect.Zero
    private var pendingDrop: ShelfDrop? = null

    fun register(target: ShelfDropTarget, bounds: Rect, owner: Any) {
        val book = sourceBook
        if (book != null &&
            target.entryKey == activeDrop?.target?.entryKey &&
            findShelfDrop(
                pointer,
                book.id,
                regions.values.map(Registration::region),
                horizontal,
                allowMerge
            ) == activeDrop
        ) {
            pendingDrop = activeDrop
        }
        regions[target.entryKey] = Registration(owner, ShelfDropRegion(target, bounds))
        if (book != null) updateTargets()
    }

    fun unregister(entryKey: String, owner: Any) {
        if (regions[entryKey]?.owner !== owner) return
        regions.remove(entryKey)
        if (activeDrop?.target?.entryKey == entryKey) activeDrop = null
    }

    fun setViewport(bounds: Rect) {
        viewport = bounds
        updateTargets()
    }

    fun begin(
        book: BookEntity,
        start: Offset,
        coverBounds: Rect,
        horizontal: Boolean,
        allowMerge: Boolean
    ) {
        pendingDrop = null
        sourceBook = book
        pointer = start
        sourceBounds = coverBounds
        dragBounds = coverBounds
        dragOffset = Offset.Zero
        this.horizontal = horizontal
        this.allowMerge = allowMerge
        dragActivated = false
        movedOneCoverDistance = false
        updateTargets()
    }

    fun dragBy(delta: Offset, minDistancePx: Float) {
        pendingDrop = null
        pointer += delta
        dragOffset += delta
        dragActivated = dragActivated || dragOffset.getDistance() >= minDistancePx
        movedOneCoverDistance = movedOneCoverDistance ||
            kotlin.math.abs(dragOffset.x) >= sourceBounds.width ||
            kotlin.math.abs(dragOffset.y) >= sourceBounds.height
        dragBounds = Rect(
            sourceBounds.left + dragOffset.x,
            sourceBounds.top + dragOffset.y,
            sourceBounds.right + dragOffset.x,
            sourceBounds.bottom + dragOffset.y
        )
        updateTargets()
    }

    fun finish(): ShelfDragResult? {
        val source = sourceBook ?: return null
        val drop = activeDrop.takeIf { dragActivated }
        val result = ShelfDragResult(
            source = source,
            drop = drop,
            showLongPressMenu = drop == null && !movedOneCoverDistance
        )
        cancel()
        return result
    }

    fun cancel() {
        sourceBook = null
        activeDrop = null
        autoScrollDirection = 0
        pendingDrop = null
        dragActivated = false
        movedOneCoverDistance = false
        dragOffset = Offset.Zero
    }

    private fun updateTargets() {
        val book = sourceBook ?: return
        val registeredRegions = regions.values.map(Registration::region)
        val pointerRegion = findShelfDropRegion(pointer, book.id, registeredRegions, horizontal)
        val drop = findShelfDrop(
            pointer,
            book.id,
            registeredRegions,
            horizontal,
            allowMerge
        )
        when {
            drop != null -> {
                pendingDrop = null
                activeDrop = drop
            }
            pointerRegion?.target?.bookId == book.id -> pendingDrop = null
            pendingDrop != null -> activeDrop = pendingDrop
            else -> {
                activeDrop = null
            }
        }
        autoScrollDirection = if (dragActivated) {
            shelfEdgeScrollDirection(pointer.y, viewport)
        } else {
            0
        }
    }
}

internal fun Modifier.collectionDragSource(
    book: BookEntity,
    bounds: () -> Rect,
    coverBounds: () -> Rect,
    horizontal: Boolean,
    allowMerge: Boolean,
    enabled: Boolean,
    pinnableContainer: PinnableContainer?,
    state: ShelfCollectionDragState,
    onDrop: (BookEntity, ShelfDrop) -> Unit,
    onLongPressOnly: (Rect) -> Unit
): Modifier = pointerInput(book, enabled, horizontal, allowMerge, pinnableContainer) {
    if (!enabled) return@pointerInput
    var pinnedHandle: PinnableContainer.PinnedHandle? = null
    detectDragGesturesAfterLongPress(
        onDragStart = { local ->
            pinnedHandle = pinnableContainer?.pin()
            state.begin(
                book = book,
                start = bounds().topLeft + local,
                coverBounds = coverBounds(),
                horizontal = horizontal,
                allowMerge = allowMerge
            )
        },
        onDrag = { change, amount ->
            change.consume()
            state.dragBy(amount, viewConfiguration.touchSlop)
        },
        onDragEnd = {
            val result = state.finish()
            pinnedHandle?.release()
            pinnedHandle = null
            when {
                result == null -> Unit
                result.drop != null -> onDrop(result.source, result.drop)
                result.showLongPressMenu -> onLongPressOnly(bounds())
            }
        },
        onDragCancel = {
            state.cancel()
            pinnedHandle?.release()
            pinnedHandle = null
        }
    )
}

@Composable
internal fun ShelfAutoScrollEffect(
    dragState: ShelfCollectionDragState,
    scrollState: ScrollableState,
    preserveScrollPosition: () -> Unit
) {
    val step = with(LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(dragState.activeDrop) {
        if (dragState.sourceBook != null) preserveScrollPosition()
    }
    LaunchedEffect(dragState.autoScrollDirection) {
        val direction = dragState.autoScrollDirection
        if (direction == 0) return@LaunchedEffect
        while (dragState.autoScrollDirection == direction) {
            if (scrollState.scrollBy(direction * step) == 0f) break
            withFrameNanos { }
        }
    }
}
