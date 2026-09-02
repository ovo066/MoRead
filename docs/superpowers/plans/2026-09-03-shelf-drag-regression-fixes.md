# Shelf Drag Regression Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复合集顶部把手首击失效、长按即误滚动、网格列间空隙选错槽位，以及拖远后无落点仍弹出长按菜单四个回归，并重新产出压缩 APK。

**Architecture:** 不新增状态层或手势框架，只修正现有 `ShelfCollectionDragState` 的输入与结束结果：落点函数补同一行空隙的最近封面选择，自动滚动改用手指 Y 坐标并由 touch slop 激活，结束结果把落点与长按菜单分开。合集弹层保留 Material3 原有黑色把手外观，在该把手内部直接调用外部 `onDismiss`，删除误加的关闭叉。

**Tech Stack:** Kotlin, Jetpack Compose, Material3 1.4.0, JUnit 4, Android Gradle Plugin/R8

**Spec:** `docs/superpowers/specs/2026-09-02-shelf-drag-order-and-collection-return-design.md`

## Global Constraints

- 合集继续独立于书架分组；不触碰分组、繁简转换、阅读器导航、Room schema 或迁移。
- Android 8.0 及以上保持可用；不增加或升级依赖。
- 删除标题栏关闭叉；顶部保留现有 `BottomSheetDefaults.DragHandle` 黑色把手外观，点击从第一帧起直接执行 `onDismiss`。
- 自动滚动只在手势位移达到系统 touch slop 后启用，并只按手指 Y 坐标是否达到整个懒布局 viewport 的 10%/90% 判定；长按本身不滚动。
- 网格封面之间的横向空隙按 X 轴距离选择最近目标：靠左目标为 `AFTER`，靠右目标为 `BEFORE`；空隙不产生 `MERGE`，源书占位仍不产生新落点。
- 手势曾沿任一轴移动至少一个起始封面宽/高后，即使最终无落点或回到原位，也不打开长按菜单；轻微移动仍沿用长按菜单。
- Ponytail `full`：只修改 `ShelfCollectionDrag.kt`、`BookCollectionComponents.kt` 和既有 `ShelfCollectionDragTest.kt`；不新增生产文件、抽象层、兜底、日志、Compose UI 测试或测试依赖。
- 四个回归的测试一次性写完，只执行一次 RED。生产代码一次性修改后，用一次 Gradle 调用执行 GREEN 聚焦测试并构建 `performance` APK；不使用 `clean`、`--rerun-tasks` 或 `--no-daemon`。
- 最终 APK 为 `app/build/outputs/apk/performance/app-performance.apk`，继续继承 release 的 R8 与资源收缩并使用 debug 签名。

---

### Task 1: Correct all four drag and collection-sheet regressions in one pass

**Files:**
- Modify: `app/src/main/java/com/mozhi/reader/feature/bookshelf/ShelfCollectionDrag.kt:38-258`
- Modify: `app/src/main/java/com/mozhi/reader/feature/bookshelf/BookCollectionComponents.kt:1-60,353-430`
- Test: `app/src/test/java/com/mozhi/reader/feature/bookshelf/ShelfCollectionDragTest.kt`

**Interfaces:**
- Consumes: existing `ShelfDrop`, `ShelfDropTarget`, `ShelfDropRegion`, `ShelfCollectionDragState`, `CollectionContentsSheet`, and `viewConfiguration.touchSlop`
- Changes: `shelfEdgeScrollDirection(dragPointerY: Float, viewport: Rect): Int`
- Changes: `ShelfCollectionDragState.dragBy(delta: Offset, minDistancePx: Float): Unit`
- Changes: `ShelfCollectionDragState.finish(): ShelfDragResult?`
- Produces: `ShelfDragResult(source: BookEntity, drop: ShelfDrop?, showLongPressMenu: Boolean)`
- Preserves: `collectionDragSource` callbacks `onDrop` and `onLongPressOnly`; all shelf and collection-member callers continue through this single modifier

- [ ] **Step 1: Add all focused regressions before production edits**

In `ShelfCollectionDragTest`, add `assertFalse` and `assertTrue` imports. Add one private `testBook()` helper and replace the three repeated `BookEntity(...)` fixtures already in the state tests with it:

```kotlin
private fun testBook() = BookEntity(
    id = 1,
    title = "书",
    author = "",
    coverPath = null,
    epubPath = "/book.epub",
    sourceType = BookSourceType.EPUB,
    importedAt = 1,
    totalChapters = 1
)
```

Add this gap regression. It catches removal of nearest-target selection or accidental `MERGE` in whitespace:

```kotlin
@Test
fun gridGapUsesTheNearestCoverEdge() {
    val second = ShelfDropTarget("book:2", bookId = 2, collectionId = null)
    val third = ShelfDropTarget("book:3", bookId = 3, collectionId = null)
    val regions = listOf(
        ShelfDropRegion(second, Rect(110f, 0f, 210f, 100f)),
        ShelfDropRegion(third, Rect(220f, 0f, 320f, 100f))
    )

    assertEquals(
        ShelfDrop(second, ShelfDropPlacement.AFTER),
        findShelfDrop(Offset(213f, 50f), 1, regions, horizontal = true, allowMerge = true)
    )
    assertEquals(
        ShelfDrop(third, ShelfDropPlacement.BEFORE),
        findShelfDrop(Offset(217f, 50f), 1, regions, horizontal = true, allowMerge = true)
    )
}
```

Replace the existing edge-threshold test body so it asserts pointer positions rather than cover rectangles:

```kotlin
@Test
fun edgeScrollUsesThePointerAndWholeViewportTenPercentThresholds() {
    val viewport = Rect(0f, 0f, 500f, 1000f)

    assertEquals(-1, shelfEdgeScrollDirection(99f, viewport))
    assertEquals(0, shelfEdgeScrollDirection(101f, viewport))
    assertEquals(0, shelfEdgeScrollDirection(899f, viewport))
    assertEquals(1, shelfEdgeScrollDirection(901f, viewport))
}
```

Add a state regression proving both the motion gate and pointer-based threshold. The initial cover already intersects the bottom trigger zone; old behavior therefore returns `1` before movement and fails this test:

```kotlin
@Test
fun edgeScrollWaitsForRealDragInsteadOfUsingTheCoverEdge() {
    val state = ShelfCollectionDragState()
    state.setViewport(Rect(0f, 0f, 500f, 1000f))
    state.begin(
        book = testBook(),
        start = Offset(50f, 850f),
        coverBounds = Rect(0f, 800f, 100f, 950f),
        horizontal = true,
        allowMerge = true
    )

    assertEquals(0, state.autoScrollDirection)
    state.dragBy(Offset(7f, 40f), minDistancePx = 50f)
    assertEquals(0, state.autoScrollDirection)
    state.dragBy(Offset(2f, 11f), minDistancePx = 50f)
    assertEquals(1, state.autoScrollDirection)
}
```

Add the drag-end regression. It catches computing menu behavior from final offset instead of the maximum displacement over the whole gesture:

```kotlin
@Test
fun oneCoverDistanceSuppressesTheMenuEvenAfterReturningToStart() {
    val state = ShelfCollectionDragState()
    val cover = Rect(0f, 0f, 100f, 150f)

    state.begin(testBook(), Offset(20f, 20f), cover, horizontal = true, allowMerge = true)
    state.dragBy(Offset(12f, 0f), minDistancePx = 8f)
    assertTrue(requireNotNull(state.finish()).showLongPressMenu)

    state.begin(testBook(), Offset(20f, 20f), cover, horizontal = true, allowMerge = true)
    state.dragBy(Offset(100f, 0f), minDistancePx = 8f)
    state.dragBy(Offset(-100f, 0f), minDistancePx = 8f)
    val result = requireNotNull(state.finish())
    assertNull(result.drop)
    assertFalse(result.showLongPressMenu)
}
```

Mechanically update existing state-test calls from `state.dragBy(delta)` to `state.dragBy(delta, minDistancePx)` and from nullable pair assertions on `finish(minDistancePx)` to `ShelfDragResult` assertions on `finish()`. Preserve the existing direct-target, source-skip, live-preview relayout, and exact-overlay geometry coverage.

- [ ] **Step 2: Run the single consolidated RED gate**

Run exactly once from the worktree, with the existing local JDK/SDK/Gradle and daemon reuse:

```bash
export JAVA_HOME=/home/wanan/.local/share/moread-toolchain/jdk
export ANDROID_HOME=/home/wanan/.local/share/moread-toolchain/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
/home/wanan/.local/share/moread-toolchain/gradle/gradle-9.6.1/bin/gradle \
  :app:testDebugUnitTest \
  --tests 'com.mozhi.reader.feature.bookshelf.ShelfCollectionDragTest' \
  --console=plain
```

Expected: FAIL before production edits because the grid gap returns no target, the initial cover starts edge scrolling, and the new drag-end result contract is absent. Record the concise failing output in the task report; do not rerun RED per individual bug.

- [ ] **Step 3: Make grid whitespace choose the nearest cover without changing direct-hit zones**

In `findShelfDrop`, first return `null` when the pointer lies in the source book's registered rectangle. Filter the remaining candidates, retain the existing direct `bounds.contains(pointer)` hit, and only when that direct hit is absent and `horizontal` is true, inspect candidates whose vertical bounds contain `pointer.y`. If `pointer.x` lies between that row's first left edge and last right edge, choose the candidate with minimum `abs(pointer.x - bounds.center.x)`.

Use the direct-hit presence to select placement:

```kotlin
val placement = when {
    direct == null -> if (pointer.x < region.bounds.center.x) {
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
```

Import only `kotlin.math.abs`. Direct hits keep the existing 25/50/25 or non-merge 50/50 zones; vertical list behavior is unchanged.

- [ ] **Step 4: Gate auto-scroll on real movement and use the pointer Y coordinate**

Change the pure threshold function to:

```kotlin
fun shelfEdgeScrollDirection(dragPointerY: Float, viewport: Rect): Int = when {
    dragPointerY <= viewport.top + viewport.height * 0.10f -> -1
    dragPointerY >= viewport.top + viewport.height * 0.90f -> 1
    else -> 0
}
```

In `ShelfCollectionDragState`, replace accumulated path `distance` with these three fields:

```kotlin
private var dragActivated = false
private var movedOneCoverDistance = false
private var dragOffset = Offset.Zero
```

Change `dragBy` to accept `minDistancePx`. After adding the delta to `dragOffset`, make both flags sticky:

```kotlin
dragActivated = dragActivated || dragOffset.getDistance() >= minDistancePx
movedOneCoverDistance = movedOneCoverDistance ||
    kotlin.math.abs(dragOffset.x) >= sourceBounds.width ||
    kotlin.math.abs(dragOffset.y) >= sourceBounds.height
```

Continue translating `dragBounds` exactly as before. In `updateTargets`, calculate:

```kotlin
autoScrollDirection = if (dragActivated) {
    shelfEdgeScrollDirection(pointer.y, viewport)
} else {
    0
}
```

Reset both flags in `begin` and `cancel`. In `collectionDragSource.onDrag`, pass `viewConfiguration.touchSlop` into `dragBy`. This makes a long press and sub-slop movement inert even when the original cover crosses an edge threshold.

- [ ] **Step 5: Separate a drag ending without a drop from a pure long press**

Add beside `ShelfDrop`:

```kotlin
internal data class ShelfDragResult(
    val source: BookEntity,
    val drop: ShelfDrop?,
    val showLongPressMenu: Boolean
)
```

Replace `finish(minDistancePx)` with `finish()`:

```kotlin
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
```

Change only the shared modifier's `onDragEnd` dispatch:

```kotlin
val result = state.finish()
when {
    result == null -> Unit
    result.drop != null -> onDrop(result.source, result.drop)
    result.showLongPressMenu -> onLongPressOnly(bounds())
}
```

All shelf grid/list and collection-member drags inherit this behavior through the existing modifier. Do not add callbacks or state in `BookshelfScreen`.

- [ ] **Step 6: Restore the original collection header and make its existing handle direct**

In `BookCollectionComponents.kt`, remove `Icons.Outlined.Close` and the title-row close `IconButton`. Import `BottomSheetDefaults` and provide this `dragHandle` slot only on `CollectionContentsSheet`:

```kotlin
dragHandle = {
    Box(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        BottomSheetDefaults.DragHandle()
    }
},
```

Keep `sheetGesturesEnabled = false`, `BackHandler(onBack = onDismiss)`, and `onDismissRequest = onDismiss`. The nested full-width click consumes the first tap and directly clears `openCollectionId`; the rendered child is the same Material3 black handle, so no new UI appears.

- [ ] **Step 7: Run static checks before the only GREEN/build gate**

Run:

```bash
git diff --check
rg -n 'Icons\.Outlined\.Close|dragBounds, viewport|finish\(minDistancePx' \
  app/src/main/java/com/mozhi/reader/feature/bookshelf \
  app/src/test/java/com/mozhi/reader/feature/bookshelf/ShelfCollectionDragTest.kt
git diff --stat
```

Expected: `git diff --check` succeeds; the obsolete close icon, cover-edge threshold call, and old finish signature are absent; only the three planned files plus this task's already-committed planning documents differ from the task base.

- [ ] **Step 8: Run one GREEN focused-test plus compressed-APK build invocation**

Run exactly once:

```bash
export JAVA_HOME=/home/wanan/.local/share/moread-toolchain/jdk
export ANDROID_HOME=/home/wanan/.local/share/moread-toolchain/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
/home/wanan/.local/share/moread-toolchain/gradle/gradle-9.6.1/bin/gradle \
  :app:testDebugUnitTest \
  --tests 'com.mozhi.reader.feature.bookshelf.ShelfCollectionDragTest' \
  :app:assemblePerformance \
  --console=plain
```

Expected: `BUILD SUCCESSFUL`; every test in `ShelfCollectionDragTest` passes; `app/build/outputs/apk/performance/app-performance.apk` and `app/build/outputs/mapping/performance/mapping.txt` are non-empty. If this complete gate exposes multiple compiler/test errors, collect all output before making one consolidated fix; do not start per-error build loops.

- [ ] **Step 9: Verify artifact metadata without another Gradle invocation**

Run:

```bash
test -s app/build/outputs/apk/performance/app-performance.apk
test -s app/build/outputs/mapping/performance/mapping.txt
head -1 app/build/outputs/mapping/performance/mapping.txt
stat -c '%s' app/build/outputs/apk/performance/app-performance.apk
sha256sum app/build/outputs/apk/performance/app-performance.apk
/home/wanan/.local/share/moread-toolchain/jdk/bin/jar tf \
  app/build/outputs/apk/performance/app-performance.apk >/dev/null
```

Expected: mapping begins `# compiler: R8`; APK size and SHA-256 are printed; ZIP integrity succeeds.

- [ ] **Step 10: Commit the implementation**

```bash
git add \
  app/src/main/java/com/mozhi/reader/feature/bookshelf/ShelfCollectionDrag.kt \
  app/src/main/java/com/mozhi/reader/feature/bookshelf/BookCollectionComponents.kt \
  app/src/test/java/com/mozhi/reader/feature/bookshelf/ShelfCollectionDragTest.kt
git commit -m "fix: correct shelf drag interactions"
```

The task report must include the one RED invocation, the one GREEN/build invocation, test count, APK size/hash, files changed, and a self-review confirming no title close icon, new dependency, migration, callback, or state layer was added.
