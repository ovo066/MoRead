package com.mozhi.reader.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.mozhi.reader.ai.listen.ListenEngine
import com.mozhi.reader.ai.listen.ListenState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** 阅读页的听书门面：全部逻辑在单例 [ListenEngine]，退出阅读页不打断播放。 */
@HiltViewModel
class ReaderListenViewModel @Inject constructor(
    private val engine: ListenEngine
) : ViewModel() {
    val state = engine.state

    fun start(bookId: Long, chapterIndex: Int, charOffset: Int) =
        engine.start(bookId, chapterIndex, charOffset)

    fun toggle() = engine.toggle()
    fun stop() = engine.stop()
    fun prevSentence() = engine.prevSentence()
    fun nextSentence() = engine.nextSentence()
    fun prevChapter() = engine.prevChapter()
    fun nextChapter() = engine.nextChapter()
    fun seekTo(chapterIndex: Int, charOffset: Int) = engine.seekTo(chapterIndex, charOffset)
}

/** 听书悬浮控制舱：上一章/上一句/播放暂停/下一句/下一章 + 退出，玻璃胶囊风格。 */
@Composable
fun ReaderListenBar(
    state: ListenState,
    palette: ReaderPalette,
    visible: Boolean,
    onToggle: () -> Unit,
    onPrevSentence: () -> Unit,
    onNextSentence: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = palette.glass,
            border = BorderStroke(1.dp, palette.glassBorder),
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 10.dp)
                .shadow(14.dp, RoundedCornerShape(24.dp), clip = false)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = state.status ?: state.chapterTitle.ifBlank { "正在朗读" },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.status != null) palette.accent else palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    ListenControlButton(
                        icon = Icons.Outlined.SkipPrevious,
                        description = "上一章",
                        palette = palette,
                        onClick = onPrevChapter
                    )
                    ListenControlButton(
                        icon = Icons.Outlined.FastRewind,
                        description = "上一句",
                        palette = palette,
                        onClick = onPrevSentence
                    )
                    Surface(
                        onClick = onToggle,
                        shape = CircleShape,
                        color = palette.accent,
                        contentColor = palette.onAccent
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) {
                                Icons.Outlined.Pause
                            } else {
                                Icons.Outlined.PlayArrow
                            },
                            contentDescription = if (state.isPlaying) "暂停" else "继续",
                            modifier = Modifier
                                .padding(9.dp)
                                .size(24.dp)
                        )
                    }
                    ListenControlButton(
                        icon = Icons.Outlined.FastForward,
                        description = "下一句",
                        palette = palette,
                        onClick = onNextSentence
                    )
                    ListenControlButton(
                        icon = Icons.Outlined.SkipNext,
                        description = "下一章",
                        palette = palette,
                        onClick = onNextChapter
                    )
                    ListenControlButton(
                        icon = Icons.Outlined.Close,
                        description = "退出听书",
                        palette = palette,
                        onClick = onExit
                    )
                }
            }
        }
    }
}

@Composable
private fun ListenControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = palette.onBackground,
            modifier = Modifier.size(22.dp)
        )
    }
}
