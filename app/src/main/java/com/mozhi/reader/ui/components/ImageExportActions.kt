package com.mozhi.reader.ui.components

import android.os.Build
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.mozhi.reader.core.media.LocalImageExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ImageDocumentRequest(val name: String, val mimeType: String)
internal class ImageDocumentContract : ActivityResultContract<ImageDocumentRequest, Uri?>() {
    override fun createIntent(context: Context, input: ImageDocumentRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType).putExtra(Intent.EXTRA_TITLE, input.name)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

@Composable
fun ImageExportActions(
    path: String?,
    modifier: Modifier = Modifier,
    onResult: ((String) -> Unit)? = null,
    menuTrigger: (@Composable (enabled: Boolean, working: Boolean, onClick: () -> Unit) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // Capture the source before opening the system picker; a reroll must not change it.
    var documentSource by rememberSaveable { mutableStateOf<String?>(null) }
    val document = rememberLauncherForActivityResult(ImageDocumentContract()) { uri ->
        val source = documentSource
        documentSource = null
        if (uri != null && source != null) scope.launch {
            working = true
            try {
                LocalImageExporter.saveToDocument(context, source, uri)
                message = "图片已导出，应用内原图保留"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "导出失败，请重试" }
            finally { working = false }
        }
    }
    var menuExpanded by remember { mutableStateOf(false) }
    val enabled = path != null && !working && documentSource == null
    LaunchedEffect(path) { message = null }
    LaunchedEffect(message) {
        if (onResult != null) message?.let { onResult(it); message = null }
    }
    val saveGallery: () -> Unit = {
        val selected = path
        if (selected != null && !working && documentSource == null) {
            menuExpanded = false
            working = true
            scope.launch {
                try {
                    LocalImageExporter.saveToGallery(context, selected)
                    message = "已保存到相册 Pictures/MoRead"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { message = error.message ?: "保存失败，请重试" }
                finally { working = false }
            }
        }
    }
    val saveDocument: () -> Unit = {
        val selected = path
        if (selected != null && !working && documentSource == null) {
            menuExpanded = false
            working = true
            scope.launch {
                try {
                    val source = withContext(Dispatchers.IO) { LocalImageExporter.source(context, selected) }
                    documentSource = selected
                    document.launch(ImageDocumentRequest(source.suggestedName, source.mimeType))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { documentSource = null; message = error.message ?: "无法导出图片" }
                finally { working = false }
            }
        }
    }
    // Keep the launcher and pending source outside the popup so closing it never loses a save.
    if (menuTrigger != null) {
        Box(modifier) {
            menuTrigger(enabled, working) { menuExpanded = true }
            MoReadDropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                if (Build.VERSION.SDK_INT >= 29) MoReadMenuItem(text = "保存到相册",
                    icon = Icons.Outlined.PhotoLibrary, enabled = enabled, onClick = saveGallery)
                MoReadMenuItem(text = "导出文件", icon = Icons.Outlined.FolderOpen,
                    enabled = enabled, onClick = saveDocument)
            }
        }
        return
    }
    Column(modifier) {
        FlowRow {
            if (Build.VERSION.SDK_INT >= 29) TextButton(enabled = enabled, onClick = saveGallery) {
                Text("保存到相册")
            }
            TextButton(enabled = enabled, onClick = saveDocument) { Text("导出文件") }
        }
        if (working) Text("正在保存…", style = MaterialTheme.typography.labelSmall)
        message?.let { Text(it, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}
