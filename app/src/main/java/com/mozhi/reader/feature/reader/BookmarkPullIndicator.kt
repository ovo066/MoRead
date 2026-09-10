package com.mozhi.reader.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mozhi.reader.R
import com.mozhi.reader.ui.theme.MoReadSpacing

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
    Surface(
        modifier = modifier.graphicsLayer {
            alpha = (progress * 2f).coerceAtMost(1f)
            translationY = -MoReadSpacing.l.toPx() * (1f - progress)
        }.semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(20.dp),
        color = palette.background,
        contentColor = if (ready) palette.accent else palette.muted,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MoReadSpacing.l, vertical = MoReadSpacing.m),
            horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.BookmarkAdd, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                stringResource(if (ready) R.string.reader_bookmark_release else R.string.reader_bookmark_pull),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
