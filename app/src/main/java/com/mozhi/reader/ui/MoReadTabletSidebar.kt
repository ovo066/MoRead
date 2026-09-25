package com.mozhi.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mozhi.reader.R
import com.mozhi.reader.core.database.entity.BookReadState
import com.mozhi.reader.core.database.entity.readState
import com.mozhi.reader.feature.bookshelf.BookshelfUiState

/** A persistent library navigator. Its scroll position is independent of the selected page. */
@Composable
internal fun MoReadTabletSidebar(
    selectedRoute: String?,
    shelf: BookshelfUiState,
    onSelect: (RootDestination) -> Unit,
    onReadState: (BookReadState?) -> Unit,
    onGroup: (Long?, Boolean) -> Unit,
    onTag: (Long) -> Unit,
    onClearFilters: () -> Unit,
    onManageGroups: () -> Unit,
    onManageTags: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val inLibrary = selectedRoute == RootDestination.Bookshelf.route
    var groupsExpanded by rememberSaveable { mutableStateOf(true) }
    var tagsExpanded by rememberSaveable { mutableStateOf(true) }
    val counts = remember(shelf.allBooks) { shelf.allBooks.groupingBy { it.readState() }.eachCount() }
    Surface(color = colors.surfaceContainerLow, modifier = modifier
        .width(MoReadLayoutPolicy.TabletSidebarWidthDp.dp).fillMaxHeight().testTag("tablet-sidebar")) {
        Column(Modifier.windowInsetsPadding(stableNavigationInsets().only(WindowInsetsSides.Vertical))
            .padding(horizontal = 16.dp)) {
            Column(Modifier.padding(start = 12.dp, top = 30.dp, bottom = 30.dp)) {
                Text("MoRead", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                    fontSize = 29.sp, color = colors.onSurface)
                Text(stringResource(R.string.sidebar_tagline), style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("tablet-sidebar-scroll")) {
                listOf(RootDestination.Bookshelf, RootDestination.Companion, RootDestination.Stats).forEach { destination ->
                    SidebarItem(
                        label = when (destination) {
                            RootDestination.Bookshelf -> stringResource(R.string.sidebar_my_library)
                            RootDestination.Companion -> stringResource(R.string.sidebar_ai_companion)
                            else -> stringResource(R.string.sidebar_reading_stats)
                        },
                        icon = destination.icon,
                        selected = selectedRoute == destination.route,
                        tag = "tablet-nav-${destination.route}",
                        onClick = { onSelect(destination) }
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 18.dp), color = colors.outlineVariant.copy(alpha = .5f))
                Text(stringResource(R.string.sidebar_library), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
                SidebarItem(stringResource(R.string.sidebar_all_books), Icons.AutoMirrored.Outlined.LibraryBooks, inLibrary && !shelf.filter.isActive,
                    count = shelf.totalBooks, tag = "tablet-filter-all", onClick = onClearFilters)
                listOf(
                    Triple(BookReadState.READING, stringResource(R.string.sidebar_filter_reading), Icons.Outlined.AutoStories),
                    Triple(BookReadState.UNREAD, stringResource(R.string.sidebar_filter_unread), Icons.Outlined.BookmarkBorder),
                    Triple(BookReadState.FINISHED, stringResource(R.string.sidebar_filter_finished), Icons.Outlined.CheckCircleOutline),
                    Triple(BookReadState.SHELVED, stringResource(R.string.sidebar_filter_shelved), Icons.Outlined.PauseCircleOutline)
                ).forEach { (state, label, icon) ->
                    SidebarItem(label, icon, inLibrary && shelf.filter.readState == state,
                        count = counts[state] ?: 0, tag = "tablet-filter-${state.name}",
                        onClick = { onReadState(if (shelf.filter.readState == state) null else state) })
                }
                SidebarSection(stringResource(R.string.sidebar_my_groups), groupsExpanded, { groupsExpanded = !groupsExpanded }, onManageGroups)
                if (groupsExpanded) {
                    shelf.groups.forEach { group ->
                        SidebarItem(group.name, Icons.Outlined.FolderOpen,
                            inLibrary && shelf.filter.groupId == group.id,
                            count = shelf.groupCounts[group.id] ?: 0, tag = "tablet-group-${group.id}",
                            onClick = { onGroup(group.id, false) })
                    }
                    SidebarItem(stringResource(R.string.sidebar_ungrouped), Icons.Outlined.FolderOpen, inLibrary && shelf.filter.ungroupedOnly,
                        count = shelf.groupCounts[null] ?: 0, onClick = { onGroup(null, true) })
                }
                SidebarSection(stringResource(R.string.sidebar_tags), tagsExpanded, { tagsExpanded = !tagsExpanded }, onManageTags)
                if (tagsExpanded) {
                    shelf.tags.forEach { tag ->
                        SidebarItem(tag.name, Icons.AutoMirrored.Outlined.Label,
                            inLibrary && tag.id in shelf.filter.tagIds,
                            count = shelf.tagCounts[tag.id] ?: 0, tag = "tablet-tag-${tag.id}",
                            onClick = { onTag(tag.id) })
                    }
                    if (shelf.tags.isEmpty()) {
                        Text(stringResource(R.string.sidebar_tags_empty), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = .5f))
            Box(Modifier.padding(vertical = 12.dp)) {
                SidebarItem(stringResource(R.string.nav_settings), Icons.Outlined.Settings, selectedRoute == RootDestination.Settings.route,
                    tag = "tablet-nav-settings", onClick = { onSelect(RootDestination.Settings) })
            }
        }
    }
}

@Composable
private fun SidebarSection(title: String, expanded: Boolean, onToggle: () -> Unit, onManage: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onToggle, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.sidebar_section_collapse else R.string.sidebar_section_expand, title), modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onManage, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Outlined.Add, stringResource(R.string.sidebar_section_manage, title), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SidebarItem(
    label: String, icon: ImageVector, selected: Boolean, count: Int? = null,
    tag: String = "tablet-item-$label", onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).testTag(tag).clip(RoundedCornerShape(12.dp))
        .background(if (selected) colors.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
        .selectable(selected = selected, role = Role.Tab, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.onSecondaryContainer else colors.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        count?.let { Text(it.toString(), style = MaterialTheme.typography.labelMedium, color = color) }
    }
}
