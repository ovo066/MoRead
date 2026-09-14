package com.mozhi.reader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.ai.search.TavilyDepth
import com.mozhi.reader.ai.search.WebSearchProvider
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.MoReadRowDivider
import com.mozhi.reader.ui.components.MoReadSecondaryPage
import com.mozhi.reader.ui.components.MoReadSection

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

    MoReadSecondaryPage(title = "网络搜索", subtitle = "选择搜索引擎，让伴读查资料、读网页。", onBack = onBack) {
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
                SearchEngineChoices(provider, state.hasKeys, viewModel::setProvider)
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

@Composable
private fun TavilyDepthSelector(
    title: String,
    selected: TavilyDepth,
    onSelect: (TavilyDepth) -> Unit
) {
    SettingChoiceField(title, if (selected == TavilyDepth.BASIC) "基础 · Basic" else "深入 · Advanced", selected.name,
        listOf(SettingChoice(TavilyDepth.BASIC.name, "基础 · Basic", "更快，更省额度", Icons.Outlined.Bolt),
            SettingChoice(TavilyDepth.ADVANCED.name, "深入 · Advanced", "获取更深入的资料", Icons.Outlined.TravelExplore)),
        { onSelect(TavilyDepth.valueOf(it)) }, icon = Icons.Outlined.Search)
}

@Composable
internal fun SearchEngineChoices(selected: WebSearchProvider, hasKeys: Map<WebSearchProvider, Boolean>, onSelect: (WebSearchProvider) -> Unit) {
    MoReadSection(title = "搜索引擎", footer = "切换引擎会保留各自保存的地址和 API Key。") {
        WebSearchProvider.entries.forEachIndexed { index, provider ->
            if (index > 0) MoReadRowDivider()
            val (brand, detail) = when (provider) {
                WebSearchProvider.FIRECRAWL -> AiBrand.FIRECRAWL to "网页搜索与正文抓取"
                WebSearchProvider.EXA -> AiBrand.EXA to "语义搜索与资料发现"
                WebSearchProvider.TAVILY -> AiBrand.TAVILY to "面向问答的搜索与提取"
            }
            SettingChoiceRow(SettingChoice(provider.name, provider.label,
                "$detail · ${if (hasKeys[provider] == true) "已保存 Key" else "待配置 Key"}", brand = brand),
                selected == provider) { onSelect(provider) }
        }
    }
}
