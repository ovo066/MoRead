package com.mozhi.reader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ai.companion.ProactiveAnnotationBatchResult
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.retrieval.AnnotationVisibility
import com.mozhi.reader.core.retrieval.ReadingScope
import com.mozhi.reader.ui.components.PersonaAvatarImage
import kotlinx.coroutines.delay

/** Place in the reader's top-center Box; caller gates foreground lifecycle + noticeActive. */
@Composable
fun ReaderAnnotationNoticeCapsule(
    result: ProactiveAnnotationBatchResult,
    message: String,
    annotations: List<AnnotationEntity>,
    readingScope: ReadingScope,
    palette: ReaderPalette,
    onView: (AnnotationEntity) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dismiss = rememberUpdatedState(onDismiss)
    val view = rememberUpdatedState(onView)
    val target = rememberUpdatedState(AnnotationVisibility.firstVisible(annotations, result.annotationIds, readingScope))
    LaunchedEffect(result) { delay(5_000); dismiss.value() }
    Surface(
        shape = RoundedCornerShape(50),
        color = palette.glassStrong,
        contentColor = palette.onBackground,
        border = BorderStroke(1.dp, palette.glassBorder),
        shadowElevation = 8.dp,
        modifier = modifier.widthIn(max = 440.dp).focusable(false).pointerInput(result) {
            var distance = 0f
            detectHorizontalDragGestures(
                onDragStart = { distance = 0f },
                onHorizontalDrag = { change, delta -> distance += delta; change.consume() },
                onDragEnd = { if (kotlin.math.abs(distance) >= 40.dp.toPx()) dismiss.value() }
            )
        }
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            PersonaAvatarImage(result.personaName, result.avatarPath, Modifier.size(32.dp))
            Text(message, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f, fill = false))
            if (target.value != null) {
                TextButton(onClick = { target.value?.let { view.value(it) }; dismiss.value() }) {
                    Text("查看", color = palette.accent)
                }
            }
        }
    }
}
