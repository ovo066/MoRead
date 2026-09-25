package com.mozhi.reader.feature.review

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ui.components.*

/** Anchored to the options button, using the bookshelf's stable floating menu host. */
@Composable
internal fun ReviewOptionsMenu(state: ReadingReviewState, initial: ReviewFilter, onDismiss: () -> Unit,
    onApply: (ReviewFilter) -> Unit, onCompose: (List<ReviewEntry>) -> Unit, onExport: (List<ReviewEntry>) -> Unit,
    onReview: (List<ReviewEntry>) -> Unit, onFontChange: (String) -> Unit) {
    var page by rememberSaveable { mutableStateOf("home") }
    val scrolls = mapOf("home" to rememberScrollState(), "source" to rememberScrollState(), "kind" to rememberScrollState(),
        "persona" to rememberScrollState(), "sort" to rememberScrollState(), "font" to rememberScrollState())
    val settings = LocalReviewReaderSettings.current
    val fonts = reviewFontChoices(settings)
    val selected = remember(state.entries, initial) { filterReview(state.entries, initial) }
    val title = when (page) { "source" -> "记录来源"; "kind" -> "内容类型"; "persona" -> "伴读角色"
        "sort" -> "排列顺序"; "font" -> "划线字体"; else -> "回顾选项" }
    MoReadStableDropdownMenu(expanded = true, onDismissRequest = onDismiss,
        width = 280.dp, maxHeight = minOf(440.dp, (LocalConfiguration.current.screenHeightDp.dp - 140.dp).coerceAtLeast(160.dp)),
        modifier = Modifier.testTag("review-options-menu"), scrollState = scrolls.getValue(page),
        contentModifier = Modifier.testTag("review-options-list"), header = if (page == "home") null else { {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 14.dp, end = 8.dp).testTag("review-options-header"),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { page = "home" }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回上一级", Modifier.size(20.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            }
        } }) {
        if (page == "home") {
            MoReadMenuItem("来源", { page = "source" }, icon = Icons.Outlined.PeopleOutline,
                trailingText = initial.source.label, modifier = Modifier.testTag("review-choose-source"))
            if (initial.source == ReviewSource.AI) MoReadMenuItem("伴读角色", { page = "persona" }, icon = Icons.Outlined.Face,
                trailingText = initial.personaId?.let { id -> state.personas.firstOrNull { it.id == id }?.name ?: "已删除角色" } ?: "全部角色",
                modifier = Modifier.testTag("review-choose-persona"))
            MoReadMenuItem("内容", { page = "kind" }, icon = Icons.Outlined.FormatQuote,
                trailingText = initial.kind.label, modifier = Modifier.testTag("review-choose-kind"))
            MoReadMenuItem("排序", { page = "sort" }, icon = Icons.Outlined.Sort,
                trailingText = if (initial.oldestFirst) "最早记录" else "最近记录", modifier = Modifier.testTag("review-choose-sort"))
            MoReadMenuItem("划线字体", { page = "font" }, icon = Icons.Outlined.TextFields,
                trailingText = fonts.firstOrNull { it.first == settings.reviewFont }?.second, modifier = Modifier.testTag("review-choose-font"))
            MoReadMenuDivider()
            MoReadMenuItem("全屏翻阅", { onReview(selected) }, icon = Icons.Outlined.AutoStories, enabled = selected.isNotEmpty())
            MoReadMenuItem("导出记录", { onExport(selected) }, icon = Icons.Outlined.IosShare,
                trailingText = "${selected.size} 条", enabled = selected.isNotEmpty())
            MoReadMenuItem("与 AI 共创笔记", { onCompose(selected) }, icon = Icons.Outlined.AutoAwesome, enabled = selected.isNotEmpty())
        } else {
            val choices = when (page) {
                "source" -> ReviewSource.entries.map { it.name to it.label }
                "kind" -> ReviewKind.entries.map { it.name to it.label }
                "sort" -> listOf("false" to "最近记录", "true" to "最早记录")
                "font" -> fonts
                else -> listOf("all" to "全部角色") + state.entries.mapNotNull { it.personaId }.distinct().map { id ->
                    id.toString() to (state.personas.firstOrNull { it.id == id }?.name ?: "已删除角色") }
            }
            val active = when (page) { "source" -> initial.source.name; "kind" -> initial.kind.name
                "sort" -> initial.oldestFirst.toString(); "font" -> settings.reviewFont; else -> initial.personaId?.toString() ?: "all" }
            choices.forEach { (id, label) ->
                val choose = {
                    when (page) {
                        "source" -> onApply(initial.copy(source = ReviewSource.valueOf(id)))
                        "kind" -> onApply(initial.copy(kind = ReviewKind.valueOf(id)))
                        "sort" -> onApply(initial.copy(oldestFirst = id.toBoolean()))
                        "font" -> onFontChange(id)
                        "persona" -> onApply(initial.copy(personaId = id.toLongOrNull()))
                    }
                    page = "home"
                }
                if (page != "font" && page != "persona") MoReadMenuItem(label, choose, selected = id == active,
                    modifier = Modifier.testTag("review-$page-$id"))
                else Surface(onClick = choose,
                    color = if (id == active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f) else androidx.compose.ui.graphics.Color.Transparent,
                    modifier = Modifier.fillMaxWidth().testTag("review-$page-$id")) {
                    Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (page == "persona" && id != "all") {
                            PersonaAvatarImage(label, state.personas.firstOrNull { it.id.toString() == id }?.avatarPath, Modifier.size(36.dp))
                            Spacer(Modifier.width(12.dp))
                        }
                        val family = if (page == "font") {
                                val spec = reviewFontSpec(settings.copy(reviewFont = id), selected.firstOrNull()?.book?.id ?: 0L, com.mozhi.reader.ui.theme.isDarkTheme())
                                remember(spec) { FontFamily(spec.typeface()) }
                            } else FontFamily.SansSerif
                        Text(label, style = MaterialTheme.typography.bodyMedium, fontFamily = family, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (id == active) Icon(Icons.Outlined.Check, "已选", Modifier.padding(start = 8.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
