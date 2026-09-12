package com.mozhi.reader.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mozhi.reader.R

@Stable
internal class BookmarkPullFeedback {
    var progress by mutableFloatStateOf(0f)
}

/** Only this small overlay observes pull frames; the text/bitmap composition stays untouched. */
@Composable
internal fun BookmarkPullIndicator(
    feedback: BookmarkPullFeedback,
    palette: ReaderPalette,
    modifier: Modifier = Modifier
) {
    val progress = feedback.progress
    if (progress <= 0f) return
    val ready = progress >= 1f
    val ribbonColor = if (ready) palette.accent else palette.muted
    Column(
        modifier = modifier.graphicsLayer {
            alpha = (progress * 2f).coerceAtMost(1f)
            translationY = -64.dp.toPx() * (1f - progress)
        }.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.End
    ) {
        Canvas(Modifier.padding(end = 18.dp).size(30.dp, 72.dp)) {
            val ribbon = Path().apply {
                moveTo(0f, 0f); lineTo(size.width, 0f)
                lineTo(size.width, size.height)
                lineTo(size.width / 2, size.height - 11.dp.toPx())
                lineTo(0f, size.height); close()
            }
            drawPath(ribbon, ribbonColor)
            drawLine(Color.White.copy(alpha = 0.18f), Offset(3.dp.toPx(), 0f),
                Offset(3.dp.toPx(), size.height - 6.dp.toPx()), 1.dp.toPx())
            if (ready) {
                drawLine(palette.background, Offset(size.width * .25f, size.height * .42f),
                    Offset(size.width * .43f, size.height * .5f), 2.dp.toPx(), StrokeCap.Round)
                drawLine(palette.background, Offset(size.width * .43f, size.height * .5f),
                    Offset(size.width * .76f, size.height * .34f), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        Text(
            stringResource(if (ready) R.string.reader_bookmark_release else R.string.reader_bookmark_pull),
            style = MaterialTheme.typography.labelMedium,
            color = if (ready) palette.accent else palette.muted,
            modifier = Modifier.padding(top = 6.dp)
                .background(palette.background.copy(alpha = .96f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
