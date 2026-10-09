package com.mozhi.reader.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.R
import com.mozhi.reader.core.datastore.CompanionProcessMode
import com.mozhi.reader.ui.components.MoReadBlock
import com.mozhi.reader.ui.components.MoReadRow
import com.mozhi.reader.ui.components.MoReadRowDivider
import com.mozhi.reader.ui.components.MoReadSecondaryPage
import com.mozhi.reader.ui.components.MoReadSection
import com.mozhi.reader.ui.components.MoReadSegmented
import com.mozhi.reader.ui.components.MoReadSwitchRow
import com.mozhi.reader.ui.theme.SemanticSlot

/**
 * 「AI 与伴读」拆出的三张二级页：聊天显示、主动行为、记忆。
 * 一级页只留入口与一行状态摘要，开关都在这里；三页与一级页共用设置页的 [SettingsViewModel]。
 */
@Composable
fun ChatDisplaySettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val display = state.processDisplay
    MoReadSecondaryPage(title = stringResource(R.string.settings_chat_display), onBack = onBack) {
        item {
            MoReadSection(title = stringResource(R.string.settings_process_section), icon = Icons.Outlined.Psychology, tone = SemanticSlot.AI) {
                val labels = mapOf(
                    CompanionProcessMode.HIDDEN to stringResource(R.string.settings_process_mode_hidden),
                    CompanionProcessMode.COMPACT to stringResource(R.string.settings_process_mode_compact),
                    CompanionProcessMode.DETAILED to stringResource(R.string.settings_process_mode_detailed)
                )
                MoReadBlock(title = stringResource(R.string.settings_process_mode)) {
                    Column {
                        MoReadSegmented(
                            options = CompanionProcessMode.entries,
                            selected = display.mode,
                            onSelect = { viewModel.setProcessDisplay(display.copy(mode = it)) },
                            label = { labels.getValue(it) }
                        )
                        Text(
                            text = stringResource(
                                when (display.mode) {
                                    CompanionProcessMode.HIDDEN -> R.string.settings_process_mode_hint_hidden
                                    CompanionProcessMode.COMPACT -> R.string.settings_process_mode_hint_compact
                                    CompanionProcessMode.DETAILED -> R.string.settings_process_mode_hint_detailed
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                val visible = display.mode != CompanionProcessMode.HIDDEN
                MoReadRowDivider()
                MoReadSwitchRow(
                    icon = Icons.Outlined.Psychology,
                    title = stringResource(R.string.settings_process_reasoning),
                    subtitle = stringResource(R.string.settings_process_reasoning_summary),
                    checked = display.showReasoning,
                    enabled = visible,
                    onCheckedChange = { viewModel.setProcessDisplay(display.copy(showReasoning = it)) }
                )
                MoReadRowDivider()
                MoReadSwitchRow(
                    icon = Icons.Outlined.Code,
                    title = stringResource(R.string.settings_process_arguments),
                    subtitle = stringResource(R.string.settings_process_arguments_summary),
                    checked = display.showArguments,
                    enabled = visible,
                    onCheckedChange = { viewModel.setProcessDisplay(display.copy(showArguments = it)) }
                )
                MoReadRowDivider()
                MoReadSwitchRow(
                    icon = Icons.Outlined.Notes,
                    title = stringResource(R.string.settings_process_results),
                    subtitle = stringResource(R.string.settings_process_results_summary),
                    checked = display.showResults,
                    enabled = visible,
                    onCheckedChange = { viewModel.setProcessDisplay(display.copy(showResults = it)) }
                )
                MoReadRowDivider()
                MoReadSwitchRow(
                    icon = Icons.Outlined.UnfoldMore,
                    title = stringResource(R.string.settings_process_live),
                    subtitle = stringResource(R.string.settings_process_live_summary),
                    checked = display.expandWhileStreaming,
                    enabled = visible,
                    onCheckedChange = { viewModel.setProcessDisplay(display.copy(expandWhileStreaming = it)) }
                )
            }
        }
        item {
            MoReadSection(title = stringResource(R.string.settings_chat_bubbles_section), icon = Icons.Outlined.ChatBubbleOutline, tone = SemanticSlot.READING) {
                MoReadSwitchRow(
                    icon = Icons.Outlined.Bolt,
                    title = stringResource(R.string.settings_suggested_replies),
                    subtitle = stringResource(R.string.settings_suggested_replies_summary),
                    checked = state.suggestionRepliesEnabled,
                    onCheckedChange = viewModel::setSuggestionReplies
                )
                MoReadRowDivider()
                MoReadSwitchRow(
                    icon = Icons.Outlined.BorderColor,
                    title = stringResource(R.string.settings_show_ai_annotations),
                    subtitle = stringResource(R.string.settings_show_ai_annotations_summary),
                    checked = state.showAiAnnotations,
                    onCheckedChange = viewModel::setShowAiAnnotations
                )
                MoReadRowDivider()
                MoReadSwitchRow(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    title = stringResource(R.string.settings_multi_bubble),
                    subtitle = stringResource(R.string.settings_multi_bubble_summary),
                    checked = state.multiBubbleEnabled,
                    onCheckedChange = viewModel::setMultiBubble
                )
                MoReadRowDivider()
                MoReadSwitchRow(
                    icon = Icons.Outlined.Visibility,
                    title = stringResource(R.string.settings_token_usage),
                    subtitle = stringResource(R.string.settings_token_usage_summary),
                    checked = state.companionTokenUsageEnabled,
                    onCheckedChange = viewModel::setCompanionTokenUsage
                )
            }
        }
    }
}

/** 应用会替用户花钱的行为：每一项都默认关，副标题写清代价。 */
@Composable
fun ProactiveBehaviorSettingsScreen(
    onBack: () -> Unit,
    onOpenAnnotationLimits: () -> Unit,
    viewModel: SettingsViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MoReadSecondaryPage(title = stringResource(R.string.settings_section_proactive), onBack = onBack) {
        item {
            MoReadSection(
                title = stringResource(R.string.settings_section_proactive),
                icon = Icons.Outlined.Bolt,
                tone = SemanticSlot.CAUTION,
                footer = stringResource(R.string.settings_section_proactive_footer)
            ) {
                MoReadSwitchRow(
                    icon = Icons.Outlined.GraphicEq,
                    title = stringResource(R.string.settings_voice_replies),
                    subtitle = stringResource(R.string.settings_voice_replies_summary),
                    checked = state.autonomy.voiceRepliesEnabled,
                    onCheckedChange = viewModel::setVoiceReplies
                )
                MoReadRowDivider()
                MoReadSwitchRow(
                    icon = Icons.Outlined.Brush,
                    title = stringResource(R.string.settings_image_replies),
                    subtitle = stringResource(R.string.settings_image_replies_summary),
                    checked = state.autonomy.imageRepliesEnabled,
                    onCheckedChange = viewModel::setImageReplies
                )
                MoReadRowDivider()
                MoReadSwitchRow(
                    icon = Icons.Outlined.BorderColor,
                    title = stringResource(R.string.settings_proactive_annotations),
                    subtitle = state.autonomy.annotationLimits.timingSummary(),
                    checked = state.autonomy.proactiveAnnotationsEnabled,
                    onCheckedChange = viewModel::setProactiveAnnotations
                )
                MoReadRowDivider()
                MoReadRow(
                    icon = Icons.Outlined.Tune,
                    title = stringResource(R.string.settings_proactive_annotation_settings),
                    subtitle = stringResource(
                        R.string.settings_proactive_annotation_settings_summary,
                        state.autonomy.annotationLimits.summary()
                    ),
                    onClick = onOpenAnnotationLimits
                )
                MoReadRowDivider()
                MoReadSwitchRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = stringResource(R.string.settings_annotation_voice),
                    subtitle = stringResource(R.string.settings_annotation_voice_summary),
                    checked = state.autonomy.proactiveAnnotationVoiceEnabled,
                    enabled = state.autonomy.proactiveAnnotationsEnabled,
                    onCheckedChange = viewModel::setProactiveAnnotationVoice
                )
                MoReadRowDivider()
                MoReadSwitchRow(
                    icon = Icons.Outlined.Image,
                    title = stringResource(R.string.settings_annotation_image),
                    subtitle = stringResource(R.string.settings_annotation_image_summary),
                    checked = state.autonomy.proactiveAnnotationImageEnabled,
                    enabled = state.autonomy.proactiveAnnotationsEnabled,
                    onCheckedChange = viewModel::setProactiveAnnotationImage
                )
            }
        }
    }
}

@Composable
fun MemorySettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MoReadSecondaryPage(title = stringResource(R.string.settings_section_memory), onBack = onBack) {
        item {
            MoReadSection(title = stringResource(R.string.settings_section_memory), icon = Icons.Outlined.Psychology, tone = SemanticSlot.READING) {
                MoReadSwitchRow(
                    icon = Icons.Outlined.Bookmarks,
                    title = stringResource(R.string.settings_long_term_memory),
                    subtitle = stringResource(R.string.settings_long_term_memory_summary),
                    checked = state.memory.longTermEnabled,
                    onCheckedChange = viewModel::setLongTermMemory
                )
                MoReadRowDivider()
                MoReadSwitchRow(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    title = stringResource(R.string.settings_cross_book_memory),
                    subtitle = stringResource(R.string.settings_cross_book_memory_summary),
                    checked = state.memory.crossBookEnabled,
                    enabled = state.memory.longTermEnabled,
                    onCheckedChange = viewModel::setCrossBookMemory
                )
                MoReadRowDivider()
                MoReadSwitchRow(
                    icon = Icons.Outlined.Search,
                    title = stringResource(R.string.settings_cross_book_search),
                    subtitle = stringResource(R.string.settings_cross_book_search_summary),
                    checked = state.memory.crossBookChatSearchEnabled,
                    enabled = state.memory.longTermEnabled,
                    onCheckedChange = viewModel::setCrossBookChatSearch
                )
            }
        }
    }
}

/** 一级页「主动行为」入口的一行状态：开了几项。 */
@Composable
internal fun proactiveSummary(state: SettingsUiState): String {
    val autonomy = state.autonomy
    val enabled = listOf(
        autonomy.voiceRepliesEnabled,
        autonomy.imageRepliesEnabled,
        autonomy.proactiveAnnotationsEnabled
    ).count { it }
    return if (enabled == 0) stringResource(R.string.settings_proactive_all_off)
    else stringResource(R.string.settings_proactive_some_on, enabled)
}

@Composable
internal fun memorySummary(state: SettingsUiState): String = when {
    !state.memory.longTermEnabled -> stringResource(R.string.settings_memory_off)
    state.memory.crossBookEnabled -> stringResource(R.string.settings_memory_cross_book)
    else -> stringResource(R.string.settings_memory_on)
}
