package com.mozhi.reader.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class VoiceChoice(val id: String, val name: String, val description: String = "")

/** Searchable voice library; a full page keeps large engine catalogs usable. */
@Composable
fun VoiceChoiceDialog(
    title: String,
    choices: List<VoiceChoice>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(choices, query) {
        choices.filter { query.isBlank() || (it.name + " " + it.description).contains(query, ignoreCase = true) }
    }
    MoReadPageDialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("搜索音色或语言") }, modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(Modifier.weight(1f)) {
                    if (visible.isEmpty()) item { Text("没有匹配的音色", modifier = Modifier.padding(vertical = 24.dp)) }
                    items(visible, key = { it.id }) { voice ->
                        MoReadRow(
                            title = voice.name, subtitle = voice.description.takeIf(String::isNotBlank),
                            onClick = { onSelect(voice.id) },
                            trailing = { RadioButton(selected = voice.id == selectedId, onClick = { onSelect(voice.id) }) }
                        )
                    }
                }
            }
        }
    }
}
