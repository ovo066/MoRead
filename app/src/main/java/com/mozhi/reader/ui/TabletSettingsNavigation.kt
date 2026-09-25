package com.mozhi.reader.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.mozhi.reader.R

internal enum class SettingsDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    @param:StringRes val groupRes: Int
) {
    REVIEW("settings-review", R.string.settings_review, Icons.Outlined.BorderColor, R.string.settings_section_reading),
    READING("settings-reading", R.string.settings_reading_appearance, Icons.Outlined.Palette, R.string.settings_section_reading),
    TTS("tts-settings", R.string.settings_tts, Icons.Outlined.RecordVoiceOver, R.string.settings_section_reading),
    DICTIONARIES("settings-dictionaries", R.string.settings_dictionaries, Icons.AutoMirrored.Outlined.MenuBook, R.string.settings_section_reading),
    VOCABULARY("settings-vocabulary", R.string.settings_vocabulary, Icons.Outlined.Bookmarks, R.string.settings_section_reading),
    SERVICES("ai-services", R.string.settings_ai_services, Icons.Outlined.Hub, R.string.settings_section_ai),
    COMPANION("settings-ai", R.string.settings_companion, Icons.Outlined.AutoAwesome, R.string.settings_section_ai),
    BACKUP("backup-settings", R.string.settings_backup, Icons.Outlined.CloudSync, R.string.settings_group_data_app),
    STORAGE("settings-data", R.string.settings_storage, Icons.Outlined.Storage, R.string.settings_group_data_app),
    ABOUT("settings-about", R.string.settings_about, Icons.Outlined.Info, R.string.settings_group_data_app)
}

internal fun settingsDestination(route: String?): SettingsDestination? = when (route) {
    "settings-review" -> SettingsDestination.REVIEW
    "settings", "settings-reading", "font-library", "image-library" -> SettingsDestination.READING
    "tts-settings", "tts-voices", "speech-cache" -> SettingsDestination.TTS
    "settings-dictionaries" -> SettingsDestination.DICTIONARIES
    "settings-vocabulary" -> SettingsDestination.VOCABULARY
    "ai-services", "provider/{providerId}" -> SettingsDestination.SERVICES
    "settings-ai", "annotation-limits", "annotation-prompts", "web-search-settings",
    "image-gen-settings", "global-presets", "user-masks" -> SettingsDestination.COMPANION
    "backup-settings" -> SettingsDestination.BACKUP
    "settings-data" -> SettingsDestination.STORAGE
    "settings-about", "api-log" -> SettingsDestination.ABOUT
    else -> null
}

/** A detail page keeps its normal back action when it opens a deeper editor. */
internal val LocalSettingsDetailRoot = compositionLocalOf { false }
internal val LocalSettingsPane = compositionLocalOf { false }

internal fun NavHostController.selectSettingsDestination(destination: SettingsDestination) {
    // The root already shows appearance on tablets; return to it instead of creating another copy.
    val target = if (destination == SettingsDestination.READING) "settings" else destination.route
    if (currentDestination?.route == target) return
    if (target == "settings") {
        popBackStack("settings", inclusive = false, saveState = true)
        return
    }
    navigate(target) {
        popUpTo("settings") { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
internal fun SettingsDetailPane(root: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSettingsPane provides true, LocalSettingsDetailRoot provides root, content = content)
}

/** Shared destinations also open from the reader; only reserve a sidebar in a settings session. */
internal fun NavGraphBuilder.settingsComposable(
    route: String,
    navController: NavHostController,
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
) = composable(route) { entry ->
    val inSettings = remember(entry) { runCatching { navController.getBackStackEntry("settings") }.isSuccess }
    val expanded = rememberMoReadWindowWidth() == MoReadWindowWidth.EXPANDED && inSettings
    Box(Modifier.fillMaxSize().padding(start = if (expanded) MoReadLayoutPolicy.TabletSidebarWidthDp.dp else 0.dp)) {
        CompositionLocalProvider(
            LocalSettingsPane provides expanded,
            LocalSettingsDetailRoot provides (expanded && SettingsDestination.entries.any { it.route == route })
        ) { content(entry) }
    }
}

@Composable
internal fun TabletSettingsSidebar(
    currentRoute: String?,
    onSelect: (SettingsDestination) -> Unit,
    onBackToLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = settingsDestination(currentRoute)
    Column(modifier.width(MoReadLayoutPolicy.TabletSidebarWidthDp.dp).fillMaxHeight()
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .windowInsetsPadding(stableNavigationInsets()).testTag("settings-sidebar")) {
        TextButton(onClick = onBackToLibrary, modifier = Modifier.padding(start = 12.dp, top = 12.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.sidebar_library))
        }
        Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 20.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SettingsDestination.entries.groupBy { it.groupRes }.forEach { (group, destinations) ->
                Text(stringResource(group), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 10.dp))
                destinations.forEach { destination ->
                    val active = destination == selected
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .selectable(active, role = Role.Tab, onClick = {
                            onSelect(destination)
                        }).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(destination.icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(destination.labelRes), style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
