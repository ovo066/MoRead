package com.mozhi.reader.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * GitHub 式阅读热力图（design/ui-adaptation-plan.md §3.5）：7 列 × [weeks] 行，
 * 最后一格是今天，颜色 = accent 按当日阅读时长插值，0 = track 灰。
 *
 * @param durationsByEpochDay epochDay -> 阅读毫秒数
 * @param todayEpochDay 今天的 epochDay（LocalDate.now().toEpochDay()）
 */
@Composable
fun ReadingHeatmap(
    durationsByEpochDay: Map<Long, Long>,
    todayEpochDay: Long,
    modifier: Modifier = Modifier,
    weeks: Int = 5,
    accent: Color = MaterialTheme.colorScheme.primary,
    track: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    muted: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val totalDays = weeks * 7
    val maxDuration = (todayEpochDay - totalDays + 1..todayEpochDay)
        .maxOf { durationsByEpochDay[it] ?: 0L }
        .coerceAtLeast(1L)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .heatmapHeight(weeks)
        ) {
            val gap = 5.dp.toPx()
            val cell = minOf(
                (size.width - gap * 6) / 7f,
                (size.height - gap * (weeks - 1)) / weeks
            )
            val radius = CornerRadius(4.dp.toPx())
            val firstDay = todayEpochDay - totalDays + 1
            for (i in 0 until totalDays) {
                val epochDay = firstDay + i
                val row = i / 7
                val col = i % 7
                val duration = durationsByEpochDay[epochDay] ?: 0L
                val color = if (duration <= 0L) {
                    track
                } else {
                    val t = 0.30f + 0.70f * (duration.toFloat() / maxDuration).coerceIn(0f, 1f)
                    accent.copy(alpha = t)
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(col * (cell + gap), row * (cell + gap)),
                    size = Size(cell, cell),
                    cornerRadius = radius
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("少", style = MaterialTheme.typography.labelSmall, color = muted)
            Row(
                modifier = Modifier.padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                listOf(0f, 0.35f, 0.6f, 0.85f, 1f).forEach { level ->
                    Box(
                        modifier = Modifier.size(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(Modifier.size(10.dp)) {
                            drawRoundRect(
                                color = if (level == 0f) track else accent.copy(alpha = 0.3f + 0.7f * level),
                                cornerRadius = CornerRadius(3.dp.toPx())
                            )
                        }
                    }
                }
            }
            Text("多", style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

/** 格子近似正方形：宽 = 7c + 6g，高 = wc + (w-1)g；gap/cell 按典型屏宽近似为 1/8。 */
private fun Modifier.heatmapHeight(weeks: Int): Modifier {
    val gapRatio = 1f / 8f
    val ratio = (7f + 6f * gapRatio) / (weeks + (weeks - 1) * gapRatio)
    return aspectRatio(ratio)
}
