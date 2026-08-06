package com.mozhi.reader.ai.media

import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.media.ImageApiProvider
import com.mozhi.reader.core.media.ImageApiSettingsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

enum class ImagePromptFormat { NATURAL_LANGUAGE, NOVELAI_DANBOORU }

/**
 * 把选段或 Agent 给出的画面意图统一改写为当前生图后端真正需要的提示词方言。
 * NovelAI 使用英文 Danbooru tags；其它后端使用紧凑的英文自然语言提示词。
 */
@Singleton
class ImagePromptComposer @Inject constructor(
    private val clientFactory: AiClientFactory,
    private val imageSettings: ImageApiSettingsStore
) {
    suspend fun compose(source: String): String {
        val clean = source.trim()
        require(clean.isNotEmpty()) { "生图提示词不能为空" }
        val format = currentFormat()
        return try {
            val chat = clientFactory.forRole(ModelRole.CHAT)
            chat.client.chat(
                messages = listOf(
                    ChatMessage(ChatRole.SYSTEM, systemInstruction(format)),
                    ChatMessage(ChatRole.USER, clean)
                ),
                options = chat.options.copy(reasoning = null)
            ).trim().let { result ->
                when (format) {
                    ImagePromptFormat.NATURAL_LANGUAGE -> result.ifBlank { clean }
                    ImagePromptFormat.NOVELAI_DANBOORU -> validatedDanbooruTags(result)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // 没有分配对话模型时仍保留生图能力；已是 tags 的 Agent 提示词不会被破坏。
            if (format == ImagePromptFormat.NOVELAI_DANBOORU) validatedDanbooruTags(clean) else clean
        }
    }

    suspend fun currentFormat(): ImagePromptFormat {
        val settings = imageSettings.current()
        return if (settings.configured && settings.provider == ImageApiProvider.NOVELAI) {
            ImagePromptFormat.NOVELAI_DANBOORU
        } else {
            ImagePromptFormat.NATURAL_LANGUAGE
        }
    }

    companion object {
        internal fun systemInstruction(format: ImagePromptFormat): String = when (format) {
            ImagePromptFormat.NATURAL_LANGUAGE ->
                "You are a novel illustration prompt editor. Output one concise English image-generation prompt only. " +
                    "Preserve the supplied characters and scene, and specify composition, lighting, atmosphere and visual style. " +
                    "Do not add later plot events. No explanation, Markdown, captions, text or watermark."
            ImagePromptFormat.NOVELAI_DANBOORU ->
                "Convert the supplied novel scene into NovelAI-compatible English Danbooru tags. " +
                    "Output only comma-separated tags, ordered as quality, subject count, characters, appearance/action, setting, " +
                    "composition, lighting and style. Use underscores for multi-word Danbooru tags when appropriate. " +
                    "No Chinese, sentences, explanation, Markdown, captions, text or watermark."
        }

        internal fun normalizeDanbooruTags(value: String): String = value
            .replace("```", "")
            .lineSequence()
            .map { it.trim().removePrefix("Tags:").removePrefix("tags:").trim() }
            .filter(String::isNotBlank)
            .joinToString(", ")
            .split(',')
            .map { it.trim().trimEnd('.', ';') }
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(", ")

        private fun validatedDanbooruTags(value: String): String {
            val tags = normalizeDanbooruTags(value)
            val isTagList = ',' in tags || tags.none(Char::isWhitespace)
            require(tags.isNotBlank() && isTagList && tags.none { it in '\u3400'..'\u9FFF' }) {
                "NovelAI 需要英文 Danbooru tags；请先分配主对话模型用于转换提示词"
            }
            return tags
        }
    }
}
