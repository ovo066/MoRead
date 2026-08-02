package com.mozhi.reader.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ui.components.blockSheetDrag

/** 书内关键词搜索弹层：即输即搜、逐章流式出结果，点击命中跳到对应位置。 */
@Composable
fun ReaderSearchSheet(
    state: ReaderSearchUiState,
    palette: ReaderPalette,
    onQueryChange: (String) -> Unit,
    onHitClick: (BookSearchHit) -> Unit
) {
    val listState = rememberLazyListState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.88f)
            .blockSheetDrag(listState)
            .imePadding()
            .padding(horizontal = 16.dp)
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = { Text("搜索本书内容…") },
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = palette.muted)
            },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "清空",
                            tint = palette.muted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            shape = RoundedCornerShape(15.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = palette.accent,
                unfocusedBorderColor = palette.glassBorder
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 8.dp)
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.isSearching) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = palette.accent,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                text = when {
                    state.isSearching -> "正在搜索…已找到 ${state.hits.size} 处"
                    state.completed && state.hits.isEmpty() -> "没有找到「${state.query.trim()}」"
                    state.completed -> "共 ${state.hits.size} 处结果"
                    else -> "输入关键词开始搜索"
                },
                style = MaterialTheme.typography.labelSmall,
                color = palette.muted,
                modifier = Modifier.padding(start = if (state.isSearching) 6.dp else 0.dp)
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(state.hits, key = { "${it.chapterIndex}-${it.charOffset}" }) { hit ->
                Surface(
                    color = palette.glass,
                    shape = RoundedCornerShape(12.dp),
                    onClick = { onHitClick(hit) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Text(
                            text = "第 ${hit.chapterIndex + 1} 章" +
                                hit.chapterTitle.takeIf(String::isNotBlank)?.let { "「$it」" }.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = highlightSnippet(hit, palette),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.onBackground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun highlightSnippet(hit: BookSearchHit, palette: ReaderPalette) = buildAnnotatedString {
    val start = hit.matchStartInSnippet.coerceIn(0, hit.snippet.length)
    val end = (start + hit.matchLength).coerceIn(start, hit.snippet.length)
    append(hit.snippet.substring(0, start))
    withStyle(SpanStyle(color = palette.accent, fontWeight = FontWeight.Bold)) {
        append(hit.snippet.substring(start, end))
    }
    append(hit.snippet.substring(end))
}
