package com.mozhi.reader.feature.companion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mozhi.reader.R
import com.mozhi.reader.ui.components.PersonaAvatarImage
import com.mozhi.reader.ui.theme.MoReadSpacing
import com.mozhi.reader.ui.theme.sectionHairline
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

private val GutterWidth = 56.dp
private val NodeSize = 40.dp
private val NodeTop = 2.dp
private val HeroDialSize = 112.dp
internal const val COMPANION_STORY_INITIAL_DAYS = 21
internal const val COMPANION_STORY_PAGE_DAYS = 30

/** Which nodes already played their reveal, so scrolling back up does not replay the thread. */
internal class CompanionStoryReveal {
    val seen = mutableSetOf<LocalDate>()
    var hero = false
}

/**
 * The companion timeline: a 24-hour clock whose thread runs down the left edge, with one small clock per
 * day. Each node draws its stretch of thread and sweeps its hand the first time it scrolls into view.
 */
internal fun LazyListScope.companionStoryItems(
    stats: CompanionStatistics, reveal: CompanionStoryReveal, visibleDays: Int, itemGap: Dp, onShowMore: () -> Unit
) {
    val story = stats.story
    if (story.days.isEmpty()) return
    item(key = "story-hero") { CompanionClockHero(stats, reveal, itemGap) }
    val shown = story.days.take(visibleDays)
    val hasMore = story.days.size > shown.size
    itemsIndexed(shown, key = { _, day -> "story-${day.date}" }) { index, day ->
        val monthHeader = index == 0 || shown[index - 1].date.withDayOfMonth(1) != day.date.withDayOfMonth(1)
        CompanionStoryDayNode(day, story, monthHeader, last = index == shown.lastIndex && !hasMore, reveal, itemGap)
    }
    if (hasMore) item(key = "story-more") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(GutterWidth - MoReadSpacing.m))
            TextButton(onClick = onShowMore, modifier = Modifier.testTag("companion-story-more")) {
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                Spacer(Modifier.width(MoReadSpacing.xs))
                val rest = story.days.size - shown.size
                Text(pluralStringResource(R.plurals.companion_story_more_days, rest, rest))
            }
        }
    }
}

@Composable
private fun CompanionClockHero(stats: CompanionStatistics, reveal: CompanionStoryReveal, itemGap: Dp) {
    val progress = remember { Animatable(if (reveal.hero) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        reveal.hero = true
    }
    val peak = peakCompanionHour(stats.roundsByHour)
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val thread = accent.copy(alpha = .3f)
    val dialDescription = peak?.let { stringResource(R.string.companion_story_dial_description, it) }
        ?: stringResource(R.string.companion_story_dial_empty)
    Column(Modifier.fillMaxWidth().testTag("companion-story")) {
        Text(stringResource(R.string.companion_story_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(MoReadSpacing.m))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(HeroDialSize).semantics { contentDescription = dialDescription }) {
                drawHeroDial(stats.roundsByHour, peak, progress.value, accent, ink, surface)
            }
            Spacer(Modifier.width(MoReadSpacing.l))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MoReadSpacing.xs)) {
                if (peak != null) {
                    Text(stringResource(R.string.companion_story_peak_caption), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.companion_story_peak_hour, stringResource(CompanionDaypart.of(peak).label()), peak),
                        style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif, color = accent)
                }
                Text(pluralStringResource(R.plurals.companion_story_active_days, stats.activeDays, stats.activeDays),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // The thread leaves the bottom of the clock and settles into the timeline's gutter.
        Canvas(Modifier.fillMaxWidth().height(MoReadSpacing.xxl)) {
            val gap = itemGap.toPx()
            val startX = HeroDialSize.toPx() / 2
            val endX = GutterWidth.toPx() / 2
            val endY = size.height + gap
            val path = Path().apply {
                moveTo(startX, 0f)
                cubicTo(startX, endY * .55f, endX, endY * .45f, endX, endY)
            }
            drawPath(path, thread.copy(alpha = thread.alpha * progress.value), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

private fun DrawScope.drawHeroDial(hourly: List<Int>, peak: Int?, progress: Float, accent: Color, ink: Color, surface: Color) {
    val radius = size.minDimension / 2 - 2.dp.toPx()
    fun point(angleDeg: Float, r: Float): Offset {
        val radians = Math.toRadians(angleDeg.toDouble())
        return Offset(center.x + (r * cos(radians)).toFloat(), center.y + (r * sin(radians)).toFloat())
    }
    drawCircle(surface, radius)
    drawCircle(ink.copy(alpha = .1f), radius, style = Stroke(1.dp.toPx()))
    for (hour in 0 until 24) {
        val major = hour % 6 == 0
        val angle = hour * 15f - 90f
        drawLine(ink.copy(alpha = if (major) .38f else .16f), point(angle, radius - 2.dp.toPx()),
            point(angle, radius - (if (major) 8.dp else 5.dp).toPx()), strokeWidth = if (major) 1.5.dp.toPx() else 1.dp.toPx())
    }
    val inner = radius * .38f
    val reach = radius - 13.dp.toPx() - inner
    val most = (hourly.maxOrNull() ?: 0).coerceAtLeast(1)
    hourly.forEachIndexed { hour, count ->
        if (count <= 0) return@forEachIndexed
        val ratio = count.toFloat() / most
        val angle = (hour + .5f) * 15f - 90f
        drawLine(accent.copy(alpha = .3f + .7f * ratio), point(angle, inner),
            point(angle, inner + reach * (.22f + .78f * ratio) * progress), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
    }
    if (peak != null) {
        val angle = -90f + (peak + .5f) * 15f * progress
        drawLine(ink.copy(alpha = .78f), center, point(angle, inner + reach * .82f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
    drawCircle(accent, 4.5.dp.toPx())
    drawCircle(surface, 1.8.dp.toPx())
}

@Composable
private fun CompanionStoryDayNode(
    day: CompanionStoryDay, story: CompanionStory, monthHeader: Boolean, last: Boolean,
    reveal: CompanionStoryReveal, itemGap: Dp
) {
    val progress = remember(day.date) { Animatable(if (day.date in reveal.seen) 1f else 0f) }
    LaunchedEffect(day.date) {
        if (progress.value < 1f) progress.animateTo(1f, tween(640, easing = FastOutSlowInEasing))
        reveal.seen += day.date
    }
    val accent = MaterialTheme.colorScheme.primary
    val thread = accent.copy(alpha = .28f)
    val locale = LocalConfiguration.current.locales[0]
    val monthPattern = stringResource(R.string.companion_story_month_pattern)
    val datePattern = stringResource(R.string.companion_story_date_pattern)
    Column(Modifier.fillMaxWidth().testTag("companion-story-day-${day.date}")) {
        if (monthHeader) Row(Modifier.fillMaxWidth().height(36.dp).drawBehind {
            val x = GutterWidth.toPx() / 2
            drawLine(thread, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
            val half = 4.dp.toPx()
            val diamond = Path().apply {
                moveTo(x, size.height / 2 - half); lineTo(x + half, size.height / 2)
                lineTo(x, size.height / 2 + half); lineTo(x - half, size.height / 2); close()
            }
            drawPath(diamond, accent)
        }, verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(GutterWidth))
            Text(day.date.format(DateTimeFormatter.ofPattern(monthPattern, locale)),
                style = MaterialTheme.typography.labelLarge, color = accent)
        }
        Row(Modifier.fillMaxWidth().drawBehind {
            val x = GutterWidth.toPx() / 2
            val node = (NodeTop + NodeSize / 2).toPx()
            drawLine(thread, Offset(x, 0f), Offset(x, node), strokeWidth = 1.5.dp.toPx())
            // Reaches across the list spacing into the next node, growing as this node reveals.
            if (!last) drawLine(thread, Offset(x, node), Offset(x, node + (size.height + itemGap.toPx() - node) * progress.value),
                strokeWidth = 1.5.dp.toPx())
        }) {
            Box(Modifier.width(GutterWidth), contentAlignment = Alignment.TopCenter) {
                CompanionDayClock(day, { progress.value }, Modifier.padding(top = NodeTop).size(NodeSize))
            }
            Column(Modifier.weight(1f).graphicsLayer {
                alpha = progress.value
                translationY = (1f - progress.value) * 14.dp.toPx()
            }) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(day.date.format(DateTimeFormatter.ofPattern(datePattern, locale)), style = MaterialTheme.typography.titleSmall)
                    relativeDay(day.date)?.let {
                        Text(stringResource(it), style = MaterialTheme.typography.labelSmall, color = accent,
                            modifier = Modifier.padding(start = MoReadSpacing.s))
                    }
                }
                val summary = listOfNotNull(
                    day.readingMs.takeIf { it >= 60_000 }?.let { stringResource(R.string.companion_story_day_read, storyDuration(it)) },
                    day.rounds.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.companion_story_day_exchanges, it, it) }
                )
                if (summary.isNotEmpty()) Text(summary.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                Column(Modifier.padding(top = MoReadSpacing.m), verticalArrangement = Arrangement.spacedBy(MoReadSpacing.m)) {
                    day.events.forEach { CompanionStoryEventRow(it, story) }
                }
            }
        }
    }
}

/** A watch face for one day: arcs where you talked, the hand at the last moment of the day's story. */
@Composable
private fun CompanionDayClock(day: CompanionStoryDay, progress: () -> Float, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val hairline = sectionHairline()
    val arcs = remember(day.roundsByHour) { companionHourArcs(day.roundsByHour) }
    Canvas(modifier) {
        val shown = progress()
        val radius = size.minDimension / 2
        drawCircle(surface, radius)
        drawCircle(hairline, radius - .5.dp.toPx(), style = Stroke(1.dp.toPx()))
        val ring = radius - 6.dp.toPx()
        val stroke = 3.dp.toPx()
        drawCircle(ink.copy(alpha = .07f), ring, style = Stroke(stroke))
        val box = Size(ring * 2, ring * 2)
        val topLeft = Offset(center.x - ring, center.y - ring)
        arcs.forEach { (start, hours) ->
            drawArc(accent, start * 15f - 90f, (hours * 15f - 4f).coerceAtLeast(4f) * shown, false, topLeft, box,
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
        val angle = Math.toRadians((-90f + day.lastMinute / 1440f * 360f * shown).toDouble())
        val reach = ring - 5.dp.toPx()
        drawLine(ink.copy(alpha = .72f), center, Offset(center.x + (reach * cos(angle)).toFloat(), center.y + (reach * sin(angle)).toFloat()),
            strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(accent, 2.6.dp.toPx())
    }
}

@Composable
private fun CompanionStoryEventRow(event: CompanionStoryEvent, story: CompanionStory) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val unknown = stringResource(R.string.companion_story_unknown_persona)
    fun persona(id: Long?) = id?.let(story.personas::get)
    val time = DateTimeFormatter.ofPattern("HH:mm")
    fun clock(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(time)
    when (event) {
        is CompanionStoryEvent.Session -> {
            val role = persona(event.personaId)
            val name = role?.name ?: unknown
            val titles = event.bookIds.take(3).mapNotNull(story.bookTitles::get).map { stringResource(R.string.companion_story_book_title, it) }
            val books = titles.joinToString(stringResource(R.string.companion_story_book_separator))
            val minutes = (event.endAt - event.at) / 60_000
            val duration = storyDuration(event.endAt - event.at)
            val sentence = when {
                titles.isEmpty() && minutes >= 1 -> stringResource(R.string.companion_story_library, name, duration)
                titles.isEmpty() -> stringResource(R.string.companion_story_library_brief, name)
                event.firstMeeting && minutes >= 1 -> stringResource(R.string.companion_story_session_first, name, books, duration)
                event.firstMeeting -> stringResource(R.string.companion_story_session_first_brief, name, books)
                minutes >= 1 -> stringResource(R.string.companion_story_session, name, books, duration)
                else -> stringResource(R.string.companion_story_session_brief, name, books)
            }
            val span = if (event.endAt - event.at >= 60_000) "${clock(event.at)} – ${clock(event.endAt)}" else clock(event.at)
            val meta = listOfNotNull(span, pluralStringResource(R.plurals.companion_story_exchanges, event.rounds, event.rounds),
                if (event.library && titles.isNotEmpty()) stringResource(R.string.companion_story_library_tag) else null)
            StoryLine({ StoryAvatar(name, role?.avatarPath) }, highlighted(sentence, listOf(name) + titles, accent), meta.joinToString(" · "),
                badge = if (event.firstMeeting && titles.isNotEmpty()) Icons.Outlined.AutoAwesome else null)
        }
        is CompanionStoryEvent.Memory -> {
            val role = persona(event.personaId)
            val name = role?.name ?: unknown
            StoryLine({ StoryAvatar(name, role?.avatarPath) },
                highlighted(stringResource(R.string.companion_story_memory, name), listOf(name), accent), clock(event.at)) {
                Text(event.summary, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = MoReadSpacing.xs).drawBehind {
                        drawLine(accent.copy(alpha = .45f), Offset(0f, 2.dp.toPx()), Offset(0f, size.height - 2.dp.toPx()), strokeWidth = 2.dp.toPx())
                    }.padding(start = MoReadSpacing.s))
            }
        }
        is CompanionStoryEvent.FirstWords -> {
            val name = persona(event.personaId)?.name ?: unknown
            StoryLine({ StoryGlyph(Icons.Outlined.Flag) },
                highlighted(stringResource(R.string.companion_story_first_words, name), listOf(name), accent), clock(event.at))
        }
        is CompanionStoryEvent.Milestone -> StoryLine({ StoryGlyph(Icons.Outlined.Stars) },
            AnnotatedString(stringResource(R.string.companion_story_milestone, event.count)), clock(event.at))
    }
}

@Composable
private fun StoryLine(
    leading: @Composable () -> Unit, text: AnnotatedString, meta: String, badge: ImageVector? = null,
    extra: @Composable ColumnScope.() -> Unit = {}
) {
    Row {
        leading()
        Column(Modifier.weight(1f).padding(start = MoReadSpacing.s)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                badge?.let { Icon(it, null, Modifier.padding(start = MoReadSpacing.xs, top = 2.dp).size(14.dp), tint = MaterialTheme.colorScheme.primary) }
            }
            extra()
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun StoryAvatar(name: String, avatarPath: String?) {
    PersonaAvatarImage(name, avatarPath, Modifier.size(24.dp), MaterialTheme.typography.labelSmall)
}

@Composable
private fun StoryGlyph(icon: ImageVector) {
    Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun storyDuration(durationMs: Long): String {
    val minutes = (durationMs / 60_000).coerceAtLeast(1)
    return if (minutes >= 60) stringResource(R.string.companion_story_hours_minutes, minutes / 60, minutes % 60)
    else stringResource(R.string.companion_story_minutes, minutes)
}

private fun highlighted(text: String, parts: List<String>, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    parts.filter(String::isNotBlank).forEach { part ->
        val start = text.indexOf(part)
        if (start >= 0) addStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold), start, start + part.length)
    }
}

private fun relativeDay(date: LocalDate, today: LocalDate = LocalDate.now()): Int? = when (date) {
    today -> R.string.companion_story_today
    today.minusDays(1) -> R.string.companion_story_yesterday
    else -> null
}

private fun CompanionDaypart.label(): Int = when (this) {
    CompanionDaypart.LATE_NIGHT -> R.string.companion_daypart_late_night
    CompanionDaypart.EARLY_MORNING -> R.string.companion_daypart_early_morning
    CompanionDaypart.MORNING -> R.string.companion_daypart_morning
    CompanionDaypart.AFTERNOON -> R.string.companion_daypart_afternoon
    CompanionDaypart.EVENING -> R.string.companion_daypart_evening
    CompanionDaypart.NIGHT -> R.string.companion_daypart_night
}
