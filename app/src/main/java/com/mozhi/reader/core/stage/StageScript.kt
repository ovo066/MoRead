package com.mozhi.reader.core.stage

/**
 * 演出模式的「场记」：把一章原文切成一拍一拍，并标出每拍的镜头、说话人、版式与效果。
 *
 * 屏幕上的字永远逐字来自原文；这里只记录元数据。所有原文坐标都是章内 UTF-16 下标，
 * 与阅读进度、批注、听书同轨，不能和 text.mz 的字节坐标混用。
 */
data class SourceRange(val start: Int, val end: Int) {
    init {
        require(start in 0..end) { "invalid range [$start, $end)" }
    }

    val length: Int get() = end - start
    operator fun contains(offset: Int): Boolean = offset in start until end
}

/** 拍的镜头类型。 */
enum class BeatKind {
    /** 章名卡（没有原文区间，取章节标题）。 */
    TITLE,
    /** 时空字幕：「三日后。」「东区，某某街。」 */
    CAPTION,
    NARRATION,
    DIALOGUE,
    /** 心声：整段括号，或「心想 / 暗道」引出的引号。 */
    THOUGHT,
    /** 拟声：「咚。咚。咚。」「轰——！」 */
    SFX,
    /** 系统提示：整行括号且含系统词。 */
    SYSTEM,
    /** 场景分隔符：黑场转场，自动通过。 */
    BREAK,
    /** 内联图片占位符。 */
    IMAGE,
    /** 脚注号、短括注、表情占位这类小字注。 */
    NOTE,
    CHAT,
    FORUM_HEAD,
    FORUM,
    LETTER,
    STATUS,
    VERSE,
    /** 弹幕：一组飘过屏幕的短评。 */
    DANMAKU,
    /** 报纸、社交动态、文件清单、笔记摘录这类卡片里的一行。 */
    CARD
}

enum class StageContainerType {
    CHAT, FORUM, LETTER, STATUS, VERSE, DANMAKU,
    /** 报纸 / 公告：报头 + 大字标题。 */
    NEWS,
    /** 微博、朋友圈这类社交动态：账号 + 正文 + 时间。 */
    SOCIAL,
    /** 文件、档案、清单。 */
    DOCUMENT,
    /** 日记、笔记、引文摘录。 */
    NOTEBOOK
}

/** 卡片里一行的角色。 */
enum class CardLineRole { MASTHEAD, HEADLINE, ACCOUNT, BODY, META, ITEM }

/** 对白的外观：普通引号 / 方括号传音 / 系统音。 */
enum class DialogueVoice { QUOTE, BRACKET, SYSTEM }

enum class BeatEnter { TYPE, FADE, RISE, SLAM, WHISPER, GLITCH, INSTANT }

enum class BeatPace { SLOW, NORMAL, FAST }

enum class ScreenEffect { SHAKE, FLASH, DIM, HEARTBEAT, VIGNETTE, BLACKOUT }

/** 行内强调词表（封闭）。 */
enum class SpanStyle { STRIKE, STRESS, NAME, TERM, WHISPER, SHOUT, SHAKE, WAVE, GLOW, GLITCH, COLD, WARM, SLOW, SPACED }

/** 每个字段由哪一层给出；回看与调试用，也决定合并优先级（USER > DIRECTOR > AUDIOBOOK > DOSSIER > RULE）。 */
enum class StageSource { RULE, DOSSIER, AUDIOBOOK, DIRECTOR, USER }

enum class LetterLineRole { SALUTATION, BODY, VERSE, SIGNATURE }

/** 与有声书分镜同一套七种情绪。 */
enum class StageEmotion(val label: String) {
    HAPPY("开心"), SAD("悲伤"), ANGRY("愤怒"), FEAR("恐惧"), DISGUST("厌恶"), SURPRISE("惊讶"), NEUTRAL("中性");

    companion object {
        fun fromLabel(label: String?): StageEmotion? = entries.firstOrNull { it.label == label?.trim() }
    }
}

/** 效果强度三档。 */
enum class StageIntensity { RESTRAINED, STANDARD, LAVISH }

/** 挂在对白上的提示语（「林晚低声道：」「她低声念出这个名字。」）；就是原文，不丢字。 */
data class StageAside(val text: String, val range: SourceRange)

data class StageSpeaker(
    /** 此处原文对说话人的称呼；不提前写出后文才揭晓的名字。 */
    val label: String,
    val confidence: Float,
    val source: StageSource,
    /** 「？？？」：原文此时还没有称呼。 */
    val unknown: Boolean = false,
    val isProtagonist: Boolean = false
)

/** 拍内展示文字 [StageBeat.text] 上的区间与风格；start/end 是该文字里的 UTF-16 下标。 */
data class StageSpan(
    val start: Int,
    val end: Int,
    val styles: Set<SpanStyle>,
    /** NAME 风格对应的人名，界面据此取角色色。 */
    val nameKey: String? = null,
    val source: StageSource = StageSource.RULE,
    /** 精排书在这段字上定的样子（字体、颜色、粗斜）。 */
    val ink: StageInk? = null
)

/** 版式容器里的一项。只放原文本来就写着的东西。 */
data class StageContainerItem(
    // 手机消息
    val sender: String? = null,
    val isMe: Boolean = false,
    val sticker: Boolean = false,
    // 论坛
    val floor: String? = null,
    val author: String? = null,
    val time: String? = null,
    /** 同一层楼的下一段。 */
    val continuation: Boolean = false,
    // 属性面板
    val label: String? = null,
    // 书信
    val letterRole: LetterLineRole? = null,
    // 卡片（报纸、动态、文件、笔记）
    val cardRole: CardLineRole? = null,
    /** 书里自带的头像（聊天）；路径同阅读页插图。 */
    val avatar: StageImage? = null,
    /** 语音消息：原文写的秒数等。 */
    val voice: Boolean = false,
    /** 精排书里这条气泡（或这一行）自己的框：底色、边框、圆角。 */
    val bubble: StageBox? = null
)

/** 书里自带的图片资源：路径与阅读页插图同一套（文件路径或 epub-zip: 归档引用）。 */
data class StageImage(
    val path: String,
    val width: Int = 0,
    val height: Int = 0,
    /** 原文图注（插图下方的说明文字），没有为 null。 */
    val caption: String? = null
)

data class StageBeat(
    val kind: BeatKind,
    /** 展示文字：逐字来自原文，只去掉了被换成容器的分隔符和内联图片占位符。 */
    val text: String,
    /** 本拍主体的原文区间；章名卡为 null。 */
    val range: SourceRange?,
    val asideBefore: StageAside? = null,
    val asideAfter: StageAside? = null,
    val speaker: StageSpeaker? = null,
    val voice: DialogueVoice? = null,
    val emotion: StageEmotion? = null,
    /** 情绪强度 0–3。 */
    val intensity: Int = 0,
    /** 语气短语，沿用有声书：轻声、低声、高声、急促、缓慢、颤抖、哽咽、句末短停。 */
    val instruction: String? = null,
    val enter: BeatEnter? = null,
    val pace: BeatPace? = null,
    val screen: ScreenEffect? = null,
    val spans: List<StageSpan> = emptyList(),
    val containerId: Int? = null,
    val item: StageContainerItem? = null,
    /** 系统提示紧跟版式容器时盖在该容器上（如「帖子已被删除」）。 */
    val overlayOn: Int? = null,
    /** 容器内旁白：夹在楼层或消息之间的叙述，容器留在屏幕上。 */
    val embedded: Boolean = false,
    /** 长对白拆出的非末段；界面在角上提示未完。 */
    val continued: Boolean = false,
    /** 系统面板标题（原文【】里的短标题，如「系统提示」）。 */
    val systemLabel: String? = null,
    /** 插图拍：书里的图片；为 null 时界面跳过。 */
    val image: StageImage? = null,
    /** 版式特写候选：海报、阶梯、叠句（是否提成特写由界面按强度档决定）。 */
    val figure: StageFigure? = null,
    /** 阶梯与叠句：同一组的第一拍序号，同组的拍在同一个特写镜头里累积。 */
    val figureGroup: Int? = null,
    /** 精排书给这拍文字定的样子（整拍的字体、颜色）。 */
    val ink: StageInk? = null,
    /** 精排书里包着这拍的框（系统框、信纸框、作者的话……）。 */
    val box: StageBox? = null,
    /** 每个字段由哪一层给出，键是字段名（speaker / emotion / instruction / enter / pace / screen / container）。 */
    val sources: Map<String, StageSource> = emptyMap()
) {
    /** 整拍覆盖的原文范围（含提示语），定位、短亮和阅读水位用。 */
    val fullRange: SourceRange?
        get() {
            val main = range ?: return null
            val start = minOf(main.start, asideBefore?.range?.start ?: main.start)
            val end = maxOf(main.end, asideAfter?.range?.end ?: main.end)
            return SourceRange(start, end)
        }
}

data class StageContainer(
    val id: Int,
    val type: StageContainerType,
    /** 手机消息：会话对象；论坛：帖子标题。都取自原文。 */
    val title: String? = null,
    /** 论坛版块名。 */
    val board: String? = null,
    /** 来自 EPUB 精排结构（而不是本地规则猜出来的）。 */
    val fromBook: Boolean = false,
    /** 精排书里容器自己的框。 */
    val box: StageBox? = null
)

data class StageScript(
    val chapterIndex: Int,
    val title: String,
    val beats: List<StageBeat>,
    val containers: List<StageContainer>,
    /** 主角（手机消息里的「我」）；来自人物档案或本章出现最多的已知人名。 */
    val protagonist: String?,
    /** 说话人词表（长的在前），界面据此给人名上色。 */
    val names: List<String>,
    /** 书里每章开头的题图（精排书的章首图），章名卡用它做底纹。 */
    val titleArt: StageImage? = null,
    /** 书里的分隔线图片，场景分隔拍用它代替默认黑场。 */
    val breakArt: StageImage? = null,
    /** 原文写到的天气与气氛，按拍序分段（不前瞻）。 */
    val scenes: List<StageScene> = emptyList(),
    /** 精排书的章名样式。 */
    val titleInk: StageInk? = null,
    /** 整本书的年代感（古风 / 现代）；认不出为 null。 */
    val era: StageEra? = null
)

/** 已知人物：来自人物档案或有声书选角，优先于本地挖掘的名字。 */
data class StageKnownName(
    val name: String,
    val aliases: List<String> = emptyList(),
    val protagonist: Boolean = false,
    val source: StageSource = StageSource.DOSSIER
)

/**
 * 编译一章所需的上下文。[history] 只能是读者已经读过的章节正文（防剧透：不前瞻），
 * 用来挖掘说话人词表；[knownNames] 来自人物档案或有声书。
 */
data class StageCompileContext(
    val history: List<String> = emptyList(),
    val knownNames: List<StageKnownName> = emptyList(),
    /** EPUB 精排书自带的结构（聊天框、楼层、弹幕、报纸……）与图片；TXT 为 null。 */
    val book: StageBookLayout? = null
)

/**
 * 从 EPUB 精排结构读出的演出线索，坐标都是章内 UTF-16 原文下标。
 * 书自己标出来的结构优先于本地规则：区间内的行直接进对应容器。
 */
data class StageBookLayout(
    val regions: List<StageBookRegion> = emptyList(),
    /** 原文「［图片］」占位所在偏移 → 图片。 */
    val images: Map<Int, StageImage> = emptyMap(),
    /** 不该成拍的装饰图（章首题图、分隔图、头像、图标）所在偏移。 */
    val decorativeImages: Set<Int> = emptySet(),
    /** 分隔线图片所在偏移（演出为场景分隔）。 */
    val separatorImages: Set<Int> = emptySet(),
    val titleArt: StageImage? = null,
    val breakArt: StageImage? = null,
    /** 书里标出的行内样式（删除线等）。 */
    val spans: List<StageBookSpan> = emptyList(),
    /** 被行内图片（脚注号、表情）拆成几行的段落：演出时合回一段。 */
    val joinRanges: List<SourceRange> = emptyList(),
    /** 脚注正文：演出为小字注，不当旁白。 */
    val noteRanges: List<SourceRange> = emptyList(),
    /** 精排书的字体、颜色与框；没有样式数据时为空。 */
    val styles: StageBookStyles = StageBookStyles()
)

data class StageBookRegion(
    val type: StageContainerType,
    val start: Int,
    val end: Int,
    /** 容器标题：会话对象、帖子标题、报头。 */
    val title: String? = null,
    val items: List<StageBookItem> = emptyList(),
    /** 区域根元素自己的框。 */
    val box: StageBox? = null
)

/** 区域里的一项；[start, end) 是它的原文区间（一项可以跨多行）。 */
data class StageBookItem(
    val start: Int,
    val end: Int,
    val item: StageContainerItem = StageContainerItem()
)

data class StageBookSpan(val start: Int, val end: Int, val style: SpanStyle)

/** 有声书分镜里的一段（已确认、修订号与当前正文一致时才传入）。 */
data class StageScriptSegment(
    val start: Int,
    val end: Int,
    val roleName: String?,
    val isNarrator: Boolean,
    val emotion: String?,
    val instruction: String?
)

/** 情绪与语气换算成的视觉倾向（纯数据，界面据此出效果）。 */
data class StageExpression(
    val enter: BeatEnter? = null,
    val screen: ScreenEffect? = null,
    val paceMultiplier: Float = 1f,
    val softer: Boolean = false,
    val louder: Boolean = false,
    val bolder: Boolean = false,
    val tremble: Boolean = false,
    val pop: Boolean = false,
    val subdued: Boolean = false,
    val warm: Boolean = false
)

/** 一拍在当前强度档下最终用到的效果（已执行硬限）。 */
data class StagePresentation(
    val enter: BeatEnter?,
    val screen: ScreenEffect?,
    val expression: StageExpression,
    val paceMultiplier: Float,
    /** 实际生效的行内强调：人名与专有名词不限，其余最多两处。 */
    val spans: List<StageSpan>,
    /** 因限频或克制档被拦下的屏幕效果（回看与调试用）。 */
    val blockedScreen: ScreenEffect? = null
)
