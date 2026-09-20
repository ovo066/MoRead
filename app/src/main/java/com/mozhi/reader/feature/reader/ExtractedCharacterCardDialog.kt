package com.mozhi.reader.feature.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ai.knowledge.ExtractedCharacterCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ExtractedCharacterCardDialog(initial: ExtractedCharacterCard, onDismiss: () -> Unit,
    onSave: (ExtractedCharacterCard) -> Unit) {
    var name by rememberSaveable(initial.name) { mutableStateOf(initial.name) }
    var description by rememberSaveable(initial.name) { mutableStateOf(initial.description) }
    var status by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft = ExtractedCharacterCard(name.trim(), description.trim())
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = exporting
        if (uri != null && json != null) scope.launch {
            status = try {
                withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(json) } }
                "角色卡已导出，可在伴读中导入"
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { "导出失败：${error.message}" }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("提取角色卡") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("核对人物资料后保存；未提取到的性格和口吻可自行补充。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("姓名") }, singleLine = true)
            OutlinedTextField(description, { description = it.take(24_000) }, label = { Text("角色资料 / 人设") }, minLines = 5, maxLines = 12)
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            TextButton(enabled = draft.name.isNotBlank() && draft.description.isNotBlank(), onClick = {
                exporting = draft.toJson(); export.launch("${draft.name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_")}.json")
            }) { Text("导出角色卡 JSON") }
        }
    }, confirmButton = { TextButton(enabled = draft.name.isNotBlank() && draft.description.isNotBlank(), onClick = { onSave(draft) }) { Text("保存为伴读角色") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
