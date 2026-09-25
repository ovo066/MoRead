package com.mozhi.reader.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.BuildConfig
import com.mozhi.reader.R
import com.mozhi.reader.core.datastore.ShelfLayout
import com.mozhi.reader.ui.components.MoReadBlock
import com.mozhi.reader.ui.components.MoReadButton
import com.mozhi.reader.ui.components.MoReadButtonStyle
import com.mozhi.reader.ui.components.MoReadRootPage
import com.mozhi.reader.ui.components.MoReadRow
import com.mozhi.reader.ui.components.MoReadRowAction
import com.mozhi.reader.ui.components.MoReadRowDivider
import com.mozhi.reader.ui.components.MoReadSecondaryPage
import com.mozhi.reader.ui.components.MoReadSection
import com.mozhi.reader.ui.components.MoReadSegmented
import com.mozhi.reader.ui.components.MoReadSwitchRow
import com.mozhi.reader.ui.components.NoteStyleColorPalette
import com.mozhi.reader.ui.theme.AccentPreset
import com.mozhi.reader.ui.theme.AppearanceSettings
import com.mozhi.reader.ui.theme.ColorSchemePreset
import com.mozhi.reader.ui.theme.MoReadSpacing
import com.mozhi.reader.ui.theme.NavStyle
import com.mozhi.reader.ui.theme.SemanticHarmony
import com.mozhi.reader.ui.theme.SemanticSlot
import com.mozhi.reader.ui.theme.ShapeStyle
import com.mozhi.reader.ui.theme.SurfaceStyle
import com.mozhi.reader.ui.theme.ThemeMode
import com.mozhi.reader.ui.theme.accentColor
import com.mozhi.reader.ui.theme.isAvailable
import com.mozhi.reader.ui.theme.isDarkTheme
import com.mozhi.reader.ui.theme.moReadMetrics
import com.mozhi.reader.ui.theme.onAccent
import com.mozhi.reader.ui.theme.rememberSchemeSide
import java.util.Locale

/** 设置首页按用户目标分组，常用入口直接可达，复杂选项再进入二级页。 */
@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    onOpenReading: () -> Unit,
    onOpenTts: () -> Unit,
    onOpenAiServices: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenData: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    onOpenDictionaries: () -> Unit = {},
    onOpenVocabulary: () -> Unit = {},
    onOpenReadingReview: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MoReadRootPage(title = stringResource(R.string.nav_settings), contentPadding = contentPadding) {
        item {
            MoReadSection(title = stringResource(R.string.settings_section_reading), icon = Icons.AutoMirrored.Outlined.MenuBook, tone = SemanticSlot.READING) {
                MoReadRow(icon = Icons.Outlined.BorderColor, title = stringResource(R.string.settings_review),
                    subtitle = stringResource(R.string.settings_review_summary), onClick = onOpenReadingReview)
                MoReadRowDivider()
                MoReadRow(
                    icon = Icons.Outlined.Palette,
                    title = stringResource(R.string.settings_reading_appearance),
                    subtitle = stringResource(R.string.settings_reading_appearance_summary),
                    onClick = onOpenReading
                )
                MoReadRowDivider()
                MoReadRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = stringResource(R.string.settings_tts),
                    subtitle = stringResource(R.string.settings_tts_summary),
                    onClick = onOpenTts
                )
                MoReadRowDivider()
                MoReadRow(icon = Icons.AutoMirrored.Outlined.MenuBook, title = stringResource(R.string.settings_dictionaries),
                    subtitle = stringResource(R.string.settings_dictionaries_summary), onClick = onOpenDictionaries)
                MoReadRowDivider()
                MoReadRow(icon = Icons.Outlined.Bookmarks, title = stringResource(R.string.settings_vocabulary),
                    subtitle = stringResource(R.string.settings_vocabulary_summary), onClick = onOpenVocabulary)
            }
        }
        item {
            MoReadSection(title = stringResource(R.string.settings_section_ai), icon = Icons.Outlined.AutoAwesome, tone = SemanticSlot.AI) {
                MoReadRow(
                    icon = Icons.Outlined.Hub,
                    title = stringResource(R.string.settings_ai_services),
                    subtitle = aiServiceSummary(state.providers.size, state.models.size),
                    onClick = onOpenAiServices
                )
                MoReadRowDivider()
                MoReadRow(
                    icon = Icons.Outlined.Psychology,
                    title = stringResource(R.string.settings_companion),
                    subtitle = stringResource(R.string.settings_companion_summary),
                    onClick = onOpenAi
                )
            }
        }
        item {
            MoReadSection(title = stringResource(R.string.settings_section_data), icon = Icons.Outlined.Storage, tone = SemanticSlot.KNOWLEDGE) {
                MoReadRow(
                    icon = Icons.Outlined.CloudSync,
                    title = stringResource(R.string.settings_backup),
                    subtitle = stringResource(R.string.settings_backup_summary),
                    onClick = onOpenBackup
                )
                MoReadRowDivider()
                MoReadRow(
                    icon = Icons.Outlined.Storage,
                    title = stringResource(R.string.settings_storage),
                    subtitle = state.localStorageBytes?.let { stringResource(R.string.settings_storage_used, formatBytes(it)) }
                        ?: stringResource(R.string.settings_storage_summary),
                    onClick = onOpenData
                )
            }
        }
        item {
            MoReadSection(title = stringResource(R.string.settings_section_app), icon = Icons.Outlined.Info) {
                MoReadRow(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.settings_about),
                    subtitle = stringResource(R.string.settings_about_summary, BuildConfig.VERSION_NAME),
                    onClick = onOpenAbout
                )
            }
        }
    }
}
@Composable
fun AiAndCompanionSettingsScreen(
    onBack: () -> Unit,
    onOpenAiServices: () -> Unit,
    onOpenAnnotationLimits: () -> Unit,
    onOpenWebSearch: () -> Unit,
    onOpenGlobalPresets: () -> Unit,
    onOpenUserMasks: () -> Unit,
    onOpenTtsSettings: () -> Unit,
    onOpenVoiceLibrary: () -> Unit,
    onOpenImageGenSettings: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MoReadSecondaryPage(title = stringResource(R.string.settings_ai_companion_title), onBack = onBack) {
        item {
            MoReadSection(title = stringResource(R.string.settings_ai_services), icon = Icons.Outlined.AutoAwesome, tone = SemanticSlot.AI) {
                MoReadRow(icon = Icons.Outlined.Hub, title = stringResource(R.string.settings_ai_services), subtitle = aiServiceSummary(state.providers.size, state.models.size), onClick = onOpenAiServices)
                MoReadRowDivider()
                MoReadRow(icon = Icons.Outlined.Language, title = stringResource(R.string.settings_web_search), subtitle = stringResource(R.string.settings_web_search_summary), onClick = onOpenWebSearch)
                MoReadRowDivider()
                MoReadRow(icon = Icons.Outlined.Tune, title = stringResource(R.string.settings_global_presets), subtitle = stringResource(R.string.settings_global_presets_summary), onClick = onOpenGlobalPresets)
                MoReadRowDivider()
                MoReadRow(icon = Icons.Outlined.PersonOutline, title = stringResource(R.string.settings_user_masks), subtitle = stringResource(R.string.settings_user_masks_summary), onClick = onOpenUserMasks)
                MoReadRowDivider()
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
                    icon = Icons.Outlined.Bolt,
                    title = stringResource(R.string.settings_token_usage),
                    subtitle = stringResource(R.string.settings_token_usage_summary),
                    checked = state.companionTokenUsageEnabled,
                    onCheckedChange = viewModel::setCompanionTokenUsage
                )
            }
        }
        item {
            MoReadSection(title = stringResource(R.string.settings_section_voice_image), icon = Icons.Outlined.GraphicEq, tone = SemanticSlot.KNOWLEDGE) {
                MoReadRow(icon = Icons.Outlined.RecordVoiceOver, title = stringResource(R.string.settings_tts_engine), subtitle = stringResource(R.string.settings_tts_engine_summary), onClick = onOpenTtsSettings)
                MoReadRowDivider()
                MoReadRow(icon = Icons.Outlined.LibraryMusic, title = stringResource(R.string.settings_voice_library), subtitle = stringResource(R.string.settings_voice_library_summary), onClick = onOpenVoiceLibrary)
                MoReadRowDivider()
                MoReadRow(icon = Icons.Outlined.Brush, title = stringResource(R.string.settings_image_gen), subtitle = stringResource(R.string.settings_image_gen_summary), onClick = onOpenImageGenSettings)
            }
        }
        item {
            // 这一组专收「应用替用户掏钱」的行为：每一项都默认关，副标题写清代价。
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

@Composable
fun ReadingAppearanceSettingsScreen(
    onBack: () -> Unit,
    onOpenFontLibrary: () -> Unit,
    onOpenImageLibrary: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MoReadSecondaryPage(title = stringResource(R.string.settings_reading_appearance), onBack = onBack) {
        item {
            MoReadSection(title = stringResource(R.string.settings_section_app_appearance), icon = Icons.Outlined.Palette, tone = SemanticSlot.READING) {
                AppLanguageBlock()
                MoReadRowDivider(inset = MoReadSpacing.l)
                AppearanceCard(
                    appearance = state.appearance,
                    onThemeModeChange = viewModel::setThemeMode,
                    onAccentChange = viewModel::setAccentPreset,
                    onCustomAccent = viewModel::setCustomAccent,
                    onColorSchemeChange = viewModel::setColorScheme,
                    onSemanticHarmonyChange = viewModel::setSemanticHarmony,
                    onSurfaceStyleChange = viewModel::setSurfaceStyle,
                    onNavStyleChange = viewModel::setNavStyle,
                    onShapeStyleChange = viewModel::setShapeStyle
                )
                MoReadRowDivider()
                MoReadRow(icon = Icons.Outlined.FontDownload, title = stringResource(R.string.settings_font_library), subtitle = stringResource(R.string.settings_font_library_summary), onClick = onOpenFontLibrary)
                MoReadRowDivider()
                MoReadRow(icon = Icons.Outlined.PhotoLibrary, title = stringResource(R.string.settings_image_library), subtitle = stringResource(R.string.settings_image_library_summary), onClick = onOpenImageLibrary)
            }
        }
        item {
            MoReadSection(title = stringResource(R.string.nav_bookshelf), icon = Icons.Outlined.AutoStories, tone = SemanticSlot.READING) {
                MoReadBlock(title = stringResource(R.string.settings_shelf_default_layout)) {
                    val labels = ShelfLayout.entries.associateWith { stringResource(it.labelRes()) }
                    MoReadSegmented(
                        options = ShelfLayout.entries,
                        selected = state.shelfLayout,
                        onSelect = viewModel::setShelfLayout,
                        label = { labels.getValue(it) }
                    )
                }
            }
        }
    }
}

@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit,
    onOpenApiLog: () -> Unit
) {
    MoReadSecondaryPage(title = stringResource(R.string.settings_about), onBack = onBack) {
        item {
            MoReadSection(title = stringResource(R.string.settings_section_app), icon = Icons.Outlined.Info) {
                AppUpdateCard()
                MoReadRowDivider()
                MoReadRow(
                    icon = Icons.Outlined.BugReport,
                    title = stringResource(R.string.settings_api_log),
                    subtitle = stringResource(R.string.settings_api_log_summary),
                    onClick = onOpenApiLog
                )
                MoReadRowDivider()
                AboutCard()
            }
        }
    }
}
/** 外观卡：主题模式 + 配色方案 + 强调色 + 语义色 / 质感 / 导航 / 形状四个维度。 */
@Composable
internal fun AppearanceCard(
    appearance: AppearanceSettings,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAccentChange: (AccentPreset) -> Unit,
    onCustomAccent: (Int) -> Unit,
    onColorSchemeChange: (ColorSchemePreset) -> Unit,
    onSemanticHarmonyChange: (SemanticHarmony) -> Unit,
    onSurfaceStyleChange: (SurfaceStyle) -> Unit,
    onNavStyleChange: (NavStyle) -> Unit,
    onShapeStyleChange: (ShapeStyle) -> Unit
) {
    var showColorPicker by remember { mutableStateOf(false) }

    // 外层素面卡由 MoReadSection 提供，这里只出内容。
    Column(
        modifier = Modifier.padding(MoReadSpacing.l),
        verticalArrangement = Arrangement.spacedBy(MoReadSpacing.l)
    ) {
            Text(stringResource(R.string.settings_theme_mode), style = MaterialTheme.typography.titleSmall)
            val themeLabels = ThemeMode.entries.associateWith { stringResource(it.labelRes()) }
            MoReadSegmented(
                options = ThemeMode.entries,
                selected = appearance.themeMode,
                onSelect = onThemeModeChange,
                label = { themeLabels.getValue(it) }
            )

            ColorSchemePicker(
                selected = appearance.colorScheme,
                onSelect = onColorSchemeChange
            )

            Column {
                Text(stringResource(R.string.settings_accent), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.settings_accent_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.s),
                verticalArrangement = Arrangement.spacedBy(MoReadSpacing.s)
            ) {
                val dark = isDarkTheme()
                val schemePrimary = rememberSchemeSide(appearance.colorScheme, dark).colors.primary
                (listOf(AccentPreset.FOLLOW) + AccentPreset.entries.filter { it != AccentPreset.FOLLOW }).forEach { preset ->
                    AccentSwatch(
                        // 「随方案」这一格显示当前方案的主色，而不是它的占位字段值。
                        color = when {
                            preset == AccentPreset.FOLLOW -> schemePrimary
                            dark -> preset.dark
                            else -> preset.light
                        },
                        label = preset.label,
                        selected = appearance.customAccentArgb == null &&
                            appearance.accent == preset,
                        onClick = { onAccentChange(preset) }
                    )
                }
            }
        CustomAccentRow(
            color = appearance.customAccentArgb?.let { Color(it) } ?: accentColor(),
            selected = appearance.customAccentArgb != null,
            onClick = { showColorPicker = true }
        )

        AppearanceDimension(
            title = stringResource(R.string.settings_semantic_harmony),
            hint = stringResource(R.string.settings_semantic_harmony_hint),
            options = SemanticHarmony.entries,
            selected = appearance.semanticHarmony,
            onSelect = onSemanticHarmonyChange,
            label = { it.label }
        )
        AppearanceDimension(
            title = stringResource(R.string.settings_surface_style),
            hint = stringResource(R.string.settings_surface_style_hint),
            options = SurfaceStyle.entries,
            selected = appearance.surfaceStyle,
            onSelect = onSurfaceStyleChange,
            label = { it.label }
        )
        AppearanceDimension(
            title = stringResource(R.string.settings_nav_style),
            hint = stringResource(R.string.settings_nav_style_hint),
            options = NavStyle.entries,
            selected = appearance.navStyle,
            onSelect = onNavStyleChange,
            label = { it.label }
        )
        AppearanceDimension(
            title = stringResource(R.string.settings_shape_style),
            hint = stringResource(R.string.settings_shape_style_hint),
            options = ShapeStyle.entries,
            selected = appearance.shapeStyle,
            onSelect = onShapeStyleChange,
            label = { it.label }
        )
    }

    if (showColorPicker) {
        AccentColorPickerDialog(
            initial = appearance.customAccentArgb?.let { Color(it) } ?: accentColor(),
            onDismiss = { showColorPicker = false },
            onConfirm = { argb ->
                onCustomAccent(argb)
                showColorPicker = false
            }
        )
    }
}

@Composable
private fun AccentSwatch(
    color: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val accentDescription = stringResource(R.string.settings_accent_description, label)
    Column(
        modifier = Modifier
            .width(48.dp)
            .clip(moReadMetrics().fieldShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = accentDescription }
            .padding(vertical = MoReadSpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MoReadSpacing.xs)
    ) {
        Box(
            modifier = Modifier.size(36.dp).background(color, CircleShape).border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = CircleShape
            ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = color.onAccent(), modifier = Modifier.size(16.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 显式入口：预设色之外，告诉用户这里可以连续取任意颜色。 */
@Composable
private fun CustomAccentRow(color: Color, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = moReadMetrics().rowShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.58f)
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(color, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Colorize,
                    contentDescription = null,
                    tint = color.onAccent(),
                    modifier = Modifier.size(17.dp)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 11.dp)
            ) {
                Text(stringResource(R.string.settings_custom_color), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (selected) {
                        stringResource(
                            R.string.settings_custom_color_enabled,
                            String.format(Locale.ROOT, "#%06X", color.toArgb() and 0x00FFFFFF)
                        )
                    } else {
                        stringResource(R.string.settings_custom_color_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = stringResource(R.string.settings_custom_color_open),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 与批注、排版页共用连续 HSV 取色器。 */
@Composable
private fun AccentColorPickerDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var preview by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_custom_accent_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NoteStyleColorPalette(
                    color = preview,
                    onColorChange = { preview = it }
                )
                Text(
                    stringResource(R.string.settings_custom_accent_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            MoReadButton(text = stringResource(R.string.action_use), onClick = { onConfirm(preview.toArgb()) })
        },
        dismissButton = {
            MoReadButton(text = stringResource(R.string.action_cancel), onClick = onDismiss, style = MoReadButtonStyle.Outlined)
        }
    )
}

/** 开源仓库地址：关于卡跳转与展示共用。 */
private const val REPO_URL = "https://github.com/ovo066/MoRead"

/** 关于卡：版本、开源仓库、许可与隐私说明。 */
@Composable
private fun AboutCard() {
    val uriHandler = LocalUriHandler.current
    // 外层素面卡由 MoReadSection 提供，这里只出内容。
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_about_app_name), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri(REPO_URL) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_about_repository), style = MaterialTheme.typography.titleSmall)
                    Text(
                        REPO_URL.removePrefix("https://"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = stringResource(R.string.settings_about_repository_open),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column {
                Text(stringResource(R.string.settings_about_privacy), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.settings_about_privacy_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Column {
                Text(stringResource(R.string.settings_about_license), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.settings_about_license_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
    }
}

/** 书架布局标签。 */
@StringRes
private fun ShelfLayout.labelRes(): Int = when (this) {
    ShelfLayout.GRID -> R.string.shelf_layout_grid
    ShelfLayout.LIST -> R.string.shelf_layout_list
}

/** AI 服务入口行的副标题：没配过就写引导语，配过就报数。 */
@Composable
private fun aiServiceSummary(providers: Int, models: Int): String =
    if (providers == 0) stringResource(R.string.settings_ai_services_empty)
    else stringResource(R.string.settings_ai_services_counts, providers, models)

/** 一个外观维度：小标题 + 一行说明 + 分段选择。 */
@Composable
private fun <T> AppearanceDimension(
    title: String,
    hint: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        MoReadSegmented(options = options, selected = selected, onSelect = onSelect, label = label)
    }
}

/**
 * 配色方案横向选择：每张卡用方案自己的颜色画一小块预览（画布 + 主色块 + 三枚语义色点），
 * 所见即所得，不必切过去才知道长什么样。跟随壁纸只在 Android 12 及以上出现。
 */
@Composable
private fun ColorSchemePicker(
    selected: ColorSchemePreset,
    onSelect: (ColorSchemePreset) -> Unit
) {
    val dark = isDarkTheme()
    val available = remember { ColorSchemePreset.entries.filter { it.isAvailable() } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(stringResource(R.string.settings_color_scheme), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_color_scheme_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            available.forEach { preset ->
                SchemeSwatchCard(
                    preset = preset,
                    dark = dark,
                    selected = preset == selected,
                    onClick = { onSelect(preset) }
                )
            }
        }
    }
}

@Composable
private fun SchemeSwatchCard(
    preset: ColorSchemePreset,
    dark: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    val side = rememberSchemeSide(preset, dark)
    val scheme = MaterialTheme.colorScheme
    val canvas = side.canvas
    val primary = side.colors.primary
    val dots = listOf(side.colors.secondary, side.colors.tertiary, side.rose.accent)
    val border = if (selected) scheme.primary else MaterialTheme.colorScheme.outlineVariant
    val shape = moReadMetrics().rowShape
    Column(
        modifier = Modifier
            .width(96.dp)
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .border(if (selected) 2.dp else 1.dp, border, shape)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(canvas),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 40.dp, height = 12.dp)
                        .clip(CircleShape)
                        .background(primary)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    dots.forEach { dot ->
                        Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(dot))
                    }
                }
            }
        }
        Text(
            text = preset.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) scheme.primary else scheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@StringRes
private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}
