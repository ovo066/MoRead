package com.mozhi.reader.feature.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderSyntaxRule
import com.mozhi.reader.ui.components.NavigationSheet
import com.mozhi.reader.ui.components.blockSheetDrag

/** 与排版分开，编辑规则后保留规则列表的滚动位置。 */
@Composable
internal fun ReaderSyntaxSheet(
    settings: ReaderSettings, palette: ReaderPalette, actions: ReaderSyntaxActions, onDismiss: () -> Unit
) {
    var draft by remember { mutableStateOf<ReaderSyntaxRule?>(null) }
    val scroll = rememberScrollState()
    NavigationSheet(onDismiss, palette.glassStrong, palette.onBackground, palette.scrim) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("语法高亮规则", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭语法高亮") }
            }
            Column(Modifier.weight(1f).fillMaxWidth().blockSheetDrag(scroll).verticalScroll(scroll)
                .testTag("syntax-rules-list").padding(horizontal = 20.dp, vertical = 12.dp)) {
                SyntaxHighlightEditor(settings, palette, actions.onSyntaxHighlightEnabledChange,
                    onEdit = { draft = it }, onAdd = {
                        draft = ReaderSyntaxRule(id = 0L, name = "自定义规则", startDelimiter = "", endDelimiter = "",
                            colorArgb = palette.accent.toArgb())
                    })
            }
        }
        draft?.let { rule ->
            SyntaxRuleEditorDialog(rule, settings.fontLibrary, readerSettings = settings, previewPalette = palette, onImportImage = actions.onImportStyleImage, onDismiss = { draft = null }, onSave = {
                actions.onSaveSyntaxRule(it)
                draft = null
            }, onDelete = if (rule.id == 0L) null else {
                { actions.onDeleteSyntaxRule(rule.id); draft = null }
            })
        }
    }
}
