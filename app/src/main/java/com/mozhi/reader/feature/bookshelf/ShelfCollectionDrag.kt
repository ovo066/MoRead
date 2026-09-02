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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.database.entity.BookEntity

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

fun findShelfDrop(
    pointer: Offset,
    sourceBookId: Long,
    regions: List<ShelfDropRegion>,
    horizontal: Boolean,
    allowMerge: Boolean
): ShelfDrop? {
    val region = regions.firstOrNull {
        it.bounds.contains(pointer) && it.target.bookId != sourceBookId
    } ?: return null
    val fraction = if (horizontal) {
        (pointer.x - region.bounds.left) / region.bounds.width
    } else {
        (pointer.y - region.bounds.top) / region.bounds.height
    }
    val placement = when {
        !allowMerge && fraction < 0.5f -> ShelfDropPlacement.BEFORE
        !allowMerge -> ShelfDropPlacement.AFTER
        fraction < 0.25f -> ShelfDropPlacement.BEFORE
        fraction > 0.75f -> ShelfDropPlacement.AFTER
        else -> ShelfDropPlacement.MERGE
    }
    return ShelfDrop(region.target, placement)
}

fun shelfEdgeScrollDirection(
    dragBounds: Rect,
    viewport: Rect
): Int = when {
    dragBounds.top <= viewport.top + viewport.height * 0.10f -> -1
    dragBounds.bottom >= viewport.top + viewport.height * 0.90f -> 1
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
    private var distance = 0f
    private var sourceBounds = Rect.Zero
    private var dragOffset = Offset.Zero
    private var horizontal = true
    private var allowMerge = true
    private var viewport = Rect.Zero
    private var pendingTargetBounds: Rect? = null

    fun register(target: ShelfDropTarget, bounds: Rect, owner: Any) {
        val book = sourceBook
        val previousBounds = regions[target.entryKey]?.region?.bounds
        val pointerOnSource = book != null && regions.values.any {
            it.region.target.bookId == book.id && it.region.bounds.contains(pointer)
        }
        when {
            book != null && target.bookId == book.id -> pendingTargetBounds = null
            target.entryKey == activeDrop?.target?.entryKey &&
                previousBounds?.contains(pointer) == true &&
                !pointerOnSource -> pendingTargetBounds = previousBounds
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
        pendingTargetBounds = null
        sourceBook = book
        pointer = start
        sourceBounds = coverBounds
        dragBounds = coverBounds
        dragOffset = Offset.Zero
        this.horizontal = horizontal
        this.allowMerge = allowMerge
        distance = 0f
        updateTargets()
    }

    fun dragBy(delta: Offset) {
        pointer += delta
        dragOffset += delta
        dragBounds = Rect(
            sourceBounds.left + dragOffset.x,
            sourceBounds.top + dragOffset.y,
            sourceBounds.right + dragOffset.x,
            sourceBounds.bottom + dragOffset.y
        )
        distance += delta.getDistance()
        updateTargets()
    }

    fun finish(minDistancePx: Float): Pair<BookEntity, ShelfDrop>? {
        val source = sourceBook
        val drop = activeDrop
        val result = if (source != null && drop != null && distance >= minDistancePx) {
            source to drop
        } else {
            null
        }
        cancel()
        return result
    }

    fun cancel() {
        sourceBook = null
        activeDrop = null
        autoScrollDirection = 0
        pendingTargetBounds = null
        distance = 0f
        dragOffset = Offset.Zero
    }

    private fun updateTargets() {
        val book = sourceBook ?: return
        val registeredRegions = regions.values.map(Registration::region)
        val drop = findShelfDrop(
            pointer,
            book.id,
            registeredRegions,
            horizontal,
            allowMerge
        )
        val pointerOnSource = registeredRegions.any {
            it.target.bookId == book.id && it.bounds.contains(pointer)
        }
        when {
            drop != null -> {
                pendingTargetBounds = null
                activeDrop = drop
            }
            pointerOnSource || pendingTargetBounds?.contains(pointer) == true -> Unit
            else -> {
                pendingTargetBounds = null
                activeDrop = null
            }
        }
        autoScrollDirection = shelfEdgeScrollDirection(dragBounds, viewport)
    }
}

internal fun Modifier.collectionDragSource(
    book: BookEntity,
    bounds: () -> Rect,
    coverBounds: () -> Rect,
    horizontal: Boolean,
    allowMerge: Boolean,
    enabled: Boolean,
    state: ShelfCollectionDragState,
    onDrop: (BookEntity, ShelfDrop) -> Unit,
    onLongPressOnly: (Rect) -> Unit
): Modifier = pointerInput(book, enabled, horizontal, allowMerge) {
    if (!enabled) return@pointerInput
    detectDragGesturesAfterLongPress(
        onDragStart = { local ->
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
            state.dragBy(amount)
        },
        onDragEnd = {
            val result = state.finish(viewConfiguration.touchSlop)
            if (result == null) onLongPressOnly(bounds()) else onDrop(result.first, result.second)
        },
        onDragCancel = state::cancel
    )
}

@Composable
internal fun ShelfAutoScrollEffect(
    dragState: ShelfCollectionDragState,
    scrollState: ScrollableState
) {
    val step = with(LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(dragState.autoScrollDirection) {
        val direction = dragState.autoScrollDirection
        if (direction == 0) return@LaunchedEffect
        while (dragState.autoScrollDirection == direction) {
            if (scrollState.scrollBy(direction * step) == 0f) break
            withFrameNanos { }
        }
    }
}
