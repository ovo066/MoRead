package com.mozhi.reader.feature.reader.stage

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.knowledge.BookCharactersRepository
import com.mozhi.reader.ai.knowledge.CharacterRole
import com.mozhi.reader.ai.stage.StageDirector
import com.mozhi.reader.core.stage.StageDirections
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.di.ApplicationScope
import com.mozhi.reader.core.datastore.ChineseConversionMode
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.PublisherStyleMode
import com.mozhi.reader.core.epub.dom.EpubDomChapter
import com.mozhi.reader.core.epub.style.EpubStyleResolver
import com.mozhi.reader.core.epub.style.StyledDomNode
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.datastore.ReaderImageImporter
import com.mozhi.reader.core.datastore.ReaderImagePurpose
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.datastore.StageSettings
import com.mozhi.reader.core.datastore.audiobookRevision
import com.mozhi.reader.core.datastore.chineseConversionModeFor
import com.mozhi.reader.core.library.AudiobookChapterState
import com.mozhi.reader.core.library.AudiobookRepository
import com.mozhi.reader.core.library.AudiobookRoleKind
import com.mozhi.reader.core.library.BookInlineImage
import com.mozhi.reader.core.library.BookLayoutStore
import com.mozhi.reader.core.library.BookMediaStore
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.ReaderTextAnchorCodec
import com.mozhi.reader.core.library.ReaderTextAnchors
import com.mozhi.reader.core.stage.StageAside
import com.mozhi.reader.core.stage.StageAssetMerger
import com.mozhi.reader.core.stage.StageBookImageSource
import com.mozhi.reader.core.stage.StageBookLayout
import com.mozhi.reader.core.stage.StageBookLayoutReader
import com.mozhi.reader.core.stage.StageImage
import com.mozhi.reader.core.stage.StageCompileContext
import com.mozhi.reader.core.stage.StageCompiler
import com.mozhi.reader.core.stage.StageKnownName
import com.mozhi.reader.core.stage.StageScript
import com.mozhi.reader.core.stage.StageScriptSegment
import com.mozhi.reader.core.stage.StageSource
import com.mozhi.reader.core.text.ChineseTextConverter
import com.mozhi.reader.feature.reader.ReaderLocateRequest
import com.mozhi.reader.ui.requireBookId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 一章的场记：[body] 是原文（坐标基准），[script] 的展示文字已按本书繁简设置转换。 */
data class StageChapter(
    val index: Int,
    val title: String,
    val body: String,
    val script: StageScript,
    /** 精排书内嵌字体：族名（小写）→ 本地文件路径。 */
    val fonts: Map<String, String> = emptyMap(),
    /** 有声书已经合成好的配音（修订号与当前正文一致才有）；配音跟读按拍取用。 */
    val voice: List<com.mozhi.reader.core.stage.StageVoiceClip> = emptyList()
) {
    val beats get() = script.beats

    /** 包含原文 [offset] 的那一拍；章首（offset ≤ 0）从章名卡开始。 */
    fun beatIndexAt(offset: Int): Int {
        if (offset <= 0) return 0
        val index = beats.indexOfFirst { beat -> beat.fullRange?.let { it.end > offset } == true }
        return if (index < 0) (beats.size - 1).coerceAtLeast(0) else index
    }
}

data class StageUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val bookTitle: String = "",
    val chapterCount: Int = 0,
    val chapter: StageChapter? = null,
    /** 当前拍；等于拍数时是章末卡。 */
    val index: Int = 0,
    /** 快进能到的最远一拍：本次看过的，或阅读水位以内的。 */
    val skipLimit: Int = -1,
    /** 换章或重编译时加一，界面据此重置舞台。 */
    val chapterGeneration: Int = 0
)

@HiltViewModel
class StageViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val library: LibraryRepository,
    private val settingsRepository: ReaderSettingsRepository,
    private val audiobookRepository: AudiobookRepository,
    private val characters: BookCharactersRepository,
    private val converter: ChineseTextConverter,
    private val layoutStore: BookLayoutStore,
    private val mediaStore: BookMediaStore,
    private val imageImporter: ReaderImageImporter,
    private val stageDirector: StageDirector,
    private val imageConsistency: com.mozhi.reader.core.library.ImageConsistencyRepository,
    private val illustrations: com.mozhi.reader.core.library.IllustrationRepository,
    private val annotations: com.mozhi.reader.core.library.AnnotationRepository,
    @ApplicationScope private val applicationScope: CoroutineScope
) : ViewModel() {
    val bookId: Long = savedStateHandle.requireBookId()
    private val startChapter: Int = savedStateHandle.get<Int>(ARG_CHAPTER) ?: -1
    private val startOffset: Int = savedStateHandle.get<Int>(ARG_OFFSET) ?: 0

    private val mutableState = MutableStateFlow(StageUiState())
    val state: StateFlow<StageUiState> = mutableState.asStateFlow()

    val readerSettings: StateFlow<ReaderSettings> = settingsRepository.cachedSettings
    val stageSettings: StateFlow<StageSettings> = settingsRepository.stageSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, StageSettings())

    /** 原文称呼 → 人物键（见 [StagePortraits]）；人物档案读出来之后才有。 */
    private val portraitKeys = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * 名牌头像：原文称呼 → 定妆图文件。形象按章节生效，只取演到的这一章为止定下的那一版，
     * 后面才换的装束不会提前出现。
     */
    val portraits: StateFlow<Map<String, String>> = kotlinx.coroutines.flow.combine(
        portraitKeys,
        imageConsistency.observeLooks(bookId),
        settingsRepository.cachedSettings.map { it.imageLibrary }.distinctUntilChanged(),
        mutableState.map { it.chapter?.index }.distinctUntilChanged()
    ) { keys, looks, library, chapterIndex ->
        if (chapterIndex == null || keys.isEmpty() || looks.isEmpty()) emptyMap()
        else {
            val files = keys.values.distinct().mapNotNull { key ->
                val id = com.mozhi.reader.ai.media.visibleLook(looks, key, chapterIndex)?.referenceIds?.firstOrNull()
                library.firstOrNull { it.id == id }?.let { key to it.filePath }
            }.toMap()
            keys.mapNotNull { (label, key) -> files[key]?.let { label to it } }.toMap()
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private var chapters: List<ChapterEntity> = emptyList()
    private var knownNames: List<StageKnownName> = emptyList()
    private var inlineImages: Map<Int, List<BookInlineImage>> = emptyMap()
    private val texts = LinkedHashMap<Int, String>(16, 0.75f, true)
    private val compiled = HashMap<Int, StageChapter>()
    private val compileLock = Mutex()
    private var maxReachedChapter = 0
    private var maxReachedOffset = 0
    private var progressJob: Job? = null
    private var resumedAt: Long? = null
    private val directorJobs = mutableMapOf<Int, Job>()
    private val directorAttempted = mutableSetOf<Int>()
    private val mutableDirectorBusy = MutableStateFlow(false)
    val directorBusy: StateFlow<Boolean> = mutableDirectorBusy.asStateFlow()

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            stageSettings.map { it.aiDirector }.distinctUntilChanged().collect { enabled ->
                if (enabled) mutableState.value.chapter?.let(::requestDirection)
                else {
                    directorJobs.values.toList().forEach { it.cancel() }
                    directorJobs.clear()
                    directorAttempted.clear()
                }
                updateDirectorBusy()
            }
        }
        viewModelScope.launch {
            // 跳过首个值：首章编译时直接读的就是它；之后真变了才作废缓存重编译，免得一进舞台就重置一次。
            stageSettings.map { it.narrationRhythm to it.generatedArt }.distinctUntilChanged().drop(1).collect {
                recompileNarration()
            }
        }
        viewModelScope.launch {
            mutableState.map { it.chapter?.index }.distinctUntilChanged().collect { updateDirectorBusy() }
        }
    }

    private suspend fun load() {
        val book = library.getBook(bookId)?.takeIf { it.removedAt == 0L }
        chapters = book?.let { library.getChapters(bookId) }.orEmpty()
        if (book == null || chapters.isEmpty()) {
            mutableState.update { it.copy(loading = false, failed = true) }
            return
        }
        maxReachedChapter = book.maxReachedChapterIndex
        maxReachedOffset = book.maxReachedCharOffset
        knownNames = runCatching { loadKnownNames() }.getOrDefault(emptyList())
        inlineImages = runCatching { mediaStore.read(bookId).groupBy { it.chapterIndex } }.getOrDefault(emptyMap())
        val chapterIndex = (if (startChapter >= 0) startChapter else book.lastReadChapterIndex)
            .coerceIn(0, chapters.lastIndex)
        val chapter = runCatching { compile(chapterIndex) }.getOrNull()
        if (chapter == null) {
            mutableState.update { it.copy(loading = false, failed = true) }
            return
        }
        val offset = if (startChapter >= 0) startOffset else book.lastReadCharOffset
        val index = chapter.beatIndexAt(offset)
        mutableState.update {
            it.copy(
                loading = false,
                bookTitle = book.title,
                chapterCount = chapters.size,
                chapter = chapter,
                index = index,
                skipLimit = maxOf(index, readLimit(chapter)),
                chapterGeneration = it.chapterGeneration + 1
            )
        }
        prefetch(chapterIndex + 1)
    }

    /** 人物档案与有声书选角里的名字：只用于认出原文里已经写出来的称呼，不会提前写出名字。 */
    private suspend fun loadKnownNames(): List<StageKnownName> {
        val people = characters.observe(bookId).first().saved?.guide?.characters.orEmpty()
        portraitKeys.value = com.mozhi.reader.core.stage.StagePortraits.index(
            people.map { com.mozhi.reader.core.stage.StagePortraitSubject(it.identity, it.name, it.aliases) }
        )
        val dossier = people.map { person ->
            StageKnownName(
                name = person.name,
                aliases = person.aliases,
                protagonist = person.role == CharacterRole.PROTAGONIST,
                source = StageSource.DOSSIER
            )
        }
        val roles = audiobookRepository.getRoles(bookId)
            .filter { it.kind == AudiobookRoleKind.CHARACTER.name }
            .map { role ->
                StageKnownName(
                    name = role.name,
                    aliases = role.aliases.split(',', '，', ';', '；').map(String::trim).filter(String::isNotEmpty),
                    source = StageSource.AUDIOBOOK
                )
            }
        val seen = dossier.mapTo(HashSet()) { it.name }
        return dossier + roles.filterNot { it.name in seen }
    }

    private suspend fun text(index: Int): String {
        texts[index]?.let { return it }
        val body = library.readChapterText(bookId, chapters[index])
        texts[index] = body
        while (texts.size > TEXT_CACHE) texts.remove(texts.keys.first())
        return body
    }

    private suspend fun compile(index: Int): StageChapter = compileLock.withLock {
        compiled[index]?.let {
            // 关导演时预编译的章节，之后开启并切入时也要补上导演请求。
            if (stageSettings.value.aiDirector) requestDirection(it)
            return@withLock compiled[index] ?: it
        }
        val chapter = chapters[index]
        val body = text(index)
        // 说话人词表只从本章与之前的章节里挖，不前瞻。
        val history = (maxOf(0, index - HISTORY_CHAPTERS) until index).map { text(it) }
        val settings = settingsRepository.cachedSettings.value
        val bookLayout = runCatching { readBookLayout(index, body) }.getOrNull()
        val art = if (stageSettings.value.generatedArt) runCatching { generatedArt(index, body) }.getOrDefault(emptyList()) else emptyList()
        var voice: List<com.mozhi.reader.core.stage.StageVoiceClip> = emptyList()
        val stageChapter = withContext(Dispatchers.Default) {
            var script = StageCompiler.compile(index, chapter.title, body, StageCompileContext(
                history = history,
                knownNames = knownNames,
                book = bookLayout?.first,
                narration = stageSettings.value.narrationRhythm
            ))
            script = mergeAudiobook(script, index, body, settings)
            voice = voiceClips(index, body, settings)
            if (stageSettings.value.aiDirector) {
                stageDirector.cached(bookId, index, body)?.let { script = StageDirections.merge(script, body, it) }
            }
            script = com.mozhi.reader.core.stage.StageGeneratedArt.merge(script, art)
            val mode = settings.chineseConversionModeFor(bookId)
            if (mode != ChineseConversionMode.OFF) script = convertForDisplay(script, mode)
            StageChapter(index, chapter.title, body, script, bookLayout?.second.orEmpty(), voice)
        }
        compiled[index] = stageChapter
        // 只留当前章附近几章：离得最远的先丢。
        while (compiled.size > COMPILED_CACHE) {
            val current = mutableState.value.chapter?.index
            val evict = compiled.keys.filter { it != current && it != index }.maxByOrNull { kotlin.math.abs(it - index) } ?: break
            compiled.remove(evict)
        }
        if (stageSettings.value.aiDirector) requestDirection(stageChapter)
        compiled[index] ?: stageChapter
    }

    /**
     * 本章已经生成的插图：读者在选段或工作室里画的，以及伴读随段评配的。同一处画过几版只取最新一版；
     * 段评配图跟着段评走——段评删了，或关了「显示 AI 批注」，图也不出现。
     */
    private suspend fun generatedArt(index: Int, body: String): List<com.mozhi.reader.core.stage.StageArtAnchor> {
        val images = illustrations.observeForBook(bookId).first()
            .filter { it.chapterIndex == index && (it.charOffset ?: -1) in 0..body.length }
        if (images.isEmpty()) return emptyList()
        val showAi = settingsRepository.showAiAnnotations.first()
        val attached = annotations.getForChapter(bookId, index)
            .mapNotNull { com.mozhi.reader.core.library.AnnotationMedia.decode(it.mediaJson).illustrationId }.toSet()
        return withContext(Dispatchers.IO) {
            images.filter { image ->
                val fromAnnotation = image.id in attached || "/annotations/" in image.imagePath
                (!fromAnnotation || (showAi && image.id in attached)) && java.io.File(image.imagePath).isFile
            }.groupBy { it.charOffset }.values.map { versions -> versions.maxBy { it.createdAt } }
                .sortedBy { it.charOffset }.take(GENERATED_ART_PER_CHAPTER)
                .map { com.mozhi.reader.core.stage.StageArtAnchor(it.charOffset ?: 0, StageImage(it.imagePath, it.pixelWidth, it.pixelHeight)) }
        }
    }

    /** 节奏改变后连同下一章预编译一起作废；等正在编译的任务退出锁，避免旧结果重新填回缓存。 */
    private suspend fun recompileNarration() {
        compileLock.withLock { compiled.clear() }
        val previous = mutableState.value.chapter ?: return
        val chapter = runCatching { compile(previous.index) }.getOrNull() ?: return
        mutableState.update { current ->
            val old = current.chapter
            if (old == null || old.index != chapter.index || old.body != chapter.body) current
            // 这一章没有可合的旁白：拍子没变，不必重置舞台。
            else if (old.beats == chapter.beats) current.copy(chapter = chapter)
            else {
                // 编译期间仍可翻拍，替换时再取最新位置；章名卡与章末卡分别保留。
                val offset = old.beats.getOrNull(current.index)?.fullRange?.start ?: 0
                val index = when {
                    current.index >= old.beats.size -> chapter.beats.size
                    current.index == 0 -> 0
                    // beatIndexAt(0) 为续读返回章名卡，这里正文从偏移零开始时仍须留在正文。
                    else -> chapter.beatIndexAt(offset).coerceAtLeast(1).coerceAtMost(chapter.beats.lastIndex.coerceAtLeast(0))
                }
                // 旧的快进上限按原文终点换算，不把旧拍序套在新节奏上，也不放行未读的整拍。
                val skipEnd = old.beats.getOrNull(current.skipLimit)?.fullRange?.end
                val skipLimit = when {
                    current.skipLimit >= old.beats.size -> chapter.beats.size
                    skipEnd != null -> chapter.beats.indexOfLast { beat -> beat.fullRange?.let { it.end <= skipEnd } ?: true }
                    else -> -1
                }
                current.copy(
                    chapter = chapter,
                    index = index,
                    skipLimit = maxOf(index, skipLimit, readLimit(chapter)),
                    chapterGeneration = current.chapterGeneration + 1
                )
            }
        }
        // 只保存续读位置；阅读水位仍由完整显示后的 onBeatRevealed 单调推进。
        if (mutableState.value.chapter === chapter) {
            onBeatShown()
            prefetch(chapter.index + 1)
        }
    }

    /** 请求挂在应用作用域；缓存与网络在导演内部切到后台，章缓存和舞台状态只在主线程替换。 */
    private fun requestDirection(chapter: StageChapter) {
        if (!stageSettings.value.aiDirector || chapter.index in directorJobs) return
        val cached = stageDirector.cached(bookId, chapter.index, chapter.body)
        if (cached != null) {
            applyDirection(chapter, cached)
            return
        }
        if (!directorAttempted.add(chapter.index)) return
        // 延迟启动到下一次主线程调度，保证先登记任务，再更新忙碌状态。
        val job = applicationScope.launch(Dispatchers.Main, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                if (!stageSettings.value.aiDirector) return@launch
                val result = stageDirector.direct(bookId, chapter.index, chapter.title, chapter.body, chapter.script.era) ?: return@launch
                if (stageSettings.value.aiDirector) applyDirection(chapter, result)
            } finally {
                if (directorJobs[chapter.index] === kotlinx.coroutines.currentCoroutineContext()[Job]) {
                    directorJobs.remove(chapter.index)
                }
                updateDirectorBusy()
            }
        }
        directorJobs[chapter.index] = job
        updateDirectorBusy()
        job.start()
    }

    private fun applyDirection(chapter: StageChapter, result: com.mozhi.reader.core.stage.StageDirectorResult) {
        val existing = compiled[chapter.index]?.takeIf { it.body == chapter.body } ?: return
        val current = mutableState.value
        val fromBeat = if (current.chapter?.index == chapter.index) current.index + 1 else 0
        val merged = existing.copy(script = StageDirections.merge(existing.script, existing.body, result, fromBeat))
        compiled[chapter.index] = merged
        mutableState.update {
            if (it.chapter?.index == chapter.index && it.chapter.body == chapter.body) it.copy(chapter = merged) else it
        }
    }

    private fun updateDirectorBusy() {
        mutableDirectorBusy.value = stageSettings.value.aiDirector && mutableState.value.chapter?.index in directorJobs
    }

    /**
     * 精排 EPUB 自带的结构、图片与样式：聊天框、楼层、弹幕、报纸、插图、分隔图都直接复用，
     * 书里的字体、颜色与边框也一并读出（返回的第二项是内嵌字体路径）。没有精排数据（TXT、旧导入）时只带插图。
     */
    private suspend fun readBookLayout(index: Int, body: String): Pair<StageBookLayout, Map<String, String>>? {
        val images = inlineImages[index].orEmpty()
        val sources = images.map { StageBookImageSource(it.charOffset, it.imagePath, it.pixelWidth, it.pixelHeight) }
        val bundle = layoutStore.readChapter(bookId, chapters[index].chapterIndex, body)
        val dom = bundle?.dom
        if (bundle != null && dom != null && dom.textLength == body.length) {
            return withContext(Dispatchers.Default) {
                val styled = runCatching { styledDom(bundle, dom) }.getOrNull()
                StageBookLayoutReader.read(dom.bodyNode, body, sources, styled, bundle.fontPaths.keys, { href -> bundle.resourcePaths[href] }) to bundle.fontPaths
            }
        }
        if (images.isEmpty()) return null
        return StageBookLayout(images = images.associate { it.charOffset to StageImage(it.imagePath, it.pixelWidth, it.pixelHeight, it.altText.takeIf { alt -> alt.length in 2..40 }) }) to emptyMap()
    }

    /** 与阅读页同一套级联：按本章 <link> 清单取样式表，算出每个节点的样式（只用相对值，视口尺寸不影响）。 */
    private fun styledDom(bundle: EpubLayoutChapterBundle, dom: EpubDomChapter): StyledDomNode {
        val linked = bundle.document.stylesheetHrefs
        val available = bundle.stylesheets + dom.embeddedStylesheets
        val sheets = if (linked.isEmpty()) available else linked.mapNotNull { href -> available.firstOrNull { it.href.equals(href, true) } }.ifEmpty { available }
        return EpubStyleResolver(sheets, 360f, 720f, 16f, 0xFF000000.toInt(), PublisherStyleMode.RESPECT, documentHref = bundle.document.href)
            .resolve(dom.bodyNode, dom.htmlNode)
    }

    /** 已确认、修订号与当前正文一致的有声书分镜：带来准确的说话人与情绪。 */
    private suspend fun mergeAudiobook(script: StageScript, index: Int, body: String, settings: ReaderSettings): StageScript {
        val chapterState = audiobookRepository.getChapter(bookId, index)?.state ?: return script
        if (chapterState !in USABLE_AUDIOBOOK_STATES) return script
        val segments = audiobookRepository.getSegments(bookId, index)
        val revision = audiobookRevision(body, settings.textReplacementRules)
        if (segments.isEmpty() || segments.any { it.revision != revision }) return script
        val roles = audiobookRepository.getRoles(bookId).associateBy { it.id }
        return StageAssetMerger.applyAudiobook(script, segments.map { segment ->
            val role = segment.roleId?.let(roles::get)
            StageScriptSegment(
                start = segment.startCharOffset,
                end = segment.endCharOffset,
                roleName = role?.name,
                isNarrator = role == null || role.kind == AudiobookRoleKind.NARRATOR.name,
                emotion = segment.emotion,
                instruction = segment.instruction
            )
        })
    }

    /**
     * 本章已经合成好的配音。分镜确认之后、修订号与当前正文一致的才算——正文改过、净化规则换过，
     * 旧配音念的就不是屏幕上这句话了。合成到一半的章节也能用，只是后半没有声音。
     */
    private suspend fun voiceClips(index: Int, body: String, settings: ReaderSettings): List<com.mozhi.reader.core.stage.StageVoiceClip> {
        val chapterState = audiobookRepository.getChapter(bookId, index)?.state ?: return emptyList()
        if (chapterState !in USABLE_AUDIOBOOK_STATES) return emptyList()
        val segments = audiobookRepository.getSegments(bookId, index)
        val revision = audiobookRevision(body, settings.textReplacementRules)
        if (segments.isEmpty() || segments.any { it.revision != revision }) return emptyList()
        return segments.mapNotNull { segment ->
            val path = segment.audioPath?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            com.mozhi.reader.core.stage.StageVoiceClip(segment.startCharOffset, segment.endCharOffset, path, segment.audioMillis)
        }
    }

    /** 展示文字与阅读页一致（繁简）；坐标仍是原文。转换改变长度时丢掉该拍的行内强调。 */
    private fun convertForDisplay(script: StageScript, mode: ChineseConversionMode): StageScript {
        fun convert(text: String) = runCatching { converter.convert(text, mode) }.getOrDefault(text)
        fun StageAside.converted() = copy(text = convert(text))
        return script.copy(
            title = convert(script.title),
            beats = script.beats.map { beat ->
                val text = convert(beat.text)
                beat.copy(
                    text = text,
                    spans = if (text.length == beat.text.length) beat.spans else emptyList(),
                    asideBefore = beat.asideBefore?.converted(),
                    asideAfter = beat.asideAfter?.converted(),
                    speaker = beat.speaker?.let { it.copy(label = convert(it.label)) },
                    item = beat.item?.let { item ->
                        item.copy(
                            sender = item.sender?.let(::convert),
                            author = item.author?.let(::convert),
                            label = item.label?.let(::convert)
                        )
                    },
                    systemLabel = beat.systemLabel?.let(::convert)
                )
            },
            containers = script.containers.map { it.copy(title = it.title?.let(::convert), board = it.board?.let(::convert)) },
            protagonist = script.protagonist?.let(::convert),
            names = script.names.map(::convert)
        )
    }

    private fun prefetch(index: Int) {
        if (index !in chapters.indices || compiled.containsKey(index)) return
        viewModelScope.launch { runCatching { compile(index) } }
    }

    /** 阅读水位以内的拍都算读过：长按快进最远到这里。 */
    private fun readLimit(chapter: StageChapter): Int = when {
        chapter.index < maxReachedChapter -> chapter.beats.size
        chapter.index > maxReachedChapter -> -1
        else -> chapter.beats.indexOfLast { beat -> beat.fullRange?.let { it.end <= maxReachedOffset } ?: true }
    }

    fun next() {
        val current = mutableState.value
        val chapter = current.chapter ?: return
        if (current.index < chapter.beats.size) showBeat(current.index + 1)
        else nextChapter()
    }

    fun previous() {
        val current = mutableState.value
        val chapter = current.chapter ?: return
        if (current.index > 0) showBeat(current.index - 1)
        else if (chapter.index > 0) openChapter(chapter.index - 1, fromEnd = true)
    }

    fun jumpTo(index: Int) {
        val chapter = mutableState.value.chapter ?: return
        showBeat(index.coerceIn(0, chapter.beats.size))
    }

    fun nextChapter() {
        val chapter = mutableState.value.chapter ?: return
        if (chapter.index < chapters.lastIndex) openChapter(chapter.index + 1, fromEnd = false)
    }

    val hasNextChapter: Boolean get() = (mutableState.value.chapter?.index ?: Int.MAX_VALUE) < chapters.lastIndex

    private fun openChapter(index: Int, fromEnd: Boolean) {
        viewModelScope.launch {
            val chapter = runCatching { compile(index) }.getOrNull() ?: return@launch
            val beat = if (fromEnd) (chapter.beats.size - 1).coerceAtLeast(0) else 0
            mutableState.update {
                it.copy(
                    chapter = chapter,
                    index = beat,
                    skipLimit = maxOf(beat, readLimit(chapter)),
                    chapterGeneration = it.chapterGeneration + 1
                )
            }
            onBeatShown()
            prefetch(index + 1)
        }
    }

    private fun showBeat(index: Int) {
        mutableState.update { it.copy(index = index, skipLimit = maxOf(it.skipLimit, index)) }
        onBeatShown()
        val chapter = mutableState.value.chapter ?: return
        if (index >= chapter.beats.size * PREFETCH_FRACTION) prefetch(chapter.index + 1)
    }

    /** 换拍后去抖保存续读位置（拍起点），与阅读页同轨。 */
    private fun onBeatShown() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            delay(PROGRESS_DEBOUNCE_MS)
            saveProgress()
        }
    }

    /** 一拍完整显示后才推进阅读水位（防剧透边界只算真正看到的字）。 */
    fun onBeatRevealed(index: Int) {
        val chapter = mutableState.value.chapter ?: return
        val end = chapter.beats.getOrNull(index)?.fullRange?.end ?: return
        if (chapter.index < maxReachedChapter || (chapter.index == maxReachedChapter && end <= maxReachedOffset)) return
        maxReachedChapter = chapter.index
        maxReachedOffset = end
        viewModelScope.launch { runCatching { library.markVisibleReadEnd(bookId, chapter.index, end) } }
    }

    private suspend fun saveProgress() {
        val current = mutableState.value
        val chapter = current.chapter ?: return
        val beat = chapter.beats.getOrNull(current.index.coerceAtMost(chapter.beats.size - 1)) ?: return
        val offset = beat.fullRange?.start ?: 0
        val locator = ReaderTextAnchorCodec.encode(
            ReaderTextAnchors.create(chapter.body, offset, offset, ChineseConversionMode.OFF)
        )
        runCatching { library.saveProgress(bookId, locator, chapter.index, offset) }
    }

    fun flushProgress() {
        progressJob?.cancel()
        viewModelScope.launch { saveProgress() }
    }

    fun onResumed() {
        if (resumedAt == null) resumedAt = System.currentTimeMillis()
    }

    fun onPaused() {
        val start = resumedAt ?: return
        resumedAt = null
        val now = System.currentTimeMillis()
        viewModelScope.launch { runCatching { library.recordReadingDuration(bookId, (now - start).coerceAtLeast(0), now) } }
        flushProgress()
    }

    /** 退出时把当前拍（含提示语）的原文范围交回阅读页定位并短亮。 */
    fun exitRequest(): ReaderLocateRequest? {
        val current = mutableState.value
        val chapter = current.chapter ?: return null
        val beat = chapter.beats.getOrNull(current.index.coerceAtMost(chapter.beats.size - 1)) ?: return null
        val range = beat.fullRange ?: return ReaderLocateRequest(chapter.index, 0, 0)
        val anchor = ReaderTextAnchors.create(chapter.body, range.start, range.end, ChineseConversionMode.OFF)
        return ReaderLocateRequest(chapter.index, range.start, range.end, ReaderTextAnchorCodec.encode(anchor))
    }

    /** 从系统选图导入图片库（背景图用途），并设为演出背景。 */
    fun importBackground(uri: Uri) {
        viewModelScope.launch {
            val asset = runCatching { imageImporter.importImage(uri, purpose = ReaderImagePurpose.BACKGROUND) }.getOrNull() ?: return@launch
            updateSettings { it.copy(backgroundImageId = asset.id) }
        }
    }

    fun updateSettings(transform: (StageSettings) -> StageSettings) {
        viewModelScope.launch {
            settingsRepository.setStageSettings(transform(stageSettings.value).normalized())
        }
    }

    override fun onCleared() {
        val start = resumedAt
        if (start != null) {
            val now = System.currentTimeMillis()
            // ViewModel 已结束，交给应用级作用域写完；失败只丢这几秒统计。
            applicationScope.launch {
                runCatching { library.recordReadingDuration(bookId, (now - start).coerceAtLeast(0), now) }
                saveProgress()
            }
        }
        super.onCleared()
    }

    companion object {
        const val ARG_CHAPTER = "chapter"
        const val ARG_OFFSET = "offset"
        private const val HISTORY_CHAPTERS = 6
        private const val TEXT_CACHE = 10
        private const val COMPILED_CACHE = 4
        private const val PROGRESS_DEBOUNCE_MS = 600L
        private const val PREFETCH_FRACTION = 0.7f
        /** 一章里排进演出的插图上限：再多就成了翻画册，盖过原文。 */
        private const val GENERATED_ART_PER_CHAPTER = 12
        private val USABLE_AUDIOBOOK_STATES = setOf(
            AudiobookChapterState.CONFIRMED.name,
            AudiobookChapterState.SYNTHESIZING.name,
            AudiobookChapterState.READY.name
        )
    }
}
