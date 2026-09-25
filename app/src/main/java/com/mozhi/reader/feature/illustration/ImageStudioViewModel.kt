package com.mozhi.reader.feature.illustration

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.knowledge.BookCharacter
import com.mozhi.reader.ai.media.*
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.core.library.*
import com.mozhi.reader.core.i18n.UiText
import com.mozhi.reader.R
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class StudioPage { GALLERY, STYLE, STYLE_EDITOR, PEOPLE, LOOK, GENERATE, QUEUE }
data class StudioEntry(val page: StudioPage = StudioPage.GALLERY, val characterKey: String? = null,
    val source: String = "", val chapterIndex: Int? = null, val charOffset: Int? = null,
    val anchor: String = "", val illustration: IllustrationEntity? = null, val imagePath: String? = null)

data class StudioState(
    val bookId: Long = 0, val title: String = "", val progress: Int = 0,
    val page: StudioPage = StudioPage.GALLERY, val returnPage: StudioPage = StudioPage.GALLERY,
    val loading: Boolean = true, val busy: Boolean = false, val error: UiText? = null,
    val capabilities: ImageCapabilities = ImageCapabilities(), val backend: String = "", val configured: Boolean = false,
    val people: List<BookCharacter> = emptyList(), val looks: List<LookSpec> = emptyList(),
    val style: StyleSpec = StyleSpec(), val styleDraft: StyleSpec = StyleSpec(), val lookDraft: LookSpec? = null,
    val styleTemplates: List<ImageStyleTemplateEntity> = emptyList(),
    val styleEditorDraft: StyleSpec = StyleSpec(presetId = "custom", natural = "", tags = ""),
    val styleEditorName: String = "", val styleEditorTemplateId: String? = null,
    val styleEditorReturn: StudioPage = StudioPage.STYLE, val stylePreviewOrigin: StudioPage? = null,
    /** 从画廊、形象页等打开的生成面板，关闭后回到这里，而不是退出整个插图工作室。 */
    val panelReturn: StudioPage? = null,
    val assets: List<ReaderImageAsset> = emptyList(), val gallery: List<IllustrationEntity> = emptyList(),
    val personas: List<PersonaEntity> = emptyList(),
    val source: String = "", val chapterIndex: Int = 0, val charOffset: Int? = null, val anchor: String = "",
    val selectedCast: List<String> = emptyList(), val useReferences: Boolean = false,
    val count: Int = 1, val sizeIndex: Int = 0, val recipe: ImageRecipe? = null,
    val candidates: List<IllustrationEntity> = emptyList(), val selectedCandidate: Int = 0,
    val generatedCount: Int = 0, val portrait: Boolean = false, val promptOverride: String = "",
    val queue: List<IllustrationQueueEntity> = emptyList(), val queuePreview: List<IllustrationQueueEntity> = emptyList(),
    val queueRunning: Boolean = false
) {
    val currentLooks: List<LookSpec> get() = selectedCast.mapNotNull { key ->
        visibleLook(looks, key, chapterIndex) ?: people.firstOrNull { it.identity == key }?.let {
            LookSpec("text-$key", key, it.name)
        }
    }
    val referencePlan get() = planReferences(capabilities, currentLooks, recipe?.style ?: style, useReferences)
    val result get() = candidates.getOrNull(selectedCandidate)
    val duplicateStyleName get() = styleTemplates.any { it.id != styleEditorTemplateId && it.name.equals(styleEditorName.trim(), ignoreCase = true) }
    val hasStyleEditorContent get() = styleEditorDraft.natural.isNotBlank() || styleEditorDraft.tags.isNotBlank() || styleEditorDraft.referenceIds.isNotEmpty()
    val canSaveStyleEditor get() = styleEditorName.isNotBlank() && !duplicateStyleName && hasStyleEditorContent && !busy && !loading
    /** 生成面板是浮在页面上的弹层；这里是它下面应当继续显示的页面。 */
    val basePage: StudioPage get() = if (page == StudioPage.GENERATE) stylePreviewOrigin ?: panelReturn ?: StudioPage.GENERATE else page
}

@HiltViewModel
class ImageStudioViewModel @Inject constructor(
    private val repository: ImageConsistencyRepository,
    private val library: LibraryRepository,
    private val illustrations: IllustrationRepository,
    private val importer: ReaderImageImporter,
    private val settings: ReaderSettingsRepository,
    private val clients: AiClientFactory,
    private val media: AiMediaGenerationService,
    private val queueService: IllustrationQueue,
    private val database: MoReadDatabase
) : ViewModel() {
    private val mutable = MutableStateFlow(StudioState())
    val state: StateFlow<StudioState> = mutable.asStateFlow()
    private var binding: Job? = null
    private var operation: Job? = null
    private var lastEntry: StudioEntry? = null

    fun enter(bookId: Long, entry: StudioEntry) {
        if (state.value.bookId == bookId && !state.value.loading && (lastEntry == entry || state.value.busy)) return
        lastEntry = entry
        binding?.cancel()
        operation?.cancel()
        mutable.value = StudioState(bookId = bookId, page = entry.page, source = entry.source,
            charOffset = entry.charOffset, anchor = entry.anchor,
            useReferences = entry.page != StudioPage.GENERATE, count = if (entry.page == StudioPage.GENERATE) 1 else 2)
        binding = viewModelScope.launch {
            try {
                val context = repository.bookContext(bookId, entry.chapterIndex)
                val resolved = try { clients.imageGeneration() } catch (_: Exception) { null }
                val personas = database.personaDao().getPersonas()
                mutable.update { it.copy(title = context.book.title, people = context.people, looks = context.looks,
                    style = context.style, styleDraft = context.style, progress = context.book.maxReachedChapterIndex,
                    chapterIndex = context.chapterIndex, loading = false, backend = resolved?.label.orEmpty(),
                    configured = resolved != null, capabilities = resolved?.client?.capabilities ?: ImageCapabilities(),
                    selectedCast = context.people.filter { p -> (listOf(p.name, p.identity) + p.aliases).any { it in entry.source } }.map { it.identity },
                    personas = personas) }
                entry.characterKey?.let(::openLook)
                entry.illustration?.let(::openResult)
                entry.imagePath?.let { path ->
                    val saved = database.illustrationDao().getForBook(bookId).firstOrNull { it.imagePath == path }
                    val sidecar = withContext(Dispatchers.IO) {
                        val file = File("$path.recipe.json")
                        if (file.isFile) runCatching { ImageRecipeCodec.decode(file.readText()) }.getOrNull() else null
                    }
                    openResult(saved ?: IllustrationEntity(bookId = bookId, imagePath = path,
                        prompt = sidecar?.shot?.text().orEmpty(), recipeJson = sidecar?.encode().orEmpty(), createdAt = 0))
                }
                launch { combine(repository.observeStyle(bookId), repository.observeLooks(bookId),
                    illustrations.observeForBook(bookId), settings.settings) { style, looks, gallery, preferences ->
                        mutable.update { it.copy(style = style, looks = looks, gallery = gallery, assets = preferences.imageLibrary) }
                    }.collect() }
                launch { combine(repository.dao.observeQueue(bookId), queueService.running) { queue, running ->
                    mutable.update { it.copy(queue = queue, queueRunning = bookId in running) }
                }.collect() }
                launch { repository.dao.observeTemplates().collect { templates -> mutable.update { it.copy(styleTemplates = templates) } } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(loading = false, error = error.message?.let(UiText::raw)) } }
        }
    }

    fun page(page: StudioPage) { mutable.update { it.copy(page = page, error = null) } }
    fun back() { page(when (state.value.page) { StudioPage.LOOK -> StudioPage.PEOPLE
        StudioPage.STYLE -> state.value.returnPage
        StudioPage.STYLE_EDITOR -> state.value.styleEditorReturn
        else -> StudioPage.GALLERY }) }
    fun openStyle() { mutable.update { it.copy(returnPage = it.page, page = StudioPage.STYLE, styleDraft = it.style) } }
    fun style(value: StyleSpec) { mutable.update { it.copy(styleDraft = value) } }
    fun newStyle() { mutable.update { it.copy(page = StudioPage.STYLE_EDITOR, styleEditorReturn = it.page,
        styleEditorDraft = StyleSpec(presetId = "custom", natural = "", tags = ""),
        styleEditorName = "", styleEditorTemplateId = null, error = null) } }
    fun copyStyle() { mutable.update { it.copy(page = StudioPage.STYLE_EDITOR, styleEditorReturn = it.page,
        styleEditorDraft = it.styleDraft.copy(presetId = "custom"), styleEditorName = "", styleEditorTemplateId = null, error = null) } }
    fun editStyle(template: ImageStyleTemplateEntity) = work {
        val draft = ImageRecipeCodec.json.decodeFromString<StyleSpec>(template.specJson)
        mutable.update { it.copy(page = StudioPage.STYLE_EDITOR, styleEditorReturn = it.page,
            styleEditorDraft = draft.copy(presetId = "custom"), styleEditorName = template.name, styleEditorTemplateId = template.id) }
    }
    fun styleEditor(value: StyleSpec) { mutable.update { it.copy(styleEditorDraft = value.copy(presetId = "custom")) } }
    fun styleEditorName(value: String) { mutable.update { it.copy(styleEditorName = value.take(80)) } }
    fun saveStyleEditor() {
        if (!state.value.canSaveStyleEditor) return
        work {
            val s = state.value
            repository.saveTemplate(s.styleEditorName.trim(), s.styleEditorDraft, s.styleEditorTemplateId ?: UUID.randomUUID().toString())
            mutable.update { it.copy(styleDraft = s.styleEditorDraft, page = StudioPage.STYLE) }
        }
    }
    fun applyTemplate(template: ImageStyleTemplateEntity) = work {
        val style = ImageRecipeCodec.json.decodeFromString<StyleSpec>(template.specJson)
        mutable.update { it.copy(styleDraft = style, page = StudioPage.STYLE) }
    }
    fun deleteTemplate(id: String) = work { repository.dao.deleteTemplate(id) }
    fun saveStyle() = work {
        repository.saveStyle(state.value.bookId, state.value.styleDraft)
        mutable.update { it.copy(style = it.styleDraft, page = it.returnPage, recipe = null) }
    }
    fun openLook(key: String) {
        val s = state.value
        val person = s.people.firstOrNull { it.identity == key } ?: return
        val look = visibleLook(s.looks, key, s.progress) ?: repository.initialLook(person, s.progress)
        mutable.update { it.copy(page = StudioPage.LOOK, lookDraft = look, error = null) }
    }
    fun look(value: LookSpec) { mutable.update { it.copy(lookDraft = value) } }
    fun newLookVersion(sinceChapter: Int = state.value.progress) { state.value.lookDraft?.let {
        look(it.copy(id = UUID.randomUUID().toString(), sinceChapter = sinceChapter.coerceIn(0, state.value.progress)))
    } }
    fun saveLook() = work {
        val s = state.value
        val look = s.lookDraft ?: return@work
        repository.saveLook(s.bookId, look)
        val updated = repository.looks(s.bookId)
        mutable.update { it.copy(looks = updated, lookDraft = visibleLook(updated, look.characterKey, it.progress), recipe = null) }
        if (state.value.lookDraft == null) openLook(look.characterKey)
    }
    fun importReference(uri: Uri, style: Boolean) = work {
        val s = state.value
        val styleDraft = if (s.page == StudioPage.STYLE_EDITOR) s.styleEditorDraft else s.styleDraft
        val ids = if (style) styleDraft.referenceIds else s.lookDraft?.referenceIds ?: return@work
        if (ids.size >= 3) { mutable.update { it.copy(error = UiText.of(R.string.image_studio_reference_full)) }; return@work }
        val asset = importer.importImage(uri, purpose = if (style) ReaderImagePurpose.STYLE_REF else ReaderImagePurpose.CHARACTER_REF, ownerBookId = s.bookId)
        if (style && s.page == StudioPage.STYLE_EDITOR) styleEditor(styleDraft.copy(referenceIds = ids + asset.id))
        else if (style) this.style(styleDraft.copy(referenceIds = ids + asset.id))
        else look(s.lookDraft!!.copy(referenceIds = ids + asset.id))
    }
    fun cast(key: String) { mutable.update { it.copy(selectedCast = if (key in it.selectedCast) it.selectedCast - key else it.selectedCast + key, recipe = null) } }
    fun source(value: String) { mutable.update { it.copy(source = value.take(12_000), recipe = null) } }
    fun count(value: Int) { mutable.update { it.copy(count = value.coerceIn(1, 4)) } }
    fun size(index: Int) { mutable.update { it.copy(sizeIndex = index, recipe = null) } }
    fun references(value: Boolean) { mutable.update { it.copy(useReferences = value, recipe = null) } }
    fun prompt(value: String) { mutable.update { it.copy(promptOverride = value.take(16_000)) } }
    fun select(index: Int) { mutable.update { it.copy(selectedCandidate = index) } }
    fun newGeneration() { mutable.update { it.copy(page = StudioPage.GENERATE, panelReturn = it.page.takeIf { page -> page != StudioPage.GENERATE }, source = "", recipe = null,
        candidates = emptyList(), portrait = false, useReferences = true, promptOverride = "", selectedCast = emptyList(), count = 2, stylePreviewOrigin = null) } }

    fun portrait() = work {
        val s = state.value
        val draft = s.lookDraft ?: return@work
        val look = if (s.capabilities.tags && !s.capabilities.multilingual) repository.translateLook(draft) else draft
        repository.saveLook(s.bookId, look)
        val style = if (s.capabilities.tags && !s.capabilities.multilingual && s.style.tags.isBlank()) s.style.copy(tags =
            repository.translateLook(LookSpec("style", "style", "", natural = s.style.natural), "visual style").tags) else s.style
        if (style != s.style) repository.saveStyle(s.bookId, style)
        val recipe = ImageRecipe(style = style, cast = listOf(look), backend = s.backend,
            size = if (s.capabilities.tags) "832x1216" else "1024x1536",
            shot = ShotSpec(listOf(look.characterKey), "standing, neutral pose", "plain background",
                "multiple views, turnaround, reference sheet, full body, face close-up in corner, no text"),
            seed = if (s.capabilities.seed) randomImageSeed() else null,
            references = planReferences(s.capabilities, listOf(look), style).references)
        mutable.update { it.copy(page = StudioPage.GENERATE, panelReturn = StudioPage.LOOK, portrait = true, selectedCast = listOf(look.characterKey),
            chapterIndex = maxOf(s.chapterIndex, look.sinceChapter),
            source = recipe.shot.text(), recipe = recipe, count = 4, candidates = emptyList(), promptOverride = "") }
    }

    fun extractAppearance() = work {
        val s = state.value
        val draft = s.lookDraft ?: return@work
        val extracted = repository.extractLook(s.bookId, draft)
        mutable.update { it.copy(lookDraft = extracted) }
    }
    fun translateAppearance() = work {
        val draft = state.value.lookDraft ?: return@work
        val translated = repository.translateLook(draft.copy(tags = ""))
        mutable.update { it.copy(lookDraft = translated) }
    }

    fun refreshService() = work {
        val resolved = clients.imageGeneration()
        mutable.update { it.copy(configured = true, capabilities = resolved.client.capabilities, backend = resolved.label, recipe = null) }
    }

    fun preparePrompt() = work {
        val recipe = buildRecipe()
        mutable.update { it.copy(recipe = recipe) }
    }

    fun sample() = work {
        val s = state.value
        val previewStyle = if (s.page == StudioPage.STYLE_EDITOR) s.styleEditorDraft else s.styleDraft
        val book = library.getBook(s.bookId) ?: return@work
        val chapter = library.getChapter(s.bookId, s.chapterIndex) ?: return@work
        val source = com.mozhi.reader.core.retrieval.ReadingScope.uptoProgress(book)
            .readableText(chapter.chapterIndex, library.readChapterText(s.bookId, chapter)).take(2_000)
        val recipe = repository.plan(s.bookId, s.chapterIndex, source, useReferences = true, styleOverride = previewStyle)
        mutable.update { it.copy(source = source, page = StudioPage.GENERATE, count = 1, stylePreviewOrigin = s.page,
            candidates = emptyList(), recipe = recipe, portrait = false, promptOverride = "", useReferences = true,
            selectedCast = recipe.cast.map { look -> look.characterKey }) }
    }
    fun leavePanel() { mutable.update { it.copy(page = it.panelReturn ?: StudioPage.GALLERY, panelReturn = null,
        candidates = emptyList(), recipe = null, portrait = false, promptOverride = "") } }
    fun leaveStylePreview() { mutable.update { it.copy(page = it.stylePreviewOrigin ?: StudioPage.STYLE,
        stylePreviewOrigin = null, candidates = emptyList(), recipe = null) } }

    private suspend fun buildRecipe(): ImageRecipe {
        val s = state.value
        val size = if (s.capabilities.tags) listOf("832x1216", "1024x1024", "1216x832")[s.sizeIndex]
            else listOf("1024x1536", "1024x1024", "1536x1024")[s.sizeIndex]
        val previewStyle = when (s.stylePreviewOrigin) { StudioPage.STYLE_EDITOR -> s.styleEditorDraft; StudioPage.STYLE -> s.styleDraft; else -> null }
        return (s.recipe ?: repository.plan(s.bookId, s.chapterIndex, s.source, s.selectedCast, s.useReferences, size, previewStyle))
            .copy(promptOverride = s.promptOverride)
    }
    fun generate(reroll: Boolean = false) = work {
        val start = state.value
        var recipe = buildRecipe()
        if (reroll && start.capabilities.seed) recipe = recipe.copy(seed = randomImageSeed())
        mutable.update { it.copy(recipe = recipe, candidates = emptyList(), generatedCount = 0) }
        repeat(start.count) { index ->
            val variant = recipe.copy(seed = recipe.seed?.let { (it + index) and 0xffffffffL })
            val result = media.generateIllustration(start.bookId, start.chapterIndex, start.charOffset, start.source,
                variant.shot.text().ifBlank { start.source }, null, start.anchor, persist = false, recipe = variant)
            mutable.update { it.copy(candidates = it.candidates + result, selectedCandidate = 0, generatedCount = index + 1) }
        }
    }

    fun saveResult() = work {
        val s = state.value
        val result = s.result ?: return@work
        if (s.portrait) {
            val character = ImageRecipeCodec.decode(result.recipeJson)?.cast?.firstOrNull() ?: return@work
            val pending = importer.prepareFile(File(result.imagePath))
            val asset = importer.confirm(pending, character.name, purpose = ReaderImagePurpose.CHARACTER_REF, ownerBookId = s.bookId)
            repository.saveLook(s.bookId, character.copy(referenceIds = listOf(asset.id) + character.referenceIds.take(2)))
            val updated = repository.looks(s.bookId)
            mutable.update { it.copy(page = StudioPage.LOOK, panelReturn = null, portrait = false, looks = updated,
                lookDraft = visibleLook(updated, character.characterKey, s.progress)) }
        } else if (result.id == 0L) {
            val saved = illustrations.insert(result)
            mutable.update { it.copy(candidates = it.candidates.map { item -> if (item.imagePath == saved.imagePath) saved else item }) }
        }
    }
    fun openResult(result: IllustrationEntity) {
        val recipe = ImageRecipeCodec.decode(result.recipeJson)
        mutable.update { it.copy(page = StudioPage.GENERATE, panelReturn = it.page.takeIf { page -> page != StudioPage.GENERATE },
            candidates = listOf(result), selectedCandidate = 0,
            recipe = recipe, source = result.sourceText.ifBlank { result.prompt }, chapterIndex = result.chapterIndex ?: it.progress,
            charOffset = result.charOffset, anchor = result.textAnchorJson, selectedCast = recipe?.cast?.map { c -> c.characterKey }.orEmpty(),
            portrait = false, count = 1, useReferences = recipe?.references?.isNotEmpty() == true,
            sizeIndex = when (recipe?.size) { "1024x1024" -> 1; "1536x1024", "1216x832" -> 2; else -> 0 },
            promptOverride = recipe?.promptOverride.orEmpty()) }
    }
    fun adjust() { mutable.update { it.copy(candidates = emptyList()) } }
    fun useResultAsStyle() = work {
        val s = state.value
        val result = s.result ?: return@work
        if (s.style.referenceIds.size >= 3) { mutable.update { it.copy(error = UiText.of(R.string.image_studio_reference_full)) }; return@work }
        val asset = importer.confirm(importer.prepareFile(File(result.imagePath)), s.title, purpose = ReaderImagePurpose.STYLE_REF, ownerBookId = s.bookId)
        repository.saveStyle(s.bookId, s.style.copy(referenceIds = s.style.referenceIds + asset.id))
    }
    fun useResultAsLook(key: String) = work {
        val s = state.value
        val person = s.people.firstOrNull { it.identity == key } ?: return@work
        val result = s.result ?: return@work
        val look = visibleLook(s.looks, key, s.chapterIndex) ?: repository.initialLook(person, s.chapterIndex)
        val asset = importer.confirm(importer.prepareFile(File(result.imagePath)), person.name, purpose = ReaderImagePurpose.CHARACTER_REF, ownerBookId = s.bookId)
        repository.saveLook(s.bookId, look.copy(referenceIds = listOf(asset.id) + look.referenceIds.take(2)))
    }
    fun useAsCover(path: String) = work {
        val asset = importer.confirm(importer.prepareFile(File(path)), state.value.title, purpose = ReaderImagePurpose.COVER)
        library.replaceBookCover(state.value.bookId, asset.filePath)
    }
    fun useAsAvatar(path: String, personaId: Long) = work {
        val asset = importer.confirm(importer.prepareFile(File(path)), state.value.title)
        val persona = database.personaDao().getPersona(personaId) ?: return@work
        database.personaDao().update(persona.copy(avatarPath = asset.filePath))
    }
    fun delete(result: IllustrationEntity) = work {
        illustrations.delete(result)
        mutable.update { it.copy(candidates = it.candidates.filterNot { item -> item.id == result.id && item.imagePath == result.imagePath },
            selectedCandidate = 0) }
    }

    fun planQueue(first: Int, last: Int) = work {
        val rows = queueService.prepare(state.value.bookId, first, last)
        mutable.update { it.copy(queuePreview = rows) }
    }
    fun removeQueuePreview(id: String) { mutable.update { it.copy(queuePreview = it.queuePreview.filterNot { row -> row.id == id }) } }
    fun updateQueueRecipe(id: String, recipe: ImageRecipe) { mutable.update { it.copy(queuePreview = it.queuePreview.map { row ->
        if (row.id == id) row.copy(recipeJson = recipe.encode()) else row }) } }
    fun startQueue() = work {
        val s = state.value
        if (s.queuePreview.isNotEmpty()) queueService.enqueue(s.queuePreview)
        mutable.update { it.copy(queuePreview = emptyList()) }
        queueService.start(s.bookId)
    }
    fun pauseQueue() = queueService.pause(state.value.bookId)
    fun retry(row: IllustrationQueueEntity) = work { queueService.retry(row) }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun cancel() { operation?.cancel(); mutable.update { it.copy(busy = false) } }

    private fun work(block: suspend () -> Unit) {
        if (state.value.busy) return
        operation = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(error = error.message?.let(UiText::raw) ?: UiText.of(R.string.image_studio_operation_failed)) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
