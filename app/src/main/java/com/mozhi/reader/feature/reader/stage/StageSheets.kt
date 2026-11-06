package com.mozhi.reader.feature.reader.stage

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import com.mozhi.reader.core.datastore.ReaderImageAsset
import com.mozhi.reader.core.datastore.ReaderImagePurpose
import com.mozhi.reader.core.datastore.StageHalo
import com.mozhi.reader.core.datastore.StageLayoutMode
import com.mozhi.reader.core.datastore.StagePaperTexture
import com.mozhi.reader.core.datastore.StageTextReveal
import com.mozhi.reader.core.datastore.StageWeather
import com.mozhi.reader.core.stage.StageImage
import com.mozhi.reader.ui.components.MoReadSlider
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mozhi.reader.R
import com.mozhi.reader.core.datastore.StageSettings
import com.mozhi.reader.core.datastore.StageTheme
import com.mozhi.reader.core.stage.BeatKind
import com.mozhi.reader.core.stage.StageIntensity
import com.mozhi.reader.ui.components.MoReadBlock
import com.mozhi.reader.ui.components.MoReadBottomSheet
import com.mozhi.reader.ui.components.MoReadSection
import com.mozhi.reader.ui.components.MoReadSegmented
import com.mozhi.reader.ui.components.MoReadSwitchRow
import com.mozhi.reader.ui.theme.MoReadSpacing

/** 回看：本章看过的拍子（说话人 + 原文），点一条跳回去。对白前钤说话人的小印，当前这句左边一道朱砂。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StageBacklogSheet(
    chapter: StageChapter,
    upTo: Int,
    current: Int,
    palette: StagePalette,
    fonts: StageFonts,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val rows = remember(chapter.script, upTo) {
        chapter.beats.withIndex().filter { (i, beat) ->
            i <= upTo && beat.kind != BeatKind.BREAK && beat.kind != BeatKind.IMAGE && beat.kind != BeatKind.TITLE && beat.text.isNotBlank()
        }
    }
    val names = remember(chapter.script) { chapter.script.names.toSet() }
    val list = rememberLazyListState()
    LaunchedEffect(rows) {
        val target = rows.indexOfFirst { it.index >= current }.takeIf { it >= 0 } ?: rows.lastIndex
        if (target > 2) list.scrollToItem(target - 2)
    }
    MoReadBottomSheet(onDismissRequest = onDismiss, containerColor = palette.slip, contentColor = palette.ink) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.7f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = MoReadSpacing.xl, vertical = MoReadSpacing.xs),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    stringResource(R.string.stage_backlog_title),
                    color = palette.ink,
                    fontFamily = fonts.brush ?: fonts.body,
                    fontSize = if (fonts.brush != null) 24.sp else 18.sp,
                    fontWeight = if (fonts.brush != null) FontWeight.Normal else FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.stage_backlog_hint),
                    color = palette.inkSoft,
                    fontFamily = fonts.body,
                    fontSize = 12.sp,
                    letterSpacing = 0.08.em,
                    modifier = Modifier.padding(start = MoReadSpacing.m, bottom = 3.dp)
                )
            }
            Text(
                chapter.script.title,
                color = palette.inkFaint,
                fontFamily = fonts.body,
                fontSize = 11.5.sp,
                letterSpacing = 0.16.em,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = MoReadSpacing.xl).padding(bottom = MoReadSpacing.s)
            )
            LazyColumn(state = list, modifier = Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = MoReadSpacing.m, vertical = MoReadSpacing.s)) {
                items(rows, key = { it.index }) { (i, beat) ->
                    val speaker = beat.speaker
                    val who = speaker?.label ?: beat.item?.sender ?: beat.item?.author
                    val whoColor = when {
                        speaker?.unknown == true -> palette.inkSoft
                        speaker?.isProtagonist == true -> palette.accent
                        who != null -> palette.roleColor(who)
                        else -> palette.inkSoft
                    }
                    val soft = beat.kind == BeatKind.THOUGHT || beat.kind == BeatKind.NOTE
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (i == current) palette.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { onJump(i) }
                            .padding(horizontal = MoReadSpacing.m, vertical = MoReadSpacing.s + 1.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            Modifier
                                .padding(top = 3.dp)
                                .size(width = 2.dp, height = 16.dp)
                                .background(if (i == current) palette.accent else androidx.compose.ui.graphics.Color.Transparent)
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.size(MoReadSpacing.s))
                        Column(Modifier.weight(1f)) {
                            if (!who.isNullOrBlank()) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 3.dp)) {
                                    if (speaker != null) {
                                        StageSeal(
                                            text = if (speaker.unknown) "?" else sealText(speaker.label, speaker.label in names),
                                            color = whoColor,
                                            paper = palette.slip,
                                            fonts = fonts,
                                            size = 15.dp,
                                            stamp = false,
                                            key = speaker.label,
                                            outline = speaker.unknown
                                        )
                                        androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                                    }
                                    Text(who, color = whoColor, fontFamily = fonts.body, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.1.em)
                                }
                            }
                            Text(
                                beat.text,
                                color = if (soft) palette.inkSoft else palette.ink,
                                fontFamily = fonts.body,
                                fontSize = 14.sp,
                                lineHeight = 1.7.em,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 演出设置：演出、文字、背景、氛围四页；改动立即生效。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun StageSettingsSheet(
    settings: StageSettings,
    images: List<ReaderImageAsset>,
    reduceMotion: Boolean,
    onChange: ((StageSettings) -> StageSettings) -> Unit,
    onImportBackground: () -> Unit,
    onDismiss: () -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    MoReadBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MoReadSpacing.l)
                .padding(bottom = MoReadSpacing.xxl)
        ) {
            Text(
                stringResource(R.string.stage_settings_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = MoReadSpacing.xs, vertical = MoReadSpacing.s)
            )
            val tabs = listOf(
                stringResource(R.string.stage_settings_tab_stage),
                stringResource(R.string.stage_settings_tab_text),
                stringResource(R.string.stage_settings_tab_backdrop),
                stringResource(R.string.stage_settings_tab_sound)
            )
            MoReadSegmented(tabs.indices.toList(), tab, { tab = it }, modifier = Modifier.padding(bottom = MoReadSpacing.m)) { tabs[it] }
            when (tab) {
                0 -> StageTabStage(settings, onChange)
                1 -> StageTabText(settings, onChange)
                2 -> {
                    StageTabBackdrop(settings, images, onChange, onImportBackground)
                    StageTabAtmosphere(settings, reduceMotion, onChange)
                }
                else -> StageTabSound(settings, onChange)
            }
        }
    }
}

@Composable
private fun StageTabStage(settings: StageSettings, onChange: ((StageSettings) -> StageSettings) -> Unit) {
    MoReadSection {
        MoReadBlock(title = stringResource(R.string.stage_settings_theme), subtitle = stringResource(R.string.stage_settings_theme_hint)) {
            Row(horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.s)) {
                for (theme in listOf(StageTheme.XUAN, StageTheme.THEATER, StageTheme.PAPER)) {
                    StageThemeCard(
                        theme = theme,
                        label = when (theme) {
                            StageTheme.XUAN -> stringResource(R.string.stage_theme_xuan)
                            StageTheme.THEATER -> stringResource(R.string.stage_theme_theater)
                            StageTheme.PAPER -> stringResource(R.string.stage_theme_paper)
                        },
                        selected = settings.theme == theme,
                        onClick = { onChange { it.copy(theme = theme) } },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        MoReadBlock(title = stringResource(R.string.stage_settings_layout), subtitle = stringResource(R.string.stage_settings_layout_hint)) {
            val page = stringResource(R.string.stage_layout_page)
            val single = stringResource(R.string.stage_layout_single)
            MoReadSegmented(StageLayoutMode.entries, settings.layout, { value -> onChange { it.copy(layout = value) } }) {
                if (it == StageLayoutMode.PAGE) page else single
            }
        }
        MoReadBlock(title = stringResource(R.string.stage_settings_intensity), subtitle = stringResource(R.string.stage_settings_intensity_hint)) {
            val labels = mapOf(
                StageIntensity.RESTRAINED to stringResource(R.string.stage_intensity_restrained),
                StageIntensity.STANDARD to stringResource(R.string.stage_intensity_standard),
                StageIntensity.LAVISH to stringResource(R.string.stage_intensity_lavish)
            )
            MoReadSegmented(StageIntensity.entries, settings.intensity, { value -> onChange { it.copy(intensity = value) } }) { labels.getValue(it) }
        }
        MoReadBlock(title = stringResource(R.string.stage_settings_auto_speed)) {
            val speeds = listOf(0.75f, 1f, 1.5f)
            val selected = speeds.minBy { kotlin.math.abs(it - settings.autoSpeed) }
            MoReadSegmented(speeds, selected, { value -> onChange { it.copy(autoSpeed = value) } }) { value ->
                if (value == 1f) "1×" else "$value×"
            }
        }
    }
    MoReadSection(modifier = Modifier.padding(top = MoReadSpacing.m)) {
        MoReadSwitchRow(
            title = stringResource(R.string.stage_settings_ai_director),
            subtitle = stringResource(R.string.stage_settings_ai_director_hint),
            checked = settings.aiDirector,
            onCheckedChange = { value -> onChange { it.copy(aiDirector = value) } }
        )
        MoReadSwitchRow(
            title = stringResource(R.string.stage_settings_book_styles),
            subtitle = stringResource(R.string.stage_settings_book_styles_hint),
            checked = settings.bookStyles,
            onCheckedChange = { value -> onChange { it.copy(bookStyles = value) } }
        )
    }
}

@Composable
private fun StageTabText(settings: StageSettings, onChange: ((StageSettings) -> StageSettings) -> Unit) {
    MoReadSection {
        MoReadBlock(title = stringResource(R.string.stage_settings_typeface)) {
            val labels = mapOf(
                com.mozhi.reader.core.datastore.StageTypeface.SONG to stringResource(R.string.stage_typeface_song),
                com.mozhi.reader.core.datastore.StageTypeface.FOLLOW to stringResource(R.string.stage_typeface_follow),
                com.mozhi.reader.core.datastore.StageTypeface.SANS to stringResource(R.string.stage_typeface_sans)
            )
            MoReadSegmented(com.mozhi.reader.core.datastore.StageTypeface.entries, settings.typeface, { value -> onChange { it.copy(typeface = value) } }) { labels.getValue(it) }
        }
        MoReadSwitchRow(
            title = stringResource(R.string.stage_settings_brush),
            subtitle = stringResource(R.string.stage_settings_brush_hint),
            checked = settings.brush,
            onCheckedChange = { value -> onChange { it.copy(brush = value) } }
        )
        MoReadBlock(title = stringResource(R.string.stage_settings_reveal), subtitle = stringResource(R.string.stage_settings_reveal_hint)) {
            val labels = mapOf(
                StageTextReveal.NONE to stringResource(R.string.stage_reveal_none),
                StageTextReveal.INK to stringResource(R.string.stage_reveal_ink),
                StageTextReveal.FADE to stringResource(R.string.stage_reveal_fade),
                StageTextReveal.LINES to stringResource(R.string.stage_reveal_lines),
                StageTextReveal.TYPE to stringResource(R.string.stage_reveal_type)
            )
            MoReadSegmented(StageTextReveal.entries, settings.textReveal, { value -> onChange { it.copy(textReveal = value) } }) { labels.getValue(it) }
        }
        if (settings.textReveal == StageTextReveal.TYPE) {
            MoReadBlock(title = stringResource(R.string.stage_settings_typing)) {
                val labels = listOf(
                    0.7f to stringResource(R.string.stage_speed_slow),
                    1f to stringResource(R.string.stage_speed_normal),
                    1.6f to stringResource(R.string.stage_speed_fast)
                )
                val selected = labels.minBy { kotlin.math.abs(it.first - settings.typingSpeed) }.first
                MoReadSegmented(labels.map { it.first }, selected, { value -> onChange { it.copy(typingSpeed = value) } }) { value ->
                    labels.first { it.first == value }.second
                }
            }
        }
    }
    MoReadSection(modifier = Modifier.padding(top = MoReadSpacing.m)) {
        if (settings.layout == StageLayoutMode.PAGE) {
            MoReadSwitchRow(
                title = stringResource(R.string.stage_settings_focus),
                subtitle = stringResource(R.string.stage_settings_focus_hint),
                checked = settings.focusDim,
                onCheckedChange = { value -> onChange { it.copy(focusDim = value) } }
            )
        } else {
            MoReadSwitchRow(
                title = stringResource(R.string.stage_settings_ghost),
                subtitle = stringResource(R.string.stage_settings_ghost_hint),
                checked = settings.ghost,
                onCheckedChange = { value -> onChange { it.copy(ghost = value) } }
            )
        }
        MoReadSwitchRow(
            title = stringResource(R.string.stage_settings_asides),
            subtitle = stringResource(R.string.stage_settings_asides_hint),
            checked = settings.showAsides,
            onCheckedChange = { value -> onChange { it.copy(showAsides = value) } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageTabBackdrop(
    settings: StageSettings,
    images: List<ReaderImageAsset>,
    onChange: ((StageSettings) -> StageSettings) -> Unit,
    onImportBackground: () -> Unit
) {
    MoReadSection {
        MoReadSwitchRow(
            title = stringResource(R.string.stage_settings_scenery),
            subtitle = stringResource(R.string.stage_settings_scenery_hint),
            checked = settings.scenery,
            onCheckedChange = { value -> onChange { it.copy(scenery = value) } }
        )
    }
    MoReadSection(modifier = Modifier.padding(top = MoReadSpacing.m)) {
        MoReadBlock(title = stringResource(R.string.stage_settings_background), subtitle = stringResource(R.string.stage_settings_background_hint)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.s)) {
                item(key = "none") {
                    BackdropTile(selected = settings.backgroundImageId == null, onClick = { onChange { it.copy(backgroundImageId = null) } }) {
                        Text(stringResource(R.string.stage_background_none), style = MaterialTheme.typography.labelMedium)
                    }
                }
                items(images.filter { it.purpose == ReaderImagePurpose.BACKGROUND || it.purpose == ReaderImagePurpose.GENERAL }, key = { it.id }) { image ->
                    BackdropTile(selected = settings.backgroundImageId == image.id, onClick = { onChange { it.copy(backgroundImageId = image.id) } }) {
                        val bitmap = rememberStageImage(StageImage(image.filePath), 192)
                        if (bitmap != null) Image(bitmap, contentDescription = image.displayName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
                item(key = "import") {
                    BackdropTile(selected = false, onClick = onImportBackground) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(stringResource(R.string.stage_background_import), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (settings.backgroundImageId != null) {
                MoReadSlider(
                    label = stringResource(R.string.stage_settings_background_dim),
                    valueText = percent(settings.backgroundDim),
                    value = settings.backgroundDim,
                    range = 0f..0.9f,
                    step = 0.05f,
                    onValueChange = { value -> onChange { it.copy(backgroundDim = value) } }
                )
                MoReadSlider(
                    label = stringResource(R.string.stage_settings_background_blur),
                    valueText = percent(settings.backgroundBlur),
                    value = settings.backgroundBlur,
                    range = 0f..1f,
                    step = 0.05f,
                    onValueChange = { value -> onChange { it.copy(backgroundBlur = value) } }
                )
            }
        }
        MoReadBlock(title = stringResource(R.string.stage_settings_halo)) {
            val labels = mapOf(
                StageHalo.NONE to stringResource(R.string.stage_halo_none),
                StageHalo.TOP to stringResource(R.string.stage_halo_top),
                StageHalo.CENTER to stringResource(R.string.stage_halo_center),
                StageHalo.BOTTOM to stringResource(R.string.stage_halo_bottom),
                StageHalo.STARRY to stringResource(R.string.stage_halo_starry)
            )
            StageChoiceChips(StageHalo.entries, settings.halo, { value -> onChange { it.copy(halo = value) } }) { labels.getValue(it) }
            if (settings.halo != StageHalo.NONE) {
                Row(horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.m), verticalAlignment = Alignment.CenterVertically) {
                    StageHaloColors.forEachIndexed { i, color ->
                        val selected = settings.haloColor == i
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                                .padding(if (selected) 4.dp else 0.dp)
                                .clip(CircleShape)
                                .background(color)
                                .clickable { onChange { it.copy(haloColor = i) } }
                        )
                    }
                }
                MoReadSlider(
                    label = stringResource(R.string.stage_settings_halo_strength),
                    valueText = percent(settings.haloStrength),
                    value = settings.haloStrength,
                    range = 0f..1f,
                    step = 0.05f,
                    onValueChange = { value -> onChange { it.copy(haloStrength = value) } }
                )
            }
        }
        MoReadBlock(title = stringResource(R.string.stage_settings_texture)) {
            val labels = mapOf(
                StagePaperTexture.NONE to stringResource(R.string.stage_texture_none),
                StagePaperTexture.GRAIN to stringResource(R.string.stage_texture_grain),
                StagePaperTexture.FIBER to stringResource(R.string.stage_texture_fiber),
                StagePaperTexture.LINEN to stringResource(R.string.stage_texture_linen),
                StagePaperTexture.SPECKLE to stringResource(R.string.stage_texture_speckle),
                StagePaperTexture.CRUMPLED to stringResource(R.string.stage_texture_crumpled)
            )
            StageChoiceChips(StagePaperTexture.entries, settings.texture, { value -> onChange { it.copy(texture = value) } }) { labels.getValue(it) }
            MoReadSlider(
                label = stringResource(R.string.stage_settings_aging),
                valueText = percent(settings.aging),
                value = settings.aging,
                range = 0f..1f,
                step = 0.05f,
                onValueChange = { value -> onChange { it.copy(aging = value) } }
            )
        }
    }
}

@Composable
private fun StageTabAtmosphere(settings: StageSettings, reduceMotion: Boolean, onChange: ((StageSettings) -> StageSettings) -> Unit) {
    MoReadSection(modifier = Modifier.padding(top = MoReadSpacing.m)) {
        MoReadBlock(title = stringResource(R.string.stage_settings_weather), subtitle = stringResource(R.string.stage_settings_weather_hint)) {
            val labels = mapOf(
                StageWeather.AUTO to stringResource(R.string.stage_weather_auto),
                StageWeather.OFF to stringResource(R.string.stage_weather_off),
                StageWeather.RAIN to stringResource(R.string.stage_weather_rain),
                StageWeather.SNOW to stringResource(R.string.stage_weather_snow),
                StageWeather.PETALS to stringResource(R.string.stage_weather_petals),
                StageWeather.LEAVES to stringResource(R.string.stage_weather_leaves),
                StageWeather.FIREFLIES to stringResource(R.string.stage_weather_fireflies),
                StageWeather.DUST to stringResource(R.string.stage_weather_dust)
            )
            StageChoiceChips(StageWeather.entries, settings.weather, { value -> onChange { it.copy(weather = value) } }) { labels.getValue(it) }
            if (settings.weather != StageWeather.OFF) {
                MoReadSlider(
                    label = stringResource(R.string.stage_settings_weather_density),
                    valueText = percent(settings.weatherDensity),
                    value = settings.weatherDensity,
                    range = 0f..1f,
                    step = 0.05f,
                    onValueChange = { value -> onChange { it.copy(weatherDensity = value) } }
                )
            }
            if (reduceMotion) {
                Text(stringResource(R.string.stage_settings_reduce_motion_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    MoReadSection(modifier = Modifier.padding(top = MoReadSpacing.m)) {
        MoReadSwitchRow(
            title = stringResource(R.string.stage_settings_mood),
            subtitle = stringResource(R.string.stage_settings_mood_hint),
            checked = settings.moodTint,
            onCheckedChange = { value -> onChange { it.copy(moodTint = value) } }
        )
    }
}

/** 声景：总开关、环境声与配乐两档音量、提示音。 */
@Composable
private fun StageTabSound(settings: StageSettings, onChange: ((StageSettings) -> StageSettings) -> Unit) {
    MoReadSection {
        MoReadSwitchRow(
            title = stringResource(R.string.stage_settings_sound),
            subtitle = stringResource(R.string.stage_settings_sound_hint),
            checked = settings.sound,
            onCheckedChange = { value -> onChange { it.copy(sound = value) } }
        )
    }
    if (settings.sound) {
        MoReadSection(modifier = Modifier.padding(top = MoReadSpacing.m)) {
            MoReadBlock(title = stringResource(R.string.stage_settings_sound)) {
                MoReadSlider(
                    label = stringResource(R.string.stage_settings_ambience),
                    valueText = percent(settings.ambienceVolume),
                    value = settings.ambienceVolume,
                    range = 0f..1f,
                    step = 0.05f,
                    onValueChange = { value -> onChange { it.copy(ambienceVolume = value) } }
                )
                MoReadSlider(
                    label = stringResource(R.string.stage_settings_music),
                    valueText = percent(settings.musicVolume),
                    value = settings.musicVolume,
                    range = 0f..1f,
                    step = 0.05f,
                    onValueChange = { value -> onChange { it.copy(musicVolume = value) } }
                )
            }
            MoReadSwitchRow(
                title = stringResource(R.string.stage_settings_cues),
                subtitle = stringResource(R.string.stage_settings_cues_hint),
                checked = settings.soundCues,
                onCheckedChange = { value -> onChange { it.copy(soundCues = value) } }
            )
        }
    }
}

/** 舞台卡：纸色的小样，下面一抹远山，选中描强调色边。 */
@Composable
private fun StageThemeCard(theme: StageTheme, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val reader = MaterialTheme.colorScheme
    val (paper, ink) = when (theme) {
        StageTheme.XUAN -> Color(0xFFF6F2EA) to Color(0xFF2A2724)
        StageTheme.THEATER -> Color(0xFF15171D) to Color(0xFFE8E2D4)
        StageTheme.PAPER -> reader.surfaceContainerHigh to reader.onSurface
    }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .clip(shape)
            .border(if (selected) 2.dp else 0.5.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.compose.foundation.Canvas(
            Modifier
                .fillMaxWidth()
                .height(58.dp)
                .background(paper)
        ) {
            // 一抹远山与一道近山：用舞台自己的墨色，选舞台时就能看到它的气质。
            val w = size.width
            val h = size.height
            fun ridge(base: Float, amp: Float, alpha: Float, salt: Int) {
                val path = androidx.compose.ui.graphics.Path()
                path.moveTo(0f, h)
                var x = 0f
                while (x <= w) {
                    val t = x / w
                    val y = base - amp * (0.5f + 0.5f * kotlin.math.sin(t * 5.2f + salt)) * (0.6f + 0.4f * kotlin.math.sin(t * 13f + salt * 2f))
                    path.lineTo(x, y)
                    x += 2f
                }
                path.lineTo(w, h)
                path.close()
                drawPath(path, ink.copy(alpha = alpha))
            }
            ridge(h * 0.72f, h * 0.30f, 0.10f, 1)
            ridge(h * 0.92f, h * 0.18f, 0.20f, 4)
            drawCircle(Color(0xFFB8432F).copy(alpha = if (theme == StageTheme.THEATER) 0.8f else 0.65f), 3.dp.toPx(), androidx.compose.ui.geometry.Offset(w * 0.78f, h * 0.30f))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = MoReadSpacing.s)
        )
    }
}

private fun percent(value: Float): String = "${(value * 100).roundToInt()}%"

/** 背景图格：选中描强调色边。 */
@Composable
private fun BackdropTile(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(64.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(if (selected) 2.dp else 0.5.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** 选项多于一行时的分段：几个小块排成流式行，选中的填强调色（与分段控件同一套配色）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> StageChoiceChips(options: List<T>, selected: T, onSelect: (T) -> Unit, label: (T) -> String) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.s), verticalArrangement = Arrangement.spacedBy(MoReadSpacing.s)) {
        for (option in options) {
            val on = option == selected
            Text(
                label(option),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onSelect(option) }
                    .padding(horizontal = MoReadSpacing.m, vertical = MoReadSpacing.s)
            )
        }
    }
}
