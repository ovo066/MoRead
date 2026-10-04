package com.mozhi.reader.feature.reader

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.R
import com.mozhi.reader.core.dictionary.*
import com.mozhi.reader.ui.components.*
import com.mozhi.reader.ui.theme.MoReadSpacing
import com.mozhi.reader.ui.theme.MoReadTokens
import com.mozhi.reader.ui.theme.fieldContainerColor
import com.mozhi.reader.ui.theme.moReadMetrics
import com.mozhi.reader.ui.theme.sectionCardColor
import com.mozhi.reader.ui.theme.sectionHairline
import java.time.ZoneId
import java.util.Calendar
import kotlinx.coroutines.launch

@Composable
internal fun VocabularyDialog(bookId: Long = 0, palette: ReaderPalette = companionChatPalette(),
    onDismiss: () -> Unit, viewModel: EnglishLearningViewModel = hiltViewModel()) {
    ReaderToolDialog(onDismiss) { VocabularyPage(bookId, palette, onDismiss, viewModel) }
}

/**
 * 生词本：概览三格兼筛选 → 按收藏时间分段的词卡。
 *
 * 词卡只放「一眼要看的」：词头、读音、词下短释义、两行释义摘要、带高亮的原文语境；
 * 完整释义点卡片进词典面板。掌握与否是学习里最频繁的动作，放在卡片右上角一触即达；
 * 编辑与移出收进「更多」（长按卡片同样打开）。改动即时生效，底部提示条可撤销。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VocabularyPage(bookId: Long = 0, palette: ReaderPalette = companionChatPalette(),
    onBack: () -> Unit, viewModel: EnglishLearningViewModel = hiltViewModel(), panelBack: Boolean = false) {
    val settings by viewModel.readerSettings.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(VocabularyFilter.ALL) }
    var editing by remember { mutableStateOf<VocabularyWord?>(null) }
    var lookup by remember { mutableStateOf<VocabularyWord?>(null) }
    val listState = rememberLazyListState()
    val searchFloating by remember { derivedStateOf { listState.isHeaderPinned(SEARCH_HEADER_KEY) } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    val haptics = LocalHapticFeedback.current

    val vocabulary = settings.vocabulary
    val stats = remember(vocabulary) { vocabularyStats(vocabulary) }
    val groups = remember(vocabulary, query, filter) {
        vocabularyGroups(vocabulary, query, filter, System.currentTimeMillis(), ZoneId.systemDefault())
    }

    // 所有改动先落库再给撤销：撤销就是把改动前的那一份原样写回（按词头覆盖，收藏时间不变）。
    fun commit(before: VocabularyWord, after: VocabularyWord?, message: Int) {
        if (after == null) viewModel.updateWord(before, remove = true) else viewModel.updateWord(after)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(resources.getString(message, before.word),
                actionLabel = resources.getString(R.string.vocabulary_undo), duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) viewModel.updateWord(before)
        }
    }
    fun toggleLearned(word: VocabularyWord) {
        haptics.performHapticFeedback(if (word.learned) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
        commit(word, word.copy(learned = !word.learned),
            if (word.learned) R.string.vocabulary_marked_learning else R.string.vocabulary_marked_learned)
    }

    Box(Modifier.fillMaxSize()) {
        ReaderToolPage(title = stringResource(R.string.settings_vocabulary), onBack = onBack, listState = listState,
            modifier = Modifier.imePadding(), panelBack = panelBack, itemSpacing = 0.dp,
            scrollingTopBar = true) {
            item(key = "vocabulary-top-gap", contentType = "gap") { Spacer(Modifier.height(MoReadSpacing.m)) }
            stickyHeader(key = SEARCH_HEADER_KEY) {
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    MoReadSearchCapsule(query, { query = it }, floating = searchFloating,
                        placeholder = stringResource(R.string.vocabulary_search_placeholder), testTagPrefix = "vocabulary")
                }
            }
            if (stats.total == 0) {
                item(key = "vocabulary-empty", contentType = "empty") {
                    VocabularyEmptyState(stringResource(R.string.vocabulary_empty_title),
                        stringResource(R.string.vocabulary_empty_body))
                }
                return@ReaderToolPage
            }
            item(key = "vocabulary-overview", contentType = "overview") {
                VocabularyOverview(stats, filter, onFilter = { filter = it },
                    modifier = Modifier.padding(top = MoReadSpacing.s))
            }
            if (groups.isEmpty()) item(key = "vocabulary-no-result", contentType = "empty") {
                VocabularyEmptyState(title = null, body = when {
                    query.isNotBlank() -> stringResource(R.string.vocabulary_no_match, query.trim())
                    filter == VocabularyFilter.LEARNING -> stringResource(R.string.vocabulary_empty_learning)
                    else -> stringResource(R.string.vocabulary_empty_learned)
                })
            }
            groups.forEach { group ->
                item(key = "vocabulary-group:${group.period}", contentType = "group") {
                    SectionLabel(vocabularyPeriodLabel(group.period), trailing = group.words.size.toString(),
                        modifier = Modifier.animateItem()
                            .padding(start = MoReadSpacing.xs, top = MoReadTokens.SectionGap, bottom = MoReadSpacing.m))
                }
                items(group.words, key = { "vocabulary-word:${it.word}" }, contentType = { "word" }) { word ->
                    VocabularyCard(word,
                        onOpen = { lookup = word },
                        onToggleLearned = { toggleLearned(word) },
                        onEdit = { editing = word },
                        onRemove = { commit(word, null, R.string.vocabulary_removed) },
                        modifier = Modifier.animateItem().padding(bottom = MoReadSpacing.s + 2.dp))
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding()
            .padding(horizontal = MoReadSpacing.xl, vertical = MoReadSpacing.l))
    }
    editing?.let { word ->
        VocabularyGlossSheet(word, onDismiss = { editing = null }, onSave = {
            viewModel.updateWord(it)
            editing = null
        })
    }
    lookup?.let { word -> DictionaryLookupDialog(word.bookId.takeIf { it != 0L } ?: bookId,
        DictionaryLookupHit(word.word, word.context, word.chapterIndex, word.offset), settings, palette,
        onDismiss = { lookup = null }, viewModel = viewModel) }
}

private const val SEARCH_HEADER_KEY = "vocabulary-search-header"

/** 吸顶条是否已经压在内容之上（不是刚好停在自己的原位）。 */
private fun LazyListState.isHeaderPinned(key: Any): Boolean {
    val header = layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return false
    return firstVisibleItemIndex > header.index ||
        (firstVisibleItemIndex == header.index && firstVisibleItemScrollOffset > 0)
}

@Composable
private fun vocabularyPeriodLabel(period: VocabularyPeriod): String = when (period) {
    VocabularyPeriod.Today -> stringResource(R.string.vocabulary_period_today)
    VocabularyPeriod.Yesterday -> stringResource(R.string.vocabulary_period_yesterday)
    VocabularyPeriod.PastWeek -> stringResource(R.string.vocabulary_period_past_week)
    is VocabularyPeriod.Month -> {
        val locale = LocalConfiguration.current.locales[0]
        remember(period, locale) {
            // 今年只写月份；按系统的本地化骨架出格式（中文「8月」、英文「August」），不拼接文案。
            val calendar = Calendar.getInstance(locale).apply { clear(); set(period.year, period.month - 1, 1) }
            val skeleton = if (period.year == Calendar.getInstance().get(Calendar.YEAR)) "MMMM" else "yMMMM"
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
            java.text.SimpleDateFormat(pattern, locale).format(calendar.time)
        }
    }
}

@Composable
private fun VocabularyOverview(stats: VocabularyStats, selected: VocabularyFilter,
    onFilter: (VocabularyFilter) -> Unit, modifier: Modifier = Modifier) {
    val shape = moReadMetrics().cardShape
    val progress by animateFloatAsState(stats.masteredFraction, tween(420), label = "vocabulary-mastery")
    val track = fieldContainerColor()
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth().clip(shape).background(sectionCardColor()).border(1.dp, sectionHairline(), shape)
        .padding(start = MoReadSpacing.xs, top = MoReadSpacing.xs, end = MoReadSpacing.xs, bottom = MoReadSpacing.m)) {
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.xs)) {
            VocabularyFilter.entries.forEach { filter ->
                OverviewTile(stats.count(filter), vocabularyFilterLabel(filter), filter == selected,
                    Modifier.weight(1f)) { onFilter(filter) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = MoReadSpacing.m, end = MoReadSpacing.m, top = MoReadSpacing.s),
            verticalAlignment = Alignment.CenterVertically) {
            // 进度只在绘制阶段读，动画期间不触发重组。
            Box(Modifier.weight(1f).height(4.dp).drawBehind {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(track, cornerRadius = radius)
                if (progress > 0f) drawRoundRect(accent, size = Size(size.width * progress, size.height), cornerRadius = radius)
            })
            Text(stringResource(R.string.vocabulary_mastery_progress, (stats.masteredFraction * 100).toInt()),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = MoReadSpacing.m))
        }
    }
}

@Composable
private fun vocabularyFilterLabel(filter: VocabularyFilter) = stringResource(when (filter) {
    VocabularyFilter.ALL -> R.string.vocabulary_filter_all
    VocabularyFilter.LEARNING -> R.string.vocabulary_filter_learning
    VocabularyFilter.LEARNED -> R.string.vocabulary_filter_learned
})

@Composable
private fun OverviewTile(count: Int, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val metrics = moReadMetrics()
    val container by animateColorAsState(if (selected) fieldContainerColor() else Color.Transparent,
        tween(160), label = "vocabulary-tile")
    val number by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant, tween(160), label = "vocabulary-tile-number")
    Column(modifier.clip(RoundedCornerShape(metrics.radiusCard - MoReadSpacing.xs)).background(container)
        .selectable(selected = selected, role = Role.Tab, onClick = onClick)
        .padding(vertical = MoReadSpacing.m), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(count.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            color = number, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(top = 2.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VocabularyCard(word: VocabularyWord, onOpen: () -> Unit, onToggleLearned: () -> Unit,
    onEdit: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val shape = moReadMetrics().cardShape
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    // 已掌握的词退到背景里，但仍可读：学过的词偶尔也要回看。
    val contentAlpha by animateFloatAsState(if (word.learned) .56f else 1f, tween(220), label = "vocabulary-learned")
    val preview = remember(word.word, word.definition, word.gloss) {
        vocabularyPreview(word.word, word.definition, word.gloss)
    }
    Column(modifier.fillMaxWidth().clip(shape).background(sectionCardColor()).border(1.dp, sectionHairline(), shape)
        .combinedClickable(onClick = onOpen, onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            menu = true
        })
        .padding(start = MoReadSpacing.l, top = MoReadSpacing.xs, end = MoReadSpacing.xs, bottom = MoReadSpacing.l)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).graphicsLayer { alpha = contentAlpha }) {
                Text(word.word, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alignByBaseline().weight(1f, fill = false))
                if (word.phonetic.isNotBlank()) {
                    Text(word.phonetic, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.alignByBaseline().padding(start = MoReadSpacing.s))
                }
            }
            MasteryToggle(word.learned, onToggleLearned)
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.vocabulary_more_actions, word.word),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                MoReadDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MoReadMenuItem(stringResource(if (word.learned) R.string.vocabulary_mark_learning else R.string.vocabulary_mark_learned),
                        onClick = { menu = false; onToggleLearned() },
                        icon = if (word.learned) Icons.Outlined.Replay else Icons.Outlined.TaskAlt)
                    MoReadMenuItem(stringResource(R.string.vocabulary_edit_gloss), onClick = { menu = false; onEdit() },
                        icon = Icons.Outlined.EditNote)
                    MoReadMenuDivider()
                    MoReadMenuItem(stringResource(R.string.vocabulary_remove), onClick = { menu = false; onRemove() },
                        icon = Icons.Outlined.DeleteOutline, destructive = true)
                }
            }
        }
        Column(Modifier.padding(end = MoReadSpacing.m).alpha(contentAlpha),
            verticalArrangement = Arrangement.spacedBy(MoReadSpacing.xs)) {
            if (word.gloss.isNotBlank()) {
                Text(word.gloss, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (preview.isNotBlank()) {
                Text(preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (word.context.isNotBlank()) ContextQuote(word.context, word.word, Modifier.padding(top = MoReadSpacing.xs))
        }
    }
}

/** 掌握开关：空心圈 ↔ 实心勾，勾上时轻轻弹一下。 */
@Composable
private fun MasteryToggle(learned: Boolean, onToggle: () -> Unit) {
    val pop = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(learned) }
    LaunchedEffect(learned) {
        if (learned && !previous) {
            pop.snapTo(.72f)
            pop.animateTo(1f, spring(dampingRatio = .42f, stiffness = 520f))
        }
        previous = learned
    }
    val description = stringResource(R.string.vocabulary_filter_learned)
    IconToggleButton(checked = learned, onCheckedChange = { onToggle() },
        modifier = Modifier.semantics { contentDescription = description }) {
        Crossfade(learned, animationSpec = tween(160), label = "vocabulary-mastery-icon") { done ->
            Icon(if (done) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, contentDescription = null,
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f),
                modifier = Modifier.size(22.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value })
        }
    }
}

/** 原文语境：左侧一道细线引出，句中的这个词加粗提亮，一眼找到它在哪。 */
@Composable
private fun ContextQuote(context: String, word: String, modifier: Modifier = Modifier) {
    val emphasis = MaterialTheme.colorScheme.onSurface
    val text = remember(context, word, emphasis) {
        val shown = context.trim().take(240)
        buildAnnotatedString {
            append(shown)
            vocabularyContextHighlight(shown, word)?.let {
                addStyle(SpanStyle(color = emphasis, fontWeight = FontWeight.SemiBold), it.first, it.last + 1)
            }
        }
    }
    Row(modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = MoReadSpacing.s + 2.dp))
    }
}

@Composable
private fun VocabularyEmptyState(title: String?, body: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = MoReadSpacing.xl, vertical = MoReadSpacing.xxl + MoReadSpacing.l),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (title != null) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(fieldContainerColor()), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = MoReadSpacing.l))
        }
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = if (title != null) MoReadSpacing.s else 0.dp))
    }
}

/** 词下标注编辑：底部弹层，两个输入框 + 一行按钮，键盘弹起时整体上推。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VocabularyGlossSheet(word: VocabularyWord, onDismiss: () -> Unit, onSave: (VocabularyWord) -> Unit) {
    var gloss by rememberSaveable(word.word) { mutableStateOf(word.gloss) }
    var phonetic by rememberSaveable(word.word) { mutableStateOf(word.phonetic) }
    MoReadBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = MoReadSpacing.l, end = MoReadSpacing.l, bottom = MoReadSpacing.xl)) {
            Text(stringResource(R.string.vocabulary_edit_gloss), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(word.word, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = MoReadSpacing.xs, bottom = MoReadSpacing.l))
            MoReadFieldGroup {
                MoReadTextField(gloss, { gloss = it.take(24) }, label = stringResource(R.string.vocabulary_gloss_label),
                    supporting = stringResource(R.string.vocabulary_gloss_supporting))
                MoReadTextField(phonetic, { phonetic = it.take(64) }, label = stringResource(R.string.vocabulary_phonetic_label))
            }
            Row(Modifier.fillMaxWidth().padding(top = MoReadSpacing.xl), horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.m)) {
                MoReadButton(stringResource(R.string.vocabulary_cancel), onDismiss, Modifier.weight(1f), style = MoReadButtonStyle.Outlined)
                MoReadButton(stringResource(R.string.vocabulary_save),
                    { onSave(word.copy(gloss = gloss.trim(), phonetic = phonetic.trim())) }, Modifier.weight(1f))
            }
        }
    }
}
