package com.mozhi.reader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mozhi.reader.core.media.ImageApiProvider
import com.mozhi.reader.core.media.NOVELAI_SAMPLERS
import com.mozhi.reader.core.media.sizeOptions
import com.mozhi.reader.ui.components.MoReadBackdrop
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 独立「生图 API」设置页：与对话 Provider 解耦。支持 OpenAI 兼容生图端点、
 * 经 chat/completions 出图的 gpt-image 中转，以及 NovelAI；配好后优先于模型分配。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGenSettingsScreen(
    onBack: () -> Unit,
    viewModel: ImageGenSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings

    MoReadBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
                Text(
                    "生图 API",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "填好服务商与模型即可生成插图；API Key 加密保存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                var providerMenuExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = providerMenuExpanded,
                    onExpandedChange = { providerMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = settings.provider.label(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("服务商") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = providerMenuExpanded,
                        onDismissRequest = { providerMenuExpanded = false }
                    ) {
                        ImageApiProvider.entries.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.label()) },
                                onClick = {
                                    providerMenuExpanded = false
                                    viewModel.setProvider(provider)
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = settings.baseUrl,
                    onValueChange = viewModel::setBaseUrl,
                    label = { Text("Base URL") },
                    supportingText = {
                        Text(
                            when (settings.provider) {
                                ImageApiProvider.NOVELAI -> "官方为 https://image.novelai.net"
                                else -> "OpenAI 官方或任意兼容中转，如 https://api.openai.com/v1"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                var keyInput by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = {
                        Text(if (state.hasApiKey) "已保存，重新输入可覆盖" else "尚未保存")
                    },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(
                            onClick = {
                                viewModel.saveApiKey(keyInput)
                                keyInput = ""
                            },
                            enabled = keyInput.isNotBlank()
                        ) { Text("保存") }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.hasApiKey) {
                    TextButton(onClick = viewModel::clearApiKey) { Text("删除已保存的 Key") }
                }

                OutlinedTextField(
                    value = settings.model,
                    onValueChange = viewModel::setModel,
                    label = { Text("模型") },
                    supportingText = {
                        Text(
                            when (settings.provider) {
                                ImageApiProvider.NOVELAI ->
                                    "如 nai-diffusion-4-5-full / nai-diffusion-3"
                                else -> "如 gpt-image-1；中转命名以其文档为准"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                var sizeMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedTextField(
                        value = settings.size,
                        onValueChange = viewModel::setSize,
                        label = { Text("图片尺寸") },
                        placeholder = { Text(settings.effectiveSize) },
                        supportingText = {
                            Text(
                                if (settings.provider == ImageApiProvider.NOVELAI) {
                                    "宽x高可自定义，会自动对齐 64 的倍数；留空用 ${settings.provider.sizeOptions().first()}"
                                } else {
                                    "留空用 ${settings.provider.sizeOptions().first()}"
                                }
                            )
                        },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { sizeMenuExpanded = true }) {
                                Icon(Icons.Outlined.ExpandMore, contentDescription = "常用尺寸")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = sizeMenuExpanded,
                        onDismissRequest = { sizeMenuExpanded = false }
                    ) {
                        settings.provider.sizeOptions().forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    sizeMenuExpanded = false
                                    viewModel.setSize(option)
                                }
                            )
                        }
                    }
                }

                if (settings.provider == ImageApiProvider.NOVELAI) {
                    OutlinedTextField(
                        value = settings.positivePrompt,
                        onValueChange = viewModel::setPositivePrompt,
                        label = { Text("固定正面提示词（可选）") },
                        supportingText = {
                            Text("英文 tags，会加在自动生成的提示词前面")
                        },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    var samplerMenuExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = samplerMenuExpanded,
                        onExpandedChange = { samplerMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = settings.sampler.ifBlank { "k_euler_ancestral（默认）" },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("采样器") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = samplerMenuExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = samplerMenuExpanded,
                            onDismissRequest = { samplerMenuExpanded = false }
                        ) {
                            NOVELAI_SAMPLERS.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        samplerMenuExpanded = false
                                        viewModel.setSampler(option)
                                    }
                                )
                            }
                        }
                    }
                    Column {
                        Text(
                            "采样步数：${settings.steps}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Slider(
                            value = settings.steps.toFloat(),
                            onValueChange = { viewModel.setSteps(it.roundToInt()) },
                            valueRange = 10f..50f,
                            steps = 39
                        )
                    }
                    Column {
                        Text(
                            "提示词相关度：${String.format(Locale.ROOT, "%.1f", settings.scale)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Slider(
                            value = settings.scale,
                            onValueChange = { viewModel.setScale((it * 2).roundToInt() / 2f) },
                            valueRange = 1f..10f
                        )
                    }
                    OutlinedTextField(
                        value = settings.negativePrompt,
                        onValueChange = viewModel::setNegativePrompt,
                        label = { Text("负面提示词（可选）") },
                        supportingText = { Text("英文 tags；留空用内置默认负面词") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = viewModel::testGenerate, enabled = !state.isTesting) {
                        if (state.isTesting) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .width(16.dp)
                                    .heightIn(max = 16.dp)
                            )
                        } else {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(if (state.isTesting) "正在生成…" else "测试生成")
                    }
                    state.message?.let { text ->
                        Text(
                            text,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }

                state.testImagePath?.let { path ->
                    AsyncImage(
                        model = path,
                        contentDescription = "测试生成的图片",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .padding(bottom = 24.dp)
                    )
                }
            }
        }
    }
}

private fun ImageApiProvider.label(): String = when (this) {
    ImageApiProvider.OPENAI_IMAGES -> "OpenAI 兼容 · 生图端点"
    ImageApiProvider.OPENAI_CHAT -> "OpenAI 兼容 · 聊天端点出图"
    ImageApiProvider.NOVELAI -> "NovelAI"
}
