package com.mozhi.reader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.ai.search.WebSearchProvider
import com.mozhi.reader.ai.search.TavilyDepth
import com.mozhi.reader.ui.components.FrostedSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSearchSettingsScreen(
    onBack: () -> Unit,
    viewModel: WebSearchSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val provider = state.settings.provider
    val savedSearchEndpoint = state.settings.searchEndpoint(provider)
    val savedScrapeEndpoint = state.settings.scrapeEndpoint(provider)
    var searchEndpoint by remember(provider, savedSearchEndpoint) {
        mutableStateOf(savedSearchEndpoint)
    }
    var scrapeEndpoint by remember(provider, savedScrapeEndpoint) {
        mutableStateOf(savedScrapeEndpoint)
    }
    var keyInput by remember(provider) { mutableStateOf("") }
    val hasKey = state.hasKeys[provider] == true

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("网络搜索") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    "开启后，伴读角色可以联网查资料、读网页。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                FrostedSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("允许伴读联网", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (state.settings.enabled) "当前使用 ${provider.label}" else "关闭时不会联网",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.settings.enabled,
                            onCheckedChange = viewModel::setEnabled
                        )
                    }
                }
            }
            item {
                Text("搜索引擎", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WebSearchProvider.entries.forEach { candidate ->
                        FilterChip(
                            selected = provider == candidate,
                            onClick = { viewModel.setProvider(candidate) },
                            label = { Text(candidate.label) }
                        )
                    }
                }
            }
            item {
                FrostedSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    shadowElevation = 5.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("${provider.label} 配置", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = searchEndpoint,
                            onValueChange = { searchEndpoint = it },
                            label = { Text("搜索接口地址") },
                            supportingText = {
                                Text(if (searchEndpoint == provider.defaultSearchEndpoint) "官方默认地址" else "支持兼容或自托管地址")
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    searchEndpoint = provider.defaultSearchEndpoint
                                    viewModel.resetEndpoint(provider)
                                }
                            ) { Text("恢复默认") }
                            TextButton(
                                enabled = searchEndpoint.startsWith("http://") || searchEndpoint.startsWith("https://"),
                                onClick = { viewModel.saveEndpoint(provider, searchEndpoint) }
                            ) { Text("保存搜索地址") }
                        }
                        OutlinedTextField(
                            value = scrapeEndpoint,
                            onValueChange = { scrapeEndpoint = it },
                            label = { Text("网页抓取接口地址") },
                            supportingText = {
                                Text(if (scrapeEndpoint == provider.defaultScrapeEndpoint) "官方默认地址" else "支持兼容或自托管地址")
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    scrapeEndpoint = provider.defaultScrapeEndpoint
                                    viewModel.resetScrapeEndpoint(provider)
                                }
                            ) { Text("恢复默认") }
                            TextButton(
                                enabled = scrapeEndpoint.startsWith("http://") || scrapeEndpoint.startsWith("https://"),
                                onClick = { viewModel.saveScrapeEndpoint(provider, scrapeEndpoint) }
                            ) { Text("保存抓取地址") }
                        }
                        if (provider == WebSearchProvider.TAVILY) {
                            TavilyDepthSelector(
                                title = "搜索深度",
                                selected = state.settings.tavilySearchDepth,
                                onSelect = viewModel::setTavilySearchDepth
                            )
                            TavilyDepthSelector(
                                title = "抓取深度",
                                selected = state.settings.tavilyExtractDepth,
                                onSelect = viewModel::setTavilyExtractDepth
                            )
                            Text(
                                "Basic 快且省；Advanced 查得更深，每次搜索多花一倍额度。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { keyInput = it },
                            label = { Text("API Key") },
                            placeholder = { Text(if (hasKey) "已保存，重新输入可覆盖" else "请输入 ${provider.label} API Key") },
                            leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hasKey) {
                                OutlinedButton(onClick = { viewModel.clearApiKey(provider) }) {
                                    Text("删除 Key")
                                }
                            }
                            TextButton(
                                enabled = keyInput.isNotBlank(),
                                onClick = {
                                    viewModel.saveApiKey(provider, keyInput)
                                    keyInput = ""
                                }
                            ) { Text(if (hasKey) "覆盖保存" else "保存 Key") }
                        }
                    }
                }
            }
            item {
                Text(
                    "API Key 加密保存在本机，不进备份；搜索词与要打开的网址会发给所选服务商。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TavilyDepthSelector(
    title: String,
    selected: TavilyDepth,
    onSelect: (TavilyDepth) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TavilyDepth.entries.forEach { depth ->
                FilterChip(
                    selected = selected == depth,
                    onClick = { onSelect(depth) },
                    label = { Text(depth.label) }
                )
            }
        }
    }
}
