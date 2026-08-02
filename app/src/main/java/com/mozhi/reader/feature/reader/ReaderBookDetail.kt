package com.mozhi.reader.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mozhi.reader.core.database.entity.BookEntity
import java.io.File
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max

@Composable
fun ReaderBookDetailOverlay(
    visible: Boolean,
    book: BookEntity?,
    chapterTitle: String,
    progress: Float,
    statistics: ReaderStatistics,
    bookmarkCount: Int,
    palette: ReaderPalette,
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { -it / 10 },
        exit = fadeOut() + slideOutVertically { -it / 10 }
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = palette.background,
            contentColor = palette.onBackground
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    DetailHeader(palette = palette, onBack = onBack)
                }
                item {
                    BookHero(
                        book = book,
                        chapterTitle = chapterTitle,
                        progress = progress,
                        palette = palette,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                item {
                    ReadingStatsRow(
                        statistics = statistics,
                        bookmarkCount = bookmarkCount,
                        palette = palette,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                item {
                    WeeklyReadingChart(
                        statistics = statistics,
                        palette = palette,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                item {
                    Button(
                        onClick = onContinue,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.accent,
                            contentColor = palette.onAccent
                        ),
                        shape = RoundedCornerShape(17.dp),
                        // 章节名可能折成两行：高度只设下限，让按钮随文字自然长高，避免第二行被截半。
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .navigationBarsPadding()
                            .heightIn(min = 52.dp)
                    ) {
                        Icon(Icons.Outlined.AutoStories, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "继续阅读 · ${chapterTitle.ifBlank { "当前章节" }}",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(palette: ReaderPalette, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(15.dp),
            color = palette.glass,
            border = BorderStroke(1.dp, palette.glassBorder)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回阅读")
            }
        }
        Text(
            text = "书籍详情",
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun BookHero(
    book: BookEntity?,
    chapterTitle: String,
    progress: Float,
    palette: ReaderPalette,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(14.dp, RoundedCornerShape(26.dp), clip = false),
        color = palette.glass,
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, palette.glassBorder)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DetailBookCover(
                book = book,
                palette = palette,
                modifier = Modifier
                    .width(94.dp)
                    .aspectRatio(0.68f)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(
                    text = "正在阅读 · ${book?.sourceType?.name ?: "EPUB"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.accent
                )
                Text(
                    text = book?.title ?: "正在载入",
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    text = book?.author?.ifBlank { "未知作者" } ?: "未知作者",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 3.dp)
                )
                Row(
                    modifier = Modifier.padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProgressRing(progress = progress, palette = palette)
                    Column(modifier = Modifier.padding(start = 10.dp)) {
                        Text(
                            text = chapterTitle.ifBlank { "当前章节" },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "全书 ${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.muted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailBookCover(book: BookEntity?, palette: ReaderPalette, modifier: Modifier = Modifier) {
    val coverFile = remember(book?.coverPath) {
        book?.coverPath?.let(::File)?.takeIf(File::isFile)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp, 13.dp, 13.dp, 6.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF4A4A4A), Color(0xFF262626))
                )
            )
    ) {
        if (coverFile != null) {
            AsyncImage(
                model = coverFile,
                contentDescription = "${book?.title.orEmpty()}封面",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = book?.title ?: "墨知阅读",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Serif,
                color = Color(0xFFEDEDED),
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(14.dp)
            )
            Text(
                text = book?.author?.ifBlank { "佚名" } ?: "佚名",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFEDEDED),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(Color(0x33FFFFFF), RoundedCornerShape(topEnd = 8.dp))
                    .padding(horizontal = 7.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun ProgressRing(progress: Float, palette: ReaderPalette) {
    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 5.dp.toPx()
            drawArc(
                color = palette.muted.copy(alpha = 0.16f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(strokeWidth, cap = StrokeCap.Round)
            )
            drawArc(
                color = palette.accent,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                style = Stroke(strokeWidth, cap = StrokeCap.Round)
            )
        }
        Text(
            text = "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ReadingStatsRow(
    statistics: ReaderStatistics,
    bookmarkCount: Int,
    palette: ReaderPalette,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        ReadingStatCell(formatDuration(statistics.totalDurationMs), "阅读时长", palette, Modifier.weight(1f))
        ReadingStatCell(statistics.readingDays.toString(), "阅读天数", palette, Modifier.weight(1f))
        ReadingStatCell(statistics.streakDays.toString(), "连续阅读", palette, Modifier.weight(1f))
        ReadingStatCell(bookmarkCount.toString(), "书签想法", palette, Modifier.weight(1f))
    }
}

@Composable
private fun ReadingStatCell(value: String, label: String, palette: ReaderPalette, modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = palette.glass,
        shape = RoundedCornerShape(17.dp),
        border = BorderStroke(1.dp, palette.glassBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = palette.muted,
                maxLines = 1,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

@Composable
private fun WeeklyReadingChart(
    statistics: ReaderStatistics,
    palette: ReaderPalette,
    modifier: Modifier = Modifier
) {
    val days = statistics.lastSevenDays.ifEmpty {
        val today = LocalDate.now().toEpochDay()
        (6 downTo 0).map { ReadingDayStat(today - it, 0) }
    }
    val maxDuration = max(days.maxOfOrNull(ReadingDayStat::durationMs) ?: 0, 1)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = palette.glass,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, palette.glassBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("近 7 日阅读", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(
                    text = "合计 ${formatDuration(days.sumOf(ReadingDayStat::durationMs))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.accent
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                days.forEachIndexed { index, day ->
                    val fraction = day.durationMs.toFloat() / maxDuration.toFloat()
                    val label = LocalDate.ofEpochDay(day.epochDay)
                        .dayOfWeek
                        .getDisplayName(TextStyle.NARROW, Locale.SIMPLIFIED_CHINESE)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .width(18.dp)
                                .height((10 + 62 * fraction).dp)
                                .background(
                                    if (index == days.lastIndex) {
                                        Brush.verticalGradient(
                                            listOf(palette.accent, palette.accent)
                                        )
                                    } else {
                                        Brush.verticalGradient(
                                            listOf(
                                                palette.accent.copy(alpha = 0.38f),
                                                palette.accent.copy(alpha = 0.58f)
                                            )
                                        )
                                    },
                                    RoundedCornerShape(8.dp, 8.dp, 4.dp, 4.dp)
                                )
                        )
                        Text(
                            text = if (index == days.lastIndex) "今" else label,
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.muted,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalMinutes = durationMs / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours}h${minutes.toString().padStart(2, '0')}m"
        totalMinutes > 0 -> "${totalMinutes}m"
        durationMs > 0 -> "<1m"
        else -> "0m"
    }
}
