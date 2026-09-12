package com.mozhi.reader.ui.components

import android.os.Build
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
fun ImageExportActions(path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember(path) { mutableStateOf(false) }
    var message by remember(path) { mutableStateOf<String?>(null) }
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
    Column(modifier) {
        FlowRow {
            if (Build.VERSION.SDK_INT >= 29) TextButton(enabled = !working && documentSource == null, onClick = {
                scope.launch {
                    working = true
                    try {
                        LocalImageExporter.saveToGallery(context, path)
                        message = "已保存到相册 Pictures/MoRead"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { message = error.message ?: "保存失败，请重试" }
                    finally { working = false }
                }
            }) { Text("保存到相册") }
            TextButton(enabled = !working && documentSource == null, onClick = {
                scope.launch {
                    working = true
                    try {
                        val source = withContext(Dispatchers.IO) { LocalImageExporter.source(context, path) }
                        documentSource = path
                        document.launch(ImageDocumentRequest(source.suggestedName, source.mimeType))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { documentSource = null; message = error.message ?: "无法导出图片" }
                    finally { working = false }
                }
            }) { Text("导出文件") }
        }
        if (working) Text("正在保存…", style = MaterialTheme.typography.labelSmall)
        message?.let { Text(it, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}
