package com.mozhi.reader.feature.listen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.mozhi.reader.ai.listen.ListenEngine
import com.mozhi.reader.ai.listen.ListenPlaybackMode
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.library.AudiobookChapterState
import com.mozhi.reader.core.library.AudiobookRepository
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.speech.SleepTimerPlan
import com.mozhi.reader.core.speech.SleepTimerPlanner
import com.mozhi.reader.core.speech.SleepTimerState
import com.mozhi.reader.ui.components.SleepTimerSheet
import com.mozhi.reader.ui.components.TtsTuningActions
import com.mozhi.reader.ui.components.TtsTuningSections
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ListenPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val engine: ListenEngine,
    private val libraryRepository: LibraryRepository,
    private val audiobookRepository: AudiobookRepository
) : ViewModel() {
    val bookId = savedStateHandle.get<String>("bookId")?.toLongOrNull() ?: 0L
    val playbackMode = if (savedStateHandle.get<String>("source") == "produced") {
        ListenPlaybackMode.PRODUCED
    } else {
        ListenPlaybackMode.STANDARD
    }
    val listenState = engine.state
    val sleepTimer = engine.sleepTimer
    val book = libraryRepository.observeBook(bookId).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null
    )

    init {
        viewModelScope.launch {
            if (bookId > 0 && !engine.isListening(bookId, playbackMode)) {
                libraryRepository.getBook(bookId)?.let { current ->
                    if (playbackMode == ListenPlaybackMode.PRODUCED) {
                        val ready = audiobookRepository.getChapters(bookId)
                            .filter { it.state == AudiobookChapterState.READY.name }
                            .map { it.chapterIndex }
                            .sorted()
                        val start = ready.firstOrNull { it >= current.lastReadChapterIndex }
                            ?: ready.firstOrNull()
                        if (start != null) engine.start(bookId, start, 0, playbackMode)
                    } else {
                        engine.start(
                            bookId,
                            current.lastReadChapterIndex,
                            current.lastReadCharOffset,
                            playbackMode
                        )
                    }
                }
            }
        }
    }

    fun toggle() = engine.toggle()
    fun stop() = engine.stop()
    fun previousSegment() = engine.prevSentence()
    fun nextSegment() = engine.nextSentence()
    fun previousChapter() = engine.prevChapter()
    fun nextChapter() = engine.nextChapter()
    fun seek(fraction: Float) = engine.seekToChapterFraction(fraction)
    fun setSleepTimer(plan: SleepTimerPlan?) = engine.setSleepTimer(plan)
}

/**
 * 沉浸听书播放页。
 *
 * 满屏一张模糊封面当底（画到状态栏之上，不留白条），上面只放三件事：封面、
 * 在读什么、怎么控制。**不显示正文**——正文有阅读页，沉浸页要的是闭眼也能用。
 * 参数与定时收进玻璃胶囊 / 折叠面板，不占版面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenPlayerScreen(
    bookId: Long,
    onBack: () -> Unit,
    onOpenReader: (Long) -> Unit,
    onOpenVoiceLibrary: () -> Unit,
    viewModel: ListenPlayerViewModel = hiltViewModel(),
    tuningViewModel: TtsTuningViewModel = hiltViewModel()
) {
    val listenState by viewModel.listenState.collectAsStateWithLifecycle()
    val sleepTimer by viewModel.sleepTimer.collectAsStateWithLifecycle()
    val book by viewModel.book.collectAsStateWithLifecycle()
    val tuningState by tuningViewModel.uiState.collectAsStateWithLifecycle()
    val current = listenState?.takeIf { it.bookId == bookId }
    var showTimer by remember { mutableStateOf(false) }
    var showTuning by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(current?.chapterProgress, dragging) {
        if (!dragging) sliderValue = current?.chapterProgress ?: 0f
    }

    val coverFile = book?.coverPath?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf(File::isFile)
    val playing = current?.isPlaying == true

    // 沉浸底：模糊封面铺满整屏（含状态栏），再压一层上深下深的墨色幕，保证文字始终可读。
    Box(Modifier.fillMaxSize().background(Color(0xFF101012))) {
        if (coverFile != null) {
            AsyncImage(
                model = coverFile,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(46.dp).scale(1.18f)
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.62f),
                            Color.Black.copy(alpha = 0.40f),
                            Color.Black.copy(alpha = 0.78f)
                        )
                    )
                )
        )

        CompositionLocalProvider(LocalContentColor provides Color(0xFFF3F1EC)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── 顶栏：收起 + 右侧「看正文」胶囊，其余都在下面的胶囊里
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassCircleButton(Icons.Outlined.ExpandMore, "收起播放页", onBack)
                    Spacer(Modifier.weight(1f))
                    GlassPill(
                        icon = Icons.AutoMirrored.Outlined.MenuBook,
                        label = "看正文",
                        onClick = { onOpenReader(bookId) }
                    )
                }

                Spacer(Modifier.weight(0.6f))

                CoverArtwork(book = book, playing = playing)

                Spacer(Modifier.height(28.dp))

                Text(
                    text = current?.chapterTitle?.takeIf { it.isNotBlank() } ?: "正在准备听书",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString {
                        append(current?.bookTitle ?: book?.title.orEmpty())
                        current?.let { append(" · 第 ${it.chapterIndex + 1}/${it.chapterCount} 章") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Surface(
                    shape = CircleShape,
                    color = if (viewModel.playbackMode == ListenPlaybackMode.PRODUCED) {
                        Color(0xFFE0C48A).copy(alpha = 0.18f)
                    } else {
                        Color.White.copy(alpha = 0.10f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (viewModel.playbackMode == ListenPlaybackMode.PRODUCED) {
                            Color(0xFFE0C48A).copy(alpha = 0.55f)
                        } else {
                            Color.White.copy(alpha = 0.18f)
                        }
                    ),
                    contentColor = if (viewModel.playbackMode == ListenPlaybackMode.PRODUCED) {
                        Color(0xFFE8D39E)
                    } else {
                        LocalContentColor.current.copy(alpha = 0.78f)
                    },
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text(
                        text = if (viewModel.playbackMode == ListenPlaybackMode.PRODUCED) {
                            "多角色成品 · 仅播放已制作章节"
                        } else {
                            "普通听书 · ${current?.engineMode?.label() ?: "TTS"}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }

                // 成品模式：额外显示当前说话角色和角色色。
                AnimatedVisibility(
                    visible = current?.scripted == true,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(140))
                ) {
                    val roleColor = current?.currentRoleColor.toRoleColor(Color(0xFFE0C48A))
                    Surface(
                        shape = CircleShape,
                        color = roleColor.copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, roleColor.copy(alpha = 0.55f)),
                        contentColor = roleColor,
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Outlined.RecordVoiceOver,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = current?.currentRoleName ?: "旁白",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // ── 本章进度
                Slider(
                    value = sliderValue.coerceIn(0f, 1f),
                    onValueChange = { dragging = true; sliderValue = it },
                    onValueChangeFinished = { dragging = false; viewModel.seek(sliderValue) },
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFF3F1EC),
                        activeTrackColor = Color(0xFFF3F1EC),
                        inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "本章 ${(sliderValue * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current.copy(alpha = 0.6f)
                    )
                    Text(
                        text = current?.status ?: if (playing) "正在朗读" else "已暂停",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 190.dp)
                    )
                }

                Spacer(Modifier.height(14.dp))

                // ── 五键传输控制
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TransportButton(Icons.Outlined.SkipPrevious, "上一章", 26.dp, viewModel::previousChapter)
                    TransportButton(Icons.Outlined.FastRewind, "上一段", 30.dp, viewModel::previousSegment)
                    PlayButton(playing = playing, onClick = viewModel::toggle)
                    TransportButton(Icons.Outlined.FastForward, "下一段", 30.dp, viewModel::nextSegment)
                    TransportButton(Icons.Outlined.SkipNext, "下一章", 26.dp, viewModel::nextChapter)
                }

                Spacer(Modifier.height(18.dp))

                // ── 快捷胶囊排：倍速 / 定时 / 音色 / 调节
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassPill(
                        icon = Icons.Outlined.Speed,
                        label = "${currentSpeed(tuningState.settings)}×",
                        onClick = { showTuning = true }
                    )
                    GlassPill(
                        icon = Icons.Outlined.Bedtime,
                        label = sleepTimer?.let(SleepTimerPlanner::label) ?: "定时",
                        highlighted = sleepTimer != null,
                        onClick = { showTimer = true }
                    )
                    GlassPill(
                        icon = Icons.Outlined.RecordVoiceOver,
                        label = "音色",
                        onClick = onOpenVoiceLibrary
                    )
                    GlassCircleButton(Icons.Outlined.Tune, "听书调节") { showTuning = true }
                }
            }
        }
    }

    if (showTimer) {
        SleepTimerSheet(
            current = sleepTimer,
            onDismiss = { showTimer = false },
            onSelect = viewModel::setSleepTimer
        )
    }
    if (showTuning) {
        ModalBottomSheet(
            onDismissRequest = { showTuning = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    "听书调节",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                TtsTuningSections(
                    settings = tuningState.settings,
                    cacheSummary = tuningState.bookCache?.let {
                        "本书已缓存 ${it.fileCount} 段 · ${formatBytes(it.totalBytes)}"
                    } ?: "本书暂无语音缓存",
                    actions = tuningActions(tuningViewModel, onOpenVoiceLibrary)
                )
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/** 大封面：播放时缓慢呼吸；无封面时退化成一枚声波纹章。 */
@Composable
private fun CoverArtwork(book: BookEntity?, playing: Boolean) {
    val transition = rememberInfiniteTransition(label = "listen-cover")
    val breath by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (playing) 1.028f else 1f,
        animationSpec = infiniteRepeatable(tween(3_200), repeatMode = RepeatMode.Reverse),
        label = "listen-cover-breath"
    )
    val settle by animateFloatAsState(
        targetValue = if (playing) 1f else 0.94f,
        animationSpec = tween(420),
        label = "listen-cover-settle"
    )
    val cover = book?.coverPath?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf(File::isFile)
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = Color.White.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth(0.66f)
            .aspectRatio(0.72f)
            .scale(breath * settle)
    ) {
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.GraphicEq,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(64.dp)
                )
            }
        }
    }
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color(0xFFF3F1EC),
        contentColor = Color(0xFF17171A),
        modifier = Modifier.size(72.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = if (playing) "暂停" else "播放",
                modifier = Modifier.size(34.dp)
            )
        }
    }
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    description: String,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = Color(0xFFF3F1EC).copy(alpha = 0.86f),
        modifier = Modifier.size(52.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(iconSize))
        }
    }
}

@Composable
private fun GlassCircleButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
        contentColor = Color(0xFFF3F1EC),
        modifier = Modifier.size(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun GlassPill(
    icon: ImageVector,
    label: String,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White.copy(alpha = if (highlighted) 0.20f else 0.10f),
        border = BorderStroke(
            1.dp,
            Color.White.copy(alpha = if (highlighted) 0.42f else 0.18f)
        ),
        contentColor = Color(0xFFF3F1EC)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun tuningActions(
    viewModel: TtsTuningViewModel,
    onOpenVoiceLibrary: () -> Unit
) = TtsTuningActions(
    onEngineModeChange = viewModel::setEngineMode,
    onAiVoiceChange = viewModel::setAiVoice,
    onSystemRateChange = viewModel::setSystemRate,
    onSystemPitchChange = viewModel::setSystemPitch,
    onAiSpeedChange = viewModel::setAiSpeed,
    onAiVolumeChange = viewModel::setAiVolume,
    onAiPitchChange = viewModel::setAiPitch,
    onAllowAudioMixingChange = viewModel::setAllowAudioMixing,
    onTrimSilenceChange = viewModel::setTrimSilence,
    onGranularityChange = viewModel::setGranularity,
    onMaxCharsChange = viewModel::setMaxChars,
    onConcurrencyChange = viewModel::setConcurrency,
    onRetryCountChange = viewModel::setRetryCount,
    onPrefetchCountChange = viewModel::setPrefetchCount,
    onOpenVoiceLibrary = onOpenVoiceLibrary,
    onClearBookCache = viewModel::clearBookCache
)

private fun currentSpeed(settings: com.mozhi.reader.core.speech.TtsSettings): String =
    "%.1f".format(
        if (settings.engineMode == com.mozhi.reader.core.speech.TtsEngineMode.SYSTEM) {
            settings.systemRate
        } else {
            settings.aiSpeed
        }
    )

private fun String?.toRoleColor(fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor(this)) }.getOrDefault(fallback)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

private fun com.mozhi.reader.core.speech.TtsEngineMode.label(): String = when (this) {
    com.mozhi.reader.core.speech.TtsEngineMode.SYSTEM -> "系统 TTS"
    com.mozhi.reader.core.speech.TtsEngineMode.AI -> "AI TTS"
}
