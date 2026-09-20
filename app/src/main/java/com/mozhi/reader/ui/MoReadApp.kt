package com.mozhi.reader.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.InsertChartOutlined
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.InsertChartOutlined
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mozhi.reader.feature.bookdetail.BookDetailScreen
import com.mozhi.reader.feature.bookshelf.BookshelfScreen
import com.mozhi.reader.feature.bookshelf.BookshelfViewModel
import com.mozhi.reader.feature.bookshelf.manage.ShelfGroupScreen
import com.mozhi.reader.feature.bookshelf.manage.TagManageScreen
import com.mozhi.reader.feature.companion.CompanionScreen
import com.mozhi.reader.feature.companion.PersonaEditorScreen
import com.mozhi.reader.feature.companion.PersonaMemoryScreen
import com.mozhi.reader.feature.importer.ImportPickerScreen
import com.mozhi.reader.feature.importer.ImportPreviewScreen
import com.mozhi.reader.feature.importer.LanTransferScreen
import com.mozhi.reader.feature.listen.AudiobookProductionScreen
import com.mozhi.reader.feature.listen.AudiobookRoleScreen
import com.mozhi.reader.feature.listen.AudiobookScriptScreen
import com.mozhi.reader.feature.listen.ListenPlayerScreen
import com.mozhi.reader.feature.reader.CompanionChatScreen
import com.mozhi.reader.feature.reader.ReaderCompanionViewModel
import com.mozhi.reader.feature.reader.ReaderLocateRequest
import com.mozhi.reader.feature.reader.ReaderScreen
import com.mozhi.reader.feature.settings.AboutSettingsScreen
import com.mozhi.reader.feature.settings.AiAndCompanionSettingsScreen
import com.mozhi.reader.feature.settings.AiServiceScreen
import com.mozhi.reader.feature.settings.AnnotationPromptSettingsScreen
import com.mozhi.reader.feature.settings.ApiLogScreen
import com.mozhi.reader.feature.settings.AppUpdatePrompt
import com.mozhi.reader.feature.settings.BackupSettingsScreen
import com.mozhi.reader.feature.settings.DataSettingsScreen
import com.mozhi.reader.feature.settings.FontLibraryScreen
import com.mozhi.reader.feature.settings.GlobalPresetSettingsScreen
import com.mozhi.reader.feature.settings.ImageGenSettingsScreen
import com.mozhi.reader.feature.settings.ImageLibraryScreen
import com.mozhi.reader.feature.settings.ProactiveAnnotationSettingsScreen
import com.mozhi.reader.feature.settings.ProviderDetailScreen
import com.mozhi.reader.feature.settings.ReadingAppearanceSettingsScreen
import com.mozhi.reader.feature.settings.SettingsScreen
import com.mozhi.reader.feature.settings.SettingsViewModel
import com.mozhi.reader.feature.settings.SpeechCacheScreen
import com.mozhi.reader.feature.settings.TtsSettingsScreen
import com.mozhi.reader.feature.settings.TtsVoiceLibraryScreen
import com.mozhi.reader.feature.settings.UserMaskSettingsScreen
import com.mozhi.reader.feature.settings.WebSearchSettingsScreen
import com.mozhi.reader.feature.stats.StatsScreen
import com.mozhi.reader.ui.components.BlurredGlassSurface
import com.mozhi.reader.ui.components.MoReadBackdrop
import com.mozhi.reader.ui.components.MoReadBoundedContent
import com.mozhi.reader.ui.theme.MoReadTokens
import com.mozhi.reader.ui.theme.NavStyle
import com.mozhi.reader.ui.theme.ReaderAppearanceScope
import com.mozhi.reader.ui.theme.isFlatSurface
import com.mozhi.reader.ui.theme.navSelectedColor
import com.mozhi.reader.ui.theme.navStyle
import com.mozhi.reader.ui.theme.onNavSelectedColor
import com.mozhi.reader.ui.theme.sectionDivider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** 聊天页 →（返回）→ 阅读页的一次性跳转参数。 */
private const val LOCATE_CHAPTER_KEY = "locate-chapter"
private const val LOCATE_START_KEY = "locate-start"
private const val LOCATE_END_KEY = "locate-end"
private const val LOCATE_ANCHOR_KEY = "locate-anchor"

internal enum class RootDestination(
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
    var bookshelfSelectionActive by remember { mutableStateOf(false) }
    val showBottomBar = RootDestination.entries.any { it.route == currentRoute } &&
        !(currentRoute == RootDestination.Bookshelf.route && bookshelfSelectionActive)
    val hazeState = rememberHazeState()
    // The permanent tablet navigator and the shelf operate on the same filter/selection state.
    val bookshelfViewModel: BookshelfViewModel = hiltViewModel()
    val shelfState by bookshelfViewModel.uiState.collectAsStateWithLifecycle()
    val selectRoot: (RootDestination) -> Unit = { item ->
        navController.navigate(item.route) {
            popUpTo(RootDestination.Bookshelf.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(incomingBookUri) {
        if (incomingBookUri != null && currentRoute != RootDestination.Bookshelf.route) {
            navController.navigate(RootDestination.Bookshelf.route) {
                popUpTo(RootDestination.Bookshelf.route) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    MoReadBackdrop {
        MoReadWindowLayout { windowWidth ->
            val expanded = windowWidth == MoReadWindowWidth.EXPANDED
            val medium = windowWidth == MoReadWindowWidth.MEDIUM
            MoReadNavigationScaffold { padding ->
                MoReadNavigationHost(
                    navController = navController,
                    modifier = Modifier.fillMaxSize().hazeSource(hazeState)
                ) {
                rootComposable(RootDestination.Bookshelf.route, expanded, medium) {
                    BookshelfScreen(
                        viewModel = bookshelfViewModel,
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
                        },
                        onOpenFolderPicker = { treeUri ->
                            navController.navigate("import-picker?treeUri=${Uri.encode(treeUri.toString())}")
                        },
                        onOpenLanTransfer = { navController.navigate("import-lan") },
                        onOpenShelfGroups = { navController.navigate("shelf-groups") },
                        onOpenShelfTags = { navController.navigate("shelf-tags") },
                        onSelectionModeChanged = { bookshelfSelectionActive = it }
                    )
                }
                rootComposable(RootDestination.Stats.route, expanded, medium) {
                    StatsScreen(contentPadding = padding)
                }
                rootComposable(RootDestination.Companion.route, expanded, medium) {
                    Box(Modifier.fillMaxSize()) {
                        CompanionScreen(
                            contentPadding = padding,
                            onEditPersona = { personaId ->
                                navController.navigate("persona/$personaId")
                            },
                            onCreatePersona = { navController.navigate("persona/0") },
                            onOpenLibraryChat = { navController.navigate("library-companion") },
                            onOpenStats = { navController.navigate("companion-stats") }
                        )
                    }
                }
                rootComposable(RootDestination.Settings.route, expanded, medium) { entry ->
                    if (expanded) SettingsDetailPane(root = true) {
                        ReadingAppearanceSettingsScreen(
                            onBack = { selectRoot(RootDestination.Bookshelf) },
                            onOpenFontLibrary = { navController.navigate("font-library") },
                            onOpenImageLibrary = { navController.navigate("image-library") },
                            viewModel = hiltViewModel<SettingsViewModel>(entry)
                        )
                    } else SettingsScreen(
                        contentPadding = padding,
                        viewModel = hiltViewModel(entry),
                        onOpenReading = { navController.navigate("settings-reading") },
                        onOpenTts = { navController.navigate("tts-settings") },
                        onOpenAiServices = { navController.navigate("ai-services") },
                        onOpenAi = { navController.navigate("settings-ai") },
                        onOpenBackup = { navController.navigate("backup-settings") },
                        onOpenData = { navController.navigate("settings-data") },
                        onOpenAbout = { navController.navigate("settings-about") },
                        onOpenDictionaries = { navController.navigate("settings-dictionaries") },
                        onOpenVocabulary = { navController.navigate("settings-vocabulary") },
                        onOpenReadingReview = { navController.navigate("settings-review") }
                    )
                }
                settingsComposable("settings-review", navController) {
                    com.mozhi.reader.feature.review.ReadingReviewScreen(
                        onBack = { navController.popBackStack() },
                        onLocate = { item ->
                            if (item.canLocate) {
                                navController.navigate("reader/${item.book.id}")
                                navController.currentBackStackEntry?.savedStateHandle?.let { handle ->
                                    handle[LOCATE_START_KEY] = item.offset
                                    handle[LOCATE_END_KEY] = item.annotation?.endCharOffset ?: item.offset
                                    handle[LOCATE_ANCHOR_KEY] = item.annotation?.textAnchorJson.orEmpty()
                                    handle[LOCATE_CHAPTER_KEY] = item.chapter
                                }
                            }
                        }
                    )
                }
                settingsComposable("settings-dictionaries", navController) {
                    com.mozhi.reader.feature.reader.DictionaryManagerPage(onBack = { navController.popBackStack() })
                }
                settingsComposable("settings-vocabulary", navController) {
                    com.mozhi.reader.feature.reader.VocabularyPage(onBack = { navController.popBackStack() })
                }
                settingsComposable("settings-reading", navController) { entry ->
                    val settingsEntry = remember(entry) {
                        navController.getBackStackEntry(RootDestination.Settings.route)
                    }
                    ReadingAppearanceSettingsScreen(
                        onBack = navController::popBackStack,
                        onOpenFontLibrary = { navController.navigate("font-library") },
                        onOpenImageLibrary = { navController.navigate("image-library") },
                        viewModel = hiltViewModel<SettingsViewModel>(settingsEntry)
                    )
                }
                settingsComposable("settings-ai", navController) { entry ->
                    val settingsEntry = remember(entry) {
                        navController.getBackStackEntry(RootDestination.Settings.route)
                    }
                    AiAndCompanionSettingsScreen(
                        onBack = navController::popBackStack,
                        onOpenAiServices = { navController.navigate("ai-services") },
                        onOpenAnnotationLimits = { navController.navigate("annotation-limits") },
                        onOpenWebSearch = { navController.navigate("web-search-settings") },
                        onOpenGlobalPresets = { navController.navigate("global-presets") },
                        onOpenUserMasks = { navController.navigate("user-masks") },
                        onOpenTtsSettings = { navController.navigate("tts-settings") },
                        onOpenVoiceLibrary = { navController.navigate("tts-voices") },
                        onOpenImageGenSettings = { navController.navigate("image-gen-settings") },
                        viewModel = hiltViewModel<SettingsViewModel>(settingsEntry)
                    )
                }
                settingsComposable("annotation-limits", navController) { entry ->
                    val settingsEntry = remember(entry) {
                        navController.getBackStackEntry(RootDestination.Settings.route)
                    }
                    ProactiveAnnotationSettingsScreen(
                        bookId = null,
                        onBack = navController::popBackStack,
                        onOpenPrompts = { navController.navigate("annotation-prompts") },
                        viewModel = hiltViewModel<SettingsViewModel>(settingsEntry)
                    )
                }
                settingsComposable("annotation-prompts", navController) { entry ->
                    val settingsEntry = remember(entry) { navController.getBackStackEntry(RootDestination.Settings.route) }
                    AnnotationPromptSettingsScreen(onBack = navController::popBackStack,
                        viewModel = hiltViewModel<SettingsViewModel>(settingsEntry))
                }
                pushComposable("annotation-limits/{bookId}") { entry ->
                    // Keep a book's settings warm when revisiting from the same detail page.
                    val owner = remember(entry) { navController.previousBackStackEntry ?: entry }
                    ProactiveAnnotationSettingsScreen(
                        bookId = entry.bookIdOrNull()
                            ?: return@pushComposable,
                        onBack = navController::popBackStack,
                        viewModel = hiltViewModel<SettingsViewModel>(owner)
                    )
                }
                settingsComposable("settings-data", navController) { entry ->
                    DataSettingsScreen(
                        onBack = navController::popBackStack,
                        onOpenBackup = { navController.navigate("backup-settings") },
                        onOpenSpeechCache = { navController.navigate("speech-cache") },
                        onOpenImages = { navController.navigate("image-library") },
                        onOpenFonts = { navController.navigate("font-library") },
                        onOpenBook = { navController.navigate("book/$it") }
                    )
                }
                settingsComposable("settings-about", navController) {
                    AboutSettingsScreen(
                        onBack = navController::popBackStack,
                        onOpenApiLog = { navController.navigate("api-log") }
                    )
                }
                settingsComposable("ai-services", navController) { entry ->
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
                settingsComposable("tts-settings", navController) {
                    TtsSettingsScreen(
                        onBack = navController::popBackStack,
                        onOpenSpeechCache = { navController.navigate("speech-cache") },
                        onOpenVoiceLibrary = { navController.navigate("tts-voices") }
                    )
                }
                settingsComposable("tts-voices", navController) {
                    TtsVoiceLibraryScreen(onBack = navController::popBackStack)
                }
                settingsComposable("speech-cache", navController) {
                    SpeechCacheScreen(
                        onBack = navController::popBackStack,
                        onOpenBackupSettings = { navController.navigate("backup-settings") }
                    )
                }
                settingsComposable("web-search-settings", navController) {
                    WebSearchSettingsScreen(onBack = navController::popBackStack)
                }
                settingsComposable("image-gen-settings", navController) {
                    ImageGenSettingsScreen(onBack = navController::popBackStack)
                }
                settingsComposable("font-library", navController) {
                    FontLibraryScreen(onBack = navController::popBackStack)
                }
                settingsComposable("image-library", navController) {
                    ImageLibraryScreen(onBack = navController::popBackStack)
                }
                settingsComposable("global-presets", navController) {
                    GlobalPresetSettingsScreen(onBack = navController::popBackStack)
                }
                settingsComposable("user-masks", navController) {
                    UserMaskSettingsScreen(onBack = navController::popBackStack)
                }
                settingsComposable("backup-settings", navController) {
                    BackupSettingsScreen(onBack = navController::popBackStack)
                }
                settingsComposable("api-log", navController) {
                    ApiLogScreen(onBack = navController::popBackStack)
                }
                pushComposable("shelf-groups") {
                    ShelfGroupScreen(onBack = navController::popBackStack)
                }
                pushComposable("shelf-tags") {
                    TagManageScreen(onBack = navController::popBackStack)
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
                        bookId = entry.bookIdOrNull()
                            ?: return@pushComposable,
                        initialAction = entry.arguments?.getString("action"),
                        onBack = navController::popBackStack,
                        onContinueReading = { bookId ->
                            navController.navigate("reader/$bookId")
                        },
                        onLocateAnnotation = { annotation ->
                            navController.navigate("reader/${annotation.bookId}")
                            navController.currentBackStackEntry?.savedStateHandle?.let { handle ->
                                handle[LOCATE_START_KEY] = annotation.startCharOffset
                                handle[LOCATE_END_KEY] = annotation.endCharOffset
                                handle[LOCATE_ANCHOR_KEY] = annotation.textAnchorJson
                                handle[LOCATE_CHAPTER_KEY] = annotation.chapterIndex
                            }
                        },
                        onListen = { bookId -> navController.navigate("listen/$bookId") },
                        onPlayAudiobook = { bookId ->
                            navController.navigate("listen/$bookId?source=produced")
                        },
                        onOpenAudiobookRoles = { bookId -> navController.navigate("audiobook-roles/$bookId") },
                        onOpenAudiobookProduction = { bookId -> navController.navigate("audiobook-production/$bookId") },
                        onOpenAnnotationLimits = { bookId ->
                            navController.navigate("annotation-limits/$bookId")
                        }
                    )
                }
                pushComposable(
                    route = "import-picker?treeUri={treeUri}",
                    arguments = listOf(
                        navArgument("treeUri") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    )
                ) {
                    ImportPickerScreen(
                        onBack = navController::popBackStack,
                        onImported = { navController.popBackStack() }
                    )
                }
                pushComposable("import-lan") {
                    LanTransferScreen(
                        onBack = navController::popBackStack,
                        onImported = { navController.popBackStack() }
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
                    // 聊天页是压在阅读页之上的二级页，跳转请求经它的 savedStateHandle 回传；
                    // 这样阅读页不必常驻监听，也不会在没打开过聊天时凭空多一条状态。
                    val locateChapter = entry.savedStateHandle
                        .getStateFlow<Int?>(LOCATE_CHAPTER_KEY, null)
                        .collectAsStateWithLifecycle()
                    val locateStart = entry.savedStateHandle
                        .getStateFlow<Int?>(LOCATE_START_KEY, null)
                        .collectAsStateWithLifecycle()
                    val locateEnd = entry.savedStateHandle
                        .getStateFlow<Int?>(LOCATE_END_KEY, null)
                        .collectAsStateWithLifecycle()
                    val locateAnchor = entry.savedStateHandle
                        .getStateFlow<String?>(LOCATE_ANCHOR_KEY, null)
                        .collectAsStateWithLifecycle()
                    val readerBookId = entry.bookIdOrNull() ?: return@pushComposable
                    ReaderAppearanceScope {
                        ReaderScreen(
                            bookId = readerBookId,
                            onBack = navController::popBackStack,
                            onOpenCompanionChat = { bookId ->
                                navController.navigate("companion-chat/$bookId")
                            },
                            onOpenListenPlayer = { bookId -> navController.navigate("listen/$bookId") },
                            pendingLocate = locateChapter.value?.let { chapter ->
                                ReaderLocateRequest(
                                    chapterIndex = chapter,
                                    startCharOffset = locateStart.value ?: 0,
                                    endCharOffset = locateEnd.value ?: 0,
                                    sourceAnchorJson = locateAnchor.value.orEmpty()
                                )
                            },
                            onPendingLocateConsumed = {
                                entry.savedStateHandle[LOCATE_CHAPTER_KEY] = null
                                entry.savedStateHandle[LOCATE_START_KEY] = null
                                entry.savedStateHandle[LOCATE_END_KEY] = null
                                entry.savedStateHandle[LOCATE_ANCHOR_KEY] = null
                            }
                        )
                    }
                }
                pushComposable(
                    route = "listen/{bookId}?source={source}",
                    arguments = listOf(
                        navArgument("source") {
                            type = NavType.StringType
                            defaultValue = "standard"
                        }
                    )
                ) { entry ->
                    val bookId = entry.bookIdOrNull()
                        ?: return@pushComposable
                    ListenPlayerScreen(
                        bookId = bookId,
                        onBack = navController::popBackStack,
                        onOpenVoiceLibrary = { navController.navigate("tts-voices") },
                        onOpenRoleAssignments = { navController.navigate("audiobook-roles/$it?source=listen") }
                    )
                }
                pushComposable(
                    route = "audiobook-roles/{bookId}?source={source}",
                    arguments = listOf(
                        navArgument("source") {
                            type = NavType.StringType
                            defaultValue = "production"
                        }
                    )
                ) { entry ->
                    val source = entry.arguments?.getString("source") ?: "production"
                    AudiobookRoleScreen(
                        onBack = navController::popBackStack,
                        onContinue = { bookId, chapterIndex ->
                            navController.navigate("audiobook-script/$bookId/$chapterIndex?source=$source")
                        }
                    )
                }
                pushComposable(
                    route = "audiobook-script/{bookId}/{chapter}?source={source}",
                    arguments = listOf(
                        navArgument("source") {
                            type = NavType.StringType
                            defaultValue = "production"
                        }
                    )
                ) { entry ->
                    val source = entry.arguments?.getString("source") ?: "production"
                    AudiobookScriptScreen(
                        onBack = navController::popBackStack,
                        onConfirmed = { bookId ->
                            if (source == "listen") {
                                navController.navigate("listen/$bookId") {
                                    popUpTo("listen/$bookId") { inclusive = true }
                                    launchSingleTop = true
                                }
                            } else {
                                navController.navigate("audiobook-production/$bookId")
                            }
                        }
                    )
                }
                pushComposable("audiobook-production/{bookId}") {
                    AudiobookProductionScreen(
                        onBack = navController::popBackStack,
                        onOpenScript = { bookId, chapterIndex ->
                            navController.navigate("audiobook-script/$bookId/$chapterIndex")
                        },
                        onPlay = { bookId ->
                            navController.navigate("listen/$bookId?source=produced")
                        }
                    )
                }
                pushComposable("companion-chat/{bookId}") { entry ->
                    val bookId = entry.bookIdOrNull()
                        ?: return@pushComposable
                    // The same reader session survives side-pane -> full-screen navigation.
                    // Standalone/deep-linked chat still owns a VM on its own entry.
                    val readerEntry = remember(entry, bookId) {
                        try {
                            navController.getBackStackEntry("reader/$bookId")
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                    }
                    val companionViewModel: ReaderCompanionViewModel = hiltViewModel(readerEntry ?: entry)
                    ReaderAppearanceScope {
                        CompanionChatScreen(
                            bookId = bookId,
                            companionViewModel = companionViewModel,
                            onBack = navController::popBackStack,
                            onLocateInBook = { chapterIndex, start, end, sourceAnchorJson ->
                                navController.previousBackStackEntry?.savedStateHandle?.let { handle ->
                                    handle[LOCATE_START_KEY] = start
                                    handle[LOCATE_END_KEY] = end
                                    handle[LOCATE_ANCHOR_KEY] = sourceAnchorJson
                                    handle[LOCATE_CHAPTER_KEY] = chapterIndex
                                }
                                navController.popBackStack()
                            }
                        )
                    }
                }
                pushComposable("library-companion") {
                    com.mozhi.reader.feature.companion.LibraryCompanionScreen(
                        onBack = navController::popBackStack,
                        onOpenStats = { navController.navigate("companion-stats") },
                        onLocate = { location ->
                            navController.navigate("reader/${location.bookId}")
                            navController.currentBackStackEntry?.savedStateHandle?.let { handle ->
                                handle[LOCATE_START_KEY] = location.start
                                handle[LOCATE_END_KEY] = location.end
                                handle[LOCATE_ANCHOR_KEY] = location.sourceAnchorJson
                                handle[LOCATE_CHAPTER_KEY] = location.chapterIndex
                            }
                        }
                    )
                }
                pushComposable("companion-stats") {
                    com.mozhi.reader.feature.companion.CompanionStatsScreen(onBack = navController::popBackStack)
                }
                pushComposable("persona/{personaId}") {
                    PersonaEditorScreen(
                        onBack = navController::popBackStack,
                        onOpenMemory = { personaId ->
                            navController.navigate("persona-memory/$personaId")
                        }
                    )
                }
                pushComposable("persona-memory/{personaId}") {
                    PersonaMemoryScreen(onBack = navController::popBackStack)
                }
                settingsComposable("provider/{providerId}", navController) {
                    ProviderDetailScreen(onBack = navController::popBackStack)
                }
                }
            }

            // Dock 是覆盖在内容上的浮层，不占 Scaffold 的 bottomBar 布局高度。
            // 否则 Scaffold 会在整屏底部预留一条矩形空白，看起来像胶囊背后的白横条。
            val settingsSession = settingsDestination(currentRoute) != null &&
                runCatching { navController.getBackStackEntry("settings") }.isSuccess
            if (expanded && settingsSession) {
                TabletSettingsSidebar(currentRoute,
                    onBackToLibrary = { selectRoot(RootDestination.Bookshelf) },
                    onSelect = navController::selectSettingsDestination,
                    modifier = Modifier.align(Alignment.CenterStart)
                )
            } else if (expanded && isRootRoute(currentRoute)) {
                MoReadTabletSidebar(
                    selectedRoute = currentRoute,
                    shelf = shelfState,
                    onSelect = selectRoot,
                    onReadState = { bookshelfViewModel.setReadStateFilter(it); selectRoot(RootDestination.Bookshelf) },
                    onGroup = { id, ungrouped -> bookshelfViewModel.selectGroup(id, ungrouped); selectRoot(RootDestination.Bookshelf) },
                    onTag = { bookshelfViewModel.toggleTagFilter(it); selectRoot(RootDestination.Bookshelf) },
                    onClearFilters = { bookshelfViewModel.clearFilter(); selectRoot(RootDestination.Bookshelf) },
                    onManageGroups = { navController.navigate("shelf-groups") },
                    onManageTags = { navController.navigate("shelf-tags") },
                    modifier = Modifier.align(Alignment.CenterStart)
                )
            }
            MoReadNavigationDock(
                visible = showBottomBar && !expanded,
                hazeState = hazeState,
                vertical = medium,
                modifier = Modifier.align(if (medium) Alignment.CenterStart else Alignment.BottomCenter),
                currentRoute = currentRoute,
                onSelect = selectRoot
            )
            AppUpdatePrompt()
        }
    }
}

/** Keep the selected capsule intact while the dock fades out with an outgoing root. */
@Composable
internal fun MoReadNavigationDock(
    visible: Boolean,
    hazeState: HazeState,
    vertical: Boolean,
    currentRoute: String?,
    modifier: Modifier = Modifier,
    onSelect: (RootDestination) -> Unit
) {
    var lastRootRoute by remember { mutableStateOf(RootDestination.Bookshelf.route) }
    val selectedRoute = currentRoute?.takeIf(::isRootRoute) ?: lastRootRoute
    SideEffect { lastRootRoute = selectedRoute }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(160))
    ) {
        AdaptiveNavDock(
            hazeState = hazeState,
            vertical = vertical,
            selectedRoute = selectedRoute,
            onSelect = { if (visible) onSelect(it) }
        )
    }
}

/**
 * Haze 玻璃导航舱：只对这块小面积常驻浮层做真实背景采样与模糊；长列表卡片仍使用
 * 低成本玻璃材质，避免整页大量离屏合成。
 */
@Composable
internal fun AdaptiveNavDock(
    hazeState: HazeState,
    vertical: Boolean,
    modifier: Modifier = Modifier,
    selectedRoute: String?,
    onSelect: (RootDestination) -> Unit
) {
    // 通栏样式只在手机宽度下成立；平板侧栏仍是竖向悬浮舱。
    if (!vertical && navStyle() == NavStyle.BAR) {
        FullWidthNavBar(
            hazeState = hazeState,
            modifier = modifier,
            selectedRoute = selectedRoute,
            onSelect = onSelect
        )
        return
    }
    Box(
        modifier = if (vertical) {
            modifier.padding(start = 14.dp)
        } else {
            modifier.fillMaxWidth()
                .windowInsetsPadding(stableNavigationInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(bottom = 10.dp)
        },
        contentAlignment = Alignment.Center
    ) {
        BlurredGlassSurface(
            hazeState = hazeState,
            shape = MoReadTokens.CapsuleShape,
            tint = MaterialTheme.colorScheme.surface,
            shadowElevation = 6.dp
        ) {
            val items: @Composable () -> Unit = {
                RootDestination.entries.forEach { item ->
                    NavDockItem(
                        item = item,
                        selected = selectedRoute == item.route,
                        vertical = vertical,
                        onClick = { onSelect(item) }
                    )
                }
            }
            if (vertical) {
                Column(
                    modifier = Modifier.padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) { items() }
            } else {
                Row(
                    modifier = Modifier.padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) { items() }
            }
        }
    }
}

@Composable
private fun NavDockItem(
    item: RootDestination,
    selected: Boolean,
    vertical: Boolean = false,
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
        if (vertical) {
            Column(
                modifier = Modifier.size(width = 56.dp, height = if (selected) 64.dp else 56.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = if (selected) item.selectedIcon else item.icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        } else Row(
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

/**
 * 通栏导航条：贴底通宽、图标在上标签在下、选中态是一枚指示胶囊（MD3 NavigationBar 语义）。
 * 与悬浮舱是同一组目的地、同一套选中色，只是布局与贴边方式不同。
 */
@Composable
private fun FullWidthNavBar(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    selectedRoute: String?,
    onSelect: (RootDestination) -> Unit
) {
    val items: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
                .windowInsetsPadding(
                    stableNavigationInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
                .heightIn(min = 80.dp)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RootDestination.entries.forEach { item ->
                NavBarItem(
                    item = item,
                    modifier = Modifier.weight(1f),
                    selected = selectedRoute == item.route,
                    onClick = { onSelect(item) }
                )
            }
        }
    }
    if (isFlatSurface()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            HorizontalDivider(color = sectionDivider())
            items()
        }
    } else {
        BlurredGlassSurface(
            hazeState = hazeState,
            modifier = modifier.fillMaxWidth(),
            shape = RectangleShape,
            tint = MaterialTheme.colorScheme.surface,
            shadowElevation = 0.dp
        ) { items() }
    }
}

@Composable
private fun NavBarItem(
    item: RootDestination,
    modifier: Modifier = Modifier,
    selected: Boolean,
    onClick: () -> Unit
) {
    // 扁平质感下指示胶囊用淡底 + 深字，与分段控件同一套口径；玻璃质感下维持实色填充。
    val flat = isFlatSurface()
    val indicator by animateColorAsState(
        targetValue = when {
            !selected -> Color.Transparent
            flat -> MaterialTheme.colorScheme.primaryContainer
            else -> navSelectedColor()
        },
        animationSpec = tween(durationMillis = 240),
        label = "nav-bar-indicator"
    )
    val iconColor by animateColorAsState(
        targetValue = when {
            !selected -> MaterialTheme.colorScheme.onSurfaceVariant
            flat -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> onNavSelectedColor()
        },
        animationSpec = tween(durationMillis = 220),
        label = "nav-bar-icon"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 220),
        label = "nav-bar-label"
    )
    Column(
        modifier = modifier
            .clip(MoReadTokens.CapsuleShape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .heightIn(min = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 32.dp)
                .clip(MoReadTokens.CapsuleShape)
                .background(indicator),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (selected) item.selectedIcon else item.icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = labelColor,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
