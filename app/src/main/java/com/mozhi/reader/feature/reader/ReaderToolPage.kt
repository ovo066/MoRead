package com.mozhi.reader.feature.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ui.MoReadWindowWidth
import com.mozhi.reader.ui.rememberMoReadWindowWidth
import com.mozhi.reader.ui.components.*

private val LocalReaderToolDismiss = compositionLocalOf<(() -> Unit)?> { null }

/** Reading tools share the navigation panel's window, safe area and fixed viewport. */
@Composable
internal fun ReaderToolDialog(
    onDismiss: () -> Unit,
    immersiveOnPhone: Boolean = false,
    content: @Composable () -> Unit
) {
    val sidePanel = rememberMoReadWindowWidth() == MoReadWindowWidth.EXPANDED
    // Keep the same Dialog and content composition when crossing the tablet breakpoint.
    NavigationSheet(onDismiss, MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.scrim.copy(alpha = .24f),
        compactPresentation = if (immersiveOnPhone) CompactNavigationPresentation.IMMERSIVE_PAGE else CompactNavigationPresentation.PAGE) {
        CompositionLocalProvider(LocalReaderToolDismiss provides if (sidePanel) onDismiss else null, content = content)
    }
}

/** Settings use the global page shell; reading tools use a compact, stationary panel header. */
@Composable
internal fun ReaderToolPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    applyTopInset: Boolean = true,
    panelBack: Boolean = false,
    content: LazyListScope.() -> Unit
) {
    val dismiss = LocalReaderToolDismiss.current
    if (dismiss == null) {
        MoReadSecondaryPage(title, onBack, modifier, listState = listState,
            applyTopInset = applyTopInset, content = content)
    } else {
        Column(modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(72.dp).padding(start = if (panelBack) 8.dp else 24.dp, end = 8.dp)
                .testTag("reader-tool-header"), verticalAlignment = Alignment.CenterVertically) {
                if (panelBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "关闭$title") }
            }
            LazyColumn(state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().blockSheetDrag(listState).testTag("secondary-page-list"),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
        }
    }
}
