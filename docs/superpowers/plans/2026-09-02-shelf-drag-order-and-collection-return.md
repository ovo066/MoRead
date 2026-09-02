# Shelf Drag Ordering and Collection Return Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让书架书籍以原尺寸封面拖动，支持合集中心合并、前后持久排序、边缘自动滚动，并修复合集立即关闭和详情返回原合集。

**Architecture:** 书架总顺序以 DataStore 中的书籍 ID 列表保存，Room 的 `collectionOrder` 继续只管理合集内部。现有 `ShelfCollectionDragState` 扩展为几何与落点状态机，`BookshelfScreen` 将纯排序结果用于即时预览和持久写入；合集返回只保存可恢复的合集 ID，不修改导航路由。

**Tech Stack:** Kotlin, Jetpack Compose, Material3, Preferences DataStore, Coroutines, JUnit 4, Android Gradle Plugin/R8

**Spec:** `docs/superpowers/specs/2026-09-02-shelf-drag-order-and-collection-return-design.md`

## Global Constraints

- 合集系统继续独立于分组系统；不得读写 `ShelfGroupEntity` 来表达合集或书架顺序。
- Android 8.0 及以上保持可用。
- 不增加依赖，不升级 Room schema，不增加数据库迁移。
- UI 复用现有 `BookCover`、`CompactBookArtwork`、主题色、圆角、`IconButton` 和 `ModalBottomSheet`；不得复制参考项目 UI。
- Ponytail `full`：复用现有文件与接口，不创建通用拖拽框架、导航参数协议、repository 抽象或额外状态层。
- 手动顺序、阅读时间锚点和“阅读后自动前移”开关保存在 DataStore；开关默认开启，新书排在已保存顺序之前，过滤排序只替换可见槽位，置顶操作把目标移动到首位。
- 开关开启时，只有 `lastReadAt` 晚于上次手动排序锚点的新阅读才移动到非置顶区前方；关闭时阅读时间不参与最终排序。
- 网格落点沿 X 轴、列表落点沿 Y 轴：前 25%=`BEFORE`，中间 50%=`MERGE`，后 25%=`AFTER`。
- 自动滚动只在拖动封面进入整个书架视口上方或下方 10% 时触发（底部阈值为整体高度 90%），每帧 `12.dp`。
- 合集详情弹层第一帧即可由关闭按钮或系统返回关闭；合集内详情返回后恢复相同合集。
- 只修改并运行 `BookCollectionModelsTest` 与 `ShelfCollectionDragTest`；不增加 Compose UI 测试。
- Tasks 1-2 不运行 Gradle，只执行 `git diff --check`。Task 3 在全部代码、任务审查完成后用一次 Gradle 调用运行两类聚焦测试并构建 `performance` APK；禁止在小修改之间反复编译。
- 最终 APK 必须是 `app/build/outputs/apk/performance/app-performance.apk`，该 build type 继承 release 的 R8 和资源收缩并使用 debug 签名。

---

### Task 1: Persist and compute shelf and collection-member order

**Files:**
- Modify: `app/src/main/java/com/mozhi/reader/core/datastore/ReaderSettingsRepository.kt:81-170,180-281,758-760,1077-1150`
- Modify: `app/src/main/java/com/mozhi/reader/core/database/dao/ShelfOrganizationDao.kt:28-78`
- Modify: `app/src/main/java/com/mozhi/reader/core/library/ShelfOrganizationRepository.kt:50-63`
- Modify: `app/src/main/java/com/mozhi/reader/feature/bookshelf/BookCollectionModels.kt:1-45`
- Modify: `app/src/main/java/com/mozhi/reader/feature/bookshelf/BookshelfViewModel.kt:112-156,267-278,359-424`
- Test: `app/src/test/java/com/mozhi/reader/feature/bookshelf/BookCollectionModelsTest.kt`

**Interfaces:**
- Produces: `ReaderSettings.shelfBookOrder: List<Long>`, `shelfBookOrderReadAnchor: Long`, `readingOrderAffectsShelf: Boolean`
- Produces: `ReaderSettingsRepository.setShelfBookOrder(value: List<Long>, readAnchor: Long): Unit`
- Produces: `ReaderSettingsRepository.setReadingOrderAffectsShelf(value: Boolean): Unit`
- Produces: `List<BookEntity>.orderedForShelf(savedOrder: List<Long>, readingOrderAffectsShelf: Boolean, readAnchor: Long): List<BookEntity>`
- Produces: `reorderShelfEntries(entries: List<ShelfEntry>, sourceBookId: Long, targetKey: String, after: Boolean): List<ShelfEntry>`
- Produces: `mergeVisibleShelfOrder(allBookIds: List<Long>, visibleBookIds: List<Long>): List<Long>`
- Produces: `reorderCollectionMembers(books: List<BookEntity>, sourceBookId: Long, targetBookId: Long, after: Boolean): List<BookEntity>`
- Produces: `BookshelfViewModel.saveShelfOrder(visibleBookIds: List<Long>): Unit`
- Produces: `BookshelfViewModel.reorderCollectionBooks(collectionId: Long, bookIds: List<Long>): Unit`
- Produces: `ShelfOrganizationRepository.reorderCollectionBooks(collectionId: Long, bookIds: List<Long>): Unit`
- Consumes: existing `ShelfEntry`, `BookEntity`, `ReaderSettingsRepository.settings`, `LibraryRepository.setPinned`

- [ ] **Step 1: Add focused failing checks for ordering semantics**

Extend the existing `book` helper so the tests can set timestamps:

```kotlin
private fun book(
    id: Long,
    collectionId: Long? = null,
    order: Int = 0,
    importedAt: Long = id,
    lastReadAt: Long = 0,
    pinnedAt: Long = 0
) = BookEntity(
    id = id,
    title = "书$id",
    author = "",
    coverPath = null,
    epubPath = "/$id.epub",
    sourceType = BookSourceType.EPUB,
    importedAt = importedAt,
    totalChapters = 1,
    lastReadAt = lastReadAt,
    pinnedAt = pinnedAt,
    collectionId = collectionId,
    collectionOrder = order
)
```

Add exactly these five tests to `BookCollectionModelsTest`:

```kotlin
@Test
fun readingOrderCanBeDisabledBeforeAnyManualOrder() {
    val read = book(1, importedAt = 1, lastReadAt = 20)
    val imported = book(2, importedAt = 10)

    assertTrue(ReaderSettings().readingOrderAffectsShelf)
    assertEquals(
        listOf(1L, 2L),
        listOf(read, imported).orderedForShelf(emptyList(), true, 0).map(BookEntity::id)
    )
    assertEquals(
        listOf(2L, 1L),
        listOf(read, imported).orderedForShelf(emptyList(), false, 0).map(BookEntity::id)
    )
}

@Test
fun onlyReadingAfterTheManualAnchorMovesAheadOfSavedOrder() {
    val beforeAnchor = book(1, importedAt = 1, lastReadAt = 20)
    val saved = book(2, importedAt = 10)
    val newImport = book(3, importedAt = 30)
    val newlyRead = book(4, importedAt = 4, lastReadAt = 21)

    assertEquals(
        listOf(4L, 3L, 2L, 1L),
        listOf(beforeAnchor, saved, newImport, newlyRead)
            .orderedForShelf(listOf(2, 1, 4), true, readAnchor = 20)
            .map(BookEntity::id)
    )
}

@Test
fun reorderingTreatsACollectionAsOneShelfSlot() {
    val first = ShelfEntry.Book(book(1))
    val collectionEntry = ShelfEntry.Collection(
        collection,
        listOf(book(2, collectionId = 7, order = 0), book(3, collectionId = 7, order = 1))
    )
    val last = ShelfEntry.Book(book(4))
    val entries = listOf(first, collectionEntry, last)

    assertEquals(
        listOf(1L, 4L, 2L, 3L),
        reorderShelfEntries(entries, sourceBookId = 4, targetKey = "collection:7", after = false)
            .flatMap(ShelfEntry::bookIds)
    )
    assertEquals(
        listOf(2L, 3L, 1L, 4L),
        reorderShelfEntries(entries, sourceBookId = 1, targetKey = "collection:7", after = true)
            .flatMap(ShelfEntry::bookIds)
    )
}

@Test
fun filteredReorderOnlyReplacesVisibleGlobalSlots() {
    assertEquals(
        listOf(5L, 2L, 3L, 4L, 1L),
        mergeVisibleShelfOrder(
            allBookIds = listOf(1, 2, 3, 4, 5),
            visibleBookIds = listOf(5, 3, 1)
        )
    )
}

@Test
fun collectionMembersMoveBeforeOrAfterOneAnother() {
    val books = listOf(
        book(1, collectionId = 7, order = 0),
        book(2, collectionId = 7, order = 1),
        book(3, collectionId = 7, order = 2)
    )

    assertEquals(
        listOf(3L, 1L, 2L),
        reorderCollectionMembers(books, sourceBookId = 3, targetBookId = 1, after = false)
            .map(BookEntity::id)
    )
    assertEquals(
        listOf(2L, 3L, 1L),
        reorderCollectionMembers(books, sourceBookId = 1, targetBookId = 3, after = true)
            .map(BookEntity::id)
    )
}
```

Import `ReaderSettings` and `org.junit.Assert.assertTrue` for the default-value assertion.

- [ ] **Step 2: Add the DataStore field and one-string persistence**

Add the three shelf-order settings immediately after `shelfLayout` in `ReaderSettings`:

```kotlin
val shelfLayout: ShelfLayout = ShelfLayout.GRID,
val shelfBookOrder: List<Long> = emptyList(),
val shelfBookOrderReadAnchor: Long = 0L,
val readingOrderAffectsShelf: Boolean = true,
```

Decode it next to `shelfLayout` in the existing `ReaderSettings(...)` mapping:

```kotlin
shelfBookOrder = preferences[Keys.ShelfBookOrder]
    ?.split(',')
    ?.mapNotNull(String::toLongOrNull)
    .orEmpty(),
shelfBookOrderReadAnchor = preferences[Keys.ShelfBookOrderReadAnchor] ?: 0L,
readingOrderAffectsShelf = preferences[Keys.ReadingOrderAffectsShelf] ?: true,
```

Add the setter next to `setShelfLayout`:

```kotlin
suspend fun setShelfBookOrder(value: List<Long>, readAnchor: Long) {
    dataStore.edit {
        it[Keys.ShelfBookOrder] = value.distinct().joinToString(",")
        it[Keys.ShelfBookOrderReadAnchor] = readAnchor
    }
}

suspend fun setReadingOrderAffectsShelf(value: Boolean) {
    dataStore.edit { it[Keys.ReadingOrderAffectsShelf] = value }
}
```

Add the key next to `Keys.ShelfLayout`:

```kotlin
val ShelfBookOrder = stringPreferencesKey("shelf_book_order")
val ShelfBookOrderReadAnchor = longPreferencesKey("shelf_book_order_read_anchor")
val ReadingOrderAffectsShelf = booleanPreferencesKey("reading_order_affects_shelf")
```

- [ ] **Step 3: Add the three pure ordering helpers to `BookCollectionModels.kt`**

Append these functions after `buildShelfEntries`:

```kotlin
internal fun List<BookEntity>.orderedForShelf(
    savedOrder: List<Long>,
    readingOrderAffectsShelf: Boolean,
    readAnchor: Long
): List<BookEntity> {
    val fallback = sortedWith(
        compareByDescending<BookEntity> { it.pinnedAt }
            .thenByDescending { if (readingOrderAffectsShelf) it.lastReadAt else 0L }
            .thenByDescending { it.importedAt }
    )
    if (savedOrder.isEmpty()) return fallback
    val byId = associateBy(BookEntity::id)
    val savedIds = savedOrder.toHashSet()
    val base = fallback.filterNot { it.id in savedIds } + savedOrder.mapNotNull(byId::get)
    val pinned = base.filter { it.pinnedAt > 0L }
    val unpinned = base.filter { it.pinnedAt == 0L }
    if (!readingOrderAffectsShelf) return pinned + unpinned
    val newlyRead = unpinned.filter { it.lastReadAt > readAnchor }
        .sortedByDescending(BookEntity::lastReadAt)
    val newlyReadIds = newlyRead.mapTo(hashSetOf(), BookEntity::id)
    return pinned + newlyRead + unpinned.filterNot { it.id in newlyReadIds }
}

internal fun reorderShelfEntries(
    entries: List<ShelfEntry>,
    sourceBookId: Long,
    targetKey: String,
    after: Boolean
): List<ShelfEntry> {
    val sourceIndex = entries.indexOfFirst {
        it is ShelfEntry.Book && it.book.id == sourceBookId
    }
    if (sourceIndex < 0) return entries
    val moved = entries[sourceIndex]
    val reordered = entries.toMutableList().apply { removeAt(sourceIndex) }
    val targetIndex = reordered.indexOfFirst { it.key == targetKey }
    if (targetIndex < 0) return entries
    reordered.add(targetIndex + if (after) 1 else 0, moved)
    return reordered
}

internal fun mergeVisibleShelfOrder(
    allBookIds: List<Long>,
    visibleBookIds: List<Long>
): List<Long> {
    val visible = visibleBookIds.toHashSet()
    val reordered = visibleBookIds.iterator()
    return allBookIds.map { id -> if (id in visible) reordered.next() else id }
}

internal fun reorderCollectionMembers(
    books: List<BookEntity>,
    sourceBookId: Long,
    targetBookId: Long,
    after: Boolean
): List<BookEntity> {
    val sourceIndex = books.indexOfFirst { it.id == sourceBookId }
    if (sourceIndex < 0) return books
    val moved = books[sourceIndex]
    val reordered = books.toMutableList().apply { removeAt(sourceIndex) }
    val targetIndex = reordered.indexOfFirst { it.id == targetBookId }
    if (targetIndex < 0) return books
    reordered.add(targetIndex + if (after) 1 else 0, moved)
    return reordered
}
```

- [ ] **Step 4: Apply and write the order in `BookshelfViewModel`**

Add these fields to the state models:

```kotlin
// BookshelfUiState, after layout
val readingOrderAffectsShelf: Boolean = true,

// BookshelfBaseState, after layout
val shelfBookOrder: List<Long>,
val shelfBookOrderReadAnchor: Long,
val readingOrderAffectsShelf: Boolean,
```

Populate all three base-state order settings from `ReaderSettings`:

```kotlin
BookshelfBaseState(
    books = books,
    layout = settings.shelfLayout,
    shelfBookOrder = settings.shelfBookOrder,
    shelfBookOrderReadAnchor = settings.shelfBookOrderReadAnchor,
    readingOrderAffectsShelf = settings.readingOrderAffectsShelf,
    filter = filter,
    recentBook = books.filter { it.lastReadAt > 0L }.maxByOrNull(BookEntity::lastReadAt),
    recentChapterTitle = chapterTitle,
    isImporting = isImporting
)
```

At the start of the `uiState` combine body, compute `orderedBooks`, then use it for both visible and all books:

```kotlin
val orderedBooks = base.books.orderedForShelf(
    savedOrder = base.shelfBookOrder,
    readingOrderAffectsShelf = base.readingOrderAffectsShelf,
    readAnchor = base.shelfBookOrderReadAnchor
)
BookshelfUiState(
    books = filterShelfBooks(orderedBooks, organization.tagRefs, effectiveFilter),
    allBooks = orderedBooks,
    readingOrderAffectsShelf = base.readingOrderAffectsShelf,
    layout = base.layout,
    filter = effectiveFilter,
    tags = organization.tags,
    groups = organization.groups,
    groupCounts = organization.groupCounts,
    tagCounts = organization.tagCounts,
    tagRefs = organization.tagRefs,
    collections = organization.collections,
    totalBooks = base.books.size,
    recentBook = base.recentBook,
    recentChapterTitle = base.recentChapterTitle,
    isImporting = base.isImporting,
    selectionActive = isSelectionActive,
    selectedBookIds = selected.intersect(base.books.map(BookEntity::id).toSet())
)
```

Delete the old private `sortedForShelf` function. Add this public ViewModel action:

```kotlin
fun saveShelfOrder(visibleBookIds: List<Long>) {
    val allBooks = uiState.value.allBooks
    val allBookIds = allBooks.map(BookEntity::id)
    val order = mergeVisibleShelfOrder(allBookIds, visibleBookIds)
    val readAnchor = allBooks.maxOfOrNull(BookEntity::lastReadAt) ?: 0L
    viewModelScope.launch { settingsRepository.setShelfBookOrder(order, readAnchor) }
}

fun setReadingOrderAffectsShelf(value: Boolean) {
    viewModelScope.launch { settingsRepository.setReadingOrderAffectsShelf(value) }
}
```

Preserve pin semantics with one private helper:

```kotlin
private suspend fun moveShelfBooksToFront(bookIds: Set<Long>) {
    val books = uiState.value.allBooks
    val current = books.map(BookEntity::id)
    settingsRepository.setShelfBookOrder(
        value = current.filter(bookIds::contains) + current.filterNot(bookIds::contains),
        readAnchor = books.maxOfOrNull(BookEntity::lastReadAt) ?: 0L
    )
}
```

Call `moveShelfBooksToFront(setOf(book.id))` after a successful single-book pin and `moveShelfBooksToFront(ids)` after a successful batch pin. Do not move IDs when unpinning.

- [ ] **Step 5: Persist collection-member order through the existing Room column**

Add this transaction beside the existing collection transactions in `ShelfOrganizationDao`:

```kotlin
@Transaction
suspend fun reorderCollectionBooks(collectionId: Long, bookIds: List<Long>) {
    bookIds.forEachIndexed { order, bookId ->
        setBookCollection(bookId, collectionId, order)
    }
}
```

Expose it in `ShelfOrganizationRepository`:

```kotlin
suspend fun reorderCollectionBooks(collectionId: Long, bookIds: List<Long>) =
    dao.reorderCollectionBooks(collectionId, bookIds)
```

Expose the one UI action in `BookshelfViewModel`:

```kotlin
fun reorderCollectionBooks(collectionId: Long, bookIds: List<Long>) {
    viewModelScope.launch { shelfRepository.reorderCollectionBooks(collectionId, bookIds) }
}
```

Do not add an entity field, database version, migration, repository abstraction, or error wrapper.

- [ ] **Step 6: Perform static verification only and commit**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only the five Task 1 production files and `BookCollectionModelsTest` are modified. Per Global Constraints, leave both named test classes unexecuted until Task 3.

Commit:

```bash
git add app/src/main/java/com/mozhi/reader/core/datastore/ReaderSettingsRepository.kt \
  app/src/main/java/com/mozhi/reader/core/database/dao/ShelfOrganizationDao.kt \
  app/src/main/java/com/mozhi/reader/core/library/ShelfOrganizationRepository.kt \
  app/src/main/java/com/mozhi/reader/feature/bookshelf/BookCollectionModels.kt \
  app/src/main/java/com/mozhi/reader/feature/bookshelf/BookshelfViewModel.kt \
  app/src/test/java/com/mozhi/reader/feature/bookshelf/BookCollectionModelsTest.kt
git commit -m "feat: persist shelf and collection ordering"
```

---

### Task 2: Replace merge-only dragging with full shelf movement and restore collection context

**Files:**
- Modify: `app/src/main/java/com/mozhi/reader/feature/bookshelf/ShelfCollectionDrag.kt`
- Modify: `app/src/main/java/com/mozhi/reader/feature/bookshelf/BookCollectionComponents.kt:90-250,332-430`
- Modify: `app/src/main/java/com/mozhi/reader/feature/bookshelf/BookshelfScreen.kt:142-418,477-568,665-825,1365-1515`
- Test: `app/src/test/java/com/mozhi/reader/feature/bookshelf/ShelfCollectionDragTest.kt`

**Interfaces:**
- Consumes: Task 1 `reorderShelfEntries(...)` and `BookshelfViewModel.saveShelfOrder(...)`
- Produces: `ShelfDropPlacement`, `ShelfDrop`, `findShelfDrop(..., allowMerge)`, `shelfEdgeScrollDirection(dragBounds, viewport)`
- Produces: `ShelfCollectionDragState.dragBounds`, `activeDrop`, `autoScrollDirection`, `setViewport(...)`
- Produces: `Modifier.collectionDragSource(..., coverBounds, horizontal, allowMerge, onDrop)`
- Consumes: Task 1 `BookshelfUiState.readingOrderAffectsShelf` and `BookshelfViewModel.setReadingOrderAffectsShelf(...)`
- Preserves: existing `BookshelfScreen.onOpenBookDetail` route callback and existing collection repository methods

- [ ] **Step 1: Replace merge-only tests with exact drop-zone, geometry, and edge-threshold checks**

Keep the existing source-skip assertion, but update it to compare `ShelfDrop` values. Add grid/list zone assertions against a target `Rect(100f, 100f, 200f, 300f)`:

```kotlin
assertEquals(
    ShelfDrop(target, ShelfDropPlacement.BEFORE),
    findShelfDrop(Offset(110f, 150f), 1, listOf(region), horizontal = true, allowMerge = true)
)
assertEquals(
    ShelfDrop(target, ShelfDropPlacement.MERGE),
    findShelfDrop(Offset(150f, 150f), 1, listOf(region), horizontal = true, allowMerge = true)
)
assertEquals(
    ShelfDrop(target, ShelfDropPlacement.AFTER),
    findShelfDrop(Offset(190f, 150f), 1, listOf(region), horizontal = true, allowMerge = true)
)
assertEquals(
    ShelfDrop(target, ShelfDropPlacement.BEFORE),
    findShelfDrop(Offset(150f, 120f), 1, listOf(region), horizontal = false, allowMerge = true)
)
assertEquals(
    ShelfDrop(target, ShelfDropPlacement.AFTER),
    findShelfDrop(Offset(150f, 280f), 1, listOf(region), horizontal = false, allowMerge = true)
)
assertEquals(
    ShelfDrop(target, ShelfDropPlacement.BEFORE),
    findShelfDrop(Offset(140f, 150f), 1, listOf(region), horizontal = true, allowMerge = false)
)
assertEquals(
    ShelfDrop(target, ShelfDropPlacement.AFTER),
    findShelfDrop(Offset(160f, 150f), 1, listOf(region), horizontal = true, allowMerge = false)
)
```

The five total-shelf assertions pass `allowMerge = true`. The two `allowMerge = false` assertions prove that collection-internal dragging divides the target at 50% and never returns `MERGE`.

Update the drag-state test to begin with separate pointer and cover bounds, then prove exact translation:

```kotlin
state.begin(
    book = book,
    start = Offset(20f, 20f),
    coverBounds = Rect(10f, 10f, 110f, 160f),
    horizontal = true,
    allowMerge = true
)
state.dragBy(Offset(30f, 40f))
assertEquals(Rect(40f, 50f, 140f, 200f), state.dragBounds)
```

Add one threshold test using viewport `Rect(0f, 0f, 500f, 1000f)`; the top boundary is 100 and bottom boundary is 900:

```kotlin
assertEquals(-1, shelfEdgeScrollDirection(Rect(0f, 99f, 50f, 199f), viewport))
assertEquals(0, shelfEdgeScrollDirection(Rect(0f, 101f, 50f, 201f), viewport))
assertEquals(0, shelfEdgeScrollDirection(Rect(0f, 799f, 50f, 899f), viewport))
assertEquals(1, shelfEdgeScrollDirection(Rect(0f, 801f, 50f, 901f), viewport))
```

- [ ] **Step 2: Implement the drop and edge pure functions in `ShelfCollectionDrag.kt`**

Replace `findShelfDropTarget` with these types and function:

```kotlin
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
```

Add the exact edge function:

```kotlin
fun shelfEdgeScrollDirection(
    dragBounds: Rect,
    viewport: Rect
): Int {
    return when {
        dragBounds.top <= viewport.top + viewport.height * 0.10f -> -1
        dragBounds.bottom >= viewport.top + viewport.height * 0.90f -> 1
        else -> 0
    }
}
```

- [ ] **Step 3: Extend `ShelfCollectionDragState` without introducing another state holder**

Keep the owner-token registration map. Replace `activeTarget` with `activeDrop` and add observable `dragBounds` and `autoScrollDirection`. Store `sourceBounds`, `dragOffset`, `horizontal`, `allowMerge`, and `viewport` privately.

Use these exact public operations:

```kotlin
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
```

`updateTargets()` maps the registered regions once, calls `findShelfDrop(..., horizontal, allowMerge)`, then `shelfEdgeScrollDirection(dragBounds, viewport)`. Call it from `register` while a source exists so scrolling layout changes refresh targets. `cancel()` must clear source, active drop, scroll direction, distance, and drag offset. `finish(minDistancePx)` returns `Pair<BookEntity, ShelfDrop>?` and then cancels.

Change `collectionDragSource` to receive `coverBounds: () -> Rect`, `horizontal: Boolean`, and `allowMerge: Boolean`; on drag start call:

```kotlin
state.begin(
    book = book,
    start = bounds().topLeft + local,
    coverBounds = coverBounds(),
    horizontal = horizontal,
    allowMerge = allowMerge
)
```

- [ ] **Step 4: Render the exact cover and register cover-only target bounds**

Change `CollectionDragOverlay` to accept `origin: Offset = Offset.Zero`. Convert `state.dragBounds.width/height` with `LocalDensity`, offset to `dragBounds.left - origin.x` / `dragBounds.top - origin.y`, and render `CompactBookArtwork` at that exact size. Keep only a subtle `alpha = 0.92f`; remove the hard-coded `84.dp x 116.dp` and scale-up. The total shelf keeps the default zero origin; the sheet passes its content origin.

In `GridBookItem` and `ListBookItem`, record both outer item bounds and artwork bounds. Pass artwork bounds to `collectionDragSource`; keep outer bounds for the existing long-press menu. Set the outer item's alpha to `0f` while `collectionDragState.sourceBook?.id == book.id`; otherwise retain the existing selection alpha.

In `GridCollectionItem` and `ListCollectionItem`, move target registration from the whole text/card container to `CollectionArtwork` bounds. Keep owner-token unregister behavior unchanged. All target highlighting must compare `collectionDragState.activeDrop?.target` with the item's target. Total-shelf book sources pass `allowMerge = true`.

- [ ] **Step 5: Add member-cover dragging to `CollectionContentsSheet`**

Add `onReorderBooks: (List<Long>) -> Unit` to `CollectionContentsSheet`. Create one `ShelfCollectionDragState` keyed by `entry.collection.id`, one `rememberLazyGridState`, and one `Offset` for the sheet content origin. Derive member preview order exactly as the total shelf does:

```kotlin
val previewBooks = remember(entry.books, memberDragState.sourceBook?.id, memberDragState.activeDrop) {
    val sourceId = memberDragState.sourceBook?.id
    val drop = memberDragState.activeDrop
    if (sourceId == null || drop?.target?.bookId == null) {
        entry.books
    } else {
        reorderCollectionMembers(
            entry.books,
            sourceBookId = sourceId,
            targetBookId = drop.target.bookId,
            after = drop.placement == ShelfDropPlacement.AFTER
        )
    }
}
```

Wrap the sheet's existing `Column` in a `Box(Modifier.fillMaxSize())`, record that Box's `boundsInRoot().topLeft`, and draw `CollectionDragOverlay(memberDragState, origin = contentOrigin)` as the last child so it stays above members. Set the members `LazyVerticalGrid` state and viewport:

```kotlin
state = memberGridState,
modifier = Modifier
    .fillMaxWidth()
    .weight(1f)
    .onGloballyPositioned { memberDragState.setViewport(it.boundsInRoot()) }
```

For each preview member, register only the `CompactBookArtwork` bounds with this target:

```kotlin
val target = remember(book.id) {
    ShelfDropTarget("member:${book.id}", bookId = book.id, collectionId = null)
}
```

Use the existing owner-token `DisposableEffect` pattern. Make the source artwork transparent while dragging and attach `collectionDragSource` with identical outer/cover bounds, `horizontal = true`, and `allowMerge = false`. Its drop callback uses authoritative `entry.books` and persists once:

```kotlin
onDrop = { source, drop ->
    val targetId = requireNotNull(drop.target.bookId)
    onReorderBooks(
        reorderCollectionMembers(
            entry.books,
            sourceBookId = source.id,
            targetBookId = targetId,
            after = drop.placement == ShelfDropPlacement.AFTER
        ).map(BookEntity::id)
    )
}
```

Pass `onLongPressOnly = { _ -> }`; ordinary click continues to call `onOpenBook`. Keep the existing three-dot remove menu unchanged. Call the shared `ShelfAutoScrollEffect(memberDragState, memberGridState)` so large collections scroll at the same viewport thresholds.

At the `BookshelfScreen` sheet call site, pass:

```kotlin
onReorderBooks = { bookIds ->
    viewModel.reorderCollectionBooks(entry.collection.id, bookIds)
}
```

- [ ] **Step 6: Wire live preview, reorder/merge actions, and automatic scrolling in `BookshelfScreen`**

Add a private request type so cancelling collection naming cannot persist a candidate move:

```kotlin
private data class CollectionCreateRequest(
    val bookIds: Set<Long>,
    val shelfOrder: List<Long>? = null
)
```

Change `collectionNameRequest` to this type. Derive preview entries from the source and active non-merge drop:

```kotlin
val previewEntries = remember(shelfEntries, collectionDragState.sourceBook?.id, collectionDragState.activeDrop) {
    val sourceId = collectionDragState.sourceBook?.id
    val drop = collectionDragState.activeDrop
    if (sourceId == null || drop == null || drop.placement == ShelfDropPlacement.MERGE) {
        shelfEntries
    } else {
        reorderShelfEntries(
            shelfEntries,
            sourceBookId = sourceId,
            targetKey = drop.target.entryKey,
            after = drop.placement == ShelfDropPlacement.AFTER
        )
    }
}
```

Pass `previewEntries` to `BookGrid` / `BookList`. Replace `onCollectionDrop` with an `onShelfDrop` callback. For every drop, compute candidate entries from the authoritative `shelfEntries`; `MERGE` always places the source after the target before flattening, so the combined slot stays at the target:

```kotlin
val reordered = reorderShelfEntries(
    shelfEntries,
    sourceBookId = source.id,
    targetKey = drop.target.entryKey,
    after = drop.placement != ShelfDropPlacement.BEFORE
)
val bookOrder = reordered.flatMap(ShelfEntry::bookIds)
when (drop.placement) {
    ShelfDropPlacement.BEFORE, ShelfDropPlacement.AFTER ->
        viewModel.saveShelfOrder(bookOrder)
    ShelfDropPlacement.MERGE -> when {
        drop.target.collectionId != null -> {
            viewModel.saveShelfOrder(bookOrder)
            viewModel.addBooksToCollection(drop.target.collectionId, setOf(source.id))
        }
        drop.target.bookId != null -> collectionNameRequest = CollectionCreateRequest(
            bookIds = setOf(source.id, drop.target.bookId),
            shelfOrder = bookOrder
        )
    }
}
```

The multi-select “新建合集” path creates `CollectionCreateRequest(state.selectedBookIds)` without an order. On dialog confirmation, save `request.shelfOrder` first when present, then call `viewModel.createCollection(name, request.bookIds)`.

Create one shared effect in `ShelfCollectionDrag.kt` for total shelf and collection grid states:

```kotlin
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
```

Use `rememberLazyGridState()` / `rememberLazyListState()`, pass each to its lazy container, call `ShelfAutoScrollEffect`, and call `collectionDragState.setViewport(it.boundsInRoot())` from the lazy container's `onGloballyPositioned`. Pass `horizontal = true` for grid book sources and `false` for list book sources.

- [ ] **Step 7: Expose the persisted reading-order option in the existing shelf filter menu**

Thread one callback, `onSetReadingOrderAffectsShelf: (Boolean) -> Unit`, from `BookshelfScreen` through `BookGrid` / `BookList`, `LibraryToolbar`, and `ShelfViewMenu`. At the root pass `viewModel::setReadingOrderAffectsShelf`.

In `ShelfViewMenu`, add one ordinary existing-style menu row immediately after the expandable “阅读状态” section:

```kotlin
MoReadMenuItem(
    text = "阅读后自动前移",
    icon = Icons.Outlined.AutoStories,
    selected = state.readingOrderAffectsShelf,
    onClick = {
        onSetReadingOrderAffectsShelf(!state.readingOrderAffectsShelf)
    }
)
```

This persistent preference is not part of `ShelfFilter.isActive`, so it does not light the filter dot and “清除筛选” does not reset it. Update the toolbar summary's existing no-filter suffix:

```kotlin
if (!searching && !filter.isActive) {
    append(if (state.readingOrderAffectsShelf) " · 阅读后前移" else " · 手动排序")
}
```

- [ ] **Step 8: Make collection dismissal immediate and retain the opened collection across details**

In `CollectionContentsSheet`, import `BackHandler` and `Icons.Outlined.Close`. Inside the `ModalBottomSheet` content, before the `Column`, register:

```kotlin
BackHandler(onBack = onDismiss)
```

Add this button to the title row immediately before the existing “合集操作” menu:

```kotlin
IconButton(onClick = onDismiss) {
    Icon(Icons.Outlined.Close, contentDescription = "关闭合集")
}
```

In `BookshelfScreen`, replace the object state with:

```kotlin
var openCollectionId by rememberSaveable { mutableStateOf<Long?>(null) }
```

Delete the `LaunchedEffect` that clears an opened collection when the current snapshot is empty. Write the collection ID when opening. Derive the sheet entry from the latest `shelfEntries` and `openCollectionId`. The sheet's `onOpenBook` must call only `onOpenBookDetail(bookId, null)`; do not clear the ID. Explicit dismissal and confirmed dissolution clear the ID.

- [ ] **Step 9: Perform static verification only and commit**

Run:

```bash
git diff --check
git status --short
rg -n "84\.dp|116\.dp|openCollection = null|findShelfDropTarget|activeTarget" \
  app/src/main/java/com/mozhi/reader/feature/bookshelf \
  app/src/test/java/com/mozhi/reader/feature/bookshelf
```

Expected: no whitespace errors; the search returns no obsolete drag size/API or old collection object state. Per Global Constraints, defer Gradle to Task 3.

Commit:

```bash
git add app/src/main/java/com/mozhi/reader/feature/bookshelf/ShelfCollectionDrag.kt \
  app/src/main/java/com/mozhi/reader/feature/bookshelf/BookCollectionComponents.kt \
  app/src/main/java/com/mozhi/reader/feature/bookshelf/BookshelfScreen.kt \
  app/src/test/java/com/mozhi/reader/feature/bookshelf/ShelfCollectionDragTest.kt
git commit -m "feat: move books directly across the shelf"
```

---

### Task 3: Run the single verification gate and produce the compressed APK

**Files:**
- Verify: all files changed by Tasks 1-2
- Produce: `app/build/outputs/apk/performance/app-performance.apk`
- Produce: `app/build/outputs/mapping/performance/mapping.txt`

**Interfaces:**
- Consumes: both focused unit test classes and the complete implementation commits
- Produces: one R8/resource-shrunk, debug-signed performance APK plus size and SHA-256 evidence

- [ ] **Step 1: Prepare ignored local build links without modifying tracked files**

From this worktree, link the repository's existing ignored Android setup only when present:

```bash
test -e local.properties || ln -s ../../local.properties local.properties
test -e app/libobjectbox-jni-linux-x64.so || \
  ln -s ../../../app/libobjectbox-jni-linux-x64.so app/libobjectbox-jni-linux-x64.so
git status --short
```

Expected: tracked working tree remains clean; ignored links do not appear.

- [ ] **Step 2: Run both focused tests and build once in one Gradle invocation**

Run exactly once after Tasks 1-2 and their task reviews are complete:

```bash
./gradlew \
  :app:testDebugUnitTest \
  --tests 'com.mozhi.reader.feature.bookshelf.BookCollectionModelsTest' \
  --tests 'com.mozhi.reader.feature.bookshelf.ShelfCollectionDragTest' \
  :app:assemblePerformance \
  --no-daemon \
  --console=plain
```

Expected: `BUILD SUCCESSFUL`; both named test classes pass; no second Gradle run is made when this command succeeds.

- [ ] **Step 3: Verify compression artifacts and record delivery evidence**

Run:

```bash
test -s app/build/outputs/apk/performance/app-performance.apk
test -s app/build/outputs/mapping/performance/mapping.txt
unzip -tqq app/build/outputs/apk/performance/app-performance.apk
stat --printf='%n %s bytes\n' app/build/outputs/apk/performance/app-performance.apk
sha256sum app/build/outputs/apk/performance/app-performance.apk
git status --short
```

Expected: APK and mapping are non-empty, ZIP integrity succeeds, and the worktree contains no uncommitted tracked changes. Record the exact byte size and digest in the report.

- [ ] **Step 4: Commit only if verification required a tracked build fix**

If Steps 2-3 succeed without source edits, create no commit. If the one verification gate exposes a compile/test error, diagnose all errors from that complete output, apply one consolidated minimal fix, repeat the same gate once, and stage from this exact bounded file list (unchanged files are ignored by Git):

```bash
git add \
  app/src/main/java/com/mozhi/reader/core/datastore/ReaderSettingsRepository.kt \
  app/src/main/java/com/mozhi/reader/core/database/dao/ShelfOrganizationDao.kt \
  app/src/main/java/com/mozhi/reader/core/library/ShelfOrganizationRepository.kt \
  app/src/main/java/com/mozhi/reader/feature/bookshelf/BookCollectionModels.kt \
  app/src/main/java/com/mozhi/reader/feature/bookshelf/BookshelfViewModel.kt \
  app/src/main/java/com/mozhi/reader/feature/bookshelf/ShelfCollectionDrag.kt \
  app/src/main/java/com/mozhi/reader/feature/bookshelf/BookCollectionComponents.kt \
  app/src/main/java/com/mozhi/reader/feature/bookshelf/BookshelfScreen.kt \
  app/src/test/java/com/mozhi/reader/feature/bookshelf/BookCollectionModelsTest.kt \
  app/src/test/java/com/mozhi/reader/feature/bookshelf/ShelfCollectionDragTest.kt
git commit -m "fix: finalize shelf drag integration"
```

The report must state whether the repeat was needed and include both command outcomes.
