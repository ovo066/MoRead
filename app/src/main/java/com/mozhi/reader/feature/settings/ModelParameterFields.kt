package com.mozhi.reader.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ui.components.MoReadRow
import com.mozhi.reader.ui.components.MoReadRowDivider
import kotlinx.serialization.json.*

internal data class ModelParameter(val key: String, val title: String, val detail: String, val max: Double, val integer: Boolean = false)

internal fun parameterValue(json: String, key: String): String? = runCatching {
    val root = AiJson.parseToJsonElement(json.ifBlank { "{}" }).jsonObject
    ((root["body"] as? JsonObject)?.get(key) ?: root[key])?.jsonPrimitive?.contentOrNull
}.getOrNull()

/** Update only this parameter, preserving headers, model-specific switches, and unrelated JSON. */
internal fun withModelParameter(json: String, parameter: ModelParameter, value: String): String {
    val root = AiJson.parseToJsonElement(json.ifBlank { "{}" }).jsonObject.toMutableMap()
    val text = value.trim()
    val number = text.toDoubleOrNull()
    require(text.isEmpty() || number != null && number.isFinite() && number >= 0 && number <= parameter.max &&
        (!parameter.integer || text.toIntOrNull()?.let { it > 0 } == true)) { "请输入有效的${parameter.title}" }
    // Raw body overrides the usual options. Remove its duplicate so the visible value is authoritative.
    (root["body"] as? JsonObject)?.let { body ->
        val rest = body.toMutableMap().apply { remove(parameter.key) }
        if (rest.isEmpty()) root.remove("body") else root["body"] = JsonObject(rest)
    }
    if (text.isEmpty()) root.remove(parameter.key)
    else root[parameter.key] = if (parameter.integer) JsonPrimitive(text.toInt()) else JsonPrimitive(number!!)
    return JsonObject(root).toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelParameterRows(extraJson: String, onChange: (String) -> Unit, inheritedJson: String? = null,
    temperatureMax: Double = 2.0) {
    val parameters = remember(temperatureMax) { listOf(
        ModelParameter("temperature", "温度", "Temperature · 调整生成内容的随机性", temperatureMax),
        ModelParameter("max_tokens", "最大生成长度", "Max Tokens · 每次回复最多生成的 token 数", Int.MAX_VALUE.toDouble(), true),
        ModelParameter("top_p", "采样范围", "Top P · 调整候选词的采样范围", 1.0)
    ) }
    var editing by remember { mutableStateOf<ModelParameter?>(null) }
    parameters.forEachIndexed { index, parameter ->
        if (index > 0) MoReadRowDivider(inset = 16.dp)
        val explicit = parameterValue(extraJson, parameter.key)
        val inherited = inheritedJson?.let { parameterValue(it, parameter.key) }
        MoReadRow(title = parameter.title, subtitle = parameter.detail, onClick = { editing = parameter }, trailing = {
            Text(explicit ?: inherited?.let { "跟随 · $it" } ?: if (inheritedJson == null) "默认" else "跟随供应商",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Icon(Icons.Outlined.ChevronRight, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        })
    }
    editing?.let { parameter ->
        val field = rememberTextFieldState(parameterValue(extraJson, parameter.key).orEmpty())
        val value = field.text.toString()
        val updated = remember(value, extraJson) { runCatching { withModelParameter(extraJson, parameter, value) } }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(parameter.title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(parameter.detail, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(state = field, lineLimits = TextFieldLineLimits.SingleLine, label = { Text("数值（留空使用默认）") },
                    keyboardOptions = KeyboardOptions(keyboardType = if (parameter.integer) KeyboardType.Number else KeyboardType.Decimal),
                    isError = updated.isFailure, supportingText = {
                        Text(if (updated.isFailure) "数值或高级 JSON 参数无效" else if (parameter.integer) "请输入正整数" else "范围：0–${parameter.max}")
                    }, modifier = Modifier.fillMaxWidth())
            }
        }, confirmButton = { TextButton(enabled = updated.isSuccess, onClick = { onChange(updated.getOrThrow()); editing = null }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } })
    }
}
