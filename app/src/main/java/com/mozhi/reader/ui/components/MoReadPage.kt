package com.mozhi.reader.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ui.theme.MoReadTokens
import com.mozhi.reader.ui.theme.sectionHairline

/**
 * 全 App 唯一的页壳。
 *
 * 改造前：24 个二级页各写各的返回顶栏 —— 三种形态（M3 TopAppBar / 手搓 Row / 玻璃圆钮）、
 * 四档横向 padding、两种标题字号。从一个页面退到另一个页面，返回箭头会跳位置，标题会变大小。
 * 这是「不成熟」观感最直接的来源，也是唯一一处改一次就能全线受益的地方。
 *
 * 版式：顶部一条 [TOP_BAR_HEIGHT] 的固定栏（返回 + 紧凑标题 + 动作），大标题作为列表首项
 * 随内容滚走；滚过一定距离后紧凑标题淡入。两个标题不同时可见，所以顶栏永远只有一行信息。
 */
@Composable
fun MoReadSecondaryPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    listState: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable (() -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    val collapsedAlpha by remember(listState) {
        derivedStateOf {
            collapsedTitleAlpha(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
    }

    MoReadBackdrop(modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            MoReadTopBar(
                title = title,
                titleAlpha = collapsedAlpha,
                onBack = onBack,
                actions = actions
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = MoReadTokens.PageGutter,
                        end = MoReadTokens.PageGutter,
                        top = 4.dp,
                        bottom = if (bottomBar == null) 32.dp else 12.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(MoReadTokens.SectionGap)
                ) {
                    item(key = "page-hero", contentType = "hero") {
                        PageHeroTitle(title = title, subtitle = subtitle)
                    }
                    content()
                }
            }
            if (bottomBar != null) {
                bottomBar()
            } else {
                // 键盘弹出时 navigationBars.exclude(ime) 归零，避免与调用点的 imePadding()
                // 叠出一条空隙（角色编辑页就是这么用的）。
                Box(
                    Modifier.windowInsetsPadding(
                        WindowInsets.navigationBars.exclude(WindowInsets.ime)
                    )
                )
            }
        }
    }
}

/**
 * 四个主页的页壳。主页没有返回键，大标题常驻在内容里，右上角可放一个动作。
 * [trailing] 是标题右侧的补充信息（「12 位角色」这类），不是按钮。
 */
@Composable
fun MoReadRootPage(
    title: String,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxSize().padding(contentPadding)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = MoReadTokens.PageGutter,
                end = MoReadTokens.PageGutter,
                top = 18.dp,
                // 底部玻璃导航舱要压在内容上，留出它的高度否则最后一组永远被挡。
                bottom = 124.dp
            ),
            verticalArrangement = Arrangement.spacedBy(MoReadTokens.SectionGap)
        ) {
            item(key = "root-hero", contentType = "hero") {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(text = title, style = MaterialTheme.typography.headlineLarge)
                    if (!trailing.isNullOrBlank()) {
                        Text(
                            text = trailing,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 10.dp, bottom = 6.dp)
                        )
                    }
                }
            }
            content()
        }
    }
}

/** 顶栏：状态栏内边距由 [MoReadBackdrop] 之外的 safeTopPadding 语义统一处理。 */
@Composable
private fun MoReadTopBar(
    title: String,
    titleAlpha: Float,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    Column(Modifier.safeTopPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TOP_BAR_HEIGHT)
                .padding(horizontal = MoReadSpacingSmall),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(TOUCH_TARGET)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回"
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
                    .alpha(titleAlpha)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
        // 紧凑标题露出后才画分隔线：页面停在顶部时顶栏与内容是连着的，不该有一道横杠。
        if (titleAlpha > 0f) {
            androidx.compose.material3.HorizontalDivider(
                color = sectionHairline().copy(alpha = sectionHairline().alpha * titleAlpha)
            )
        }
    }
}

/** 大标题：衬线，作为列表首项跟着内容一起滚。 */
@Composable
private fun PageHeroTitle(title: String, subtitle: String?) {
    Column(modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

private val MoReadSpacingSmall = 8.dp
private val TOUCH_TARGET = 44.dp
private val TOP_BAR_HEIGHT = 52.dp

/** 大标题滚出多少像素后紧凑标题完全显现。约等于大标题自身的高度。 */
internal const val TITLE_COLLAPSE_DISTANCE_PX = 56

/**
 * 顶栏紧凑标题的不透明度。
 *
 * 纯函数：列表首项一旦滚出视口（index > 0）就固定为 1，否则按首项偏移线性淡入。
 * 抽出来是为了能被单测钉死 —— 边界写错的表现是「标题在顶部就已经显形」或
 * 「滚很远还没出来」，两者在真机上都要滚半天才发现。
 */
internal fun collapsedTitleAlpha(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int): Float {
    if (firstVisibleItemIndex > 0) return 1f
    if (firstVisibleItemScrollOffset <= 0) return 0f
    return (firstVisibleItemScrollOffset.toFloat() / TITLE_COLLAPSE_DISTANCE_PX).coerceIn(0f, 1f)
}
