# 墨知 MoRead 代码地图（CODE_MAP）

> 给 AI 编码代理/新会话的速查文档：**先读这里，不要再全库扫一遍**。与实现出现出入时以代码为准，并回来修订本文件。构建方式见 [README.md](../README.md)。维护者的私有工作笔记（CLAUDE.md / DEVELOPMENT_PLAN.md / DECISIONS.md）不随开源仓库发布，公开克隆中没有这些文件属正常。
>
> 更新时间：2026-08-20（0.11.1，schema v19）。

## 0. 一图总览

```
单模块 app/（包名 com.mozhi.reader），单 Activity + Compose Navigation + Hilt + Room + DataStore
分层：ui/（壳与主题）  feature/*（页面，互相禁止 import）  ai/*（LLM/Agent/媒体）  core/*（数据与平台设施）
依赖方向：feature → ai → core；feature → core；ui → feature。core 不得反向 import ai/feature。
```

- 导航图与四主页 + 全部路由：[ui/MoReadApp.kt](app/src/main/java/com/mozhi/reader/ui/MoReadApp.kt)（在原有 bookshelf / stats / companion / settings / reader / book / import / provider 等路由外，新增 shelf-groups / shelf-tags / tts-voices / listen-player/{bookId} / audiobook-roles/{bookId} / audiobook-script/{bookId}/{chapter} / audiobook-production/{bookId}）
- 入口：MainActivity（ACTION_VIEW/SEND 外部导入书籍）、MoReadApplication（WorkManager 初始化、正文补齐扫描）

## 1. core/ —— 数据与平台设施

### core/database（Room，schema v19，导出在 app/schemas/）
- `MoReadDatabase` / `DatabaseMigrations`（1→19 全链）/ `DatabaseConverters` / `PersonaSeeds`（内置双角色模板，迁移与新建各插一次）/ `ShelfTagBackfill`（旧逗号标签规范化与稳定配色）
- entity/`LibraryEntities.kt`：books、chapters（正文坐标 = text.mz 字节区间 + UTF-16 charCount）、bookmarks、reading_daily、ai_providers、ai_models（能力属模型：CHAT/EMBEDDING/TTS/IMAGE + endpointPath/extraJson）、conversations（rollingSummary/summarizedThroughMessageId 前情提要水位）、messages（attachmentsJson、maskId）、model_assignments（ModelRole：CHAT/CHEAP/SUGGESTION/EMBEDDING/TTS/IMAGE → modelId）
- entity/`ShelfEntities.kt`：shelf_groups、shelf_tags、book_tag_refs；entity/`TtsVoiceEntity.kt`：可搜索/筛选/排序的 AI 音色库；entity/`AudiobookEntities.kt`：audiobook_roles、audiobook_chapters、audiobook_segments（UTF-16 坐标、角色/引擎、生成状态与音频元数据）
- entity/`CompanionEntities.kt`：personas（ST 卡字段/世界书/userProfile 画像/memoryEnabled/chatAppearanceJson）、`PersonaChatAppearance`+Codec（气泡形状/背景图/字体/字号）、annotations（(chapterIndex,startCharOffset,endCharOffset) 定位）、notes（kind：NOTE/PLOT_SUMMARY）、illustrations
- dao/：BookDao、AiProviderDao、PersonaDao、ChatDao、AnnotationDao、NoteDao、IllustrationDao、ShelfOrganizationDao、TtsVoiceDao、AudiobookDao

### core/library（书籍数据仓库层）
- `LibraryRepository`：书/章 CRUD、进度（saveProgress/updateReadPosition）、书签、阅读时长；`readChapterText(bookId, chapter)` 是唯一正文读取口
- `BookTextStore`/`BookTextWriter`：每书一个 `text.mz` 纯文本 blob（UTF-8 字节区间在 chapters 行上）；`LegacyLocatorConverter` 老 Locator→新坐标
- `BookCoverStore`（封面）、`BookMediaStore`（EPUB 内联图 sidecar）、`AttachmentStore`（聊天附件，长边 1568/JPEG q85）、`AnnotationRepository`、`NoteRepository`、`NoteExporter`（Markdown 导出/分享）、`IllustrationRepository`、`ShelfOrganizationRepository`、`TtsVoiceRepository`、`AudiobookRepository`
- `core/importer/BookImportGateway`：外部 Uri → 导入会话的唯一入口（`prepare` 走预览，`importDirectly` 供批量用）；`BatchImportScheduler`（接口在 core，实现在 feature/importer，供书架调用）；`FolderScanner`（DocumentsContract 递归扫描，限深 8/上限 500）
- `core/importer/lan`：**局域网传书**。`LanBookServer`（手写最小 HTTP，端口 1122 起）、`HttpRequestParser`、`MultipartReader`（流式，边界跨缓冲安全）、`LanUploadNaming`（路径穿越/控制字符/保留名清洗）、`NetworkAddresses`、`LanTransferService`（dataSync 前台服务）；页面资源在 `assets/lan/`

### core/vector（ObjectBox，免插件 Java 实体路线）
- Java 门面三件套 `VectorDb`/`VectorQueries` + 实体 `BookChunk`/`MemoryEntry`（含 maskId；1024 维 HNSW 余弦；Kotlin 不得直接触生成类）；`searchMemories` 有 bookId/maskId 过滤重载，另有 listMemories/removeMemory/removeMemoriesForPersona 供记忆管理页
- `ChapterChunker`（480/640 段→句→硬切）、`Embeddings`（短维补零/超长 MRL 截断）

### 其余 core
- `core/datastore`：`ReaderSettingsRepository`（阅读设置、扩展排版、语法高亮、主题、字体库、图片库、`companionMemorySettings` 记忆三开关）、`CompanionMemorySettings`、`UserMaskStore`（用户面具）、`GlobalPromptPresetStore`、`ReaderFontImporter`（SFNT 名称识别 + 外部打开导入）、`ReaderImageImporter`（背景/封面共享资产）、`CustomReaderTheme`（三色自定义主题 JSON codec）
- `core/speech`：`SpeechCacheStore`（filesDir/speech-cache，全局容量预算 LRU）、`SpeechCacheSync`（复用备份 WebDAV 账号双向补齐）+`SpeechCacheSyncWorker`（仅不计费网络）、`TtsSettingsStore`（引擎模式 + 系统/AI 参数 + 独立 TTS API + 系统音量补偿）、`SystemTtsSpeaker`（系统 TTS 门面：speak 一次性 / speakBatch 听书批量含 utterance 回调）、`SentenceSegmenter`/`ParagraphSegmenter`、`SleepTimerPlanner`、`TtsVoiceRepository`
- `core/media`：`ImageApiSettingsStore`（独立生图 API：provider/baseUrl/model/size/negative/sampler/steps/scale，Key 别名 `standalone-image-api`）
- `core/security/ApiKeyStore`（EncryptedSharedPreferences，全部 API Key 唯一存放处）
- `core/di`：NetworkModule（OkHttp）、StorageModule（DataStore<Preferences> 单例）、VectorModule（BoxStore）、CoroutineModule（`@ApplicationScope`，供「离开界面也要跑完」的生成用）
- `core/backup`：`BackupArchiveManager`（Room+DataStore+用户文件打包；**CURRENT_DATABASE_VERSION 必须与库版本同步**）、`WebDavClient`、`BackupSettingsStore`、`WebDavBackupWorker`
- `core/update`：应用内更新检查；`core/diag`：API 调用日志
- `core/library/BookQuoteLocator`：引文逐字/归一化定位（add_annotation 与聊天「跳到原文」共用）
- `core/readium/ReadiumServices`：EPUB streamer 装配（仅导入期用）

## 2. ai/ —— LLM 客户端、Agent、媒体

### ai/client（四方言自实现，全部走 OkHttp/SSE）
- `ChatApiClient`（接口 + AiJson + normalizeBase）；实现：`OpenAiCompatClient`、`OpenAiResponsesClient`、`ClaudeClient`（缓存 TTL 5m/1h）、`GeminiClient`；`AiModels`（ChatMessage/parts 多模态、ChatOptions、ReasoningEffort）、`AiError`（AiClientException 族 + http/transport 映射）、`RequestOverrides`（extraJson 的 headers/body 透传 + provider/model 浅合并）
- `AiClientFactory`：**所有客户端的唯一出厂口**。`forRole(ModelRole)` 聊天/向量；`mediaForRole(TTS|IMAGE)`；`imageGeneration()` 生图统一出口。独立 TTS/生图配置存在时用 id=-1 假实体合成客户端优先生效，否则回落 model_assignments
- 媒体：`OpenAiMediaClient`（/images/generations、/audio/speech、MiniMax /t2a_v2 hex、**chat/completions 出图**）、`ChatImageExtractor`（chat 响应挖图：images[]/markdown/data URI/data[]）、`NovelAiImageClient`（zip 解包 + v4_prompt，sampler/steps/scale 可配）、`ImageGenerationClient` 接口
- `ai/provider`：`AiProviderRepository`（Provider/模型/分配 CRUD + apiKeyFor）、`ProviderProtocolPolicy`（能力×适配器→协议路由）、`ModelCatalog`（目录拉取，OpenRouter 三端点聚合）、`ProviderConnectionTester`

### ai/agent
- `AgentLoop`：≤8 轮工具循环，流式，消息落库；`runDetached` 支持指定 `ModelRole` 供无会话批处理复用；组装历史时注入会话前情提要（不落库）；`AgentTool`/`ToolSpec`；`MemoryScope`（记忆开关×面具→工具可见范围）
- `ReaderToolset`：按书组装工具——get_reading_progress、search_book（向量+词法降级+懒索引）、read_book_section（严格截到已读）、recall_memory、add_annotation（quote 逐字定位）、write_note、save_plot_summary、generate_image、synthesize_speech；enabledTools 为 Persona 白名单

### 其余 ai
- `ai/prompt`：`CompanionContextBuilder`（每轮重建系统提示词：人设+**用户画像**+面具+进度+场景+记忆；防剧透；引用格式指引）、`GlobalPromptInjector`、`SelectionPrompts`
- `ai/chat`：`AiChatRepository`（会话/消息持久化、分支/编辑/重生成）、`ReplySuggestionService`（SUGGESTION→CHEAP→CHAT 回落 + Parser）、`CompanionGenerationTracker`（在跑的会话及其 Job，退出界面后回来仍能看到等待态并停止它）
- `ai/embedding`：`BookEmbeddingPipeline`/`ChapterEmbedder`（≤32/批断点续跑）/`BookEmbeddingScheduler`（单书懒索引 uniqueWork）/`EmbeddingProgressTracker`
- `ai/memory`：`MemoryConsolidator` + Worker（30 条/收尾 10 条触发，CHEAP 总结→写入前检索去重 ADD/UPDATE/DELETE→向量；顺带整段改写用户画像）、`RollingSummarizer`+`RollingSummaryPlanner`（前情提要）、`MemoryOperationParser`/`UserProfileParser`、`PersonaMemoryRepository`（记忆管理页读写）
- `ai/persona`：`PersonaRepository`（save 保留库里的 userProfile——画像归记忆系统所有）、`SillyTavernCardParser`（PNG/JSON 卡）、`PersonaAvatarStore`
- `ai/search`：`WebSearchService`（web_search / web_scrape 工具的实现）
- `ai/media/AiMediaGenerationService`：插图落盘（illustrations 表）+ 语音缓存（SHA-256 键，filesDir/speech-cache，容量预算淘汰）；语音请求可携带 emotion/instruction，MiniMax 映射 `voice_setting.emotion`，OpenAI 映射 `instructions`
- `ai/audiobook`：`DialogueRuleSegmenter`（规则对白与 UTF-16 坐标）、`AudiobookRoleExtractor`（规则优先 + CHEAP 可选精排）、`AudiobookScriptAgent`/`AudiobookScriptParser`、`VoiceAssignmentParser`、`AudiobookCostEstimator`、`AudiobookProducer`（仅合成 AI 段、段级续跑）、`AudiobookProductionWorker`（dataSync 前台任务）
- `ai/listen`：**连续听书 + 成品播放**。`ListenEngine` 单例（STANDARD 普通 TTS 与 PRODUCED 多角色成品严格分流；READY 剧本按角色顺序分派 SYSTEM/AI、暴露角色名与颜色，未制作/过期章节跳过且不静默回落；同书同模式启动幂等；pause/resume/seek/跨章；音频焦点/becoming-noisy/WakeLock）+ `ListenService`（MediaSession 与 mediaPlayback 前台通知控制）

## 3. feature/ —— 页面（互相禁止 import）

### feature/reader（最大包；自绘阅读器 + 阅读页所有弹层）
- 引擎 engine/：`ReaderContentController`（三章窗口 + (chapterIndex,charOffset) 唯一真源，页索引派生；isDisplaying 供听书翻页判定）、`ChapterTypesetter`+`TextPageModel`（TextChapter/TextPage/TextLine/TextColumn 布局树）、`TextMeasure`/`AndroidTextMeasure`、`PageSelection`（选区几何）、`PageAnnotations`（批注高亮/marker 几何 + `TransientHighlightSpan`：听书当前句与引文定位共用）
- 渲染 render/：`PageBitmapRenderer`（整页位图：页眉/正文/批注高亮/听书句底色/页脚）、`ReaderPageStyle`
- 翻页：`PageTurnDriver`（手势状态机）、`PageTurnCompositor`、`PageFoldGeometry`（仿真折页）
- 组合层：`ReaderScreen`（装配一切：chrome/弹层/选区动作/听书/音量键翻页接线）、`ReaderPane`（位图持有 + 手势 + 选区 + 批注点击）、`ReaderViewModel`（进度/书签/批注/设置）、`ReaderChrome`（顶栏+底部 dock：目录/书签/排版/听书胶囊/伴读）、`ReaderTypographySheet`（主面板四项直调 + 六张分类二级页，回调收在 `ReaderTypographyActions`；承载它的 sheet 无遮罩半高，调参时正文实时可见）、`ReaderSearch`+VM（书内关键词）、`ReaderBookDetail`（详情浮层）、`ReaderListenOrb`（可拖拽听书悬浮球，默认左上、独立于 chrome 可见性）+`ReaderListenViewModel`
- AI 相关：`ReaderAiSheet`+`ReaderAiViewModel`（选词翻译/解析/提问）、`AiRichText`（流式 Markdown）、`ReaderSelectionMediaViewModel`（选区 TTS/生图；ownsSystemSpeech 区分听书）、`CompanionOrb`（悬浮球）、`CompanionChatScreen`（全屏聊天页：附件/建议胶囊/嵌入式发送钮/角色背景图）、`CompanionCitation`（AI 引用解析 → 「跳到原文」胶囊）、`ReaderCompanion`（气泡 timeline 等 internal 组件）、`ReaderCompanionViewModel`（AgentLoop 驱动 + 建议回复）、`BookTextSearch`（IO 流式 indexOf）

### 其他 feature
- `feature/importer`：TXT 管线（TxtChapterSplitter 正则规则集 + TxtTocRuleLoader + TextEncodingDetector + TxtImportWorker）、`BatchImportWorker`（多选/文件夹/局域网共用，TXT 自动取最佳规则）、`ImportPickerScreen`+VM（文件夹勾选）、`LanTransferScreen`+VM（地址/二维码/接收列表）、`AiChapterRuleAgent`（CHEAP 批量模型多轮探索规则，只产待确认提案）、EPUB（EpubTextExtractor/MetadataResolver/Generator）、`ImportCoordinator`+`ImportSessionStore`+预览页、`BookTextMaterializeWorker`（老书补 text.mz）、`BookEmbeddingWorker`
- `feature/bookshelf`：书架（网格/列表、文字封面直排、分组/标签筛选、长按归类）；`manage/` 提供分组与标签管理页
- `feature/bookdetail`：书籍详情页（统计、剧情梗概与笔记、段落批注、插图廊、笔记编辑/导出、有声书状态与创建入口）
- `feature/listen`：`ListenPlayerScreen`（沉浸播放页：满屏模糊封面 + 五键控制 + 玻璃胶囊，**不显示正文**）、`AudiobookPageShell`（有声书三页共用页壳与字号层级：AudiobookPage/Card/SectionTitle/Hint/Metric）、`AudiobookRoleScreen`、`AudiobookScriptScreen`、`AudiobookProductionScreen` 及对应 ViewModel
- `feature/companion`：伴读主页（角色列表/激活/记忆胶囊）+ `PersonaEditorScreen`（ST 卡导入/世界书/记忆开关/聊天外观+实时预览）+ `PersonaMemoryScreen`+VM（记忆逐条管理与画像编辑）+ `PersonaAppearanceCards`
- `feature/stats`：阅读统计（热力图 + 日/月/年精度与周期导航）
- `feature/settings`：`SettingsScreen`（Provider 列表/模型分配/建议回复开关/语音与生图入口/外观/应用）、`FontLibraryScreen`（多字体导入/识名/重命名/删除/设为正文）、`ImageLibraryScreen`（共享背景/封面资产管理）、`ProviderDetailScreen`+VM（模型管理/目录拉取/测试连接）、`TtsSettingsScreen`+VM（系统/AI 引擎 + 独立 TTS API）、`TtsVoiceLibraryScreen`（音色库：说明卡 + 搜索/性别/标签筛选 + 试听 + 「设为听书默认」写 TtsSettings.aiVoiceId；设置页「语音与图像」有直达行）、`ImageGenSettingsScreen`+VM（独立生图 API + 测试生成）、`ProviderLabels`（预设与标签）

## 4. ui/ —— 壳与主题
- `MoReadApp`（NavHost + 玻璃底部导航舱）；components/（`MoReadSurfaces` 玻璃面/Backdrop、`MoReadWidgets`、`PersonaAvatar`、`ReadingHeatmap`、`SheetDragBlocker`、`ChatBubbleStyle`（角色外观→气泡样式/字体/字号，编辑页预览与聊天页共用））；theme/（`AppTheme`/`Theme`/`DesignTokens`/`Type`；**明暗判定用 isDarkTheme()**，阅读页强调色 accentColorFor(dark)）

## 5. 关键流程速查

| 流程 | 路径 |
|---|---|
| 导入 | MainActivity/书架 → BookImportGateway → ImportCoordinator → Txt/Epub Worker → text.mz + chapters → 预览确认 |
| 翻页 | ReaderPane 手势 → PageTurnDriver → controller.moveTo* → contentHook → holder.refresh → PageBitmapRenderer |
| 选词 AI | PageSelection → ReaderScreen.onAiAction → ReaderAiViewModel → AiClientFactory.forRole(CHAT) 流式 → ReaderAiSheet |
| 伴读对话 | CompanionChatScreen → ReaderCompanionViewModel → AgentLoop（CompanionContextBuilder + ReaderToolset）→ ChatDao 落库 |
| 听书 | ReaderChrome 听书键 → ReaderListenViewModel.start → ListenEngine 会话循环 → 进度写回 + ListenState → ReaderScreen 自动翻页/高亮 + ReaderListenOrb 悬浮控制，ListenService 通知 |
| 有声书制作 | 详情页 → 角色确认（AudiobookRoleExtractor）→ 逐章剧本确认（AudiobookScriptAgent）→ AudiobookProductionWorker → AudiobookProducer 仅生成 AI 段 → READY |
| 有声书播放 | ListenEngine 校验 chapter revision → 按 audiobook_segments 顺序读取 → SYSTEM 实时 TTS / AI 命中缓存或即时合成 → 角色名与颜色同步到播放器 |
| 生图 | 任意入口 → AiMediaGenerationService.generateIllustration → AiClientFactory.imageGeneration()（独立配置优先） |
| embedding | search_book 发现无索引 → BookEmbeddingScheduler.enqueueForBook → Worker → ChapterEmbedder → ObjectBox |
| 批量导入 | 书架导入弹层（多选/文件夹/局域网）→ BatchImportScheduler → BatchImportWorker → importDirectly 逐本 |
| 局域网传书 | LanTransferService → LanBookServer(1122) → 浏览器上传落 cache/lan-inbox → 批量导入 |
| 记忆 | 每轮结束 MemoryConsolidationWorker：consolidateAvailable（提炼→去重→向量→画像）→ RollingSummarizer.refresh |
| 跳到原文 | 聊天气泡引用胶囊 → BookQuoteLocator → savedStateHandle 回传 → ReaderViewModel.goToPosition + 临时高亮 |

## 6. 配置与密钥索引
- DataStore（单文件 Preferences）：阅读设置/主题（reader_*、custom_reader_themes）、TTS（tts_*，含 tts_ai_provider/base_url/group_id/model、播放调音、系统音量补偿）、生图（image_api_*，含 sampler/steps/scale）、伴读开关（companion_suggestion_replies）、书架布局与筛选等
- EncryptedSharedPreferences（ApiKeyStore）：Provider Key 按 alias；独立配置固定别名 `standalone-tts-api` / `standalone-image-api`
- Room schema 版本历史一览在 `DatabaseMigrations`；改表必须导 schema + MigrationTest（androidTest，连 MuMu 跑）

## 7. 测试地图（app/src/test）
- 客户端/协议：StreamParsing、OpenAiResponsesPayload、MultimodalEncoding、OpenAiEndpointOverride、OpenAiMediaClient、ChatImageExtractor、NovelAiImageClient、ProviderProtocolPolicy、ModelCatalog、ProviderConnectionTester
- Agent/伴读：AgentLoop、ReaderToolsetTools（quote 定位等）、CompanionContextBuilder、ReplySuggestionParser、MemoryConsolidator、SillyTavernCardParser、CompanionAutoFollow
- 阅读器：ReaderContentController、ChapterTypesetter、PageSelection、PageAnnotations、PageTurnGeometry、BookTextSearch、AiRichTextNormalizer（FakeMeasure 提供测量桩）
- 导入/传书：FolderScannerFilter、HttpRequestParser、MultipartReader、LanUploadNaming
- 记忆：RollingSummaryPlanner、MemoryOperationParser（含 UserProfileParser）
- 引用定位：CompanionCitationParser、BookQuoteLocator；语音：SpeechCacheSyncNaming、ParagraphSegmenter、SleepTimerPlanner、SpeechCacheBookStats
- 数据/导入：TxtChapterSplitter、AiChapterRuleAgent、TextEncodingDetector、EpubTextExtractor/Generator/MetadataResolver、BookTextWriter、LegacyLocatorConverter、MessageAttachment、PersonaSeeds、CustomReaderThemeCodec、PersonaChatAppearanceCodec、ReaderFontLibraryCodec、ReaderImageLibraryCodec、SfntFontNameReader、StatsViewModel
- 向量/切分：ChapterChunker、Embeddings、VectorStoreSpike（本机 JVM 真跑 ObjectBox）；语音：SentenceSegmenter；书架：ShelfTagBackfill、TagNameNormalizer、ShelfFilter
- 有声书：DialogueRuleSegmenter、AudiobookScriptParser、VoiceAssignmentParser、AudiobookCostEstimator、EnginePolicy、EmotionDialectMapper、AudiobookRevision
- androidTest：core/database/MigrationTest（16 例，含 v17→v18→v19；需模拟器）

## 8. 修改指南（常见任务从哪下手）
- 加一个 Agent 工具 → `ai/agent/ReaderToolset` + 单测（ReaderToolsetToolsTest），Persona 白名单在 forBook
- 加阅读页弹层/入口 → `ReaderChrome`（dock 五键慎动）+ `ReaderScreen` 装配
- 动正文渲染/高亮 → engine 几何（PageAnnotations/PageSelection）+ `PageBitmapRenderer` + `ReaderPane` holder 传参三处成套
- 加 LLM 供应商特殊行为 → `ProviderProtocolPolicy`（路由）/`ProviderLabels`（预设）/对应 client
- 加设置项 → 对应 Store（core/datastore、core/speech、core/media）+ 设置页 + 若为二级页在 MoReadApp 挂路由
- 动记忆行为 → `ai/memory`（固化/提要）+ `CompanionContextBuilder`（注入）+ `VectorQueries`（检索过滤），三处口径要一致
- 加排版设置 → `ReaderSettingsRepository` + `ReaderTypographyActions` + 对应分类二级页，并更新该页的 `summaryOf`
