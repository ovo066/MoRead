package com.mozhi.reader.feature.reader.stage

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import com.mozhi.reader.core.datastore.StageTextReveal
import com.mozhi.reader.core.datastore.StageLayoutMode
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.MusicOff
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.R
import com.mozhi.reader.core.datastore.ReaderKeyAction
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.StageSettings
import com.mozhi.reader.core.stage.BeatEnter
import com.mozhi.reader.core.stage.BeatKind
import com.mozhi.reader.core.stage.RevealSchedule
import com.mozhi.reader.core.stage.ScreenEffect
import com.mozhi.reader.core.stage.StageBeat
import com.mozhi.reader.core.stage.StageAtmosphere
import com.mozhi.reader.core.stage.StageEffectBudget
import com.mozhi.reader.core.stage.StageFigure
import com.mozhi.reader.core.stage.StagePresentation
import com.mozhi.reader.core.stage.StageReveal
import com.mozhi.reader.core.stage.sound.StageSoundCue
import com.mozhi.reader.feature.reader.ReaderHardwareKeyDispatcher
import com.mozhi.reader.feature.reader.ReaderLocateRequest
import com.mozhi.reader.feature.reader.readerPalette
import com.mozhi.reader.ui.theme.MoReadSpacing
import com.mozhi.reader.ui.theme.isDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** 舞台上的临时状态：自动、快进、弹层、提示与下一拍的出场方式。都不落盘。 */
@Stable
private class StageControls {
    var auto by mutableStateOf(false)
    var skipping by mutableStateOf(false)
    var backlogOpen by mutableStateOf(false)
    var settingsOpen by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    /** 下一拍是否直接整句出现：后退、回看跳转、快进时为真。 */
    var nextInstant by mutableStateOf(false)
    /** 最近一次换拍的方向：1 向前、-1 向后。黑场和插图拍据此决定自动跨过的方向。 */
    var direction by mutableIntStateOf(1)
}

/**
 * 演出模式：一屏一拍，点一下走一拍。屏幕上的字逐字来自原文，效果只是场记里的元数据。
 * 退出时把当前拍的原文范围交给 [onExit]，阅读页据此定位并短亮。
 */
@Composable
fun StageScreen(
    onExit: (ReaderLocateRequest?) -> Unit,
    viewModel: StageViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.stageSettings.collectAsStateWithLifecycle()
    val readerSettings by viewModel.readerSettings.collectAsStateWithLifecycle()
    val palette = stagePalette(settings.theme, readerPalette(readerSettings, isDarkTheme()))
    val fonts = remember(readerSettings.font, readerSettings.customFontPath, settings.typeface, settings.brush) {
        stageFonts(readerSettings, settings.typeface).copy(brush = StageBrushFamily.takeIf { settings.brush })
    }
    val context = LocalContext.current
    val reduceMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val controls = remember { StageControls() }
    val sound = rememberStageSound()
    val exit: () -> Unit = {
        controls.auto = false
        controls.skipping = false
        viewModel.flushProgress()
        onExit(viewModel.exitRequest())
    }

    StageSystemBars()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onResumed()
                Lifecycle.Event.ON_PAUSE -> {
                    controls.auto = false
                    controls.skipping = false
                    viewModel.onPaused()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) viewModel.onResumed()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onPaused()
        }
    }
    BackHandler {
        when {
            controls.backlogOpen -> controls.backlogOpen = false
            controls.settingsOpen -> controls.settingsOpen = false
            else -> exit()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .drawBehind { drawRect(palette.backdrop) }
    ) {
        val chapter = state.chapter
        if (chapter == null) {
            StageLoading(state.failed, palette, exit)
        } else {
            val soundOn = stringResource(R.string.stage_sound_on)
            val soundOff = stringResource(R.string.stage_sound_off)
            StageStage(
                chapter, state, settings, readerSettings, palette, fonts, reduceMotion, controls, viewModel, exit,
                onToggleSound = {
                    val next = !settings.sound
                    viewModel.updateSettings { it.copy(sound = next) }
                    controls.toast = if (next) soundOn else soundOff
                },
                onThunder = { delayMillis -> sound.thunder(delayMillis) },
                onTitleImpact = { sound.cue(StageSoundCue.INK_DROP) },
                onCue = sound::cue
            )
            StageSoundBinding(sound, settings, chapter, state.index)
        }
        StageLightsUp()
    }
}

@Composable
private fun StageLoading(failed: Boolean, palette: StagePalette, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.stage_load_failed), color = palette.inkSoft, fontSize = 14.sp)
                Spacer(Modifier.height(MoReadSpacing.m))
                TextButton(onClick = onExit) { Text(stringResource(R.string.stage_back_to_reading), color = palette.accent) }
            }
        } else {
            // 本地书秒开是常态：只有真慢时才转圈。
            var showSpinner by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { delay(450); showSpinner = true }
            if (showSpinner) CircularProgressIndicator(color = palette.accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.StageStage(
    chapter: StageChapter,
    state: StageUiState,
    settings: StageSettings,
    readerSettings: ReaderSettings,
    palette: StagePalette,
    baseFonts: StageFonts,
    reduceMotion: Boolean,
    controls: StageControls,
    viewModel: StageViewModel,
    exit: () -> Unit,
    onToggleSound: () -> Unit = {},
    onThunder: (Long) -> Unit = {},
    onTitleImpact: () -> Unit = {},
    onCue: (StageSoundCue) -> Unit = {}
) {
    // 精排书的内嵌字体随章加载；没有时就是阅读页的字体。
    val bookFonts = rememberBookFonts(chapter.fonts)
    val fonts = remember(baseFonts, bookFonts) { baseFonts.copy(book = bookFonts) }
    val beats = chapter.beats
    val presentations = remember(chapter.script, settings.intensity, reduceMotion) {
        StageEffectBudget.apply(beats, settings.intensity, reduceMotion)
    }
    val index = state.index
    val beat = beats.getOrNull(index)
    val presentation = presentations.getOrNull(index)
    val beatKey = "${state.chapterGeneration}:$index"
    val names = remember(chapter.script) { chapter.script.names.toSet() }

    // —— 揭示时钟：每拍一个；后退、跳转、快进时直接整句出现 ——
    val instant = remember(beatKey) { controls.nextInstant || controls.skipping }
    val schedule = remember(beatKey, settings.typingSpeed, settings.textReveal) {
        revealSchedule(beat, presentation, settings, instant || reduceMotion)
    }
    val clock = remember(beatKey) { StageRevealClock(schedule, instant) }
    var revealed by remember(beatKey) { mutableStateOf(instant) }
    LaunchedEffect(beatKey) {
        controls.nextInstant = false
        withTimeoutOrNull(schedule.totalMillis.toLong()) { snapshotFlow { clock.finished }.first { it } }
        clock.finished = true
        revealed = true
        if (beat != null) viewModel.onBeatRevealed(index)
    }
    val isRevealed by rememberUpdatedState(revealed)
    val advance: () -> Unit = {
        controls.direction = 1
        if (!isRevealed) {
            clock.finished = true
            revealed = true
        } else {
            viewModel.next()
        }
    }
    val goBack: () -> Unit = {
        controls.direction = -1
        controls.nextInstant = true
        viewModel.previous()
    }
    // 手势处理器只创建一次，必须经由最新的引用调用，否则会拿着第一拍的揭示时钟。
    val currentAdvance by rememberUpdatedState(advance)
    val currentExit by rememberUpdatedState(exit)

    // —— 屏幕效果 ——
    val effect = presentation?.screen.takeIf { !instant }
    val dim by animateFloatAsState(if (presentation?.screen == ScreenEffect.DIM) palette.dimScrim else 0f, tween(800), label = "dim")
    val vignette by animateFloatAsState(if (presentation?.screen == ScreenEffect.VIGNETTE) 1f else 0f, tween(900), label = "vignette")
    val shake = remember { Animatable(1f) }
    val heartbeat = remember { Animatable(1f) }
    val flash = remember { Animatable(0f) }
    // 场景分隔的化开转场：0→1 墨洇开盖满，1→2 淡去露出下一场。
    val wipe = remember { Animatable(0f) }
    LaunchedEffect(beatKey) {
        if (!instant) when {
            effect == ScreenEffect.SHAKE -> onCue(StageSoundCue.IMPACT)
            effect == ScreenEffect.HEARTBEAT -> onCue(StageSoundCue.HEARTBEAT)
            beat?.kind == BeatKind.SYSTEM -> onCue(StageSoundCue.CHIME)
        }
        when (effect) {
            ScreenEffect.SHAKE -> if (beat?.kind == BeatKind.SFX) {
                // 拟声：每个拟声字砸下时轻震一下。
                schedule.clusters.forEachIndexed { i, range ->
                    if (beat.text.substring(range.first, range.last + 1).any { it.isLetter() }) launch {
                        delay(schedule.startMillis[i] + 120L)
                        shake.snapTo(0f)
                        shake.animateTo(1f, tween(220))
                    }
                }
            } else {
                shake.snapTo(0f)
                shake.animateTo(1f, tween(380))
            }
            ScreenEffect.HEARTBEAT -> {
                heartbeat.snapTo(0f)
                heartbeat.animateTo(1f, tween(2_200))
            }
            ScreenEffect.FLASH -> {
                flash.snapTo(0.85f)
                flash.animateTo(0f, tween(500))
            }
            else -> Unit
        }
    }
    // 雷雨：隔一阵闪一下电，闪电之后才滚过雷声（远近不同，间隔也不同）。
    val storm = StageAtmosphere.sceneAt(chapter.script.scenes, index)?.weather == com.mozhi.reader.core.stage.StageWeatherCue.STORM && !reduceMotion && settings.intensity != com.mozhi.reader.core.stage.StageIntensity.RESTRAINED
    LaunchedEffect(storm) {
        if (!storm) return@LaunchedEffect
        var n = 0
        while (true) {
            delay(5_000L + (StageNoise.hash(n, 501) * 9_000).toLong())
            flash.snapTo(0.42f)
            flash.animateTo(0f, tween(140))
            delay(70)
            flash.snapTo(0.6f)
            flash.animateTo(0f, tween(520))
            onThunder(400L + (StageNoise.hash(n, 502) * 1_400).toLong())
            n++
        }
    }
    // 场景分隔与暂不显示的插图拍：顺着换拍方向自动跨过；向前时先化开一下。
    // 黑场在切到下一拍后由下一拍负责淡出：切拍会取消本拍的协程，不能在这里等淡出。
    LaunchedEffect(beatKey) {
        val kind = beat?.kind
        if (beat?.isSkipped() != true) {
            if (wipe.value > 0f) {
                if (wipe.value < 1f) wipe.snapTo(1f)
                wipe.animateTo(2f, tween(760, easing = InkEasing))
                wipe.snapTo(0f)
            }
            return@LaunchedEffect
        }
        if (controls.direction < 0) {
            controls.nextInstant = true
            viewModel.previous()
            return@LaunchedEffect
        }
        if (kind == BeatKind.BREAK && !instant) {
            wipe.snapTo(0f)
            wipe.animateTo(1f, tween(if (reduceMotion) 200 else 640, easing = InkEasing))
            delay(if (chapter.script.breakArt != null) 520 else 160)
            controls.nextInstant = false
            viewModel.next()
        } else {
            controls.nextInstant = instant
            viewModel.next()
        }
    }
    // 自动播放：揭示完成后按字数停留；章末卡停一会儿接着下一章。
    LaunchedEffect(beatKey, controls.auto, revealed, controls.backlogOpen, controls.settingsOpen) {
        if (!controls.auto || !revealed || controls.backlogOpen || controls.settingsOpen) return@LaunchedEffect
        if (beat == null) {
            delay(2_000)
            if (viewModel.hasNextChapter) viewModel.nextChapter() else controls.auto = false
        } else if (!beat.isSkipped()) {
            delay(StageReveal.autoHoldMillis(beat, settings.autoSpeed))
            controls.direction = 1
            viewModel.next()
        }
    }
    // 快进：只快进到读过的地方，未读处停下。
    val skipStopped = stringResource(R.string.stage_skip_stopped)
    LaunchedEffect(controls.skipping, beatKey) {
        if (!controls.skipping) return@LaunchedEffect
        if (beat != null && index < state.skipLimit) {
            delay(90)
            controls.direction = 1
            controls.nextInstant = true
            viewModel.next()
        } else {
            controls.skipping = false
            controls.toast = skipStopped
        }
    }
    LaunchedEffect(controls.toast) { if (controls.toast != null) { delay(1_800); controls.toast = null } }

    val single = settings.layout == StageLayoutMode.SINGLE
    val typography = remember(readerSettings.fontScale, readerSettings.lineHeight, readerSettings.paragraphSpacingEm, readerSettings.firstLineIndentEm, readerSettings.letterSpacingEm) {
        stageTypography(readerSettings)
    }
    val ghost = if (single && settings.ghost && beat?.isLine() == true) beats.getOrNull(index - 1)?.takeIf { it.isLine() } else null
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importBackground(uri)
    }
    val finishedClock = remember { StageRevealClock(StageReveal.schedule("", emptyList(), typing = false).copy(totalMillis = 0), instant = true) }

    // —— 背景层：背景图、光晕、质感、泛黄、按原文的天气与气氛 ——
    val scene = remember(chapter.script, index) { StageAtmosphere.sceneAt(chapter.script.scenes, index) }
    val backgroundPath = remember(settings.backgroundImageId, readerSettings.imageLibrary) {
        settings.backgroundImageId?.let { id -> readerSettings.imageLibrary.firstOrNull { it.id == id }?.filePath }
    }
    val sceneryAnimates = !reduceMotion && settings.intensity != com.mozhi.reader.core.stage.StageIntensity.RESTRAINED
    StageBackdrop(
        settings = settings,
        palette = palette,
        imagePath = backgroundPath,
        scene = scene,
        era = chapter.script.era,
        seed = remember(chapter.index) { (viewModel.bookId * 131 + chapter.index).toInt() },
        weather = stageWeather(settings, scene, settings.intensity, reduceMotion),
        animate = sceneryAnimates
    )

    // —— 内容层：手势与按键 ——
    val focus = remember { FocusRequester() }
    val keys = remember { ReaderHardwareKeyDispatcher() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val density = LocalDensity.current
    val swipeUp = with(density) { 60.dp.toPx() }
    val swipeDown = with(density) { 100.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                keys.dispatch(
                    event.nativeKeyEvent,
                    readerSettings.volumeKeysPageTurn,
                    !controls.backlogOpen && !controls.settingsOpen,
                    readerSettings.physicalKeyBindings
                ) { action ->
                    controls.auto = false
                    if (action == ReaderKeyAction.PREVIOUS_PAGE) goBack() else advance()
                }
            }
            .graphicsLayer {
                val s = shake.value
                if (s < 1f) translationX = sin(s * PI.toFloat() * 7f) * (1f - s) * 7.dp.toPx()
                val h = heartbeat.value
                if (h < 1f) {
                    val pulse = heartbeatPulse(h)
                    scaleX = 1f + pulse * 0.02f
                    scaleY = scaleX
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.position
                    var moved = false
                    var consumed = down.isConsumed
                    var released: Offset? = null
                    val timedOut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) consumed = true
                            if ((change.position - start).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (!change.pressed) { released = change.position; break }
                        }
                    } == null
                    if (timedOut && !moved && !consumed) {
                        // 长按：快进到读过的地方，松手停下。
                        controls.auto = false
                        controls.skipping = true
                        while (awaitPointerEvent().changes.any { it.pressed }) Unit
                        controls.skipping = false
                        return@awaitEachGesture
                    }
                    while (released == null) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) consumed = true
                        if (!change.pressed) released = change.position
                    }
                    val end = released ?: return@awaitEachGesture
                    if (consumed) return@awaitEachGesture
                    val dx = end.x - start.x
                    val dy = end.y - start.y
                    when {
                        dy < -swipeUp && abs(dy) > abs(dx) -> { controls.auto = false; controls.backlogOpen = true }
                        dy > swipeDown && abs(dy) > abs(dx) -> currentExit()
                        !moved -> { controls.auto = false; currentAdvance() }
                    }
                }
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.displayCutout))
                .padding(top = 56.dp, bottom = 104.dp, start = MoReadSpacing.xl, end = MoReadSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StageGhost(ghost, palette, fonts)
            BoxWithConstraints(Modifier.weight(1f).widthIn(max = StageContentMaxWidth).fillMaxWidth()) {
                val size = IntSize(constraints.maxWidth, constraints.maxHeight)
                val promoted = remember(chapter.script, settings.intensity) { StageEffectBudget.figures(beats, settings.intensity) }
                val plan = remember(chapter.script, size, typography, single, promoted) {
                    StagePlanner.plan(beats, chapter.body, stageMetrics(size, density, typography), single, promoted)
                }
                val shotIndex = if (index >= beats.size) -1 else plan.shotIndex(index)
                AnimatedContent(
                    targetState = shotIndex,
                    transitionSpec = {
                        val toTitle = targetState >= 0 && plan.shots.getOrNull(targetState)?.let { beats[it.first].kind == BeatKind.TITLE } == true
                        (fadeIn(tween(if (toTitle) 520 else 260, delayMillis = 90)) + slideInVertically(tween(320)) { it / 28 }) togetherWith
                            fadeOut(tween(if (toTitle) 260 else 160))
                    },
                    label = "stage-shot",
                    modifier = Modifier.fillMaxSize()
                ) { target ->
                    val shot = plan.shots.getOrNull(target)
                    if (target < 0 || shot == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            StageEndCard(chapter.script.title, palette, fonts, viewModel.hasNextChapter, viewModel::nextChapter, exit)
                        }
                        return@AnimatedContent
                    }
                    // 离场中的镜头（目标已经换了）整组静止显示，不再借用当前拍的时钟。
                    val live = index in shot
                    val upTo = if (live) index else shot.last
                    val shotClock = if (live) clock else finishedClock
                    val still = instant || !live
                    when (shot.kind) {
                        StageShotKind.FLOW -> StageFlowPage(
                            shot, plan, beats, presentations, upTo, shotClock, still, settings, palette, fonts, names, typography, reduceMotion,
                            cue = live && revealed && !controls.auto && !controls.skipping
                        )
                        StageShotKind.FIGURE -> StageViewport {
                            if (beats[shot.first].figure == StageFigure.LADDER) {
                                StageLadder(beats, presentations, shot.first, upTo, shotClock, still, settings.textReveal, palette, fonts, typography, reduceMotion)
                            } else {
                                StageStack(beats, (shot.first..shot.last).toList(), upTo, still, palette, fonts, typography, reduceMotion)
                            }
                        }
                        StageShotKind.CONTAINER -> StageViewport {
                            StageContainerShot(chapter, presentations, shot, upTo, shotClock, still, palette, fonts, names, settings.showAsides, reduceMotion, settings.bookStyles)
                        }
                        StageShotKind.SOLO -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            StageSoloBeat(
                                beat = beats[shot.first],
                                presentation = presentations[shot.first],
                                clock = shotClock,
                                instant = still,
                                key = "b:${state.chapterGeneration}:${shot.first}",
                                palette = palette,
                                fonts = fonts,
                                names = names,
                                settings = settings,
                                typography = typography,
                                reduceMotion = reduceMotion,
                                titleArt = chapter.script.titleArt,
                                titleInk = chapter.script.titleInk,
                                onTitleImpact = onTitleImpact
                            )
                        }
                    }
                }
            }
        }
    }

    // —— 遮罩层 ——
    // 压暗：四周的墨向中间收拢（不再是整屏灰罩），中间的字仍然清楚。
    if (dim > 0f) {
        val inkEdge = if (palette.isDark) Color.Black else palette.sceneryInk
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.radialGradient(
                        0.25f to Color.Transparent,
                        1f to inkEdge.copy(alpha = (dim * 3.2f).coerceAtMost(0.72f)),
                        center = Offset(size.width / 2f, size.height * 0.45f),
                        radius = size.maxDimension * 0.62f
                    )
                )
            }
        )
    }
    val pulse = if (heartbeat.value < 1f) heartbeatPulse(heartbeat.value) else 0f
    val vignetteAlpha = (vignette * 0.75f + pulse * 0.6f).coerceIn(0f, 1f)
    if (vignetteAlpha > 0f) {
        val edge = if (palette.isDark) Color.Black.copy(alpha = 0.62f) else Color(0xFF3C2814).copy(alpha = 0.28f)
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.radialGradient(
                        0.45f to Color.Transparent,
                        1f to edge,
                        center = Offset(size.width / 2f, size.height * 0.45f),
                        radius = size.maxDimension * 0.75f
                    ),
                    alpha = vignetteAlpha
                )
            }
        )
    }
    if (flash.value > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = flash.value }.background(Color.White))
    if (wipe.value > 0f) {
        val wipeInk = if (palette.isDark) palette.ink else palette.sceneryInk
        StageInkWipe(wipe.value, wipeInk, seed = chapter.index * 7 + index)
        val art = chapter.script.breakArt
        if (art != null && beat?.kind == BeatKind.BREAK) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = (wipe.value.coerceAtMost(1f) * 2f - 1f).coerceIn(0f, 1f) }, contentAlignment = Alignment.Center) {
                StageBreakArt(art)
            }
        }
    }

    // —— 界面层 ——
    val directing by viewModel.directorBusy.collectAsStateWithLifecycle()
    StageTopBar(
        title = chapter.script.title,
        progress = if (beats.isEmpty()) 1f else ((index + 1).toFloat() / beats.size).coerceIn(0f, 1f),
        auto = controls.auto,
        directing = directing,
        palette = palette,
        fonts = fonts,
        onExit = exit
    )
    StageToolbar(
        auto = controls.auto,
        skipping = controls.skipping,
        sound = settings.sound,
        palette = palette,
        fonts = fonts,
        onSound = { onToggleSound() },
        onBacklog = { controls.auto = false; controls.backlogOpen = true },
        onAuto = { controls.auto = !controls.auto; controls.skipping = false },
        onSkip = {
            controls.auto = false
            when {
                controls.skipping -> controls.skipping = false
                index < state.skipLimit -> controls.skipping = true
                else -> controls.toast = skipStopped
            }
        },
        onMore = { controls.auto = false; controls.settingsOpen = true },
        modifier = Modifier.align(Alignment.BottomCenter)
    )
    controls.toast?.let { message -> StageToast(message, palette, Modifier.align(Alignment.TopCenter)) }
    if (controls.backlogOpen) {
        StageBacklogSheet(
            chapter = chapter,
            upTo = maxOf(index, state.skipLimit).coerceAtMost(beats.size - 1),
            current = index,
            palette = palette,
            fonts = fonts,
            onJump = { target ->
                controls.backlogOpen = false
                controls.direction = 1
                controls.nextInstant = true
                viewModel.jumpTo(target)
            },
            onDismiss = { controls.backlogOpen = false }
        )
    }
    if (controls.settingsOpen) {
        StageSettingsSheet(
            settings = settings,
            images = readerSettings.imageLibrary,
            reduceMotion = reduceMotion,
            onChange = viewModel::updateSettings,
            onImportBackground = { backgroundPicker.launch("image/*") },
            onDismiss = { controls.settingsOpen = false }
        )
    }
}

/** 自动跨过的拍：场景分隔，以及没有可显示图片的插图占位。 */
private fun StageBeat.isSkipped(): Boolean = kind == BeatKind.BREAK || (kind == BeatKind.IMAGE && image == null)

private fun StageBeat.isLine(): Boolean =
    containerId == null && overlayOn == null && (kind == BeatKind.NARRATION || kind == BeatKind.DIALOGUE || kind == BeatKind.THOUGHT)

/** 心跳：两组双跳，第二下弱一些。 */
private fun heartbeatPulse(t: Float): Float {
    fun bump(center: Float, width: Float) = (1f - abs(t - center) / width).coerceAtLeast(0f)
    return maxOf(bump(0.14f, 0.12f), bump(0.42f, 0.12f) * 0.7f, bump(0.64f, 0.12f) * 0.9f, bump(0.90f, 0.10f) * 0.6f)
}

/** 当前拍的揭示时间表：设置为逐字时打字型逐字出，否则整句淡入或逐行浮现；容器只算最新一项。 */
private fun revealSchedule(beat: StageBeat?, presentation: StagePresentation?, settings: StageSettings, instant: Boolean): RevealSchedule {
    val empty = StageReveal.schedule("", emptyList(), typing = false)
    if (beat == null || presentation == null) return empty.copy(totalMillis = 0)
    if (instant) return StageReveal.schedule(beat.text, presentation.spans, typing = false).copy(totalMillis = 0)
    when (beat.kind) {
        BeatKind.SFX -> return sfxSchedule(beat.text, settings.typingSpeed)
        BeatKind.TITLE -> return empty.copy(totalMillis = STAGE_TITLE_MILLIS)
        BeatKind.CHAT -> return empty.copy(totalMillis = if (beat.item?.isMe == true) 420 else 860)
        BeatKind.FORUM_HEAD, BeatKind.STATUS, BeatKind.VERSE, BeatKind.NOTE -> return empty.copy(totalMillis = 600)
        BeatKind.IMAGE -> return empty.copy(totalMillis = if (beat.image != null) 900 else 0)
        BeatKind.DANMAKU -> return empty.copy(totalMillis = 520)
        BeatKind.CARD -> return empty.copy(totalMillis = 700)
        BeatKind.BREAK -> return empty.copy(totalMillis = 0)
        else -> Unit
    }
    // 直接显示：文字立刻完整出现（入场与屏幕效果照常），这一拍马上算演完。
    if (settings.textReveal == StageTextReveal.NONE) {
        return StageReveal.schedule(beat.text, presentation.spans, typing = false).copy(totalMillis = 0)
    }
    val typed = settings.textReveal == StageTextReveal.TYPE
    val base = when (beat.kind) {
        BeatKind.CAPTION -> 70f
        BeatKind.SYSTEM -> 34f
        BeatKind.FORUM -> 26f
        BeatKind.LETTER -> 70f
        else -> 46f
    }
    val schedule = StageReveal.schedule(
        beat.text,
        presentation.spans,
        typing = typed,
        baseCharMillis = base,
        paceMultiplier = presentation.paceMultiplier,
        speed = settings.typingSpeed,
        startDelayMillis = if (typed && beat.asideBefore != null) 380 else 0
    )
    val floor = when (presentation.enter) {
        BeatEnter.WHISPER -> if (typed) 1_600 else 600
        BeatEnter.FADE, BeatEnter.RISE -> if (typed) 700 else 0
        BeatEnter.SLAM -> 400
        else -> 0
    }
    val lines = if (settings.textReveal == StageTextReveal.LINES) 340 + 90 * ((beat.text.length + 15) / 16) else 0
    // 墨晕：整句从左到右洇过去，最后一个字也要收清才算演完。
    val ink = if (settings.textReveal == StageTextReveal.INK) {
        StageGlyphMotion.inkDelay(beat.text.length - 1, beat.text.length) + StageGlyphMotion.INK_MILLIS.toInt()
    } else 0
    return schedule.copy(totalMillis = maxOf(schedule.totalMillis, floor, lines, ink))
}

/** 舞台的排版参数跟随阅读页：字号倍率、行高、首行缩进与字距。 */
internal fun stageTypography(settings: ReaderSettings): StageTypography {
    val scale = settings.fontScale.takeIf { it.isFinite() }?.coerceIn(0.8f, 1.5f) ?: 1f
    return StageTypography(
        fontScale = scale,
        proseSize = (18.5f * scale).coerceIn(15f, 27f),
        lineHeightEm = (settings.lineHeight * 1.12f).coerceIn(1.6f, 2.2f),
        paragraphEm = settings.paragraphSpacingEm.coerceIn(0.3f, 1.5f),
        indentEm = settings.firstLineIndentEm.coerceIn(0f, 4f),
        letterSpacingEm = (settings.letterSpacingEm + 0.02f).coerceIn(0f, 0.2f)
    )
}

/** 版式容器镜头：容器里已出场的项，外加夹在里面的旁白或盖在上面的系统提示。 */
@Composable
private fun StageContainerShot(
    chapter: StageChapter,
    presentations: List<StagePresentation>,
    shot: StageShot,
    upTo: Int,
    clock: StageRevealClock,
    instant: Boolean,
    palette: StagePalette,
    fonts: StageFonts,
    names: Set<String>,
    showAsides: Boolean,
    reduceMotion: Boolean,
    look: Boolean
) {
    val beats = chapter.beats
    val cid = shot.containerId ?: return
    val container = chapter.script.containers.getOrNull(cid) ?: return
    val items = (shot.first..upTo).filter { beats[it].containerId == cid && !beats[it].embedded }
    val current = beats[upTo]
    val overlay = current.takeIf { it.embedded || it.overlayOn != null }
    val frame = StageContainerFrame(container, items.map { beats[it] }, if (overlay != null) -1 else items.indexOf(upTo), instant, look)
    val presentation = presentations[upTo]
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StageContainerView(
                frame = frame,
                palette = palette,
                fonts = fonts,
                clock = clock,
                reduceMotion = reduceMotion,
                modifier = Modifier.graphicsLayer { alpha = if (overlay != null) 0.5f else 1f }
            )
            if (overlay != null && overlay.embedded) {
                Spacer(Modifier.height(MoReadSpacing.l))
                StageLineBeat(overlay, presentation, palette, fonts, clock, names, showAsides, reduceMotion)
            }
        }
        if (overlay != null && !overlay.embedded) {
            Box(Modifier.stageEnter(presentation.enter, "c:${shot.first}:${overlay.range?.start}", instant)) {
                StageSystemPanel(overlay, presentation, palette, fonts, clock, reduceMotion)
            }
        }
    }
}

/** 单拍特写：章名卡、插图、时空字幕、拟声、系统面板、小字注。 */
@Composable
private fun StageSoloBeat(
    beat: StageBeat,
    presentation: StagePresentation,
    clock: StageRevealClock,
    instant: Boolean,
    key: String,
    palette: StagePalette,
    fonts: StageFonts,
    names: Set<String>,
    settings: StageSettings,
    typography: StageTypography,
    reduceMotion: Boolean,
    titleArt: com.mozhi.reader.core.stage.StageImage?,
    titleInk: com.mozhi.reader.core.stage.StageInk? = null,
    onTitleImpact: () -> Unit = {}
) {
    if (beat.figure == StageFigure.POSTER) {
        StagePoster(beat, instant, settings.textReveal, palette, fonts, reduceMotion)
        return
    }
    Box(Modifier.stageEnter(presentation.enter, key, instant), contentAlignment = Alignment.Center) {
        when (beat.kind) {
            BeatKind.TITLE -> Box(contentAlignment = Alignment.Center) {
                if (titleArt != null) Box(Modifier.matchParentSize()) { StageTitleArt(titleArt, palette, key, instant) }
                // 开章动画点一下就收：时钟被点按置完时，章名卡直接跳到最后一帧。
                StageTitleCard(beat.text, palette, fonts, key, instant || clock.finished, withArt = titleArt != null, ink = titleInk?.takeIf { settings.bookStyles }, onImpact = onTitleImpact)
            }
            BeatKind.IMAGE -> beat.image?.let { StageImageBeat(it, palette, fonts, key, instant) } ?: Spacer(Modifier.height(1.dp))
            BeatKind.CAPTION -> StageCaption(beat, palette, fonts, clock, reduceMotion)
            BeatKind.SFX -> StageSfx(beat, palette, fonts, clock, reduceMotion)
            BeatKind.SYSTEM -> StageSystemPanel(beat, presentation, palette, fonts, clock, reduceMotion, settings.bookStyles)
            BeatKind.NOTE -> StageNote(beat, palette, fonts)
            BeatKind.BREAK -> Spacer(Modifier.height(1.dp))
            else -> StageLineBeat(beat, presentation, palette, fonts, clock, names, settings.showAsides, reduceMotion)
        }
    }
}

@Composable
private fun StageGhost(ghost: StageBeat?, palette: StagePalette, fonts: StageFonts) {
    val alpha by animateFloatAsState(if (ghost != null) 0.28f else 0f, tween(450), label = "ghost")
    var last by remember { mutableStateOf<StageBeat?>(null) }
    if (ghost != null) last = ghost
    val shown = ghost ?: last
    if (shown == null || alpha <= 0.01f) return
    val prefix = shown.speaker?.label?.let { "$it  " }.orEmpty()
    Text(
        text = prefix + shown.text,
        color = palette.ink,
        fontFamily = fonts.body,
        fontSize = 13.5.sp,
        lineHeight = 1.7.em,
        maxLines = 2,
        textAlign = TextAlign.Center,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 300.dp)
            .padding(bottom = MoReadSpacing.xxl)
            .graphicsLayer { this.alpha = alpha }
    )
}

/** 章末：一道细墨线、行书「本章完」、章名，下面两个按钮（下一章是实心墨色，回到阅读是描边）。 */
@Composable
private fun StageEndCard(title: String, palette: StagePalette, fonts: StageFonts, hasNext: Boolean, onNext: () -> Unit, onBack: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.stageEnter(BeatEnter.FADE, title, false)) {
        Box(Modifier.width(1.dp).height(44.dp).background(Brush.verticalGradient(listOf(Color.Transparent, palette.inkSoft))))
        Spacer(Modifier.height(MoReadSpacing.l))
        // 「本章完」用书宋：行书的「本」是草写，容易被看成别的字。
        Text(
            stringResource(R.string.stage_chapter_end),
            color = palette.ink,
            fontFamily = fonts.body,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.em
        )
        Spacer(Modifier.height(MoReadSpacing.s))
        Text(title, color = palette.inkSoft, fontFamily = fonts.body, fontSize = 12.sp, letterSpacing = 0.16.em, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
        Spacer(Modifier.height(MoReadSpacing.xxl))
        Row(horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.m)) {
            StagePillButton(stringResource(R.string.stage_back_to_reading), palette, fonts, primary = !hasNext, onClick = onBack)
            if (hasNext) StagePillButton(stringResource(R.string.stage_next_chapter), palette, fonts, primary = true, onClick = onNext)
        }
    }
}

@Composable
private fun StagePillButton(text: String, palette: StagePalette, fonts: StageFonts, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .height(44.dp)
            .clip(shape)
            .then(if (primary) Modifier.background(palette.ink) else Modifier.border(1.dp, palette.inkFaint.copy(alpha = 0.6f), shape))
            .clickable(onClick = onClick)
            .padding(horizontal = MoReadSpacing.xl + 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (primary) palette.background else palette.ink, fontFamily = fonts.body, fontSize = 13.5.sp, letterSpacing = 0.18.em)
    }
}

/** 顶栏：左边收起，中间章名（宋体、疏朗字距），章名下一道细墨线标进度，朱砂一点是当前位置。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.StageTopBar(title: String, progress: Float, auto: Boolean, directing: Boolean, palette: StagePalette, fonts: StageFonts, onExit: () -> Unit) {
    val animatedProgress by animateFloatAsState(progress, tween(420), label = "progress")
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(palette.background.copy(alpha = 0.78f), palette.background.copy(alpha = 0f))))
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.displayCutout))
            .padding(bottom = MoReadSpacing.m)
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = MoReadSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onExit) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.stage_exit), tint = palette.chrome, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    title,
                    color = palette.chrome,
                    fontFamily = fonts.body,
                    fontSize = 12.sp,
                    letterSpacing = 0.22.em,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(7.dp))
                // AI 导演在读这一章时，进度线上的朱砂点轻轻呼吸。
                val breath = if (directing) {
                    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "directing")
                    transition.animateFloat(
                        0.35f, 1f,
                        androidx.compose.animation.core.infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse),
                        label = "directing-alpha"
                    ).value
                } else 1f
                Canvas(Modifier.width(120.dp).height(7.dp)) {
                    val y = size.height / 2f
                    val x = size.width * animatedProgress
                    drawLine(palette.chrome.copy(alpha = 0.28f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    drawLine(palette.accent.copy(alpha = 0.75f), Offset(0f, y), Offset(x, y), 1.5.dp.toPx())
                    drawCircle(palette.accent.copy(alpha = breath), (if (directing) 3.2f else 2.6f).dp.toPx(), Offset(x, y))
                }
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (auto) {
                    Text(
                        stringResource(R.string.stage_auto),
                        color = palette.accent,
                        fontFamily = fonts.body,
                        fontSize = 10.sp,
                        letterSpacing = 0.14.em,
                        modifier = Modifier
                            .border(1.dp, palette.accent.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

/** 底栏：五个低对比的小按钮（回看、自动、快进、声景、设置），开着的那项染朱砂、下方一点；底下一层纸色渐隐，压在布景上也看得清。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageToolbar(
    auto: Boolean,
    skipping: Boolean,
    sound: Boolean,
    palette: StagePalette,
    fonts: StageFonts,
    onSound: () -> Unit,
    onBacklog: () -> Unit,
    onAuto: () -> Unit,
    onSkip: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(palette.background.copy(alpha = 0f), palette.background.copy(alpha = 0.86f))))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .padding(horizontal = MoReadSpacing.l, vertical = MoReadSpacing.m)
                .padding(top = MoReadSpacing.l),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StageToolButton(Icons.Outlined.History, stringResource(R.string.stage_backlog), false, palette, fonts, onBacklog)
            StageToolButton(if (auto) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, stringResource(R.string.stage_auto), auto, palette, fonts, onAuto)
            StageToolButton(Icons.Outlined.FastForward, stringResource(R.string.stage_skip), skipping, palette, fonts, onSkip)
            StageToolButton(if (sound) Icons.Outlined.MusicNote else Icons.Outlined.MusicOff, stringResource(R.string.stage_sound), sound, palette, fonts, onSound)
            StageToolButton(Icons.Outlined.Tune, stringResource(R.string.stage_more), false, palette, fonts, onMore)
        }
    }
}

@Composable
private fun StageToolButton(icon: ImageVector, label: String, active: Boolean, palette: StagePalette, fonts: StageFonts, onClick: () -> Unit) {
    val tint = if (active) palette.accent else palette.chrome
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = MoReadSpacing.m, vertical = MoReadSpacing.xs + 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint, fontFamily = fonts.body, fontSize = 10.sp, letterSpacing = 0.14.em)
        Spacer(Modifier.height(3.dp))
        Box(Modifier.size(3.dp).clip(CircleShape).background(if (active) palette.accent else Color.Transparent))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageToast(message: String, palette: StagePalette, modifier: Modifier = Modifier) {
    Text(
        message,
        color = palette.ink,
        fontSize = 12.sp,
        letterSpacing = 0.06.em,
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .padding(top = 72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.slip)
            .border(0.5.dp, palette.inkFaint.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(horizontal = MoReadSpacing.l, vertical = MoReadSpacing.s)
    )
}

/** 熄灯开场：先是一片黑，舞台随后亮起。 */
@Composable
private fun StageLightsUp() {
    val lights = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        delay(120)
        lights.animateTo(0f, tween(520))
    }
    if (lights.value > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = lights.value }.background(Color.Black))
}

/**
 * 演出期间隐藏状态栏与导航栏，从边缘滑动可临时唤出；离开时只把导航栏交还（状态栏由阅读页自己管）。
 * 阅读页在转场结束离开组合时会还原状态栏，所以转场后与每次回到前台时再隐藏一次。
 */
@Composable
private fun StageSystemBars() {
    val activity = LocalContext.current as? android.app.Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember(activity) { activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) } }
    fun hide() {
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
    }
    LaunchedEffect(controller) {
        hide()
        delay(450)
        hide()
    }
    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) hide() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller?.show(WindowInsetsCompat.Type.navigationBars())
        }
    }
}
