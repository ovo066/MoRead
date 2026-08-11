package com.mozhi.reader.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.InsertChartOutlined
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.InsertChartOutlined
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mozhi.reader.feature.bookdetail.BookDetailScreen
import com.mozhi.reader.feature.bookshelf.BookshelfScreen
import com.mozhi.reader.feature.companion.CompanionScreen
import com.mozhi.reader.feature.companion.PersonaEditorScreen
import com.mozhi.reader.feature.importer.ImportPreviewScreen
import com.mozhi.reader.feature.reader.CompanionChatScreen
import com.mozhi.reader.feature.reader.ReaderScreen
import com.mozhi.reader.feature.settings.AiServiceScreen
import com.mozhi.reader.feature.settings.ApiLogScreen
import com.mozhi.reader.feature.settings.AppUpdatePrompt
import com.mozhi.reader.feature.settings.ImageGenSettingsScreen
import com.mozhi.reader.feature.settings.FontLibraryScreen
import com.mozhi.reader.feature.settings.ImageLibraryScreen
import com.mozhi.reader.feature.settings.GlobalPresetSettingsScreen
import com.mozhi.reader.feature.settings.BackupSettingsScreen
import com.mozhi.reader.feature.settings.ProviderDetailScreen
import com.mozhi.reader.feature.settings.SettingsScreen
import com.mozhi.reader.feature.settings.SettingsViewModel
import com.mozhi.reader.feature.settings.TtsSettingsScreen
import com.mozhi.reader.feature.settings.UserMaskSettingsScreen
import com.mozhi.reader.feature.settings.WebSearchSettingsScreen
import com.mozhi.reader.feature.stats.StatsScreen
import com.mozhi.reader.ui.components.MoReadBackdrop
import com.mozhi.reader.ui.theme.MoReadTokens
import com.mozhi.reader.ui.theme.isDarkTheme
import com.mozhi.reader.ui.theme.navSelectedColor
import com.mozhi.reader.ui.theme.onNavSelectedColor

/**
 * 页面转场（Material 3 motion）：
 * - 根页互切用 fade-through——旧页 90ms 快出、新页再 210ms 淡入，两页几乎不
 *   叠帧绘制。Navigation 默认的 700ms 双页叠加淡入淡出既拖沓又让重列表掉帧。
 * - 二级页推入用 shared-axis X：入场从右侧 1/4 滑入，底页微退场；返回镜像。
 */
private const val ROOT_FADE_OUT_MS = 90
private const val ROOT_FADE_IN_MS = 210
private const val PUSH_MS = 280

private fun rootEnter(): EnterTransition =
    fadeIn(tween(ROOT_FADE_IN_MS, delayMillis = ROOT_FADE_OUT_MS, easing = LinearOutSlowInEasing)) +
        scaleIn(
            initialScale = 0.92f,
            animationSpec = tween(ROOT_FADE_IN_MS, delayMillis = ROOT_FADE_OUT_MS, easing = LinearOutSlowInEasing)
        )

private fun rootExit(): ExitTransition =
    fadeOut(tween(ROOT_FADE_OUT_MS, easing = FastOutLinearInEasing))

/** 二级页统一注册入口：带 shared-axis X 转场的 composable。 */
private fun NavGraphBuilder.pushComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
) = composable(
    route = route,
    arguments = arguments,
    enterTransition = {
        slideInHorizontally(tween(PUSH_MS, easing = FastOutSlowInEasing)) { it / 4 } +
            fadeIn(tween(PUSH_MS, easing = LinearOutSlowInEasing))
    },
    exitTransition = {
        slideOutHorizontally(tween(PUSH_MS, easing = FastOutSlowInEasing)) { -it / 8 } +
            fadeOut(tween(160, easing = FastOutLinearInEasing))
    },
    popEnterTransition = {
        slideInHorizontally(tween(PUSH_MS, easing = FastOutSlowInEasing)) { -it / 8 } +
            fadeIn(tween(PUSH_MS, easing = LinearOutSlowInEasing))
    },
    popExitTransition = {
        slideOutHorizontally(tween(PUSH_MS, easing = FastOutSlowInEasing)) { it / 4 } +
            fadeOut(tween(160, easing = FastOutLinearInEasing))
    },
    content = content
)

private enum class RootDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
) {
    Bookshelf(
        route = "bookshelf",
        label = "书架",
        icon = Icons.Outlined.AutoStories,
        selectedIcon = Icons.Filled.AutoStories
    ),
    Stats(
        route = "stats",
        label = "统计",
        icon = Icons.Outlined.InsertChartOutlined,
        selectedIcon = Icons.Filled.InsertChartOutlined
    ),
    Companion(
        route = "companion",
        label = "伴读",
        icon = Icons.Outlined.AutoAwesome,
        selectedIcon = Icons.Filled.AutoAwesome
    ),
    Settings(
        route = "settings",
        label = "设置",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Filled.Settings
    )
}

@Composable
fun MoReadApp(
    incomingBookUri: Uri? = null,
    onIncomingBookConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = RootDestination.entries.any { it.route == currentRoute }

    LaunchedEffect(incomingBookUri) {
        if (incomingBookUri != null && currentRoute != RootDestination.Bookshelf.route) {
            navController.navigate(RootDestination.Bookshelf.route) {
                popUpTo(RootDestination.Bookshelf.route) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    MoReadBackdrop {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                // 背景由 MoReadBackdrop 画，Scaffold 只做布局所以是透明的；但 contentColor
                // 必须显式给 —— 默认 contentColorFor(Transparent) 匹配不到任何角色，会返回
                // Color.Unspecified，于是所有没写死颜色的 Text 都拿不到前景色（夜间即黑底黑字）。
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground
            ) { padding ->
                NavHost(
                    navController = navController,
                    startDestination = RootDestination.Bookshelf.route,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = { rootEnter() },
                    exitTransition = { rootExit() },
                    popEnterTransition = { rootEnter() },
                    popExitTransition = { rootExit() }
                ) {
                composable(RootDestination.Bookshelf.route) {
                    BookshelfScreen(
                        contentPadding = padding,
                        externalImportUri = incomingBookUri,
                        onExternalImportConsumed = onIncomingBookConsumed,
                        onOpenBook = { bookId -> navController.navigate("reader/$bookId") },
                        onOpenBookDetail = { bookId, action ->
                            // 长按菜单的「编辑详情 / 修改封面」直接深链到详情页对应的对话框，
                            // 免得把同一套编辑器在书架再实现一遍。
                            navController.navigate(
                                if (action == null) "book/$bookId" else "book/$bookId?action=$action"
                            )
                        },
                        onOpenImportPreview = { sessionId ->
                            navController.navigate("import/$sessionId")
                        }
                    )
                }
                composable(RootDestination.Stats.route) {
                    StatsScreen(contentPadding = padding)
                }
                composable(RootDestination.Companion.route) {
                    CompanionScreen(
                        contentPadding = padding,
                        onEditPersona = { personaId ->
                            navController.navigate("persona/$personaId")
                        },
                        onCreatePersona = { navController.navigate("persona/0") }
                    )
                }
                composable(RootDestination.Settings.route) { entry ->
                    SettingsScreen(
                        contentPadding = padding,
                        viewModel = hiltViewModel(entry),
                        onOpenAiServices = { navController.navigate("ai-services") },
                        onOpenWebSearch = { navController.navigate("web-search-settings") },
                        onOpenTtsSettings = { navController.navigate("tts-settings") },
                        onOpenImageGenSettings = { navController.navigate("image-gen-settings") },
                        onOpenFontLibrary = { navController.navigate("font-library") },
                        onOpenImageLibrary = { navController.navigate("image-library") },
                        onOpenGlobalPresets = { navController.navigate("global-presets") },
                        onOpenUserMasks = { navController.navigate("user-masks") },
                        onOpenBackup = { navController.navigate("backup-settings") },
                        onOpenApiLog = { navController.navigate("api-log") }
                    )
                }
                pushComposable("ai-services") { entry ->
                    // AI 服务与设置页共用同一个热状态，避免推入二级页时重新订阅数据库，
                    // 先画一帧空列表再补内容造成的闪烁。
                    val settingsEntry = remember(entry) {
                        navController.getBackStackEntry(RootDestination.Settings.route)
                    }
                    AiServiceScreen(
                        onBack = navController::popBackStack,
                        onOpenProvider = { providerId ->
                            navController.navigate("provider/$providerId")
                        },
                        viewModel = hiltViewModel<SettingsViewModel>(settingsEntry)
                    )
                }
                pushComposable("tts-settings") {
                    TtsSettingsScreen(onBack = navController::popBackStack)
                }
                pushComposable("web-search-settings") {
                    WebSearchSettingsScreen(onBack = navController::popBackStack)
                }
                pushComposable("image-gen-settings") {
                    ImageGenSettingsScreen(onBack = navController::popBackStack)
                }
                pushComposable("font-library") {
                    FontLibraryScreen(onBack = navController::popBackStack)
                }
                pushComposable("image-library") {
                    ImageLibraryScreen(onBack = navController::popBackStack)
                }
                pushComposable("global-presets") {
                    GlobalPresetSettingsScreen(onBack = navController::popBackStack)
                }
                pushComposable("user-masks") {
                    UserMaskSettingsScreen(onBack = navController::popBackStack)
                }
                pushComposable("backup-settings") {
                    BackupSettingsScreen(onBack = navController::popBackStack)
                }
                pushComposable("api-log") {
                    ApiLogScreen(onBack = navController::popBackStack)
                }
                pushComposable(
                    route = "book/{bookId}?action={action}",
                    arguments = listOf(
                        navArgument("action") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    )
                ) { entry ->
                    BookDetailScreen(
                        bookId = entry.arguments?.getString("bookId")?.toLongOrNull()
                            ?: return@pushComposable,
                        initialAction = entry.arguments?.getString("action"),
                        onBack = navController::popBackStack,
                        onContinueReading = { bookId ->
                            navController.navigate("reader/$bookId")
                        }
                    )
                }
                pushComposable("import/{sessionId}") {
                    ImportPreviewScreen(
                        onBack = navController::popBackStack,
                        onImported = { bookId ->
                            navController.navigate("reader/$bookId") {
                                popUpTo(RootDestination.Bookshelf.route)
                            }
                        }
                    )
                }
                pushComposable("reader/{bookId}") { entry ->
                    ReaderScreen(
                        bookId = entry.arguments?.getString("bookId")?.toLongOrNull()
                            ?: return@pushComposable,
                        onBack = navController::popBackStack,
                        onOpenCompanionChat = { bookId ->
                            navController.navigate("companion-chat/$bookId")
                        }
                    )
                }
                pushComposable("companion-chat/{bookId}") { entry ->
                    CompanionChatScreen(
                        bookId = entry.arguments?.getString("bookId")?.toLongOrNull()
                            ?: return@pushComposable,
                        onBack = navController::popBackStack
                    )
                }
                pushComposable("persona/{personaId}") {
                    PersonaEditorScreen(onBack = navController::popBackStack)
                }
                pushComposable("provider/{providerId}") {
                    ProviderDetailScreen(onBack = navController::popBackStack)
                }
                }
            }

            // Dock 是覆盖在内容上的浮层，不占 Scaffold 的 bottomBar 布局高度。
            // 否则 Scaffold 会在整屏底部预留一条矩形空白，看起来像胶囊背后的白横条。
            if (showBottomBar) {
                BottomNavDock(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    selectedRoute = currentRoute,
                    onSelect = { item ->
                        navController.navigate(item.route) {
                            popUpTo(RootDestination.Bookshelf.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            AppUpdatePrompt()
        }
    }
}

/**
 * 轻量玻璃导航舱：用高透明度表面、描边与小阴影保留玻璃层次，但不做实时背景
 * 采样/模糊。Dock 覆盖面积虽小，实时 blur 仍会让整个滚动内容进入离屏合成，
 * 对中低端 GPU 的帧时间影响与书籍数量无关。
 */
@Composable
private fun BottomNavDock(
    modifier: Modifier = Modifier,
    selectedRoute: String?,
    onSelect: (RootDestination) -> Unit
) {
    val darkTheme = isDarkTheme()
    val borderColor = if (darkTheme) {
        Color.White.copy(alpha = 0.12f)
    } else {
        Color.White.copy(alpha = 0.65f)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = MoReadTokens.CapsuleShape,
            color = MaterialTheme.colorScheme.surface.copy(
                alpha = if (darkTheme) 0.92f else 0.96f
            ),
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, borderColor)
        ) {
            Row(
                modifier = Modifier.padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RootDestination.entries.forEach { item ->
                    NavDockItem(
                        item = item,
                        selected = selectedRoute == item.route,
                        onClick = { onSelect(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NavDockItem(
    item: RootDestination,
    selected: Boolean,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) navSelectedColor() else Color.Transparent,
        animationSpec = tween(durationMillis = 240),
        label = "nav-dock-container"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            onNavSelectedColor()
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 220),
        label = "nav-dock-content"
    )
    Surface(
        onClick = onClick,
        shape = MoReadTokens.CapsuleShape,
        color = containerColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier
                .animateContentSize(animationSpec = tween(240))
                .padding(
                    start = if (selected) 16.dp else 11.dp,
                    end = if (selected) 18.dp else 11.dp,
                    top = 11.dp,
                    bottom = 11.dp
                ),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (selected) item.selectedIcon else item.icon,
                contentDescription = item.label,
                modifier = Modifier.size(22.dp)
            )
            if (selected) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 7.dp)
                )
            }
        }
    }
}
