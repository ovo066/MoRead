package com.mozhi.reader.core.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import android.media.ExifInterface
import android.graphics.RectF
import com.mozhi.reader.ai.client.*
import com.mozhi.reader.ai.knowledge.*
import com.mozhi.reader.ai.media.*
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.retrieval.ReadingScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable

data class ImageBookContext(val book: BookEntity, val people: List<BookCharacter>, val looks: List<LookSpec>,
    val style: StyleSpec, val chapterIndex: Int)

@Serializable private data class AppearanceDraft(val natural: String = "", val tags: String = "", val attributes: List<LookAttribute> = emptyList())

@Singleton
class ImageConsistencyRepository @Inject constructor(
    private val database: MoReadDatabase,
    private val library: LibraryRepository,
    private val characters: BookCharactersRepository,
    private val settings: ReaderSettingsRepository,
    private val clients: AiClientFactory,
    @ApplicationContext private val context: Context
) {
    val dao get() = database.imageConsistencyDao()
    private val vibeMutex = Mutex()
    private val json = ImageRecipeCodec.json

    fun observeStyle(bookId: Long) = dao.observeStyle(bookId).map { decodeStyle(it) }
    fun observeLooks(bookId: Long) = dao.observeLooks(bookId).map { rows -> rows.mapNotNull(::decodeLook) }
    suspend fun style(bookId: Long) = decodeStyle(dao.style(bookId))
    suspend fun looks(bookId: Long) = dao.looks(bookId).mapNotNull(::decodeLook)
    suspend fun validateRecipe(bookId: Long, recipe: ImageRecipe, chapterIndex: Int?) {
        val book = library.getBook(bookId) ?: error("书籍不存在")
        val boundary = minOf(chapterIndex ?: book.maxReachedChapterIndex, book.maxReachedChapterIndex)
        require(chapterIndex == null || chapterIndex <= book.maxReachedChapterIndex) { "当前已读范围不包含这张插图，请重新选择已读场景" }
        require(recipe.cast.all { it.sinceChapter <= boundary }) { "配方含后续章节的人物形象，请按当前进度重新调整" }
    }
    suspend fun saveStyle(bookId: Long, style: StyleSpec) {
        validateStyle(style)
        dao.saveStyle(BookImageStyleEntity(bookId, json.encodeToString(style)))
    }
    private fun validateStyle(style: StyleSpec) {
        require(style.referenceIds.size <= 3 && style.natural.length <= 8_000 && style.tags.length <= 8_000)
        require(style.seed == null || style.seed in 0..0xffffffffL)
    }
    suspend fun saveTemplate(name: String, style: StyleSpec, id: String? = null) {
        validateStyle(style)
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 80)
        require(dao.observeTemplates().first().none { it.id != id && it.name.equals(clean, ignoreCase = true) }) { "模板名称已存在" }
        dao.saveTemplate(ImageStyleTemplateEntity(id ?: UUID.randomUUID().toString(), clean,
            json.encodeToString(style), System.currentTimeMillis()))
    }
    suspend fun renameTemplate(id: String, name: String) {
        val template = dao.template(id) ?: return
        saveTemplate(name, json.decodeFromString<StyleSpec>(template.specJson), id)
    }
    suspend fun saveLook(bookId: Long, look: LookSpec) {
        require(look.sinceChapter >= 0 && look.characterKey.isNotBlank() && look.referenceIds.size <= 3)
        require(look.natural.length <= 12_000 && look.tags.length <= 12_000)
        val existing = dao.looks(bookId).firstOrNull { it.characterKey == look.characterKey && it.sinceChapter == look.sinceChapter }
        val stable = look.copy(id = existing?.id ?: look.id)
        dao.saveLook(CharacterLookEntity(stable.id, bookId, stable.characterKey, stable.sinceChapter, json.encodeToString(stable)))
    }
    private fun decodeStyle(row: BookImageStyleEntity?) = row?.let {
        runCatching { json.decodeFromString<StyleSpec>(it.specJson) }.getOrNull()
    } ?: StyleSpec()
    private fun decodeLook(row: CharacterLookEntity) = runCatching { json.decodeFromString<LookSpec>(row.specJson) }.getOrNull()

    suspend fun bookContext(bookId: Long, chapterIndex: Int? = null): ImageBookContext {
        val book = library.getBook(bookId) ?: error("书籍不存在")
        val chapter = (chapterIndex ?: book.maxReachedChapterIndex).coerceIn(0, book.maxReachedChapterIndex.coerceAtLeast(0))
        val scope = ReadingScope.uptoProgress(book).intersect(ReadingScope.upto(chapter, Int.MAX_VALUE))
        val people = characters.observe(bookId).first().saved?.guide?.characters.orEmpty().mapNotNull { person ->
            val evidence = person.evidence.filter { scope.allowsPosition(it.chapterIndex, it.fact.end) }
            val attributes = person.attributes.filter { scope.allowsPosition(it.evidence.chapterIndex, it.evidence.fact.end) }
            if (evidence.isEmpty() && attributes.isEmpty() && person.manualDescription == null) null
            else person.copy(evidence = evidence, attributes = attributes, relationships = emptyList())
        }
        return ImageBookContext(book, people, looks(bookId), style(bookId), chapter)
    }

    fun initialLook(person: BookCharacter, chapterIndex: Int): LookSpec {
        val attributes = person.attributes.filter { it.kind == CharacterAttributeKind.APPEARANCE && it.evidence.chapterIndex <= chapterIndex }
            .map { LookAttribute("", it.value, it.evidence.chapterIndex, it.evidence.fact.start, it.evidence.fact.end, it.evidence.fact.quote) }
        return LookSpec(UUID.randomUUID().toString(), person.identity, person.name, sinceChapter = attributes.maxOfOrNull { it.chapterIndex ?: 0 } ?: 0,
            natural = attributes.joinToString("; ") { it.value }, attributes = attributes,
            source = if (attributes.isEmpty()) "manual" else "text")
    }

    suspend fun translateLook(look: LookSpec, subject: String = "appearance"): LookSpec {
        if (look.tags.isNotBlank() || look.natural.isBlank()) return look
        if (look.natural.none { it in '\u3400'..'\u9FFF' }) return look.copy(tags = look.natural)
        for (role in listOf(ModelRole.CHEAP, ModelRole.CHAT)) {
            try {
                val model = clients.forRole(role)
                val result = model.client.chat(listOf(ChatMessage(ChatRole.SYSTEM,
                    "Translate this fixed $subject description into concise comma-separated English tags. Do not add or omit details. Output tags only."),
                    ChatMessage(ChatRole.USER, look.natural)), model.options.copy(reasoning = null))
                val tags = ImagePromptComposer.normalizeDanbooruTags(result)
                require(tags.isNotBlank() && tags.none { it in '\u3400'..'\u9FFF' })
                return look.copy(tags = tags)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
        error("无法生成英文外貌标签，请配置对话模型或手动填写 NovelAI 标签")
    }

    /** Explicit user action. Every extracted attribute must quote a readable source passage. */
    suspend fun extractLook(bookId: Long, current: LookSpec): LookSpec {
        val book = library.getBook(bookId)?.takeIf { it.removedAt == 0L } ?: error("书籍正文已移除")
        val scope = ReadingScope.uptoProgress(book)
        val passages = linkedMapOf<Int, String>()
        var remaining = 20_000
        for (chapter in library.getChapters(bookId).sortedBy { it.chapterIndex }) {
            if (!scope.allowsChapter(chapter.chapterIndex) || remaining <= 0) break
            val text = scope.readableText(chapter.chapterIndex, library.readChapterText(bookId, chapter))
            if (current.name !in text && current.characterKey !in text) continue
            // Bounded, literal paragraphs containing the name; no unread text reaches the model.
            val picked = text.lineSequence().filter { current.name in it || current.characterKey in it }.joinToString("\n").take(remaining)
            if (picked.isNotBlank()) { passages[chapter.chapterIndex] = picked; remaining -= picked.length }
        }
        require(passages.isNotEmpty()) { "已读内容中没有找到这个人物的原文，请手动填写外貌" }
        val model = try { clients.forRole(ModelRole.CHEAP) } catch (_: Exception) { clients.forRole(ModelRole.CHAT) }
        val response = model.client.chat(listOf(ChatMessage(ChatRole.SYSTEM,
            "Extract ONLY explicit physical appearance of the named character. Return JSON {natural: concise description, tags: English comma-separated appearance tags, attributes:[{label,value,chapterIndex,quote}]}. " +
                "Each quote must be an exact verbatim substring of its supplied chapter. Do not infer ethnicity, age, clothing or colors. Omit unknown facts. Chapter indices are zero based."),
            ChatMessage(ChatRole.USER, "Character: ${current.name}\n${json.encodeToString(passages)}")), model.options.copy(reasoning = null))
        val clean = response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val draft = json.decodeFromString<AppearanceDraft>(clean)
        val attributes = draft.attributes.map { attribute ->
            val index = attribute.chapterIndex ?: error("外貌依据缺少章节")
            require(attribute.quote.isNotBlank() && attribute.quote in passages[index].orEmpty()) { "外貌依据未通过原文核对" }
            val chapter = library.getChapter(bookId, index) ?: error("原文章节已变化")
            val text = scope.readableText(index, library.readChapterText(bookId, chapter))
            val start = text.indexOf(attribute.quote)
            require(start >= 0)
            attribute.copy(start = start, end = start + attribute.quote.length)
        }
        require(attributes.isNotEmpty()) { "已读内容中没有明确外貌，请手动填写或上传定妆图" }
        // Existing manual fields win; extracted evidence stays separately inspectable.
        val keepManual = current.source == "manual" && current.natural.isNotBlank()
        return current.copy(natural = if (keepManual) current.natural else attributes.joinToString("; ") { it.value },
            sinceChapter = maxOf(current.sinceChapter, attributes.maxOf { it.chapterIndex ?: 0 }),
            tags = if (keepManual) current.tags else draft.tags, attributes = attributes,
            source = if (keepManual) "manual" else "text")
    }

    /** Planner never sees saved looks or future character attributes. Only a shot is editable by the LLM. */
    suspend fun plan(bookId: Long, chapterIndex: Int?, source: String, castKeys: List<String>? = null,
        useReferences: Boolean = false, size: String? = null, styleOverride: StyleSpec? = null): ImageRecipe {
        val book = bookContext(bookId, chapterIndex)
        val client = clients.imageGeneration()
        val allowed = book.people.map { it.identity }.toSet()
        val fallbackCast = book.people.filter { person -> (listOf(person.name, person.identity) + person.aliases).any { it in source } }.map { it.identity }
        var shot = ShotSpec(cast = fallbackCast, action = source.take(8_000))
        for (role in listOf(ModelRole.CHEAP, ModelRole.CHAT)) {
            try {
                val chat = clients.forRole(role)
                val names = book.people.map { mapOf("id" to it.identity, "name" to it.name, "aliases" to it.aliases.joinToString(", ")) }
                val instruction = "Plan one novel illustration. Return only JSON {cast:[known character ids], action, setting, composition, lighting, mood}. " +
                    "Only describe actions, location, camera, light and mood. NEVER describe hair, eyes, face, body, age, clothing or visual style; fixed appearance cards are added by code. " +
                    "Do not infer future plot or invent character ids. " + if (client.client.capabilities.tags) "Use concise English comma-separated tags in each scene field." else "Use concise English scene fields."
                val response = chat.client.chat(listOf(ChatMessage(ChatRole.SYSTEM, instruction),
                    ChatMessage(ChatRole.USER, "Known cast: ${json.encodeToString(names)}\nScene: ${source.take(12_000)}")), chat.options.copy(reasoning = null))
                shot = ShotParser.parse(response, allowed)
                break
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep original scene if both chat routes are unavailable. */ }
        }
        val selected = (castKeys ?: shot.cast).filter { it in allowed }.distinct()
        val cast = selected.mapNotNull { key ->
            val existing = visibleLook(book.looks, key, book.chapterIndex)
            var look = existing ?: book.people.firstOrNull { it.identity == key }?.let { initialLook(it, book.chapterIndex) }
            if (look != null && client.client.capabilities.tags && !client.client.capabilities.multilingual) look = translateLook(look)
            if (look != null && look != existing && (look.natural.isNotBlank() || look.tags.isNotBlank())) saveLook(bookId, look)
            look
        }
        var style = styleOverride ?: book.style
        if (client.client.capabilities.tags && !client.client.capabilities.multilingual && style.tags.isBlank() && style.natural.isNotBlank()) {
            style = style.copy(tags = translateLook(LookSpec("style", "style", "", natural = style.natural), "visual style").tags)
            if (styleOverride == null) saveStyle(bookId, style)
        }
        val refs = planReferences(client.client.capabilities, cast, style, useReferences)
        return ImageRecipe(style = style, cast = cast, shot = shot.copy(cast = selected), references = refs.references,
            seed = if (client.client.capabilities.seed) style.seed ?: randomImageSeed() else null,
            size = size ?: client.client.defaultImageSize, backend = client.label)
    }

    suspend fun request(recipe: ImageRecipe, client: ImageGenerationClient): ImageRequest {
        val caps = client.capabilities
        require(recipe.cast.size <= caps.maxCharacters) { "当前模型支持的出场人物数量不足，请减少出场人物" }
        val assembled = RecipeAssembler.assemble(recipe, caps)
        if (caps.tags && !caps.multilingual) require((listOf(assembled.prompt) + assembled.characterPrompts).none { text -> text.any { it in '\u3400'..'\u9FFF' } }) {
            "当前 NovelAI 模型需要英文标签，请分配对话模型用于转换，或手动填写英文镜头与外貌标签"
        }
        val assets = settings.settings.first().imageLibrary.associateBy { it.id }
        val refs = recipe.references.map { ref ->
            val asset = assets[ref.assetId] ?: error("参考图已移除，请重新选择参考图")
            val bytes = withContext(Dispatchers.IO) { normalizedReference(File(asset.filePath), caps.tags && ref.kind == ReferenceKind.CHARACTER) }
            if (client is NovelAiImageClient && ref.kind == ReferenceKind.STYLE) {
                val cacheKey = MessageDigest.getInstance("SHA-256").digest(bytes + "${recipe.backend}:${ref.informationExtracted}".toByteArray()).joinToString("") { "%02x".format(it) }
                val encoded = vibeMutex.withLock {
                    withContext(Dispatchers.IO) {
                        val cache = File(context.filesDir, "image-vibes/$cacheKey.bin")
                        if (cache.isFile && cache.length() in 1..30L * 1024 * 1024) cache.readBytes() else {
                            val data = client.encodeVibe(bytes, ref.informationExtracted)
                            cache.parentFile?.mkdirs()
                            val temporary = File(cache.parentFile, "$cacheKey.tmp")
                            temporary.writeBytes(data)
                            check(temporary.renameTo(cache)) { "Vibe 缓存保存失败" }
                            data
                        }
                    }
                }
                ImageReferenceInput(ref, encoded, encodedVibe = true)
            } else ImageReferenceInput(ref, bytes)
        }
        return ImageRequest(assembled.prompt, assembled.characterPrompts, assembled.negative, refs, recipe.seed, recipe.size)
    }

    /** Hashes and encodings only; never caches API keys or authentication headers. */
    private fun normalizedReference(file: File, precise: Boolean): ByteArray {
        require(file.isFile && file.length() in 1..40L * 1024 * 1024) { "参考图不存在或过大" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        var source = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("无法读取参考图")
        val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply { when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
        } }
        if (!matrix.isIdentity) {
            val oriented = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            if (oriented !== source) source.recycle()
            source = oriented
        }
        val ratio = source.width.toFloat() / source.height
        val (w, h) = if (precise) {
            if (ratio < .85f) 1024 to 1536 else if (ratio > 1.18f) 1536 to 1024 else 1472 to 1472
        } else {
            val scale = minOf(1f, 1536f / maxOf(source.width, source.height))
            (source.width * scale).toInt().coerceAtLeast(1) to (source.height * scale).toInt().coerceAtLeast(1)
        }
        val target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.drawColor(if (precise) Color.BLACK else Color.WHITE)
        val fit = minOf(w.toFloat() / source.width, h.toFloat() / source.height)
        val left = (w - source.width * fit) / 2
        val top = (h - source.height * fit) / 2
        canvas.drawBitmap(source, null, RectF(left, top, w - left, h - top), Paint(Paint.FILTER_BITMAP_FLAG))
        source.recycle()
        return try { ByteArrayOutputStream().use { target.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
        finally { target.recycle() }
    }
}

fun randomImageSeed(): Long = kotlin.random.Random.nextLong(0, 0x100000000L)
