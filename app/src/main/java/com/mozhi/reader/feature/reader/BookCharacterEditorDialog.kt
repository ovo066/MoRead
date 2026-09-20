package com.mozhi.reader.feature.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.mozhi.reader.ui.components.MoReadPageDialog
import com.mozhi.reader.ai.knowledge.BookCharacter

@Composable
internal fun BookCharacterEditorDialog(person: BookCharacter?, people: List<BookCharacter>, onDismiss: () -> Unit,
    onSave: (String?, String, String) -> Unit) {
    var name by rememberSaveable(person?.identity) { mutableStateOf(person?.name.orEmpty()) }
    var description by rememberSaveable(person?.identity) {
        mutableStateOf(person?.manualDescription ?: person?.evidence?.joinToString("\n") { it.fact.text }.orEmpty())
    }
    val duplicate = people.any { it.identity != person?.identity && (it.name.equals(name.trim(), true) || it.identity.equals(name.trim(), true)) }
    MoReadPageDialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(20.dp)) {
                Text(if (person == null) "新增书中人物" else "编辑书中人物", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(name, { name = it.take(80) }, label = { Text("姓名") }, singleLine = true, isError = duplicate,
                        supportingText = if (duplicate) ({ Text("已有同名人物，请直接编辑该人物") }) else null,
                        modifier = Modifier.fillMaxWidth().testTag("character-name"))
                    OutlinedTextField(description, { description = it.take(24_000) }, label = { Text("人物资料 / 身份与关系") }, minLines = 8,
                        modifier = Modifier.fillMaxWidth().testTag("character-description"))
                    Text("手动整理的资料会保留，后续 AI 提取不会覆盖。原文依据单独保存，可回到正文核对。", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(enabled = name.isNotBlank() && !duplicate, onClick = { onSave(person?.identity, name.trim(), description.trim()) }) { Text("保存人物") }
                }
            }
        }
    }
}
