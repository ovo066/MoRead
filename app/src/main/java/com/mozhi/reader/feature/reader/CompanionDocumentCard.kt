package com.mozhi.reader.feature.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.mozhi.reader.R
import com.mozhi.reader.ai.agent.DocumentKind
import com.mozhi.reader.ai.agent.DocumentSpec
import com.mozhi.reader.ai.agent.DocumentSpecCodec
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 伴读交给用户的一份文件（create_document）。聊天流里只占一张固定高度的卡：
 * 标题、字数与两行预览；点开全屏阅读，可分享或经系统文件选择器保存（无需存储权限）。
 */
@Composable
internal fun CompanionDocumentCard(spec: DocumentSpec, palette: ReaderPalette, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val preview = remember(spec) { DocumentSpecCodec.preview(spec) }
    val actions = rememberDocumentActions(spec)
    Surface(
        color = palette.glass,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, palette.glassBorder),
        modifier = modifier.fillMaxWidth().clickable { expanded = true }
    ) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = palette.accent.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp)) {
                Icon(spec.documentKind.icon(), null, Modifier.padding(10.dp).size(22.dp), tint = palette.accent)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    spec.title, style = MaterialTheme.typography.labelLarge, color = palette.onBackground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(R.string.document_card_meta, stringResource(spec.documentKind.label()), spec.content.length),
                    style = MaterialTheme.typography.labelSmall, color = palette.muted, maxLines = 1
                )
                Text(
                    preview, style = MaterialTheme.typography.bodySmall, color = palette.muted,
                    maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            IconButton(onClick = actions.share, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Share, stringResource(R.string.document_share), Modifier.size(18.dp), tint = palette.muted)
            }
        }
    }
    if (expanded) CompanionDocumentDialog(spec, palette, actions) { expanded = false }
}

private class DocumentActions(val share: () -> Unit, val save: () -> Unit)

@Composable
private fun rememberDocumentActions(spec: DocumentSpec): DocumentActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fileName = DocumentSpecCodec.fileName(spec, stringResource(R.string.document_file_fallback))
    val chooserTitle = stringResource(R.string.document_share)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) scope.launch {
            val ok = runCatching { writeTo(context, uri, spec.content) }.isSuccess
            Toast.makeText(context, if (ok) R.string.document_saved else R.string.document_failed, Toast.LENGTH_SHORT).show()
        }
    }
    return remember(spec, fileName) {
        DocumentActions(
            share = {
                scope.launch {
                    runCatching {
                        val uri = withContext(Dispatchers.IO) {
                            val dir = File(context.cacheDir, "export").apply { mkdirs() }
                            val file = File(dir, fileName).apply { writeText(spec.content, Charsets.UTF_8) }
                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        }
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/markdown"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, spec.title)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, chooserTitle))
                    }.onFailure { Toast.makeText(context, R.string.document_failed, Toast.LENGTH_SHORT).show() }
                }
            },
            save = {
                runCatching { launcher.launch(fileName) }
                    .onFailure { Toast.makeText(context, R.string.document_failed, Toast.LENGTH_SHORT).show() }
            }
        )
    }
}

private suspend fun writeTo(context: Context, uri: Uri, content: String) = withContext(Dispatchers.IO) {
    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        ?: error("cannot open document")
}

@Composable
private fun CompanionDocumentDialog(spec: DocumentSpec, palette: ReaderPalette, actions: DocumentActions, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = palette.background, contentColor = palette.onBackground, shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        spec.title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.chart_close)) }
                }
                Box(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    AiRichText(spec.content, palette, parseSynchronously = true)
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = actions.share) {
                        Icon(Icons.Outlined.Share, null, Modifier.size(16.dp), tint = palette.accent)
                        Text(stringResource(R.string.document_share), color = palette.accent, modifier = Modifier.padding(start = 6.dp))
                    }
                    TextButton(onClick = actions.save) {
                        Icon(Icons.Outlined.SaveAlt, null, Modifier.size(16.dp), tint = palette.accent)
                        Text(stringResource(R.string.document_save), color = palette.accent, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

private fun DocumentKind.icon(): ImageVector = when (this) {
    DocumentKind.READING_REPORT -> Icons.Outlined.MenuBook
    DocumentKind.CHAPTER_SUMMARY -> Icons.Outlined.Notes
    DocumentKind.CHARACTER_SHEET -> Icons.Outlined.Groups
    DocumentKind.QUOTE_COLLECTION -> Icons.Outlined.FormatQuote
    DocumentKind.FREE -> Icons.Outlined.Description
}

private fun DocumentKind.label(): Int = when (this) {
    DocumentKind.READING_REPORT -> R.string.document_kind_reading_report
    DocumentKind.CHAPTER_SUMMARY -> R.string.document_kind_chapter_summary
    DocumentKind.CHARACTER_SHEET -> R.string.document_kind_character_sheet
    DocumentKind.QUOTE_COLLECTION -> R.string.document_kind_quote_collection
    DocumentKind.FREE -> R.string.document_kind_free
}
