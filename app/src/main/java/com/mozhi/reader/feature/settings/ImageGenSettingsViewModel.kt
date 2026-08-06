package com.mozhi.reader.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.client.AiClientException
import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.core.media.ImageApiProvider
import com.mozhi.reader.core.media.ImageApiSettings
import com.mozhi.reader.core.media.ImageApiSettingsStore
import com.mozhi.reader.core.media.defaultBaseUrl
import com.mozhi.reader.core.media.defaultModel
import com.mozhi.reader.core.security.ApiKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImageGenSettingsUiState(
    val settings: ImageApiSettings = ImageApiSettings(),
    val hasApiKey: Boolean = false,
    val isTesting: Boolean = false,
    val testImagePath: String? = null,
    val message: String? = null
)

@HiltViewModel
class ImageGenSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ImageApiSettingsStore,
    private val apiKeyStore: ApiKeyStore,
    private val clientFactory: AiClientFactory
) : ViewModel() {

    private val hasKey = MutableStateFlow(
        !apiKeyStore.get(ImageApiSettingsStore.API_KEY_ALIAS).isNullOrBlank()
    )
    private val testing = MutableStateFlow(false)
    private val testImage = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null)

    val uiState = combine(
        store.settings,
        hasKey,
        testing,
        testImage,
        message
    ) { settings, key, isTesting, imagePath, msg ->
        ImageGenSettingsUiState(settings, key, isTesting, imagePath, msg)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ImageGenSettingsUiState()
    )

    fun setProvider(provider: ImageApiProvider) = update {
        it.copy(
            provider = provider,
            baseUrl = provider.defaultBaseUrl(),
            model = provider.defaultModel(),
            size = ""
        )
    }

    fun setBaseUrl(value: String) = update { it.copy(baseUrl = value.trim()) }
    fun setModel(value: String) = update { it.copy(model = value.trim()) }
    fun setSize(value: String) = update { it.copy(size = value.trim()) }
    fun setPositivePrompt(value: String) = update { it.copy(positivePrompt = value) }
    fun setNegativePrompt(value: String) = update { it.copy(negativePrompt = value) }
    fun setSampler(value: String) = update { it.copy(sampler = value.trim()) }
    fun setSteps(value: Int) = update { it.copy(steps = value.coerceIn(1, 50)) }
    fun setScale(value: Float) = update { it.copy(scale = value.coerceIn(0f, 10f)) }

    fun saveApiKey(raw: String) {
        val key = raw.trim()
        if (key.isEmpty()) return
        apiKeyStore.put(ImageApiSettingsStore.API_KEY_ALIAS, key)
        hasKey.value = true
        message.value = "API Key 已保存"
    }

    fun clearApiKey() {
        apiKeyStore.remove(ImageApiSettingsStore.API_KEY_ALIAS)
        hasKey.value = false
        message.value = "已删除 API Key"
    }

    /** 测试当前生效的生图出口（独立配置优先，未配置则回落模型分配）。 */
    fun testGenerate() {
        if (testing.value) return
        viewModelScope.launch {
            testing.value = true
            message.value = null
            try {
                val resolved = clientFactory.imageGeneration()
                val current = uiState.value.settings
                val prompt = if (current.configured && current.provider == ImageApiProvider.NOVELAI) {
                    TEST_NOVELAI_PROMPT
                } else {
                    TEST_PROMPT
                }
                val generated = resolved.client.generateImages(prompt).first()
                val bytes = resolved.client.materializeImage(generated)
                val path = withContext(Dispatchers.IO) {
                    val directory = File(context.cacheDir, "image-api-test").apply { mkdirs() }
                    directory.listFiles()?.forEach(File::delete)
                    val output = File(directory, "test-${System.currentTimeMillis()}.png")
                    output.writeBytes(bytes)
                    output.absolutePath
                }
                testImage.value = path
                message.value = "测试成功：${resolved.label}"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                message.value = when (error) {
                    is AiClientException -> error.message ?: "生成失败"
                    is IllegalArgumentException -> error.message ?: "生成失败"
                    else -> "生成失败:${error.message ?: error.javaClass.simpleName}"
                }
            } finally {
                testing.value = false
            }
        }
    }

    private fun update(transform: (ImageApiSettings) -> ImageApiSettings) {
        viewModelScope.launch { store.update(transform) }
    }

    private companion object {
        const val TEST_PROMPT = "安静的书斋一角，暖色台灯下摊开的线装书，水墨插画风格，无文字"
        const val TEST_NOVELAI_PROMPT =
            "masterpiece, best quality, absurdres, 1girl, reading, book, indoors, study, warm_lighting, detailed_background"
    }
}
