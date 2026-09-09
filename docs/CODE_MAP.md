# MoRead 代码地图

本文面向源码阅读者和贡献者，介绍当前仓库的模块边界、主要调用链和回归测试入口。功能介绍与构建前提见 [README](../README.md)，依赖许可见 [THIRD_PARTY_NOTICES](../THIRD_PARTY_NOTICES.md)。

下文 Kotlin 路径默认以 `app/src/main/java/com/mozhi/reader/` 为根。文件名是定位入口，不代表该功能只需要修改这一个文件；调整调用链时，请同时检查对应的持久化、UI 和测试。

## 1. 仓库结构

| 路径 | 职责 |
| --- | --- |
| [`app/src/main`](../app/src/main) | Android 应用、资源与清单 |
| [`app/src/test`](../app/src/test) | JVM 单元测试：解析、检索、排版、坐标映射和状态转换等 |
| [`app/src/androidTest`](../app/src/androidTest) | Android 数据库迁移、恢复和 Compose 交互测试 |
| [`app/schemas`](../app/schemas) | Room 导出的各版本数据库结构 |
| [`app/objectbox-models`](../app/objectbox-models) | ObjectBox 向量存储模型 |
| [`gradle/libs.versions.toml`](../gradle/libs.versions.toml) | 依赖与构建插件版本目录 |
| [`scripts/gradle.ps1`](../scripts/gradle.ps1) | Windows 构建入口，处理含中文路径的工作区 |
| [`.github/workflows`](../.github/workflows) | PR/主分支 CI 与正式 APK 发布工作流 |

应用是单 `app` 模块：Kotlin、Jetpack Compose、Hilt、Room、DataStore、WorkManager；网络使用 OkHttp，向量数据使用 ObjectBox。最低 Android 版本与构建 SDK 以 [`app/build.gradle.kts`](../app/build.gradle.kts) 为准。

## 2. 应用入口与页面装配

- [`MoReadApplication.kt`](../app/src/main/java/com/mozhi/reader/MoReadApplication.kt)：Hilt 应用入口、恢复启动处理、正文物化任务和按需转换预热。
- [`MainActivity.kt`](../app/src/main/java/com/mozhi/reader/MainActivity.kt)：Activity 与外部打开书籍的入口。
- [`ui/MoReadApp.kt`](../app/src/main/java/com/mozhi/reader/ui/MoReadApp.kt)：Compose 导航图，装配书架、阅读、伴读、统计及设置页面。
- `ui/components/` 与 `ui/theme/`：共用页面、控件、间距和主题；新增设置页优先复用这里的组件。
- `core/di/`：应用协程、网络、数据库和向量存储等依赖注入。

## 3. 数据与设置

| 入口 | 职责与修改注意事项 |
| --- | --- |
| [`core/database/MoReadDatabase.kt`](../app/src/main/java/com/mozhi/reader/core/database/MoReadDatabase.kt) | Room 实体/DAO 集合与版本常量；当前 schema 为 **25** |
| [`core/database/DatabaseMigrations.kt`](../app/src/main/java/com/mozhi/reader/core/database/DatabaseMigrations.kt) | 数据库迁移；新增迁移后在 `core/di/StorageModule.kt` 注册，并提交导出的 schema |
| `core/database/entity/`、`core/database/dao/` | 书籍、章节、合集、标签、批注、对话、角色与有声书的数据定义和查询 |
| [`core/datastore/ReaderSettingsRepository.kt`](../app/src/main/java/com/mozhi/reader/core/datastore/ReaderSettingsRepository.kt) | 阅读排版、主题、书架顺序及按书保存的设置 |
| `core/library/` | 书籍、正文、布局、附件、笔记和书架组织的存储访问层 |
| [`core/security/ApiKeyStore.kt`](../app/src/main/java/com/mozhi/reader/core/security/ApiKeyStore.kt) | API Key 的加密存储入口；不要把凭据写入普通设置或测试样例 |
| [`core/library/BookReadProgress.kt`](../app/src/main/java/com/mozhi/reader/core/library/BookReadProgress.kt) | 统一的阅读进度计算，避免各页面自行换算百分比 |

Room 结构、迁移、备份版本校验和实际数据文件要保持一致。向量模型的修改还需检查 ObjectBox 模型文件及索引恢复行为。

## 4. 导入、书架与合集

### 导入链路

`feature/importer/` 的选择/预览页面与 `ImportCoordinator` 负责用户流程；`core/importer/BookImportGateway` 提供导入入口，后台批量工作通过 WorkManager 执行。

- TXT：`TextEncodingDetector` → `TxtChapterSplitter` / `TxtTocRuleLoader` → 正文存储；`AiChapterRuleAgent` 提供可选的 AI 分章规则辅助。
- EPUB：`EpubPackageInspector`、`EpubMetadataResolver`、`EpubTextExtractor`、`EpubTocMapper` 与 `EpubLayoutDocumentParser` 分别处理包、元信息、文本、目录与布局文档。
- 正文与资源：`core/library/BookTextStore`、`BookTextWriter`、`BookLayoutStore`、`BookMediaStore`。
- 局域网传书：`core/importer/lan/` 的 HTTP 服务、请求解析与上传命名，页面入口为 `feature/importer/LanTransferScreen`。

### 书架与合集

- [`feature/bookshelf/BookshelfViewModel.kt`](../app/src/main/java/com/mozhi/reader/feature/bookshelf/BookshelfViewModel.kt)：书籍观察、筛选、选择及书架操作。
- [`feature/bookshelf/BookCollectionModels.kt`](../app/src/main/java/com/mozhi/reader/feature/bookshelf/BookCollectionModels.kt)：书籍/合集展示模型、可见成员与全体成员、排序合并规则。
- [`feature/bookshelf/ShelfCollectionDrag.kt`](../app/src/main/java/com/mozhi/reader/feature/bookshelf/ShelfCollectionDrag.kt)：拖拽目标与状态转换；`BookCollectionComponents.kt` 和 `BookshelfScreen.kt` 负责 UI 接线。
- [`core/library/ShelfOrganizationRepository.kt`](../app/src/main/java/com/mozhi/reader/core/library/ShelfOrganizationRepository.kt) 与 `core/database/dao/ShelfOrganizationDao.kt`：合集、分组、标签和成员顺序的持久化。

**修改重点：**筛选后的全选、删除和拖拽应使用正确的可见成员集合；不能把隐藏书籍误算为用户已选择。拖拽结束时应按最新列表提交顺序，并保持置顶边界及未显示成员的相对顺序。合集与书架分组是不同的数据概念。

## 5. 阅读器、EPUB 排版与繁简转换

### 阅读与绘制链路

`ReaderScreen` / `ReaderViewModel` → `engine/ReaderContentController` → `ChapterTypesetter` → 页面模型 → `render/PageBitmapRenderer` → `ReaderPane` / `ReaderScrollPane`。

- [`feature/reader/engine/`](../app/src/main/java/com/mozhi/reader/feature/reader/engine)：文本测量、分页、选区、批注几何与正文控制。
- [`core/epub/`](../app/src/main/java/com/mozhi/reader/core/epub)：CSS 解析、级联、DOM 适配与样式解析。
- `engine/EpubBoxLayoutBackend` 与 `engine/epub/`：EPUB 盒树、行内/块布局、分页和排版后端。
- `PageTurnDriver`、`PageTurnCompositor`、`PageFoldGeometry`、`PageBitmapWindow`：翻页驱动、合成、折页几何与位图窗口。
- `ReaderChrome`、`ReaderTypography*`、`ReaderNavigationSheets`：阅读工具栏、排版设置与导航弹层。
- `ReaderTableOfContents`、`BookTextSearch`、`ReaderSearchViewModel`：目录与书内搜索。

调整排版或高亮时，要一起检查分页缓存、坐标转换、选区几何和绘制，不能只改屏幕上的字符串。

### 繁简转换与原文坐标

- [`core/datastore/BookChineseConversion.kt`](../app/src/main/java/com/mozhi/reader/core/datastore/BookChineseConversion.kt)：按书保存 `OFF`、`TW2SP`、`S2TWP` 模式。
- [`core/text/ChineseTextConverter.kt`](../app/src/main/java/com/mozhi/reader/core/text/ChineseTextConverter.kt)：OpenCC 转换、初始化与预热。
- [`feature/reader/engine/ChineseChapterPresenter.kt`](../app/src/main/java/com/mozhi/reader/feature/reader/engine/ChineseChapterPresenter.kt)：为纯文本和 EPUB 生成显示章节，并转换原文/显示范围。
- [`core/library/ReaderTextAnchor.kt`](../app/src/main/java/com/mozhi/reader/core/library/ReaderTextAnchor.kt)：跨转换模式的范围、锚点和边界映射。

**持久化坐标以原文为准。**繁简词组转换可能改变长度；不能把显示偏移直接写入阅读进度、书签或批注，也不能假设原文与显示文案可按总长度线性换算。已有准确原文范围时，避免用重复的上下文匹配覆盖它。修改这里还要检查搜索、伴读引用、听书高亮与选词相关调用点。

## 6. AI 伴读、检索与记忆

| 模块 | 入口与职责 |
| --- | --- |
| 协议客户端 | `ai/client/` 的 OpenAI 兼容、Responses、Claude、Gemini 客户端；`AiClientFactory` 和 `ai/provider/ProviderProtocolPolicy` 负责选择 |
| 对话 | `ai/chat/AiChatRepository`、`ai/prompt/CompanionContextBuilder`；UI 为 `ReaderCompanionViewModel`、`CompanionChatScreen` 和相关组件 |
| 工具调用 | `ai/agent/AgentLoop`、`CompanionToolRouter`、`ReaderToolset`、`ReaderToolsetReadback` |
| 阅读范围与检索 | `core/retrieval/ReadingScope`、`ReadableCorpus`（位于 `ai/agent/`）、`RetrievalPipeline` 与 `BookGrep` |
| 书籍向量 | `ai/embedding/` 负责章节切分后的嵌入、进度与增量续跑；`core/vector/` 提供切分和向量相关数据 |
| 长期记忆 | `ai/memory/` 的滚动总结、记忆固化与仓库；角色配置及导入位于 `ai/persona/` |
| 主动行为 | `core/datastore/CompanionAutonomySettings`、`ProactiveAnnotationLimits` / `Quota` 与 `ai/companion/ProactiveAnnotationService` |
| 媒体与听书 | `ai/media/`、`ai/listen/`、`ai/audiobook/`；缓存、TTS 设置与系统引擎位于 `core/speech/` |

**边界：**AI 读取书籍内容必须遵守阅读进度范围；新增工具应同时检查路由、权限和纯函数测试。会调用外部服务的自动行为应有明确设置和费用提示。对话呈现优先在 `CompanionMessageParts`、`CompanionChatList`、`CompanionProcessCard`、`CompanionComposer` 各自职责内修改，不要把解析规则堆入 Composable。

## 7. 备份、恢复与应用更新

- [`core/backup/BackupArchiveManager.kt`](../app/src/main/java/com/mozhi/reader/core/backup/BackupArchiveManager.kt)：备份打包、清单校验、恢复准备及启动时恢复；支持的数据库版本来自 `MoReadDatabase.VERSION`。
- `core/backup/BackupRepository`、`WebDavClient`、`WebDavBackupWorker`：手动/自动 WebDAV 备份与传输。
- `feature/settings/BackupSettingsScreen` / `BackupSettingsViewModel`：进度、设置和恢复 UI。
- `core/update/`：版本查询、安装准备与更新偏好；对应设置 UI 位于 `feature/settings/`。

恢复校验应先于替换用户数据；新表、新文件格式或新数据库版本必须覆盖兼容性测试。

## 8. 构建与回归验证

准备 JDK 17、Android SDK 37 和本地 SDK 配置后，在仓库根目录执行：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

涉及正式构建或 R8/资源打包的改动，还需验证：

```sh
./gradlew :app:testDebugUnitTest :app:assembleRelease :app:assemblePerformance
```

`release` 的正式签名需要自行配置密钥；仓库不提供密钥。`performance` 使用 debug 签名并启用优化，仅用于开发机性能验证，不是正式发布包。Windows 可将上述任务传给 `scripts/gradle.ps1`。

连接模拟器或测试设备后，Android 数据库与 Compose 测试使用 `:app:connectedDebugAndroidTest`。JVM 测试不能代替这类设备测试。部分真实 EPUB 样书测试依赖外部 fixture；缺少样书时可能跳过，应在测试报告中区分跳过与通过。

| 修改领域 | 优先检查的测试 |
| --- | --- |
| 合集、筛选与拖拽 | `BookCollectionModelsTest`、`ShelfCollectionDragTest`、`ShelfFilterTest`；Android 下的 `ShelfCollectionDaoTest`、`ShelfCollectionDragComposeTest`、`LibraryRepositoryDeleteBookTest` |
| 繁简转换与定位 | `ChineseTextConverterTest`、`ReaderTextAnchorTest`、`ChineseChapterPresenterTest`，特别是词组伸缩与重复文本 |
| 数据结构与恢复 | Android 下的 `MigrationTest`、`BackupArchiveManagerTest`；JVM 下的 `BackupArchivePathsTest` |
| 阅读进度与 AI 检索 | `ReadingScopeTest`、`RetrievalPipelineTest` 及对应 Agent 工具测试 |
| 排版与导入 | `feature/reader/engine/`、`core/epub/` 和 `feature/importer/` 对应的单元测试；真实样书与设备阅读回归 |

提交改动时，请同步更新失效的地图入口；不要把本地路径、凭据、维护工作笔记或未公开的功能计划放入公共文档。

## 伴读稳定性与宽屏阅读边界

- `ui/WindowLayout.kt` 按真实窗口约束区分布局。窗口达到 840dp 使用侧边导航；`ShelfGridLayout.kt` 让书架和合集按可用宽度选择列数，拖拽坐标始终在同一坐标系中换算。
- `ReaderCompanionLayout.kt` 保持正文的组合位置稳定，按需在右侧嵌入同一个阅读页 ViewModel 的 `CompanionChatPane`。窗口缩窄时隐藏侧栏，但保留用户偏好和聊天会话。
- `ReaderPaneHolder` 和 `ScrollPaneHolder` 由阅读页 ViewModel 持有；进入全屏聊天只解绑 UI 回调，返回时复用分页三页位图或滚动章节条带、字体与排版。字体、视口、安全区或阅读模式变化必须重新校验排版环境，主题、批注和背景变化必须使绘制缓存失效。
- `ReaderSafeInsets` 在分页与滚动阅读中共用稳定系统栏和显示切口安全区；沉浸模式只隐藏状态栏，不清零顶部留白，显示/隐藏动画不触发无意义重排。
- `CompanionChatScroll` 在历史消息首次测量前锚定最后一条的底部；异步历史与会话切换先校验书籍和角色归属，避免空会话、旧列表和最新消息来回闪现，手动翻阅历史仍优先于自动跟随。
- `PageSpread.kt`、`SpreadGeometry.kt`、`SpreadLeafGeometry.kt` 统一章内页对、触点映射和书脊翻页。逻辑页码、聚焦的正文锚点与双页左页索引互不替代；章末空白页不是加载占位页，也不写入阅读进度。
- 段评引起的重排只影响实际改变的章节；替换布局就绪前保留旧布局。翻页提交校验布局代次，加载失败不能当作空章继续翻动。
- `AgentLoop.RoundStarted` 和 `MessageEntity.clientRoundId` 在持久化之前确定气泡身份；`CompanionStreamingReducer` 的状态由 Main 线程串行管理，Room 与流事件的到达顺序不应改变列表 key。
- 生图/TTS 配置用 `CommittedTextFieldState` 保留本地选择区和输入法组合态，失焦或操作前提交；`SettingsWriteQueue` 顺序保存并提供 flush 屏障，存储回声不覆盖正在输入的草稿。
- `ProactiveAnnotationScheduler` 是全应用唯一的有界串行生成入口。`ProactiveAnnotationJobEntity` 按书籍、章节、角色和正文修订版持久去重；段落结果与已完成段落账本在同一 Room 事务中落库。
- `ProactiveAnnotationParagraphs` 在本地选择段落，生成请求只接收截至目标段落的正文前缀。`AnnotationVisibility` 在正文、详情、聊天工具和完成提示共用来源范围检查；预生成不能拓宽阅读水位。
- 随读段评的 `style` 按内容语义选择：荧光对应金句/精彩段落，波浪线对应已读前缀中的线索/呼应，直线对应知识点/典故。生成、保存和绘制都保留三种样式；不随机分配，也不批量改写已有段评。
- `ProactiveAnnotationNoticeComposer` 默认使用不调用 API 的内置条数提示；显式选择快速模型时，`ModelRole.CHEAP` 仅接收有长度上限的角色名、性格与说话风格，生成一句角色口吻的共读弹幕，不传正文、段评、历史、记忆或条数。超时、失败和不合规输出回落互动短句，不回落统计通知；胶囊不抢焦点，未读结果不提供跳转入口。
- 随读段评设置复用父页面的 `SettingsViewModel`，冷启动先显示稳定页面框架，真实设置加载前不渲染临时默认开关和滑块。

对应回归测试位于 `feature/reader/*Spread*Test`、`engine/ReaderContentControllerTest`、`CompanionChat*Test`、`ReaderPaneRetentionTest`、`settings/ProactiveAnnotationSettingsScreenTest`、`core/retrieval/AnnotationVisibilityTest`、`ai/companion/`、`ui/WindowLayoutTest` 和数据库迁移/可见水位的 Android 测试中。
