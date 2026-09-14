package com.mozhi.reader.feature.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mozhi.reader.R
import com.mozhi.reader.core.database.entity.AiModelType
import com.mozhi.reader.core.database.entity.AiProviderAdapter
import com.mozhi.reader.ui.theme.sectionCardColor
import com.mozhi.reader.ui.theme.sectionHairline

internal enum class AiBrand(@DrawableRes val drawable: Int, val tint: Color, val label: String,
    val originalColors: Boolean = false) {
    OPENAI(R.drawable.ic_ai_openai, Color(0xFF26836A), "OpenAI"),
    CLAUDE(R.drawable.ic_ai_claude, Color(0xFFC47854), "Claude"),
    GEMINI(R.drawable.ic_ai_gemini, Color(0xFF4B80E8), "Gemini", true),
    DEEPSEEK(R.drawable.ic_ai_deepseek, Color(0xFF4D6BFE), "DeepSeek"),
    QWEN(R.drawable.ic_ai_qwen, Color(0xFF8065D7), "Qwen"),
    ZHIPU(R.drawable.ic_ai_zhipu, Color(0xFF5075CF), "GLM"),
    MINIMAX(R.drawable.ic_ai_minimax, Color(0xFFE05B8F), "MiniMax", true),
    OLLAMA(R.drawable.ic_ai_ollama, Color(0xFF708079), "Ollama"),
    META(R.drawable.ic_ai_meta, Color(0xFF347CE0), "Llama"),
    KIMI(R.drawable.ic_ai_kimi, Color(0xFF63758B), "Kimi"),
    OPENROUTER(R.drawable.ic_ai_openrouter, Color(0xFF7C73A9), "OpenRouter"),
    FIRECRAWL(R.drawable.ic_ai_firecrawl, Color(0xFFE6783E), "Firecrawl"),
    EXA(R.drawable.ic_ai_exa, Color(0xFF4671D5), "Exa"),
    TAVILY(R.drawable.ic_ai_tavily, Color(0xFFBE8C43), "Tavily"),
    MISTRAL(R.drawable.ic_ai_mistral, Color(0xFFDD8939), "Mistral"),
    GROK(R.drawable.ic_ai_grok, Color(0xFF6A747F), "Grok"),
    VOLCENGINE(R.drawable.ic_ai_volcengine, Color(0xFF006EFF), "火山方舟", true),
    SILICONFLOW(R.drawable.ic_ai_siliconflow, Color(0xFF6E29F6), "硅基流动", true)
}

/** Model identity is independent of the gateway that serves it, including OpenRouter prefixes. */
internal fun modelBrand(name: String): AiBrand? {
    val value = name.lowercase(java.util.Locale.ROOT)
    return when {
        "deepseek" in value -> AiBrand.DEEPSEEK
        "claude" in value || "anthropic" in value -> AiBrand.CLAUDE
        "gemini" in value || "gemma" in value -> AiBrand.GEMINI
        "qwen" in value || "qwq" in value || "qvq" in value -> AiBrand.QWEN
        "glm" in value || "zhipu" in value || "智谱" in value || "z.ai" in value -> AiBrand.ZHIPU
        "minimax" in value || "mini-max" in value || value.substringAfterLast('/').startsWith("abab") -> AiBrand.MINIMAX
        "llama" in value -> AiBrand.META
        "kimi" in value || "moonshot" in value -> AiBrand.KIMI
        "mistral" in value || "mixtral" in value || "codestral" in value -> AiBrand.MISTRAL
        "grok" in value -> AiBrand.GROK
        "gpt" in value || "openai/" in value || "dall-e" in value || value.startsWith("text-embedding-") ||
            Regex("(^|/)o[134]([-. :]|$)").containsMatchIn(value) -> AiBrand.OPENAI
        else -> null
    }
}

internal fun providerBrand(adapter: AiProviderAdapter, name: String = "", url: String = ""): AiBrand? {
    val host = runCatching { java.net.URI(url.trim()).host?.lowercase(java.util.Locale.ROOT) }.getOrNull().orEmpty()
    fun domain(value: String) = host == value || host.endsWith(".$value")
    val title = name.lowercase(java.util.Locale.ROOT)
    // A compatible protocol does not change the identity of the service hosting it.
    val service = when {
        domain("volces.com") || domain("volcengine.com") -> AiBrand.VOLCENGINE
        domain("siliconflow.cn") || domain("siliconflow.com") -> AiBrand.SILICONFLOW
        domain("openrouter.ai") -> AiBrand.OPENROUTER
        domain("bigmodel.cn") || domain("z.ai") -> AiBrand.ZHIPU
        domain("minimaxi.com") || domain("minimax.io") || domain("minimax.chat") -> AiBrand.MINIMAX
        domain("generativelanguage.googleapis.com") -> AiBrand.GEMINI
        domain("deepseek.com") -> AiBrand.DEEPSEEK
        domain("anthropic.com") -> AiBrand.CLAUDE
        domain("api.openai.com") -> AiBrand.OPENAI
        "火山" in title || "方舟" in title || "volcengine" in title -> AiBrand.VOLCENGINE
        "硅基" in title || "siliconflow" in title || "siliconcloud" in title -> AiBrand.SILICONFLOW
        "ollama" in title || runCatching { java.net.URI(url).port }.getOrNull() == 11434 -> AiBrand.OLLAMA
        "openrouter" in title -> AiBrand.OPENROUTER
        "openai" in title -> AiBrand.OPENAI
        else -> modelBrand(name)
    }
    return service ?: when (adapter) {
        AiProviderAdapter.OPENAI -> AiBrand.OPENAI
        AiProviderAdapter.ANTHROPIC -> AiBrand.CLAUDE
        AiProviderAdapter.GEMINI -> AiBrand.GEMINI
        AiProviderAdapter.DEEPSEEK -> AiBrand.DEEPSEEK
        AiProviderAdapter.MINIMAX -> AiBrand.MINIMAX
        AiProviderAdapter.OPENROUTER -> AiBrand.OPENROUTER
        AiProviderAdapter.CUSTOM -> null
    }
}

internal fun AiModelType.icon(): ImageVector = when (this) {
    AiModelType.CHAT -> Icons.Outlined.ChatBubbleOutline
    AiModelType.EMBEDDING -> Icons.Outlined.DataArray
    AiModelType.RERANK -> Icons.Outlined.Sort
    AiModelType.TTS -> Icons.Outlined.GraphicEq
    AiModelType.IMAGE -> Icons.Outlined.Image
}

@Composable
internal fun AiIdentityIcon(brand: AiBrand?, fallback: ImageVector = Icons.Outlined.Hub,
    modifier: Modifier = Modifier, size: Dp = 44.dp, description: String? = null) {
    val tint = brand?.tint ?: MaterialTheme.colorScheme.primary
    Surface(modifier.size(size), shape = RoundedCornerShape(14.dp), color = tint.copy(alpha = 0.09f)) {
        Box(contentAlignment = Alignment.Center) {
            if (brand == null) Icon(fallback, description, tint = tint, modifier = Modifier.size(size * 0.56f))
            else Icon(painterResource(brand.drawable), description,
                tint = when { brand.originalColors -> Color.Unspecified; brand == AiBrand.ZHIPU -> MaterialTheme.colorScheme.onSurface; else -> tint },
                modifier = Modifier.size(size * 0.64f))
        }
    }
}

@Composable
internal fun ModelIdentityIcon(name: String, type: AiModelType, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val brand = modelBrand(name)
    AiIdentityIcon(brand, type.icon(), modifier, size, "${brand?.label ?: type.label()} 模型图标")
}

internal data class SettingChoice(
    val key: String, val title: String, val subtitle: String = "",
    val icon: ImageVector = Icons.Outlined.Tune, val brand: AiBrand? = null
)

@Composable
internal fun SettingChoiceField(title: String, value: String, selected: String, choices: List<SettingChoice>,
    onSelect: (String) -> Unit, icon: ImageVector = Icons.Outlined.Tune, brand: AiBrand? = null) {
    var expanded by remember { mutableStateOf(false) }
    Surface(onClick = { expanded = true }, color = sectionCardColor(), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, sectionHairline()), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AiIdentityIcon(brand, icon, size = 38.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (expanded) SettingChoiceDialog(title, choices, selected, { expanded = false }) {
        expanded = false
        onSelect(it)
    }
}

@Composable
internal fun SettingChoiceDialog(title: String, choices: List<SettingChoice>, selected: String,
    onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val visible = remember(choices, query) { choices.filter { (it.title + " " + it.subtitle).contains(query.trim(), true) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (choices.size > 6) OutlinedTextField(value = query, onValueChange = { query = it },
                placeholder = { Text("搜索名称或供应商") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(visible, key = SettingChoice::key) { choice ->
                    SettingChoiceRow(choice, selected == choice.key) { onSelect(choice.key) }
                }
                if (visible.isEmpty()) item { Text("没有匹配的选项", modifier = Modifier.padding(16.dp)) }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
internal fun SettingChoiceRow(choice: SettingChoice, selected: Boolean, onClick: () -> Unit) {
    Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent,
        shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AiIdentityIcon(choice.brand, choice.icon, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(choice.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (choice.subtitle.isNotBlank()) Text(choice.subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            RadioButton(selected, onClick = null)
        }
    }
}
