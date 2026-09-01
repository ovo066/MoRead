package com.mozhi.reader.feature.bookshelf

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
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

fun findShelfDropTarget(
    pointer: Offset,
    sourceBookId: Long,
    regions: List<ShelfDropRegion>
): ShelfDropTarget? = regions.firstOrNull { region ->
    region.bounds.contains(pointer) && region.target.bookId != sourceBookId
}?.target

@Stable
internal class ShelfCollectionDragState {
    var sourceBook by mutableStateOf<BookEntity?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    var activeTarget by mutableStateOf<ShelfDropTarget?>(null)
        private set

    private val regions = mutableStateMapOf<String, ShelfDropRegion>()
    private var distance = 0f

    fun register(target: ShelfDropTarget, bounds: Rect) {
        regions[target.entryKey] = ShelfDropRegion(target, bounds)
    }

    fun unregister(entryKey: String) {
        regions.remove(entryKey)
        if (activeTarget?.entryKey == entryKey) activeTarget = null
    }

    fun begin(book: BookEntity, start: Offset) {
        sourceBook = book
        pointer = start
        distance = 0f
    }

    fun dragBy(delta: Offset) {
        pointer += delta
        distance += delta.getDistance()
        activeTarget = sourceBook?.let { book ->
            findShelfDropTarget(pointer, book.id, regions.values.toList())
        }
    }

    fun finish(minDistancePx: Float): Pair<BookEntity, ShelfDropTarget>? {
        val source = sourceBook
        val target = activeTarget
        val result = if (source != null && target != null && distance >= minDistancePx) {
            source to target
        } else {
            null
        }
        cancel()
        return result
    }

    fun cancel() {
        sourceBook = null
        activeTarget = null
        distance = 0f
    }
}

internal fun Modifier.collectionDragSource(
    book: BookEntity,
    bounds: () -> Rect,
    enabled: Boolean,
    state: ShelfCollectionDragState,
    onDrop: (BookEntity, ShelfDropTarget) -> Unit,
    onLongPressOnly: (Rect) -> Unit
): Modifier = pointerInput(book, enabled) {
    if (!enabled) return@pointerInput
    detectDragGesturesAfterLongPress(
        onDragStart = { local -> state.begin(book, bounds().topLeft + local) },
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
