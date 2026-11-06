# MoRead 代码地图

本文面向源码阅读者和贡献者，介绍当前仓库的模块边界、主要调用链和回归测试入口。功能介绍与构建前提见 [README（English）](../README.md) / [中文版](../README.zh-CN.md)，依赖许可见 [THIRD_PARTY_NOTICES](../THIRD_PARTY_NOTICES.md)。

下文 Kotlin 路径默认以 `app/src/main/java/com/mozhi/reader/` 为根。文件名是定位入口，不代表该功能只需要修改这一个文件；调整调用链时，请同时检查对应的持久化、UI 和测试。

## 1. 仓库结构

| 路径 | 职责 |
| --- | --- |
| [`app/src/main`](../app/src/main) | Android 应用、资源与清单 |
| [`app/src/main/res`](../app/src/main/res) | 默认中文与英文字符串资源、`xml/locales_config.xml` 应用语言声明；迁移约定见 [本地化指南](LOCALIZATION.md) |
| [`app/src/test`](../app/src/test) | JVM 单元测试：解析、检索、排版、坐标映射和状态转换等 |
| [`app/src/androidTest`](../app/src/androidTest) | Android 数据库迁移、恢复和 Compose 交互测试 |
| [`app/schemas`](../app/schemas) | Room 导出的各版本数据库结构 |
| [`app/objectbox-models`](../app/objectbox-models) | ObjectBox 向量存储模型 |
| [`gradle/libs.versions.toml`](../gradle/libs.versions.toml) | 依赖与构建插件版本目录 |
| [`scripts/gradle.ps1`](../scripts/gradle.ps1) | Windows 构建入口，处理含中文路径的工作区；优先复用同一工作区已有盘符映射，避免增量缓存跨根失效，不删除外部创建的映射 |
| [`.github/workflows`](../.github/workflows) | PR/主分支 CI 与正式 APK 发布工作流 |

应用是单 `app` 模块：Kotlin、Jetpack Compose、Hilt、Room、DataStore、WorkManager；网络使用 OkHttp，向量数据使用 ObjectBox。最低 Android 版本与构建 SDK 以 [`app/build.gradle.kts`](../app/build.gradle.kts) 为准。

## 2. 应用入口与页面装配

- [`MoReadApplication.kt`](../app/src/main/java/com/mozhi/reader/MoReadApplication.kt)：Hilt 应用入口、恢复启动处理、正文物化任务和按需转换预热。
- [`MainActivity.kt`](../app/src/main/java/com/mozhi/reader/MainActivity.kt)：Activity 与外部打开书籍的入口。
- [`ui/MoReadApp.kt`](../app/src/main/java/com/mozhi/reader/ui/MoReadApp.kt)：Compose 导航图，装配书架、阅读、伴读、统计及设置页面；Dock 渐隐期间保留原选中项。
- [`ui/MoReadNavigation.kt`](../app/src/main/java/com/mozhi/reader/ui/MoReadNavigation.kt)：按起止路由统一选择转场；根页互切只做淡出淡入，二级页进退使用配套的横向共享轴动画。根页安全区忽略系统栏可见性，宽屏侧栏留白属于各根页而非共享 NavHost。
- `ui/BookNavigation` 为所有含 `{bookId}` 的二级路由声明 Long 参数，并统一读取导航与 SavedStateHandle；旧版本恢复的字符串或整数编号在这一边界兼容。
- `ui/components/` 与 `ui/theme/`：共用页面、控件、间距和主题；新增设置页优先复用这里的组件。
- 设置页复用共用控件的信息层级、间距与主题。`ui/components/MoReadPageDialog` 统一独立整页窗口的背景；它与平板 `NavigationSheet` 共用透明系统栏和主题配置。`ReaderToolPage` 为阅读工具提供固定标题与独立滚动内容。
- `ui/theme/AppTheme` 的 `AppearanceSettings` 将配色方案、色彩搭配、质感、导航与形状密度分别保存。`ColorSchemes` 提供默认的「纸墨」（暖白纸底、墨色文字、朱砂点睛）、三套莫兰迪日夜色板、原版灰阶及 Android 12+ 壁纸取色；纸墨与原版同属 `isClassic` 外观（玻璃质感、标准形状、悬浮舱）；`MoReadTheme` 统一装配，`Metrics` 提供随密度变化的尺寸。选择方案会套用推荐质感/形状并恢复方案主色；选择「原版」还恢复悬浮舱与默认色彩搭配，明暗和字体保留。
- `ui/RootDock` / `ui/FloatingRootDock`：手机悬浮导航舱。舱固定靠左、右侧一枚上下文圆钮（`DockAction`：书架是可展开的搜索，伴读页是「新的书库伴读」，统计与设置没有钮），页签宽度由 `dockTabWidth` 按屏宽算出，舱与圆钮不会重叠；没有钮的页面舱舒展拉满整行（`dockFullTabWidth`），切页时页签宽度弹性过渡、圆钮晚一拍出现。四个根页都随滚动收成一粒（`RootDockState` 只观察根页 NavHost 内已被消费的纵向滚动，`RootDockStateTest`）；选中底是一块弹簧滑动的墨底，页签不各自收手势，由舱统一处理点按与拖拽：按下即拿起一枚玻璃透镜（不等长按），极硬弹簧贴手指滑动、逐格轻震、快滑拉伸，松手切页，收起态点一下先展开（命中与橡皮筋几何在 `DockTabGeometry`；墨底在页签序号空间动画，绘制时由 `DockTrack` 按当前宽度换算，切页伸缩不打断滑动；均见 `DockTabGeometryTest`）；搜索词存在 `BookshelfViewModel.searchQuery`，切到其他根页时清空。投影 `floatingGlassShadow` 只画在形状外沿。通栏导航与平板仍在书架内吸顶搜索胶囊。
- `ui/BookOpenTransition`：「翻开书」转场。`bookCoverCapture` 记录封面画面与根布局位置，`openBook` 在 `BookOpenOverlay` 里让纸页从封面位置长满屏幕、封面沿书脊翻开，中途进入阅读页后淡出；系统动画时长为 0 时直接进入。入口为书架续读卡与书籍详情的继续阅读。
- `ui/components/MoReadSurfaces` 统一处理原有玻璃、磨砂与不透明表面及页面背景，浮层不绘制阴影，`MoReadControls` 提供分段、滑条、数字输入（`MoReadNumberRow`，支持「不限」）与胶囊按钮；`SemanticPalettes` 是内置标签与角色配色的公共入口。手机导航支持悬浮舱与通栏，平板保持侧栏；阅读目的地通过 `ReaderAppearanceScope` 保留纸色及原有浮层尺寸。
- [平板与窗口适配](TABLET_UI.md)：根导航在底部导航、紧凑导航栏与完整侧栏之间切换。`TabletSettingsNavigation` 组织设置分类与详情，`CompanionWorkspace` 为书库和单书聊天复用常驻会话列表，`MoReadDetailLayout` 为书籍详情和听书提供摘要／内容分栏。`NavigationSheet` 统一宽屏侧面板与窄屏底部弹层，避免各功能复制窗口判断。
- `core/di/`：应用协程、网络、数据库和向量存储等依赖注入。

## 3. 数据与设置

| 入口 | 职责与修改注意事项 |
| --- | --- |
| [`core/database/MoReadDatabase.kt`](../app/src/main/java/com/mozhi/reader/core/database/MoReadDatabase.kt) | Room 实体/DAO 集合与版本常量；当前 schema 为 **35** |
| [`core/database/DatabaseMigrations.kt`](../app/src/main/java/com/mozhi/reader/core/database/DatabaseMigrations.kt) | 数据库迁移；新增迁移后在 `core/di/StorageModule.kt` 注册，并提交导出的 schema |
| `core/database/entity/`、`core/database/dao/` | 书籍、章节、合集、标签、批注、对话、角色与有声书的数据定义和查询 |
| [`core/datastore/ReaderSettingsRepository.kt`](../app/src/main/java/com/mozhi/reader/core/datastore/ReaderSettingsRepository.kt) | 阅读排版、主题、书架顺序及按书保存的设置 |
| `core/library/` | 书籍、正文、布局、附件、笔记和书架组织的存储访问层 |
| [`core/security/ApiKeyStore.kt`](../app/src/main/java/com/mozhi/reader/core/security/ApiKeyStore.kt) | API Key 的加密存储入口；不要把凭据写入普通设置或测试样例 |
| [`core/library/BookReadProgress.kt`](../app/src/main/java/com/mozhi/reader/core/library/BookReadProgress.kt) | 统一的阅读进度计算，避免各页面自行换算百分比 |

Room 结构、迁移、备份版本校验和实际数据文件要保持一致。向量模型的修改还需检查 ObjectBox 模型文件及索引恢复行为。

- `core/storage/StorageRepository` / `StorageFiles` 统一扫描本地数据并按实体归属统计。`feature/settings/DataSettingsScreen` / `DataSettingsViewModel` 提供按书管理、语音/索引清理及保留记录的管理入口。全局占用按文件计数，不重复累计共享资源；安全清理仅处理过期导出副本、已安装或已过时的更新安装包和可重新提取的封面；「临时与系统缓存」按来源目录细分（`cacheBucketOf`）。无关联资源属于需单独确认的深度清理。
- `core/library/BookRemovalCoordinator` 统一书架单本/批量与存储页的移除策略。`books.removedAt` 非零表示正文已移除、个人记录保留；书架及正文补齐不包含这些书，统计使用包含保留记录的查询。只有明确选择永久删除才触发个人记录级联删除，同时清理新语音目录、附件、插图及相关记忆。原始外部文件与共享图片库素材不属于删书范围。
- 书库伴读话题可能包含多本书，独立于单书删除保留，用户在话题历史中单独确认删除。书籍移除前也检查相关书库话题是否正在生成；正文删除、修改或已读范围缩小后，旧话题只保留历史，不允许继续发送旧上下文或直接沿用引用。
- `ReaderImageAsset.purpose` 区分背景、封面及未分类素材；旧图片无损保留，分类变更不改变使用中的引用。删除检查同时覆盖已移除正文但仍保留封面的书。
- `AppearanceSettings.appFont` 与阅读字体独立，通过 `ui/theme/AppFont` 异步校验并应用到界面 Typography；缺失或损坏的字体回退默认。字体库导入本身不改变阅读字体，阅读页内导入保持原有选择行为。
- `core/media/LocalImageExporter` 与 `ui/components/ImageExportActions` 共用本地图片导出，覆盖书籍插图廊、阅读页和伴读预览。Android 10+ 使用 MediaStore pending 行原子发布到相册，失败移除未完成行；文件导出使用系统文档选择器，不申请广泛相册权限。

## 4. 导入、书架与合集

### 导入链路

`feature/importer/` 的选择/预览页面与 `ImportCoordinator` 负责用户流程；`core/importer/BookImportGateway` 提供导入入口，后台批量工作通过 WorkManager 执行。

- TXT：`TextEncodingDetector` → `TxtChapterSplitter` / `TxtTocRuleLoader` → 正文存储，不生成 EPUB，`epubPath` 为空；`AiChapterRuleAgent` 提供可选的 AI 分章规则辅助。重新分章按章节索引严格读取正文，还原独立保存的章名；旧生成副本只在全部正文验证通过后移除。
- 批量 TXT 使用流式指纹与解码、`TxtFileText` 的 64 KiB 磁盘缓存和 `TxtChapterRange` 目录索引；内置规则仅扫描短标题行，候选规则不复制正文，自动分节只检查有限窗口。`BookTextWriter.writeSlices` 分块规范化和压缩写入，超长单章也不需要整体载入内存。
- `LibraryRepository.insertTextBook` 先生成完整正文，再用单次事务发布书籍、章节与目录，避免逐章回写位置及暴露未完成书籍。`BatchImportRunner` 串行执行并保存小型原子检查点，取消保留清单，恢复后继续未完成项；进度通知按阶段和时间节流。多选和文件夹入口保存持久读取授权。`TxtStreamingImportTest`、`TxtAtomicPublicationTest`、`BatchImportRunnerTest` 覆盖文本一致性、发布失败和中断恢复。
- 单本 TXT 预览同样分块解码；`ImportSessionStore` 保存 UTF-16LE 文件与轻量章节索引，重启恢复、切换规则、AI 规则验证和确认入库都不加载整本正文，兼容迁移旧 UTF-8 会话。单本后台进度共用合并与节流逻辑。`TxtImportStorageTest` 覆盖 65.7 MB 文件的预览、恢复、规则切换和正文一致性，`ImportSessionStoreTest` 覆盖旧会话迁移及失败重试。
- EPUB：`core/epub/EpubPackageInspector`、`EpubTextExtractor`、`EpubLayoutDocumentParser` 与 `EpubLegacyStyleBridge` 处理包、文本与布局解析；`feature/importer/EpubMetadataResolver`、`EpubTocMapper` 负责导入元信息与目录。单本、批量与局域网导入共用 `ImportCoordinator` 的归档预处理：`EpubArchiveNormalizer` 在交给 Readium 前规范化导入副本中内容相同的重复 ZIP 条目，源文件和指纹不变。优先扫描中央目录；系统拒绝打开归档时流式检查本地条目，不依赖 Android 版本或异常文字。仅实际存在重复条目才尝试修复，内容冲突、损坏及取消均中止，不丢弃资源或覆盖原文件。
- `core/epub/EpubChapterFallback` 共用导入与缓存重建的单章降级策略。精排解析异常时使用文本提取器保留正文及图片锚点，在现有包诊断中记录 `chapter-text-fallback-v1`；缓存淘汰、重启及精排解析恢复后仍遵循已记录的提取路径，核对正文完全一致后走原生普通文本排版。旧书修复逐章核对已存正文，一致时只更新辅助数据，不重写正文；不一致则停止修复。取消、虚拟机错误及正文提取失败不能被当作精排降级吞掉。
- `core/readium/EpubUriContainer` 为带冒号等特殊资源名的 EPUB 提供 URI 兼容视图：恢复归档条目，并转义清单、目录与文档中的本地引用；原始书包、资源名和正文保持不变，外部 URL 不改写。
- EPUB 导入复用：`EpubLegacyStyleBridge.newDocumentScope` 在章节间共享不可变 CSS 规则索引，但元素样式缓存按文档隔离；`EpubArchiveImageReader` 直接将图片解析为归档条目，同一导入复用 ZIP 索引与别名映射，结束时关闭。
- 正文与资源：`core/library/BookTextStore`、`BookTextWriter`、`BookLayoutStore`、`BookMediaStore`。EPUB 导入只保存正文、包/章节索引及字体；`BookLayoutCache` 首次阅读章节时解析 DOM，并以 gzip 存入应用缓存，每本上限 8 MiB、全局 64 MiB。淘汰不影响原书，解析器版本变化只丢弃缓存；读取时核对规范正文，不能因重新解析而移动字符锚点。旧布局在归档可用时转换为单个索引，保留无归档旧库的兼容读法。
- `BookTextArchive` 将正文按 64 KiB 独立压缩，章节读取只解压相交块。章节字节坐标始终指向解压后的 UTF-8 流，不能用压缩文件大小代替正文长度；正文修订哈希也基于解压内容。新导入直接压缩，旧书由正文维护任务或存储页手动压缩，完整校验后原子替换；EPUB 原书继续保留用于重建。
- `EpubArchiveAsset` / `EpubArchivePool` 将插图、SVG 与 CSS 背景直接交给渲染器按需读取，ZIP 句柄按阅读器生命周期复用，位图按像素预算缓存并降采样。`BookMediaStore` 只为内联数据等例外保存副本，旧副本与归档逐字节校验后才删除。内嵌 CSS 随当前章节解析并保持级联顺序；布局 JSON 省略默认值，但 schema 字段显式必填。
- 局域网传书：`core/importer/lan/` 的 HTTP 服务、请求解析与上传命名，页面入口为 `feature/importer/LanTransferScreen`。
- 重复导入：`core/importer/SourceFingerprint` 在复制源文件时计算 SHA-256，`books.sourceSha256` / `sourceByteSize` 记录指纹（32→33 迁移，旧书为空）。`ImportCoordinator` 命中已有书（含只保留记录的书）时，单本导入返回 `PreparedImport.Duplicate` 由 `DuplicateImportDialog` 让用户选择，批量与局域网导入抛 `DuplicateBookException` 并跳过。旧 EPUB 只在字节数相同时补算指纹；旧 TXT 没有原文件，只按 `BookTitles` 规范化书名弱提示。字体库、图片库经 `core/datastore/ReaderAssetDedup` 复用同一内容的条目（图片按用途区分），`ai/persona/PersonaCardIdentity` 阻止重复导入同一张角色卡。回归见 `ImportDeduplicationTest`、`MigrationTest.migrate32To33AddsEmptySourceFingerprints`。

### 书架与合集

- [`feature/bookshelf/BookshelfViewModel.kt`](../app/src/main/java/com/mozhi/reader/feature/bookshelf/BookshelfViewModel.kt)：书籍观察、筛选、选择及书架操作。
- [`feature/bookshelf/BookCollectionModels.kt`](../app/src/main/java/com/mozhi/reader/feature/bookshelf/BookCollectionModels.kt)：书籍/合集展示模型、可见成员与全体成员、排序合并规则。
- [`feature/bookshelf/ShelfCollectionDrag.kt`](../app/src/main/java/com/mozhi/reader/feature/bookshelf/ShelfCollectionDrag.kt)：拖拽目标与状态转换；书籍进入合集卡片任意位置时优先加入合集，卡片间隙仍可排序。`previewShelfEntries` 不对合集目标做实时换位预览，避免目标躲开触点；`BookCollectionComponents.kt` 和 `BookshelfScreen.kt` 负责 UI 接线。
- [`feature/bookshelf/BookLongPressOverlay.kt`](../app/src/main/java/com/mozhi/reader/feature/bookshelf/BookLongPressOverlay.kt)：独立 Popup 窗口显示在根导航之上，而不是依赖局部 `zIndex`。`BookMenuPlacement.kt` 计算上下左右布局及有限重叠回退；短窗口允许覆盖部分封面和 dock，菜单限高可滚动，但仍避让系统安全区并保持触达尺寸。
- `ShelfTagIndex`：书架标签以「# 名字」索引页签呈现（井号用标签色，选中项加粗并带同色短线），替代原来的色点胶囊。
- `BookshelfScreen.kt` 只管页面状态、导入入口与合集接线；子组件分在 `BookshelfLayouts.kt`（网格/列表与空状态）、`BookshelfHeader.kt`（问候、继续阅读卡、工具栏与视图菜单）和 `BookCovers.kt`（封面与文字封面）。
- [`core/library/ShelfOrganizationRepository.kt`](../app/src/main/java/com/mozhi/reader/core/library/ShelfOrganizationRepository.kt) 与 `core/database/dao/ShelfOrganizationDao.kt`：合集、分组、标签和成员顺序的持久化。

**修改重点：**筛选后的全选、删除和拖拽应使用正确的可见成员集合；不能把隐藏书籍误算为用户已选择。拖拽结束时应按最新列表提交顺序，并保持置顶边界及未显示成员的相对顺序。合集与书架分组是不同的数据概念。

## 5. 阅读器、EPUB 排版与繁简转换

### 阅读与绘制链路

`ReaderScreen` / `ReaderViewModel` → `engine/ReaderContentController` → `ChapterTypesetter` → 页面模型 → `render/PageBitmapRenderer` → `ReaderPane` / `ReaderScrollPane`。

- `ReaderPane` 的分页状态与位图窗口在 `ReaderPaneHolder`，长按选区在 `ReaderSelectionController`，选词工具栏在 `ReaderSelectionToolbar`；滚动模式对应 `ScrollPaneHolder` / `ScrollSelectionController`。
- [`feature/reader/engine/`](../app/src/main/java/com/mozhi/reader/feature/reader/engine)：文本测量、分页、选区、批注几何与正文控制。
- [`core/epub/`](../app/src/main/java/com/mozhi/reader/core/epub)：EPUB 包检查、正文与布局解析、单章降级，以及 CSS 解析、级联、DOM 适配与样式解析。
- `engine/EpubBoxLayoutBackend` 与 `engine/epub/`：EPUB 盒树、行内/块布局、分页和排版后端。`EpubLayoutCapability` 记录不支持的布局能力并选择回退（目前只有 vertical-lr）；`EpubDomFragmentLocator` 用 DOM fragment id 定位目录锚点。级联从 `<html>` 开始（`EpubDomChapter.htmlNode`），`html { writing-mode }`、根字号与 `rem` 与浏览器一致。
- 竖排（`writing-mode: vertical-rl`）不另写引擎：`engine/epub/EpubVerticalFrame` 把物理盒属性（四边、圆角、宽高、阴影、定位偏移）映射到顺时针旋转 90° 的坐标系，整章仍由横排引擎排版分页，行即物理上的列。`TextPage.verticalFrameWidth` 标记竖排页，`physicalToFrame` / `frameToPhysical`（`engine/VerticalPageGeometry`）是命中、选区、手柄和批注几何进出旋转坐标系的唯一入口。字向按 UAX #50（`engine/VerticalOrientation`，由 Unicode 数据生成）：直立字形用字体的 `vert` 替换，字体没有时退到 Unicode 竖排标点（`VerticalForms`），再没有才侧转；`text-orientation`、`text-combine-upright`（縦中横）在行内布局落到 `TextColumn`。渲染器只旋转画布，并在各自位置把直立字、图片、背景图和渐变转回物理方向；页面背景在旋转前绘制。竖排页的翻页方向为从右往左：`PageTurnDriver.mirrorProvider` 在镜像空间里处理手势，`ReaderPane` 镜像合成帧并预翻转页图，所有翻页动画共用同一套几何。滚动模式下竖排页按整屏条带拼接。回归见 `EpubVerticalWritingTest`、`VerticalOrientationTest` 与 `ReaderPageTouchTest`。
- CSS 细节：`CssGradientParser` 解析 `linear-/radial-gradient` 及 `repeating-` 形式（含多层 `background` 取最上层有图的一层）；`border-*-style` 的 dashed/dotted/double/groove/ridge/inset/outset、`text-shadow`、`white-space: nowrap`、`position: relative/absolute`（相对最近的定位祖先，含 `transform: translate()`）进入样式与页面模型。绝对定位的行按文档序插入输出，保持 text.mz 坐标单调。边框描边落在边框盒内侧；`body` 背景图按 `background-size/repeat/position` 铺开（`TextPage.backgroundLayer`）。回归见 `CssEffectsParserTest`、`EpubCssEffectsLayoutTest`。
- `engine/epub/EpubInlineLayout` 将纯图片段落与正文缩进分开：图片仍遵循对齐和宽度，含文字的行内图片段落仍保留正文缩进。浮动图片旁的剩余空间不足以容纳正常文字短串或下一张图片时，移到相交浮动区域下方再排，避免强制逐字换行形成窄竖列；普通图文环绕保持。回归见 `EpubImageLayoutRegressionTest`。
- `CssParser` 可通过书内资源解析器按声明顺序展开 `@import`，保留媒体条件、相对资源路径并限制循环；`EpubTypesetterV2` 的规则缓存包含依赖样式。文字装饰保留线型与颜色，`TextDecorationPainter` 绘制波浪、双线、点线和虚线，跨字簇保持相位；链接保留出版颜色与显式 `text-decoration:none`。回归见 `CssPublisherCompatibilityTest` 与真实 Paint 的 `EpubPublisherStyleRenderTest`。
- 用户排版在 EPUB 里必须可调。首行缩进统一走 `resolveFirstLineIndent`：原书优先模式照原书声明排，智能模式按用户设置与出厂值的比例缩放原书缩进（保留原书给引文等段落的相对差别），原书声明为 0 时直接用用户设置，接管模式完全采用用户设置，悬挂负缩进在所有模式保留。`text-indent` 是继承属性，祖先上的一条声明会让每个段落都算已声明，不能据此关闭用户设置。字间距按簇补偿：平台按 run 在字符之间分摊字间距，逐簇测量会全部丢失，两个 EPUB 后端都要加上 `TextMeasure.clusterLetterSpacing`。这两项只有真实 Paint 才测得出来，回归见 `engine/epub/EpubUserTypographyTest`。
- `TextPage.fullPageArtwork`、`ImmersiveArtworkFit` 与 `PageBitmapRenderer`：大幅独立插画的整页展示与背景绘制。明确限定尺寸的小图不能被放大全屏；背景、分页和绘制缓存必须一致。
- 背景插画和空的定高盒参与分页；百分比高度基于确定的父容器高度解析，`background-size` 的 `auto` 维度在绘制时按图像比例计算。普通正文的根画布与无装饰的整章容器跟随阅读纸色，插画、边框和内容面板保留；默认不强制段首段尾各留两行，显式 `orphans` / `widows` 仍生效。
- `PageTurnDriver`、`PageTurnCompositor`、`PageFoldGeometry`、`PageBitmapWindow`：翻页驱动、合成、折页几何与位图窗口。
- EPUB 图片长按：`engine/ReaderPageImage` 按实际绘制矩形命中图片并保留每次出现的字符锚点；`ReaderPane` / `ReaderScrollPane` 映射单页、双页和滚动坐标。`ReaderEpubImageDialog` / `ReaderImageTransform` 提供全屏缩放、拖动、旋转、复位及定位原文，打开或关闭不改变阅读位置。
- `core/media/EpubImageFiles` 按需缓存选中的本地/归档图片，预览限制像素预算；未旋转栅格图导出保留原始字节，旋转或 SVG 导出为有像素上限的 PNG 副本，并复用 `ImageExportActions`。原 EPUB 不会被修改。
- `PullBookmarkGesture` / `BookmarkPullIndicator`：方向锁定、阈值与松手提示；只在 `ReaderPane` 的翻页触摸链路启用，不接入 `ReaderScrollPane`。反向回拉、多指、选区拖动与取消事件不能添加书签；`ReaderViewModel` 先捕获当前页，再在互斥区内映射到原文坐标并检查重复，普通书签按钮仍可取消书签。
- `ReaderScreenState` 管理短期弹层状态、排版卡片切换和统一的翻页/自动阅读输入规则；子对话框关闭后保留父弹层。`ReaderUiState` 是阅读展示模型，`ReaderObservedState` 按书籍元数据、批注和偏好组合仓库数据流；通知等副作用由 ViewModel 单独管理。
- `ReaderChrome`、`ReaderNavigationSheets`：阅读工具栏与导航弹层。右上角一级菜单只有书签、书内搜索、自动阅读、外语阅读与「更多」（标题样式、语法高亮、操作区域、TXT 净化、重新识别章节）。选区工具栏按 `core/datastore/SelectionToolbarItems` 分成第一排五项与「更多」菜单，用户可在「阅读交互」里把可选项提到第一排（`ReaderSettings.selectionToolbarExtras`）。排版入口为 `ReaderTypographySheet` / `ReaderTypographyMainPanel`；字体、主题背景、阅读交互分别在 `ReaderTypographyFontPage`、`ReaderTypographyThemePage`、`ReaderTypographyBehaviorPages`。语法高亮从右上角菜单进入 `ReaderSyntaxSheet`，复用 `NavigationSheet` 和 `ReaderSyntaxHighlightEditor`；操作区域、TXT 净化及重新识别章节在「更多工具」中，自动阅读保留一级入口。各子页仅接收对应职责的回调组；`ReaderTypographyCard` 负责实时排版预览，`ReaderTypographyControls` / `ReaderTypographyStepper` 提供共享控件。
- `readerControlsPalette` 为目录、进度条、选区菜单、手柄和阅读伴读浮层提供随应用主题变化的强调色；正文保持阅读主题自身配色。`ReaderEpubImageDialog` 继承应用 MaterialTheme，避免自定义阅读主题或图片编辑页覆盖应用强调色。
- `LocalDictionaryRepository` 对 MDX / MDD 流式计算 SHA-256 并串行发布导入，兼容旧库补算指纹；相同内容复用词典及资源，保留启停状态，标题无效时回退源文件名。`DictionaryCompanionFiles` 读取获授权目录；文件多选和文件夹导入关联同名/分卷 MDD、CSS 及其字体/图片依赖，独立资源保留相对路径，路径不能越出词典。`MdictStylesheet` 展开旧式编号样式。`DictionaryWebContent` 显式返回本地主文档 HTML，资源缺失返回 404，使用软件图层；基础样式先于词典样式，夜间逐项调整颜色而保留层级。`DictionaryCompanionImportTest`、`DictionaryStyleDeviceTest` 覆盖配套导入与真实弹层的日夜样式；可用 `MOREAD_MDX_FIXTURE` 运行本地词典兼容测试，原词典不会进入仓库。
- `AutoReadSession`、`AutoReadPaging` 与 `ReaderAutoReadControls`：阅读页右上角一级菜单中的自动阅读入口，支持匀速滚动、定时翻页和可选固定屏幕参考线。运行态不持久化；触摸、菜单、后台、语音播放与显式导航使会话暂停，必须手动继续。分页复用现有代次校验和位图提交，滚动复用章节条带与原文进度，计时器不直接写已读水位。参考线不生成批注，也不表示逐句跟读。
- `ReaderTableOfContents`、`BookTextSearch`、`ReaderSearchViewModel`：目录与书内搜索。`ReaderContentsSheet` 是目录页签的界面：章节节律条（`buildChapterRhythm` 按字数取宽、长篇聚合到最多 24 段；有分卷时段不跨卷、卷间留空并标卷名，`readerTocVolumes`），点一段时段抬高、目标章整行短暂高亮、贯穿全书的进度线、当前章卡片、右缘刻度条（`scrubberIndex`）与「回到当前章 / 正序倒序」浮条，回归见 `ChapterRhythmTest`。目录弹层四个页签为目录、大纲、人物、书签。
- `ReaderKnowledgeSheet` / `ReaderKnowledgeViewModel`：目录旁的“大纲”和“人物”页签。大纲展示连贯梗概，原文依据单独展开；人物页有“读到此处 / 全书”两档范围，支持手动提取、断点继续和查找。打开页面不调用模型，生成前显示范围、字数、模型与调用上限；“读到此处”只发送已读正文，末章截到当前进度，“全书”确认明确包含未读内容。
- `ui/components/MoReadBottomSheet` 是全部底部弹层的公共入口，自动关闭边缘拉伸并拦截列表越界滚动与惯性，保留标题／把手拖动关闭。`NavigationSheet` 在其上固定内部内容视口并提供宽屏侧栏；外层保持完整窗口约束，使底边正确贴住窗口，不能在其 modifier 上限制比例高度。内部页面填满视口，新列表无需逐页补丁。目录、大纲、人物、书签四个页签用独立的可保存状态保留滚动位置，进度栏尺寸固定，同书刷新保留旧快照。`ReaderKnowledgeVisualTest` 在真实弹层中验证全部页签的底部贴边、边界滑动、切页与刷新，不能只检查独立列表截图。
- `ai/knowledge/ChapterKnowledgeRepository` / `ChapterKnowledgeAgent`：使用批量任务模型和独立 `AgentLoop`，每段最多两轮提交与纠错，不创建聊天记录。每章最多处理 60000 个 UTF-16 字符，按最多 10000 字的段落整理；长章额外合成为一篇连贯梗概。全部引文核对通过且来源仍有效后才原子替换；取消、失败、正文改版或水位缩小保留旧结果。
- 人物提取有两种模式。默认的「人物档案」由 `CharacterResearchAgent` 按固定的四步完成，不要求模型会调用工具：`CharacterCandidates` 在本地按姓氏、对白归属与跨章分布给候选人名打分；`CharacterRoster` 用一次请求把候选整理成名单（剔除非人名、合并称呼、定主次），失败时回落本地候选；每位人物再用一次请求写档案，`CharacterPassages` 选出的段落编号后交给模型，出场多的人物读到更多段落，并优先选入与其他主要人物同场的段落。打开联网时，最后用对话模型经 `note_external` 补充 `BookCharacterGuide.externalNotes`，「读到此处」时提示词限定只查元数据、界面默认折叠，这一步失败不影响结果。单个人物失败只记下名字，其余照常发布。回归见 `CharacterResearchPipelineTest`、`CharacterResearchPartsTest`、`BookCharactersRepositoryTest`。
- 人物档案（`CharacterDossier`）包含一句话简介、小传、性别/年龄/身份、外貌、性格、按章节的经历、关系、成长变化与台词。模型只写段落编号，`CharacterDossierCodec` 在本地换算成章内 UTF-16 锚点（`SourceAnchor`，取人名或对方称呼所在的句子），所以每条内容仍能跳回原文；只有台词经 `core/text/QuoteMatcher` 逐字核对，对不上的静默丢弃。人名与关系对象必须出现在给出的段落中，缺字段、超长或类型写歪只影响那一项。生成时同时回填旧的 `evidence` / `attributes`，插图形象与角色卡等调用方不受影响。档案的关系方向是「对方是他的什么人」，旧资料的关系是「本人是对方的什么人」，界面合并两侧时按此区分。
- 人物请求经 `KnowledgeJsonRequest` 发出：纯文本 JSON，不发工具 schema；按首个增量（含思维链）、相邻增量间隔与单轮总时长限时，慢但持续输出的模型不会被截断；超时、断网、限流与 5xx 自动退避重试；解析失败时带上原因修正一次。`CharacterJsonSalvage` 容忍说明文字、尾逗号与注释，被截断时退回到最后一个完整元素。完整扫描的工具式提交（`ChapterKnowledgeAgent`）共用同一套限时与重试；截断后救回却一个人物都没剩下时要求重写，不当成「本段没有人物」缓存。
- 完整扫描的人物资料引文由 `core/text/QuoteMatcher` 定位：容忍空白、全半角标点、引号与省略号写法的差异，记录的始终是原文片段。`ChapterKnowledgeCodec.parseCharacters` 逐条取舍，对不上的引文、未知属性种类或跨段引文只丢弃该条，全部失败才要求模型修正。属性含性格（`PERSONALITY`）。扫描结束后 `CharacterBiographer` 为事实最多的人物写简介、小传、性格与主次，只送已核对的事实文字，结果进逐人缓存，失败不阻断发布。
- `KnowledgeGenerationRunner` 使用应用级作用域，每章任务和全书人物任务独立开始、停止和报错；`KnowledgeRequestLimiter` 公平限制并发模型请求。任务开始时 `KnowledgeTaskKeepAlive` 拉起 `KnowledgeTaskService`（dataSync 前台服务）：切到后台或熄屏仍保持联网，通知栏显示 `KnowledgeProgress`（阶段、已完成/总数、正在整理的人名）与停止键，持有随进度续期的 WakeLock，全部任务结束后退出；用户不在应用中时，人物任务完成或失败另发一条提醒。人物档案逐人写入 `book_character_dossiers`，缓存键由模型、提示版本、称呼与段落正文决定：停止、失败或进程被系统结束后再提取只补剩下的人，段落未变的人物跨次复用；发布后只保留本次用到的缓存。
- 人物档案界面（`feature/reader`）：`ReaderKnowledgeTaskOrb` 在任务进行且目录弹层关闭时浮在阅读页上，显示进度环、刚整理好的人物头像与计数，点按直接打开「人物」页签。人物页按主角 / 主要人物 / 其他人物分组（`CharacterRosterCard`，头像为定妆图或渐变首字），顶部状态行高度固定，进度更新不移动列表。点卡片进入弹层内的 `CharacterDossierPage`：简介与基本信息、小传、性格、外貌、经历时间线、成长变化、自我中心关系图与关系列表、台词、手动资料与书外资料，各条可跳回原文，关系人可直接切到其档案，系统返回先回到列表。`BookRelationGraph` 用 `ai/agent/RelationLayout` 的确定性布局画全书关系图，可缩放平移。分组、摘要、时间线、双向关系合并与布局是 `CharacterDossierLayout` 的纯函数。
- `BookCharactersRepository` / `BookCharactersCodec`：按章读取正文，逐段提取人物；范围可收到阅读进度（`ReadingScope.uptoProgress`，末章只送已读部分）。分段结果绑定原文修订、模型、提示版本、分段范围和正文哈希，作为持久缓存跨次提取复用，复用时重新逐字核对，读过更多章节后“更新到当前进度”只为新正文计费；仅在全部章节完成后替换已发布人物资料，并把分段归到已发布的那一代，未完成的进度才算断点。按姓名精确合并，保留各人的初始介绍和后续事实，不猜测别名，聚合时不同时持有全书原文。
- `ChapterKnowledgeEntity` / `ChapterKnowledgeDao`：`chapter_knowledge` 保存章节来源、已读终点、模型与提示版本；兼容旧版证据式内容。`BookCharacterGuideEntity` / `BookCharacterPartEntity` / `BookCharacterDossierEntity` 与 `BookCharacterDao` 分别保存人物资料（含本次是否只扫到阅读进度）、完整扫描的分段缓存和人物档案的逐人缓存（`book_character_dossiers`，34→35 迁移），独立于有声书角色。章节跳转验证已读范围，人物跳转验证整书修订和逐字引文；永久删书均由外键级联清理。

- `PageAnnotations` 将段评画成原文范围内的划线与末端小点，整个划线文字区域可点击；段评不生成 `InlineMarkerReservation`，新增或删除评论不改变字距、行高和分页。插图按钮仍使用独立占位。`PageWindowRefreshQueue` 合并翻页期间的更新，在翻页提交或取消后按最终窗口刷新，点击几何使用实际显示的批注版本。已保存的 AI 段评随数据更新原地出现，不等待已读进度；`ReaderPane.readTrackingEnabled` 在入场完成或恢复前台时触发实际绘制，仅用于及时确认实际已读范围。回归参考 `ReaderAnnotationStabilityTest`。
- `engine/LineBreakRules` 供两条 EPUB 排版路径共用中文行首行尾禁则：在已测量宽度内回退到合法断点，闭合标点随前文移到下一行，再按设置均排；不将连续标点追加到正文右边距外。TXT 和 EPUB 均排时，成对符号、开闭标点及西文单词内部不再分配额外字距。极窄容器没有合法断点时保留测量内的硬换行。回归及原生绘制样张参考 `ReaderPunctuationLayoutTest`。

调整排版或高亮时，要一起检查分页缓存、坐标转换、选区几何和绘制，不能只改屏幕上的字符串。

### 英语学习与阅读工具

- `core/datastore/ReaderTapZones` 持久化正文九格与页眉、页脚动作；`ReaderTapZonesDialog` 配置，`ReaderPane` / `ReaderScrollPane` 消费同一规则。操作区域和阅读辅助复用 `ReaderFullscreenDialog`，延伸到状态栏及显示切口区域。
- `core/dictionary/MdictReader` 按块读取 MDX/MDD v1/v2；`LocalDictionaryRepository` 负责导入、索引缓存和本地资源读取。`DictionaryWebContent` 限制脚本及外部请求，保留词典 HTML/CSS 排版。
- `AiDictionaryEntry` 解析独立的中文标注、音标和 Markdown 释义字段；`WordGlossExtraction` 兼容旧格式并过滤元数据字段。`VocabularyCodec` 修复旧版误存的字段名标注；`AiDictionaryEntryTest`、`DictionaryWordGlossTest` 覆盖 `tugs`、富文本与旧收藏兼容。
- 切换词典后保存已有生词，`EnglishLearningViewModel.saveWord` 同步替换完整释义、词下短释义及音标，保留学习状态与收藏来源；设置变化经 `ReaderPageStyle.wordGlosses` 触发正文重排。
- `EnglishLearningViewModel` 共用词典与生词数据，AI 词典使用 `ModelRole.CHEAP`；`DictionaryLookupDialog` 承接分页、滚动阅读的划线查词，不限制语言，AI 释义复用 `AiRichText` 并保持切换后的滚动位置。`DictionaryManagerPage` 支持多本 MDX / MDD 管理，`VocabularyPage` 管理收藏（概览三格兼作全部/学习中/已掌握筛选，词卡按收藏时间分段；分段、筛选、释义摘要与语境高亮是 `VocabularyBook.kt` 的纯函数，由 `VocabularyBookTest` 覆盖；掌握、移出即时生效并可撤销），设置导航分别注册词典与生词本目的地，平板保留分类侧栏。阅读中的对应 Dialog、中英对照和单段译文操作复用 `ReaderToolDialog` 右侧面板；阅读辅助进入生词本时保留返回、搜索与滚动状态。`EnglishLearningDialog` 仅管理可选英文阅读辅助；`ReaderSyntaxHighlighter` 提供生词划线及英文词首加粗。
- `ParagraphTranslationRepository` 将译文缓存放在 `reader-custom/translations`，与原文分离并校验段落摘要。`ReaderViewModel` 管理单段、当前页、当前章请求及取消，`BilingualReadingDialog` 管理本书显示开关；长按译文或正文选区的“本段对照”进入单段重翻译、显示/隐藏和删除操作。
- `ModelRole.TRANSLATION` 独立分配阅读翻译模型，未分配时回落 CHAT 模型且不注入聊天预设；`TranslationModelSelector` 与 AI 服务页共用模型选择行，选文翻译也走此角色。更多选项可重新翻译已有段落。
- `engine/WordGlossLayout` 在排版时预留词下标注高度；`ParagraphTranslationLayout` 为 TXT 和 EPUB 生成零源长度译文行，保留英文复制、朗读、阅读位置和书签坐标。
- `LibraryRepository.previewTextReplacementRules` / `TextCleanupPreviewDialog` 预览净化差异；应用时校验原文版本，使用同一份规则快照，区分普通文本、正则和听书专用规则。
- 人物页通过 `ExtractedCharacterCard` / `ExtractedCharacterCardDialog` 将已有的带出处人物资料编辑为伴读角色，或导出可重新导入的 SillyTavern V2 JSON 卡。
- `BookCharacterEditorDialog` 支持手动新增、编辑姓名与资料。`BookCharactersRepository.saveCharacter` 在书籍锁内保存；生成发布时合并最新手动资料，`BookCharactersCodec` 分离手动描述与原文依据，保留重命名身份及滚动锚点。
- `ResponsesParameterCompatibility` 在 Responses HTTP 参数校验失败时有限移除服务端明确拒绝的可选参数，覆盖流式与非流式，不重放已输出的流。回归见 `ResponsesParameterCompatibilityTest`。
- 外语阅读不再限于英文：`core/dictionary/LearningLanguage` 定义按书的学习语言（默认按 `ScriptDetector` 识别文字系统）与段落对照的 `TranslationTarget`；`ForeignWords` 为拉丁（含变音）、希腊、西里尔与韩文取词，`WordGlossMatcher` 对日文等无空格文字按收藏词条做最长子串匹配，生词标注、划线弹窗与仿生阅读共用它（旧名 `EnglishWords` 只是转发）。译文缓存记录目标语言，生词记录语言并可在生词本按语言筛选。语言与译文语言用 `ui/components/MoReadChoiceDialog` 的选择弹窗（文字徽标而非国旗）。回归见 `LearningLanguageTest`、`LanguageChoiceDialogVisualTest`。
- 回归入口：`MdictReaderTest`、`EnglishReadingLayoutTest`、`EnglishReadingVisualTest`、`DictionaryWebContentTest`、`ReaderTapZonesDialogTest`、`TextCleanupPreviewTest`、`ExtractedCharacterCardTest`。

### 演出模式（一句一屏）

阅读页右上角菜单的「演出模式」进入全屏演出页（路由 `stage/{bookId}`）：一屏只放一拍（通常是一句），点一下走一拍，旁白、对白、心声、拟声、系统提示各有版面，手机消息、论坛楼层、书信、属性面板这类内容进入对应的版式容器、逐条累积。屏幕上的字逐字来自原文，效果只是元数据。

- `core/stage/` 是「场记」（`StageScript`）：节拍、镜头类型、说话人、提示语旁注、版式容器、行内强调与入场/屏幕效果，全部挂在章内 UTF-16 原文坐标上。入口 `StageCompiler`：`StageLines` 逐段分类（旁白、对白、心声、时空字幕、拟声、系统提示、场景分隔、内联图片、脚注与表情占位），`StageGrouping` 识别版式容器（同行或分行格式的论坛楼层、手机消息、属性面板与资料卡、书信、诗词），`StageQuotes` 找对白（含方括号式传音、跨段长对白、跨段提示语）并拆分超长对白，`StageNames` 挖说话人词表。容器与镜头都要线索叠加才成立，宁缺毋滥。
- 说话人只用读者已经读过的正文判断：词表来自当前章与之前若干章（说话小句开头按「下一个字是否分散」切出名字边界，带「·」的音译名按左右分支修剪），人物档案与有声书选角的名字经 `StageCompileContext.knownNames` 传入；名牌写原文此处的称呼，置信度不足时只出对白框、不出名牌。
- 精排 EPUB 的结构直接复用：`StageBookLayoutReader` 读已解析、带原文坐标的 DOM，按 class 词根认出聊天框（含书内头像、左右、语音条）、论坛楼层、弹幕、报纸、社交动态、文件清单、属性面板、引文/笔记框，以及插图（带图注）、章首题图、分隔图与删除线，经 `StageCompileContext.book` 交给编译器；书标出的区域优先于本地规则，认不出就回落。`StageAmbience` 按原文写明的声光与情绪词给旁白屏幕效果、给对白结巴抖字/惊叹放大/省略放慢。
- `StageAssetMerger` 合入已确认且修订号一致的有声书分镜（说话人、情绪、语气）。`StageEffects` 把情绪与语气换成视觉倾向，并执行硬限：一拍最多一个强效果、行内强调最多两处（人名与专有名词不计）、屏幕效果按强度档限频，「克制」档与系统「移除动画」退化为淡入。海报（整屏竖排朱砂大字）很稀少：`StageFigures.posterStrength` 只认独立成段、两半同位同字的三三/四四对句（「粗看离谱，细看合理。」），对白、惊叹、带人名或口语虚词的句子都不算，`StageEffectBudget.figures` 再限每章至多一张（华丽档两张，相隔 40 拍以上），AI 导演核对过的金句优先。`StageReveal` 给出逐字揭示时间表与自动播放停留时长。`StageTextRules` 放界面会用到的原文判断，界面代码里不出现中文字面量。
- 场景氛围：`StageAtmosphere` 按拍维护天气、布景（山野、江河、海、树林、竹林、城市、室内、古风庭院、校园、乡村、沙漠）、时段、光源与有限时长（14 拍）的情绪，只从已出现的原文更新；布景、时段与光源只认旁白、字幕与提示语（对白和心声里提到的地点不算），并排除比喻、回忆、否定、目的地与成语/人名里的跨词；章首可承接上一章末尾的环境，`era` 按现代/古代标志词密度给整本书的年代感。可选的 AI 导演（`ai/stage/StageDirector`，默认关，走廉价模型）按约 1600 字的段落窗口返回封闭词表里的场景标签与逐字摘录的金句，不跨窗前瞻、按章缓存在 `filesDir/stage-director/`；`StageDirections.merge` 只把结果并进还没演到的拍，金句只有与某拍原文逐字相同时才提成海报。
- 声音：`core/stage/sound/` 是不依赖 Android 的确定性合成器（环境层：雨、暴雨、风、浪、溪、虫、鸟、火、城市、树叶、风铃；程序配乐六种；提示音六种；Freeverb 混响、DC 阻断与软限幅），`StageSoundMatcher` 把场景换成各层音量与配乐风格。低频为主的声音会把能量放到 250 Hz–4 kHz，保证手机外放可听。`feature/reader/stage/StageSoundPlayer` 独占 AudioTrack 与音频焦点，参数在音频块边界交接；`StageSound` 只在打开声景时创建播放器，跟随生命周期暂停与释放。
- `feature/reader/stage/`：`StageViewModel` 编译当前章并预编译下一章，保存进度（拍起点）、推进阅读水位（`markVisibleReadEnd`，一拍完整显示后才推进）、记阅读时长，退出时经 `ReaderLocateRequest` 把当前拍的原文范围交回阅读页定位并短亮。`StageScreen` 负责手势（点按、长按快进到已读处、上滑回看、下滑退出）、音量键、自动播放、屏幕效果（压暗是四周的墨向中间收拢）、雷雨闪电、场景分隔的化开转场与沉浸系统栏；`StageKineticText`/`StageParagraphText` 整段排版一次、按字形簇逐个绘制，揭示进度与颤动相位只在绘制阶段读取，逐字动效（含墨晕：字形影子由洇开收拢到清晰）是纯函数 `StageGlyphMotion`；文字显示效果默认关（直接显示），句末呼吸的墨点提示可以点按继续。
- 布景与笔墨：`StageScenery` 按（布景、时段、阴天、年代、明暗、尺寸、章种子）确定性地生成两层水墨位图（屏幕 1/3 分辨率，远景与近景，主体压在下方一角、中间留白给文字），镜头以每秒 15 次的低频更新缓慢错动，换景时交叉化开，光源（烛火摇曳、灯光、篝火、霓虹）与情绪颜料在运行时叠加；`StageSceneryPainter.paintPixels` 是纯 Kotlin，单测可出图核对。`StageInk` 放印章（说话人名牌、章名卡章序）、开章墨滴、场景分隔的飞白横画、溅墨与竖排工具；内置行书 `res/font/stage_brush.ttf`（志莽行书，OFL）用于章名、海报、拟声、喊叫、书信与印章，可在设置里关掉。`StagePalette` 定舞台配色（宣纸、墨夜、跟随阅读），国画颜料（朱砂、藤黄、花青、赭石、石绿、胭脂）在 `StagePigments`。
- `StageContainers`、`StageCards`（弹幕、报纸、动态、文件、笔记）、`StageArtwork`（书内插图、题图、分隔图、头像的解码与展示）、`StageBeatViews`（章名卡、字幕、拟声、系统面板）、`StageBalloons`（对白小笺与名牌）、`StageFigureViews`（海报、阶梯、叠句）、`StageSheets` 分别是版式容器、各镜头组件与回看/设置弹层。设置保存在 `core/datastore/StageSettings`（带 `revision`，旧版本存下的默认值读取时换成新默认）。

### 繁简转换与原文坐标

- [`core/datastore/BookChineseConversion.kt`](../app/src/main/java/com/mozhi/reader/core/datastore/BookChineseConversion.kt)：按书保存 `OFF`、`TW2SP`、`S2TWP` 模式。
- [`core/text/ChineseTextConverter.kt`](../app/src/main/java/com/mozhi/reader/core/text/ChineseTextConverter.kt)：OpenCC 转换、初始化与预热。
- [`feature/reader/engine/ChineseChapterPresenter.kt`](../app/src/main/java/com/mozhi/reader/feature/reader/engine/ChineseChapterPresenter.kt)：为纯文本和 EPUB 生成显示章节，并转换原文/显示范围。
- [`core/library/ReaderTextAnchor.kt`](../app/src/main/java/com/mozhi/reader/core/library/ReaderTextAnchor.kt)：跨转换模式的范围、锚点和边界映射。
- `ReaderPresentationResolver` 以转换模式和正文来源代次共同标识一次解析，原文读取也属于该次快照；过期计算最多重新执行一次，取消继续传播。EPUB 预览携带快照，真正跳转前再次检查，不能将旧正文的字符偏移应用到新正文。

**持久化坐标以原文为准。**繁简词组转换可能改变长度；不能把显示偏移直接写入阅读进度、书签或批注，也不能假设原文与显示文案可按总长度线性换算。已有准确原文范围时，避免用重复的上下文匹配覆盖它。修改这里还要检查搜索、伴读引用、听书高亮与选词相关调用点。

### 阅读统计

阅读统计由 `feature/stats/StatsViewModel` 聚合，`StatsScreen`、`StatsCards`、`StatsCharts` 呈现总 / 年 / 月 / 周 / 日视图。阅读热力默认紧接时长概览，后续为月历、趋势、时段、时间线、排行和带配色的标签/作者云；`StatsSettingsStore` 保存组件显示与顺序。`StatsHistory` 将每日阅读量最大的书籍封面铺入月历日期格，多本阅读显示数量提示，点选日期通过真实 `NavigationSheet` 查看完整记录；月历始终聚合锚点所在的整月。时间线按周展示每本书的阅读日期，只连接实际相邻的阅读日，完整日记录使用带封面的日期节点列表。封面加载保持固定尺寸，缺失或损坏时回落书名底图。

`ReadingTimeSlices` 按当地小时边界切分实际阅读时长，`reading_hourly` 与 `reading_daily` 在同一事务累积；跨午夜及夏令时保持总量，旧日记录不推算为小时记录，保留记录的书籍仍参与统计。

## 6. AI 伴读、检索与记忆

| 模块 | 入口与职责 |
| --- | --- |
| 协议客户端 | `ai/client/` 的 OpenAI 兼容、Responses、Claude、Gemini 客户端；`AiClientFactory` 和 `ai/provider/ProviderProtocolPolicy` 负责选择 |
| 对话 | `ai/chat/AiChatRepository`、`ai/prompt/CompanionContextBuilder`；UI 为 `ReaderCompanionViewModel`、`CompanionChatScreen` 和相关组件 |
| 书库伴读 | `feature/companion/LibraryCompanion*`、`LibraryChat*`；`ai/companion/LibraryCompanionRunner` 管理与页面分离的单次生成，`LibraryScopeGuard` 校验来源范围 |
| 陪伴足迹 | `feature/companion/CompanionStatistics`、`CompanionStory`、`CompanionStatsScreen` / `CompanionStoryTimeline` / `CompanionStatsViewModel`；`ChatDao` 只向统计层返回消息元数据和字数，不加载正文 |
| 工具调用 | `ai/agent/AgentLoop`、`AgentToolExecutor`、`ToolResult`、`CompanionToolRouter`、`ReaderToolset`（注册与只读分组）、`ReaderToolsetReadback`；工具实现在 `ReaderBookTools`（读原文/检索/记忆）、`ReaderNoteTools`、`ReaderExternalTools`（联网/生图/TTS）及 `GrepBookTool` 等独立文件 |
| 阅读范围与检索 | `core/retrieval/ReadingScope`、`ReadableCorpus`（位于 `ai/agent/`）、`RetrievalPipeline` 与 `BookGrep` |
| 书籍向量 | `ai/embedding/` 负责章节切分后的嵌入、进度与增量续跑；`core/vector/` 提供切分和向量相关数据 |
| 长期记忆 | `ai/memory/` 的滚动总结、记忆固化与仓库；角色配置及导入位于 `ai/persona/` |
| 主动行为 | `core/datastore/CompanionAutonomySettings`、`ProactiveAnnotationLimits` / `Quota` / `ProactiveAnnotationContextSettings` 与 `ai/companion/ProactiveAnnotationService` / `ProactiveAnnotationContext` |
| 媒体与听书 | `ai/media/`、`ai/listen/`、`ai/audiobook/`；缓存、TTS 设置与系统引擎位于 `core/speech/` |

- 默认聊天外观下角色的话不装气泡（`ChatBubbleStyle.inline`），正文与互动岛直接排在页面上、与角色名对齐；自定义了气泡形状或底色的角色仍画气泡。脚注来源以引文卡呈现。段落讨论弹层按「页边批注」排版，讨论是视觉主体：原文是比讨论小一档的宋体摘录块（按实际划线样式绘制，默认折三行、点击展开），样式与颜色收在「原文」小标行末的样式钮里、点开后就地可改；讨论用更大一号的界面黑体挂在一根竖线上，回复缩进一级。
- 互动回答：`ai/genui/` 解析模型输出的 DIL 文档并编译为可恢复的界面程序，`runtime/GenUiEngine` / `GenUiSession` 通过 QuickJS 执行，`ui/genui/GenUiHost` / `GenUiRenderer` 将界面树渲染为原生 Compose 控件。`feature/reader/CompanionGenUi` 接入消息展示、会话状态和动作；`core/datastore/CompanionGenUiSettings` 保存生成级别、样式、图片与运行策略。图片默认点按生成，运行时不提供 DOM 或直接网络访问。回归入口为 `GenUiCompilerTest`、`GenUiEngineTest`、`GenUiJniKeepRulesTest`、`GenUiContrastTest` 和设备端 `GenUiAndroidRuntimeTest`。
- 对话导出：会话历史弹层（`CompanionConversationSheet`，书内与书库伴读共用）每行的导出按钮打开 `ConversationExportDialog`，把整段会话导出为 Markdown，可分享或经系统文件选择器保存（无需存储权限）。`ConversationTranscript.buildConversationMarkdown` 是纯函数：只导出用户与 AI 发言，AI 回复复用 `parseCompanionParts` 去掉气泡与语音标记、互动界面取文字投影，可选附上思考过程与工具名，不导出工具结果原文。回归见 `ConversationTranscriptTest`。

**边界：**AI 读取书籍内容必须遵守阅读进度范围；新增工具应同时检查路由、权限和纯函数测试。会调用外部服务的自动行为应有明确设置和费用提示。对话呈现优先在 `CompanionMessageParts`、`CompanionChatList`、`CompanionProcessCard`、`CompanionComposer` 各自职责内修改，不要把解析规则堆入 Composable。

- 书库伴读从伴读主页的悬浮按钮进入，直接使用阅读页的头像、顶栏、气泡、输入区和滚动规则。用户可以不选书直接聊天，最多 4 本“重点讨论”只是偏好，不是访问白名单。模型先用 `find_books` 查本地书名、作者、标签、分组，再按需读取单书材料；不会自动扫描并上传全书库正文。
- `LibraryConversationSources` 在实际读取时登记来源，每轮最多实际查阅 4 本书，每个话题最多关联 32 本。重点书籍不占用查阅预算，选满重点仍可按需查其他书。`LibraryBookScopes` 将独立已读水位和正文修订指纹写入 `conversations.bookScopesJson`；新一轮可随真实阅读进度扩大范围，单轮内固定。正文移除、修订变化或水位缩小时拒绝重发旧上下文。`AgentLoop.validateContext` 每次模型轮次前后校验，工具读取也前后核验；本地正文读取与删除互斥，网络嵌入请求不持有文件写入锁。
- `AgentTool.execute` 返回 `ToolResult.Success` / `Failure`，状态、部分结果标记和错误码与模型正文分开；禁止通过提示文案判断成败。普通与独立 Agent 循环共用 `AgentToolExecutor`，非法 JSON 参数不会执行工具，取消异常向上传播。执行异常和降级诊断统一记录调用标识、错误码、异常类型与栈位置，正文、参数和异常消息不进入诊断日志；历史工具消息继续保留原有内容格式。
- `LibraryCompanionToolset` 提供按 `book_id` 路由的目录、已有笔记、区分来源的划线、章节读取及 `search_book` / `grep_book`。指定正文单次最多 5 章 / 6000 字，模型循环最多 4 轮。已有向量索引可用于查询；缺失时走本地 BM25，不隐式创建索引。不注入没有跨书来源约束的长期记忆、画像，也不提供联网、生图、语音、删书或改正文工具。用户手写的无锚笔记属于用户提供材料，不当作已验证原文。
- `LibraryOrganizationTool` 只能生成标签及一级分组的待确认方案，不能执行修改。`LibraryOrganizationCoordinator` 由用户界面确认后，在同一个 Room 事务中校验书籍、标签名称/关联和分组快照，应用变更并更新方案状态；元数据已变化则拒绝覆盖。取消不写书架，重复确认不重复应用，失败整体回滚。方案保存在工具消息中，删除话题不会撤销已确认的整理。
- `LibraryCitationParser` / `LibraryCitationVerifier` 仅识别显式书籍编号、章节号和逐字引文；点击来源时才读取对应章，在正确书籍的已读范围内匹配，再携带原文 UTF-16 锚点进入阅读页。不存在或过期的引用不会跳转。书库消息复用 `CompanionChatScroll` 的稳定 key、手势优先和贴底逻辑。
- 书库气泡支持复制、编辑、删除、分支与重新生成，失败或停止后可重试。历史修改由 `LibraryCompanionRunner` 与发送共用互斥区；重生成前核对来源和角色，复用原用户消息，并清掉旧生成事件缓存。用户编辑截断后文，AI 编辑保留后文；分支保留工具结果与消息身份，但清空滚动摘要。已知书籍来源按保留轮次过滤，未知旧记录保守保留范围；已应用的书架整理不因聊天历史修改而撤销。
- “陪伴足迹”展示一起读过的书、阅读时长、聊天字数及交流日，支持全部 / 书内 / 书库与近 7 天 / 30 天 / 全部筛选。保留记录的已移除书籍仍计入，彻底删除书籍记录后不再计入书数和阅读时长，独立保存的跨书对话仍可保留。时长来自关联书籍在所选日期内的 `reading_daily`，不是 AI 同时在线的计时；字数由 SQLite 统计保留的双方正文，不含思考过程、工具结果和系统上限提示。有效交流要求用户在下一条用户消息前收到非空、非工具调用的回复，选段问答和段评不计。可识别分支按消息身份去重，未知旧身份只能按现存记录计。`messages.sourceBookIdsJson` 保存当前轮重点与实际查阅书籍的并集；`null` 为旧记录未知，`[]` 为明确未关联，不能用后续查到的书反填早期闲聊。界面不是模型用量或费用账单。
- 陪伴足迹的时间线由纯函数 `buildCompanionStory` 生成：完成轮次按同一角色、同一本书、间隔不超过 30 分钟合并为一次“共读”，并标出每本书第一次被聊起；首句、第 10/50/100… 次交流在所选范围的全部历史中计数，再按时段筛选展示；长期记忆摘要（`PersonaMemoryRepository.recent`，经 `VectorQueries.listRecentMemories` 跨角色读取）按其来源会话类型归入书内或书库范围。每天一个节点，节点上的 24 小时小表盘标出交流的时段（`companionHourArcs` 合并跨午夜的连续小时），顶部表盘展示全部交流的小时分布与高峰时段。当天阅读时长只计当天聊到的书。时间线文案均在字符串资源中；进入视口时的连线与指针动画只在绘制阶段读取进度。回归见 `CompanionStoryTest` 与 `ReadingAndCompanionVisualTest`。
- 并行工具调用：`ai/agent/ParallelToolRunner` 把一轮里相邻的只读调用（`ConcurrencySafeTools`，`AgentTool.concurrencySafe`）并发执行，最多 4 个，写入类调用串行；工具状态事件按完成先后发出，落库与回填历史仍按调用顺序。`AgentLoop.pairToolResults` 在下一次请求前补齐被中断的调用、丢弃孤立结果。`LibraryConversationSources` 的预算登记加锁。回归见 `ParallelToolRunnerTest`、`AgentLoopTest`。
- 过程卡显示由 `core/datastore/CompanionProcessDisplay` 控制（隐藏 / 简洁 / 详细，思考、参数、结果预览与生成时展开四个开关），经 `LocalCompanionProcessDisplay` 提供给 `CompanionProcessCard`；详细模式按 `processStepGroups` 把并行执行的查询框在一起。设置在「AI 与伴读 → 聊天显示」，同页还有建议回复、多气泡与用量；主动行为与记忆各有二级页（`feature/settings/CompanionSettingsPages`）。
- 脚注式引用：`ai/companion/CitationFootnotes` 把 `〔原文 …〕「…」`、书库 `〔书籍#… 第N章〕「…」` 与网页 `〔来源 URL〕` 按整条消息首次出现的顺序编号，渲染为 `moread-cite:n` 上标链接；`feature/reader/CompanionFootnotes` 拦截链接，在点击处弹出纸色卡片（引文与前后可读上下文），气泡底部列出来源，原文跳回阅读页、网页用浏览器打开。网页标题与摘要由 `ai/agent/WebSourceParser` 从同一会话的工具结果还原。回归见 `CitationFootnotesTest`。
- 图表：`ai/agent/ChartTools` 提供 `create_chart`（`ChartSpecCodec` 校验柱状、折线、饼图、雷达、关系图与时间线规格）与 `count_mentions`（阅读范围内按章字面计数）；成功的调用在聊天流里以 `CompanionChartCard` 显示，可放大导出图片。柱状与折线使用 Vico，其余在 Canvas 自绘，关系图布局由确定性的 `RelationLayout` 计算。回归见 `ChartToolsTest`。
- 文件：`ai/agent/DocumentTools` 提供 `create_document`（`DocumentSpecCodec` 校验标题、类型与 Markdown 正文），模型把读书报告、章节总结、人物档案、金句摘录这类成篇内容交给用户。与图表一样，规格就是工具调用参数，不另建表；成功的调用在聊天流里以 `CompanionDocumentCard` 显示：固定高度的卡片，点开全屏预览，可分享（FileProvider）或经系统文件选择器保存。素材须先经只读工具取得，所以天然受阅读进度约束；段评讨论不开放。回归见 `DocumentSpecCodecTest`。
- `ai/agent/ChapterSearchBounds` 解析 `search_book` / `grep_book` 的章节区间，并与阅读水位求交。正文加载从实际起始章开始；Grep 游标绑定上下界，阅读进度增长不会扩大旧查询。`RetrievalRequest.firstChapterIndex` 在融合前和邻居扩展后都执行下界检查。
- `core/vector/VectorQueries` 在章节范围内不超过 512 个切片时使用精确余弦排序（限制向量复制量）；较大范围使用有界 ANN 补召回，不承诺穷举。
- `AiModelType.RERANK` / `ModelRole.RERANK` 提供独立可选重排模型；自定义供应商使用 `RerankApiClient` 调用 `/rerank` 或模型自定义路径，采用 query/documents 与 index/relevance_score 格式。`ConfiguredChunkReranker` 只重排已过滤候选的有界前缀：最多 24 段、每段 800 字、合计 12000 字、5 秒。未分配时不调用重排模型，异常、超时或不完整排名回落原融合排序；不更改向量阈值，不重建索引。书内与书库检索共用此链路，发送候选前及返回证据前再次核对正文和范围。
- `feature/bookdetail/AnnotationIndex` 明确区分全部、我的与 AI 划线，并支持进一步按 AI 角色筛选。来源以 `personaId` 是否为空为准，不按样式、颜色或是否自动生成推断；删除角色不会将其批注算作用户内容。计数使用全部已保存批注，不按阅读进度隐藏。
- `feature/review/ReadingReviewScreen` 是设置中的全局「划线与笔记」入口，手机与平板共用。`ReviewOptionsMenu` 复用书架的锚定悬浮菜单并提供二级选择页，`ReadingReviewPreferences` 持久化筛选与布局；`ReadingReviewModels` 统一书籍、来源、角色、类型与关键词筛选，已生成段评直接提供计数、瀑布流、全屏回顾、导出与 AI 素材，AI 笔记仍按每本书的可读范围过滤；已移除正文的书仍可回顾保留记录。`ReadingReviewDetails` 复用伴读头像和段评讨论；全屏回顾默认保留纸页交接，右上角可切换「立方」「长廊」两种 3D 舞台（`ReviewCardMotion.reviewCard3d` 纯函数给出每帧旋转、枢轴、明暗与光泽位置，选择存入 `ReadingReviewPreferences.focusMotion`），3D 模式下重力传感器让居中卡片轻微倾斜（`ReviewCardTilt`，只在 3D 模式注册，读数只在图层/绘制阶段读取），上下滑仍是打开与返回；`ReviewExporter` 生成带署名与出处的 Markdown / 图片。右上角菜单的「多选管理」进入多选：只在当前筛选结果里选（`reviewSelection`），可批量删除（`ReadingReviewViewModel.deleteAll` 先取含讨论串的快照，几秒内可撤销）、改划线样式、导出、翻阅与共创。`ReadingReviewComposer` 仅在主动请求时把勾选素材交给 AI，草稿经编辑确认后保存，并保留 AI 来源与可读范围；保存前复查素材和范围。回归入口为 `feature/review/ReadingReviewTest`、`ReadingReviewViewModelTest` 与 `ReadingReviewVisualTest`。
- 书籍详情页按封面取色：`feature/bookdetail/CoverPalette` 从降采样封面中提取主色调与按饱和度加权的特征色（米白纸色、灰阶不算），再按当前明暗生成页顶色调与满足 WCAG AA 的强调色；没有封面或封面为灰阶时退回应用强调色并减弱色调。`BookDetailAtmosphere` 把 36 px 缩略图先做 CPU 盒式模糊再放大铺满页顶（Android 12+ 叠加 `Modifier.blur`），色调层与遮罩都渐隐为透明以衔接页面背景，随滚动淡出；整页在 `MaterialTheme` 中把 primary 换成封面强调色。`DetailBookObject` 把封面画成带书脊折痕、书页厚度与同色投影的实体书，按压处会 3D 倾斜（只观察触点、不消费，页面照常滚动）。缩略图与色板按文件路径、修改时间与大小缓存。回归见 `CoverPaletteTest`、`BookDetailVisualTest` 与 `BookDetailNavigationTest`。
- 详情页批注使用 `AnnotationIndexSheet` / `NavigationSheet`，每个来源与角色筛选保留独立滚动状态。点击段评将原文坐标和 `textAnchorJson` 交给既有 `ReaderLocateRequest` 路径，复用繁简坐标转换与短暂高亮。段落讨论打开时预选“上次点名过的角色 → 当前伴读角色 → 第一个角色”，留空发送即请该角色点评原文，有文字时同样带上它，不落空用户消息；再点一次选中的胶囊可取消点名（只保存用户想法），取消只对当前这条发言生效、不写回记忆。
- `AiServiceScreen` / `ProviderDetailScreen` 按供应商、用途和生成参数分组。`AiSettingsComponents` 根据模型名识别系列图标，独立于中转供应商；未知模型按能力显示图标。图标是随包分发的本地矢量资源。`ModelParameterFields` 编辑常用参数并保留其他 JSON，请求体中的同名覆盖项一并处理，防止界面值与请求值不一致。`WebSearchSettingsScreen` 使用带图标的引擎列表。
- 图标转换脚本 `scripts/convert-ai-icons.py` 显式分隔 SVG 圆弧的两个标志位，避免紧凑的 `01` 被 Android/Compose 当成一个数而破坏轮廓；彩色品牌保留原始渐变。GLM 使用 Z.ai 标志，火山方舟与硅基流动按名称或接口主机识别，服务图标独立于兼容协议。
- `StorageDistributionChart` 用环形图和按占用排序的条形图展示真实文件大小，点击分类联动高亮；小项可合并或展开，汇总保持字节总量一致。正文压缩只处理阅读正文，结果区分已压缩、文件过小、没有压缩收益、文件缺失及失败，避免把所有情况都显示为释放 0 B。
- `ui/components/FontPreviewChoice` 在阅读字体、主题字体、语法高亮字体和伴读字体的选择器中显示实际字形样本；候选横向列表使用懒加载，不因浏览预览自动修改选中的字体。
- 离线指标与外部样书工具在 `core/retrieval/evaluation/` 测试包中，说明见 [本地检索评测](RETRIEVAL_EVALUATION.md)。公开仓库只保留运行器与指标单测，真实书籍和标注由调用者在本地配置。

## 7. 备份、恢复与应用更新

- [`core/backup/BackupArchiveManager.kt`](../app/src/main/java/com/mozhi/reader/core/backup/BackupArchiveManager.kt)：备份打包、清单校验、恢复准备及启动时恢复；支持的数据库版本来自 `MoReadDatabase.VERSION`。
- `core/backup/BackupRepository`、`WebDavClient`、`WebDavBackupWorker`：手动/自动 WebDAV 备份与传输。
- `feature/settings/BackupSettingsScreen` / `BackupSettingsViewModel`：进度、设置和恢复 UI。
- `core/update/`：版本查询、安装准备与更新偏好；`UpdateDownloads` 在启动时删掉已安装或已过时的更新包。对应设置 UI 位于 `feature/settings/`。

恢复校验应先于替换用户数据；新表、新文件格式或新数据库版本必须覆盖兼容性测试。

## 8. 构建与回归验证

准备 JDK 21、Android SDK 37 和本地 SDK 配置后，在仓库根目录执行。应用字节码目标仍为 Java 17；聊天 Markdown 依赖的 JVM 类需要 Java 21，因此包含实际富文本绘制的单测不能在 JDK 17 上运行。

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

涉及正式构建或 R8/资源打包的改动，还需验证：

```sh
./gradlew :app:testDebugUnitTest :app:assembleRelease :app:assemblePerformance
```

`release` 的正式签名需要自行配置密钥；仓库不提供密钥。`performance` 使用 debug 签名并启用优化，仅用于开发机性能验证，不是正式发布包。Windows 可将上述任务传给 `scripts/gradle.ps1`。

连接模拟器或测试设备后，Android 数据库与 Compose 测试使用 `:app:connectedDebugAndroidTest`。JVM 测试不能代替这类设备测试。部分真实 EPUB 样书测试依赖外部 fixture；缺少样书时可能跳过，应在测试报告中区分跳过与通过。

EPUB 兼容回归包括 `EpubImportCompatibilityTest`（资源 URI 与目录）、`EpubImportPipelineTest`（导入和资源落盘）、`EpubArtworkPaginationTest`（内嵌 CSS、背景与分页）及 `EpubBackgroundRenderTest`（真实像素和图片比例）。真实样书可通过 `MOREAD_EPUB_FIXTURE` 或按平台路径分隔符拼接的 `MOREAD_EPUB_FIXTURES` 提供；可选的 `MOREAD_EPUB_RENDER_DIR` 接收日间与夜间画面，书籍内容不进入仓库测试样例。

| 修改领域 | 优先检查的测试 |
| --- | --- |
| 合集、筛选与拖拽 | `BookCollectionModelsTest`、`ShelfCollectionDragTest`、`ShelfFilterTest`；Android 下的 `ShelfCollectionDaoTest`、`ShelfCollectionDragComposeTest`、`LibraryRepositoryDeleteBookTest` |
| 长按菜单与书签手势 | `BookMenuPlacementTest`、`BookLongPressOverlayTest`、`PullBookmarkGestureTest`、`ReaderPageTouchTest`、`ReaderViewModelPersistenceTest`；下拉书签同时检查距离、方向和最短持续时间，回拉及多指取消不得变成翻页 |
| 本地化资源 | `LocalizationResourcesTest`：英文、默认回退、格式参数与复数；`LocalizationResourceParityTest`：翻译键、占位符与 translatable；`AppLocalesTest`：语言映射、切换与 `UiText`；`HardcodedTextRatchetTest`：界面层硬编码中文只减不增。断言中文文案的 Robolectric 界面测试须用 `zh-rCN` 限定符（默认 en-US） |
| 繁简转换与定位 | `ChineseTextConverterTest`、`ReaderTextAnchorTest`、`ChineseChapterPresenterTest`，特别是词组伸缩与重复文本 |
| 数据结构与恢复 | Android 下的 `MigrationTest`、`BackupArchiveManagerTest`；JVM 下的 `BackupArchivePathsTest` |
| 阅读进度与 AI 检索 | `ReadingScopeTest`、`RetrievalPipelineTest` 及对应 Agent 工具测试 |
| 工具结果与阅读交互契约 | `AgentToolExecutorTest`、`AgentLoopTest`：跨语言状态、真实工具失败、取消、降级诊断及两种循环一致性；`ReaderScreenStateTest`、`ReaderPresentationResolverTest`、`BookNavigationTest`：弹层互斥、子对话框、快照过期及编号兼容 |
| 章节检索范围与来源筛选 | `ChapterSearchRangeTest`、`GrepBookToolTest`、`AnnotationIndexTest` / `AnnotationIndexUiTest`；`RankingMetricsTest` 验证指标计算，真实语料评测为显式启用 |
| 排版与导入 | `EpubLegacyStyleBridgeTest`、`EpubArchiveImageReaderTest`、`EpubDomFragmentLocatorTest`、`EpubLayoutCapabilityTest`、`ImmersiveArtworkFitTest` 与对应引擎测试；真实样书与设备阅读回归 |
| 布局压缩兼容 | `BookLayoutStoreTest`、`GzipTextFilesTest`：旧索引、明文/gzip 混合、缺失或损坏文件、重试及新导入 |
| 按需布局与归档图片 | `BookLayoutArchiveCacheTest`、`BookMediaArchiveTest`、`EpubImportPipelineTest`：缓存淘汰/版本、正文坐标、归档 PNG/SVG 渲染、旧副本校验和缺省 schema |
| 单章精排降级 | `EpubChapterFallbackTest`、`EpubChapterFallbackImportTest`、`BookLayoutArchiveCacheTest`：整本导入、图片锚点、取消、提取失败、缓存淘汰与重启后的正文一致性 |
| 书籍详情配色 | `CoverPaletteTest`、`BookDetailVisualTest`（生成的封面、日夜两色与无封面回退） |
| 阅读统计 | `StatsViewModelTest`、`StatsSettingsStoreTest`、`StatsScreenVisualTest`、`ReadingTimeSlicesTest`、`BookStorageAndTimeMigrationTest`：周期聚合、整月封面数据、跨周/年连续阅读区间、组件持久化、大格热力、真实日期/时间线弹层、跨午夜/夏令时及历史数据迁移 |
| 页面切换稳定性 | `NavigationMotionTest`、`NavigationStabilityTest`、`BookDetailNavigationTest`：真实 NavHost 内逐帧检查根页不缩放、阅读返回的系统栏留白与列表锚点、书籍详情标题与正文位置、宽屏视口、Dock 退场选中态和快速切页恢复 |
| 正文压缩与设置交互 | `BookTextArchiveTest`、`BookTextStoreCompressionTest`：跨块读取、内容哈希、坐标与中断保护；`AiSettingsVisualTest`：图标、参数编辑、小屏与深色界面；`AnnotationNavigationSheetTest`：真实弹层贴底、边界滑动、筛选与进度刷新 |
| 图标轮廓与存储图表 | `AiIconRenderingTest` 将 Compose 矢量渲染与原始 SVG 独立渲染逐像素比对；`StorageDistributionTest` 检查分类汇总、占比和零释放反馈，`StorageScreenVisualTest` 验证图表选择、刷新、小屏和深色界面 |
| 存储、素材与数据保留 | `StorageFilesTest`、`UpdateDownloadsTest`、`AssetPreferencesTest`、`LibraryRepositoryRemovalTest`（含真实 25→26→27 迁移和历史保留）、`LocalImageExporterTest`、`DataManagementUiTest`、`AppFontTest` |
| 自动阅读 | `AutoReadSessionTest`、`AutoReadSurfaceTest`、`AutoReadUiTest`；停顿不追赶、取消不提交、加载超时暂停，仍需真机检查滚动手感与生命周期 |
| 演出模式 | `StageCompilerTest`（示例章节逐拍断言、各镜头与容器的正反例、坐标与重叠不变式）、`StageEffectBudgetTest`、`StageFiguresTest`（海报只给对得最紧的对句与每章限额；设置 `MOREAD_STAGE_TEXT_DIRS` 时打印真书里每档的海报数与句子）、`StageRevealTest`、`StageAssetMergerTest`、`StageSettingsTest`（含旧版本默认值迁移）、`StageGlyphMotionTest`；`StageAtmosphereTest`（布景/时段/光源/情绪/天气的正反例、持续与衰减、章首承接、年代感、前缀不前瞻）、`StageDirectionsTest`、`StageDirectorParseTest`（AI 导演解析容错、分窗不跨窗、缓存与降级）；`StageSoundMatcherTest`、`StageSynthTest`（峰值、静音、确定性、平滑、零分配与性能）；`StageSceneryPreviewTest`（布景确定性与留白；设置 `MOREAD_STAGE_SCENERY_DIR` 时按布景 × 时段 × 明暗出图）；`StageBookLayoutReaderTest`（精排结构正反例）；`StageEpubBooksTest` 读取 `MOREAD_EPUB_FIXTURES` 跑真解析器回归并打印镜头/容器覆盖；`StageSampleBooksTest` 与 `StageSceneCoverageTest` 读取 `MOREAD_STAGE_TEXT_DIRS`（逐章 TXT 目录，首行为章名）跑本地真书回归与场景覆盖统计，`StageSoundExportTest` 在设置 `MOREAD_STAGE_SOUND_DIR` 时导出试听 WAV；未设置时跳过，书籍内容不进入仓库 |
| 书库伴读与统计 | `LibraryCompanionToolsetTest`、`LibraryCatalogToolTest`、`LibraryConversationSourcesTest`、`LibraryCompanionRunnerTest`、`LibraryOrganizationCoordinatorTest`、`CompanionStatisticsTest` / `CompanionStatisticsDatabaseTest`、`ReadingAndCompanionVisualTest`（真实控件、合成示例，不调用外部模型） |
| 章节大纲与人物 | `ChapterKnowledgeTest`、`ChapterKnowledgeRepositoryTest`、`BookCharactersRepositoryTest`、`CharacterResearchPipelineTest`、`CharacterDossierCodecTest`、`KnowledgeJsonRequestTest`、`CharacterJsonSalvageTest`、`CharacterBiographerTest`、`KnowledgeGenerationRunnerTest`、`ReaderKnowledgeViewModelTest`、`CharacterDossierLayoutTest`、`ReaderKnowledgeTaskOrbTest`、`ReaderKnowledgeVisualTest`；覆盖整章合成、未读末章、断点核对、独立取消、并发限流、刷新保留和真实弹层中的边界滑动/切页/进度更新 |
| 重排与历史修改 | `RerankApiClientTest`、`ConfiguredChunkRerankerTest`、`LibraryHistoryMutationTest`；覆盖输入预算、失败回落、取消、引用完整性和分支后的上下文 |

提交改动时，请同步更新失效的地图入口；不要把本地路径、凭据、维护工作笔记或未公开的功能计划放入公共文档。

## 9. 本地化

`res/values/strings.xml` 保留默认中文，`res/values-en/strings.xml` 提供增量英文翻译。当前范围是底部导航与平板侧栏、设置首页及阅读与外观 / AI 与伴读 / 关于页、书籍长按菜单与阅读状态、部分合集提示、书签反馈、外部字体导入和导入进度，其余界面仍为硬编码中文。

- `core/i18n/AppLocales` / `AppLanguage`：应用内语言（跟随系统 / 简体中文 / English）。Android 13+ 走系统 per-app language（`res/xml/locales_config.xml` 声明可选项）；Android 8–12 存私有 SharedPreferences，由 `MainActivity.attachBaseContext` 与 `MoReadApplication`（启动及配置变化后）套用。入口是 `feature/settings/AppLanguageSettings` 的 `AppLanguageBlock`，位于「阅读与外观 → 应用外观」。
- `core/i18n/UiText`：ViewModel、Worker 等非 Compose 层传递资源编号与参数，在显示处解析（Compose 用 `ui/components/UiTextResources` 的 `asString()`）；`ReaderEvent.ShowLocalizedMessage` 携带 `UiText`。不要把解析后的字符串存入长期状态或数据库。
- `HardcodedTextRatchetTest` 按文件统计 `feature/`、`ui/` 与 `MainActivity` 中含汉字的字符串字面量，只许减少不许增加，基线在 `app/src/test/resources/i18n/`。

Compose 使用 `stringResource` / `pluralStringResource`。资源命名、占位符、复数、基线更新和验证约定见 [Localization](LOCALIZATION.md)。

## 10. 伴读稳定性与宽屏阅读边界

- `ai/client/ChatUsage.kt` 归一化 OpenAI、Responses、Claude 和 Gemini 的累计用量；`AgentLoop` 按请求保存输入/输出与耗时到 `MessageEntity`，数据库 30→31 迁移为历史消息保留空值。`CompanionTokenUsage.kt` 控制气泡下统计的统一格式，设置开关通过 CompositionLocal 覆盖阅读伴读和书库伴读。
- `MainActivity.acceptFileIntent` 在书籍导入前将 MDX 路由到词典管理；`LocalDictionaryRepository.supportsMdx` 识别文档显示名称、本地文件 URI 与 MDX MIME。`ExternalDictionaryOpenTest` 覆盖系统选择器解析、扩展名大小写、文件导入及损坏文件不发布。

- `ui/WindowLayout.kt` 按真实窗口约束区分布局。窗口达到 840dp 使用侧边导航；`ShelfGridLayout.kt` 让书架和合集按可用宽度选择列数，拖拽坐标始终在同一坐标系中换算。
- `ui/MoReadTabletSidebar` 为宽窗口提供常驻书库导航，与书架共用 `BookshelfViewModel` 的阅读状态、分组和标签筛选；侧栏独立滚动。`TabletBookshelfComponents` 提供最近在读和局部搜索，视图切换仍只在右上角原有菜单中；封面按实际内容宽度排成 3～6 列（导航条窗口固定 3 列，侧栏窗口按 132dp 最小格；窗口变宽列数只增不减，`ShelfGridLayoutTest` 逐 dp 校验），窄窗口沿用手机布局。视图菜单的「每行本数」（`ReaderSettings.shelfGridColumns`，0 为自动、3～6 为手动）同时作用于书架与合集详情，窄容器里逐列让步，封面不窄于 56dp。手机和平板的书籍、合集列表行都直接排列在画布上，以细分隔线区分，不再逐行包裹圆角卡片。`TabletBookshelfVisualTest` 覆盖横竖屏、深色、筛选、窗口缩放、后台刷新和截图。
- `ReaderCompanionLayout.kt` 保持正文的组合位置稳定，按需在右侧嵌入同一个阅读页 ViewModel 的 `CompanionChatPane`。窗口缩窄时隐藏侧栏，但保留用户偏好和聊天会话。
- `ReaderPaneHolder` 和 `ScrollPaneHolder` 由阅读页 ViewModel 持有；进入全屏聊天只解绑 UI 回调，返回时复用分页三页位图或滚动章节条带、字体与排版。字体、视口、安全区或阅读模式变化必须重新校验排版环境，主题、批注和背景变化必须使绘制缓存失效。
- `ReaderSafeInsets` 在分页与滚动阅读中共用稳定系统栏和显示切口安全区；沉浸模式只隐藏状态栏，不清零顶部留白，显示/隐藏动画不触发无意义重排。
- `CompanionChatScroll` 在历史消息首次测量前锚定最后一条的底部；`ReaderCompanionViewModel` 缓存聊天标题上下文，首次展示前有界预定位引用。异步历史与会话切换在挂起前后校验会话归属，避免空会话、旧列表和最新消息来回闪现，手动翻阅历史仍优先于自动跟随。
- `PageSpread.kt`、`SpreadGeometry.kt`、`SpreadLeafGeometry.kt` 统一章内页对、触点映射和书脊翻页。逻辑页码、聚焦的正文锚点与双页左页索引互不替代；章末空白页不是加载占位页，也不写入阅读进度。
- 段评引起的重排只影响实际改变的章节；替换布局就绪前保留旧布局。翻页提交校验布局代次，加载失败不能当作空章继续翻动。
- `AgentLoop.RoundStarted` 和 `MessageEntity.clientRoundId` 在持久化之前确定气泡身份；`CompanionStreamingReducer` 的状态由 Main 线程串行管理，Room 与流事件的到达顺序不应改变列表 key。
- 生图/TTS 配置用 `CommittedTextFieldState` 保留本地选择区和输入法组合态，失焦或操作前提交；`SettingsWriteQueue` 顺序保存并提供 flush 屏障，存储回声不覆盖正在输入的草稿。
- `ProactiveAnnotationScheduler` 是全应用唯一的有界串行生成入口。`ProactiveAnnotationJobEntity` 按书籍、章节、角色和正文修订版持久去重；段落结果与已完成段落账本在同一 Room 事务中落库。旧轮次标记 `DONE` 后仍须核对当前条数上限与可生成范围；调高上限或重访此前跳过的范围时，只补缺失候选，保留成功账本与失败重试预算。
- `ProactiveAnnotationParagraphs` 在本地选择段落；生成请求接收截至目标段落的本章前缀，以及 `ProactiveAnnotationContext` 从严格前文章节检索的相关原文和有效梗概。检索复用本地 BM25，不增加模型调用；梗概须同时通过正文修订版、来源范围与原文哈希校验，正文变化后停止旧请求。`AnnotationVisibility.forDisplay` 只遵守用户的 AI 批注显示开关，已保存段评在正文、详情、回顾和完成提示中立即可查看，讨论不要求先读到原文。自动聊天上下文和查询工具仍检查来源范围；预生成不修改阅读水位。开启提前章节时，正在阅读的章节每轮按角色处理至多 3 个候选段落（`PROACTIVE_VISIBLE_CHAPTER_SLOTS_PER_PASS`），预生成章节每轮 1 个，随后轮到其他章节，防止本章不限条数阻塞下一章；进入章节模式下每个候选开工前重读阅读进度，读者已翻过的候选直接跳过（不计失败），调用留给前方未读段落；同轮失败候选只在完整一轮结束后参加最多两轮的重试，前文上下文缓存复用并校验正文修订版。
- 段评目标按句切成 200 字以内的小块，降低目标跨页导致引文所在页看不到段评的概率。进入章节生成时从读者当前页之后选段。每格正选输出无法解析或引文无法定位时，改用同格次选；超时和请求错误不换段。引文只在目标内用 `QuoteMatcher.bestOverlap` 定位，失败原因写入日志 `ProactiveAnnotation`（不含正文）。
- `ProactiveAnnotationContextSettings` 提供省流 8,000、均衡 16,000、充分 32,000 字符及 4,000～64,000 自定义预算，默认均衡。预算覆盖本章正文（含单独重复的目标段落）、前文片段与梗概，不含角色设定和写作提示词；全局与单书覆盖共用设置页，修改预算会让在途任务失效。
- `AnnotationDiscussionService` 固定提供只读书内查询工具，不再按发言关键词裁剪。模型可先搜索再回读核对，独立循环最多五轮；提示词与工具共用实际 `ReadingScope`。记忆检索同时遵守全局/角色开关、当前用户面具与跨书对话开关，段评讨论仍不写入聊天记忆。
- 随读段评的 `style` 按内容语义选择：荧光对应金句/精彩段落，波浪线对应已提供原文中的线索/呼应，直线对应知识点/典故。生成、保存和绘制都保留三种样式；不随机分配，也不批量改写已有段评。
- 额度用完必须让用户看得见：调度器在当日额度耗尽时按每本书每天一次推送 `dailyBudgetExhausted` 提示，文案固定且不调用模型；设置页在每日上限下显示当天已生成条数。预生成会把额度提前用在尚未读到的章节上，没有这两处提示，用户只会看到连续多章没有段评。调度器的工作循环只在自身作用域被取消时结束，单次请求的取消不影响后续章节。
- `ProactiveAnnotationNoticeComposer` 默认使用不调用 API 的内置条数提示；显式选择快速模型时，`ModelRole.CHEAP` 仅接收有长度上限的角色名、性格与说话风格，生成一句角色口吻的共读弹幕，不传正文、段评、历史、记忆或条数。超时、失败和不合规输出回落互动短句，不回落统计通知；胶囊不抢焦点，已生成结果立即提供查看入口。
- 回退诊断只记录超时、错误类型和输出长度，不记录模型原文、角色资料或异常消息里的连接信息。
- 随读段评设置复用父页面的 `SettingsViewModel`，冷启动先显示稳定页面框架，真实设置加载前不渲染临时默认开关和滑块。
- `CompanionAutonomySettings` 保存多伴读选择和独立段评预设。未单独选角色时跟随当前伴读；多选时按角色分别去重、共享每日额度并预留后续角色份额。`AnnotationPromptSettingsScreen` 复用预设编辑器，支持多条开关、编辑和四种注入位置；`ProactiveAnnotationPrompts` 提供可修改的内置口吻与划线风格，原文定位及输出格式契约独立保留。
- 主动段评通过 `ModelRole.PROACTIVE_ANNOTATION` 分配，未配置时只回落 `CHEAP`，已配置模型的错误不会暗中切换模型。每章“不限制”按全部候选段落生成，没有固定 10 条限制；`ProactiveAnnotationParagraphs.generationBuckets` 按约 800 原文字符的区间轮流取段，先覆盖全章再补各区间剩余段落，避免未完成的预生成只覆盖前几页。切片续跑固定本轮起点与分组，去重或翻页不重排区间；有限条数仍按原文字符位置分布。`ProactiveAnnotationParagraphs` 拆分超长段落，保留 UTF-16 边界并限制上下文只到目标结尾；每日限额与已完成段落去重仍适用；已保存段评立即可查看。

对应回归测试位于 `feature/reader/*Spread*Test`、`engine/ReaderContentControllerTest`、`CompanionChat*Test`、`ReaderPaneRetentionTest`、`settings/ProactiveAnnotationSettingsScreenTest`、`core/retrieval/AnnotationVisibilityTest`、`ai/companion/`、`ui/WindowLayoutTest` 和数据库迁移/可见水位的 Android 测试中。


### 阅读外观、人物资料与紧凑词云

- `feature/reader/ModernPageCurl` / `ModernPageCurlShader` 是「现代仿真」的 GPU 圆柱反投影着色器。单页的折线由 `ModernCurlFold` 每帧在 CPU 上解出（按页面高度归一化：外缘抓取点按按下位置的纵坐标取锚，折到虚拟指尖上，折线是两者的中垂线、再让出半圈圆柱；斜度受书脊约束，落页前逐渐摆正），着色器只做逐像素的 `asin` 分段反投影、选择正反面，并画出纸边投影（翻起的页缘压在当前页上的细阴影）与掀开处的投影（随触达距离加宽、落页前淡出）。双页仍在着色器内按深度选择曲面，两种模式都按材质截面扣除书脊位移，避免纸张在装订端自由旋转；支持背面透字、纸边高光及双页书脊效果。Android 13 以下回落原有仿真。
- `ModernCurlMotion` 驱动单页 `PageTurnDriver.MODERN_CURL`：虚拟指尖 `curlX/curlY` 以一阶跟随（约 40 ms）追赶真实指尖，所以按在页面中部时页缘会顺滑地移到手指下而不是跳过去，按在上下角附近会斜着掀起页角；向后翻时上一页从左侧整张翻回来。松手判定：任何向前的轻扫都翻页，明确向后甩则取消，停住松手时需从按下处拖过约四分之一页宽。松手、点按翻页和接住回弹都从纸张当前位置走同一条三次曲线（起速衔接手指速度、到终点停稳，整幅最长约 1/3 秒）；新触摸立即完成已确认的上一轮翻页，让连续快速轻扫各自翻过一页，取消回弹则可接住继续拖。双页仍按铰链的相对行程与原有落页动画。
- `core/datastore/ReaderTitleStyle` 保存章首字体、色彩、边框、图片资产、对齐与 CSS 声明。右上角菜单中的 `ReaderTitleStyleSheet` 管理多套命名样式，支持选择、复制、编辑、重命名与删除；阅读主题通过稳定 ID 记住选择，删除时保留最新外观快照。`ReaderTitleStyleEditor` 的预览与实际阅读复用 `ChapterTypesetter` / `PageBitmapRenderer`，排版缓存包含标题样式与图片引用；`TextLine.isReaderTitle` 区分阅读器标题和 EPUB 出版商标题。
- `core/datastore/ReaderKeyBindings` 保存物理按键与上一页/下一页的对应关系，兼容原音量键开关；已知按键使用 Android 键码和组合修饰键，未命名按键保留扫描码，不依赖会随重连变化的设备 ID。翻页方式与阅读交互中的 `ReaderPhysicalKeysSheet` 提供音量方向预设、多个按键录制、冲突确认和移除。录制直接处理对话框窗口的按键事件，松开后才允许保存；`ReaderHardwareKeyDispatcher` 仅在阅读正文时接管匹配按键，同时消费按下/抬起并忽略长按重复。对应回归为 `ReaderKeyBindingsTest`、`ReaderHardwareKeysTest` 和真实父弹层中的 `ReaderPhysicalKeysVisualTest`；`ReaderInputDeviceTest` 验证真机窗口录制与连续滑动。
- 标题和高亮规则编辑器复用 `ReaderStyleEditorComponents`，提供实时预览、独立滚动页签和常驻保存操作；高亮支持输入示例文字验证匹配。字号、留白与装饰控件会同步对应 CSS 属性，避免旧预设覆盖刚调整的值。
- 标题 CSS 支持颜色、字体族/字重、字号、对齐、上下/水平间距、内边距、边框颜色/粗细/圆角；尺寸使用正文 `em`。高亮 CSS 支持颜色、背景色、字体族、粗斜体与文字装饰。编辑器显示不支持或无效的声明，图片和字体复用导入资产库。
- `ReaderSettingsRepository.updateBoundTypography` 原子更新当前书籍与日夜槽选中的主题；`resolveThemeSlot` / `resolveForBook` 应用完整字体、排版与标题快照。内置主题通过 `ReaderThemeTypographyCodec` 分别记住排版，旧设置首次使用时保留当前排版。
- `ChapterKnowledgeAgent` 输出带独立引文的别名、年龄、性别、身份与人物关系；`ChapterKnowledgeCodec` 验证引文及双方称呼，`BookCharactersCodec` 仅合并明确且唯一归属的别名，兼容旧资料。人物页的展示见上文「人物档案界面」。
- 书架网格和列表仅搜索栏吸顶常驻；问候语与右上角视图/导入操作随内容滚动，书籍区保留分组、筛选标签、拖拽与排序。
- `StatsCloudLayout` / `PackedStatsCloud` 在后台完成确定性的字形遮罩碰撞与螺旋排布。按阅读时长映射字号，部分标签旋转，小词填补空隙；放不下的条目提供展开列表。算法思路参考 [d3-cloud 官方说明](https://github.com/jasondavies/d3-cloud)，使用独立 Kotlin 实现。
- 回归入口：`ReaderStyleCssTest`、`CharacterProfileTest`、`ReaderEnhancementsVisualTest`、`ReaderStyleEditorVisualTest`、`BookshelfPinnedSearchTest` 与 `ReaderKnowledgeVisualTest`（真实导航父弹层）。`ModernCurlMotionTest`、`ModernCurlFoldTest` 与 `ReaderPageTouchTest` 覆盖指尖跟随、四分之一页宽与轻扫翻页、反向甩动取消、接住回弹、点按翻页、折线几何与投影淡出；`ModernPageCurlGpuTest` 在 Android 13 及以上真机的硬件画布验证卷页像素、斜向手势、掀开处投影、双页与首尾状态；AGSL 不支持软件画布。

### 阅读样式 CSS 绘制

- `core/datastore/ReaderStylePaint.kt`：解析线性渐变的角度、色标和透明度；CSS 是唯一持久化来源。
- `ReaderStyleCss` 支持 `background` / `background-image: linear-gradient(...)`、`background-clip: text`（含 WebKit 前缀）、`color: linear-gradient(...)` 扩展写法，以及 `url("asset:图片ID")` 图片库背景。编辑器可导入并插入引用。
- `ReaderPaintSpan` 保留完整匹配标识；TXT、EPUB 两套布局及英语叠加样式均保留它。渲染按当前页同一匹配的包围框计算渐变，换行共享坐标，不逐字重置；跨页按各页可见区域铺设，背景图片居中裁切铺满。
- `ReaderGradientShader.kt` / `PageBitmapRenderer` 原生绘制渐变和图片，不改变文字宽度、分页、选区或批注定位。标题文字与标题背景可分别设置渐变。EPUB 出版方已指定的颜色和背景仍优先。
- 语法范围为声明列表；不支持选择器、动画、径向渐变、多层背景或远程 / 内嵌 SVG URL。用法见 [阅读样式 CSS](READER_STYLE_CSS.md)。

- 分享模板：core/datastore/ReviewShareTemplate 定义持久化样式与 CSS 校验，ReaderSettingsRepository 保存独立模板列表；feature/review/ReviewTemplateEditor 提供保存、编辑和另存，ReviewTemplateRendering 与 ReviewExporter 共用预览及导出排版，复用字体库、图片库与语法高亮。ReviewTypographyTest / ReviewTemplateRenderTest 覆盖重载、更新、删除、CSS 和原生图片输出。

### 本地音色与有声书选角

- core/speech/SystemTtsSpeaker.kt 枚举 Android TTS 引擎及公开音色，查询独立于播放。SystemTtsVoice.kt 将角色音色绑定到引擎包名与 Voice 名称，设置、试听、听书共用解析。
- `ai/client/GeminiTtsClient` 使用 Gemini 原生 Interactions TTS，正文与风格元数据分开，解析 REST `steps` 音频并保存 WAV；音色库支持多语言预设与分页读取在线音色，听书、选区和伴读复用现有合成缓存。新安装默认系统 TTS，已有引擎选择保留。
- `core/speech/TtsProviderProfiles` 为云端服务和本地 TTS 应用分别保存配置。设置页切换或返回前提交输入草稿，云端密钥按服务商加密存储，旧共享密钥只迁移到切换前的服务商；切回后恢复地址、模型、音色与参数。
- `ai/client/MimoTtsClient` 通过小米 MiMo 聊天接口合成语音，正文放在 assistant 消息、风格放在 user 消息，解析 Base64 音频；`FishTtsClient` 使用 Fish Studio / Fish Audio 的 `/tts`、模型请求头与 `reference_id`，适配 S1/S2 情绪标记及语速、音量。两者由 `AiClientFactory` 接入统一朗读出口，`TtsHttpTransport` 负责可取消请求、响应大小限制及错误处理。协议参考：[MiMo](https://mimo.mi.com/docs/en-US/api/audio/tts)、[Fish Audio](https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech)。
- `ai/client/ElevenLabsTtsClient` 使用原生 `/text-to-speech/{voice_id}`、`xi-api-key` 与 `model_id`，支持 MP3/WAV/Opus、账号音色及克隆音色 ID、自定义中转地址和 0.7–1.2 语速；`eleven_v3` 不发送语速。设置页按服务商保存配置与加密密钥，试听、选文和听书复用统一出口及缓存。音量、音调与自由文本风格指令不传入该协议。参考：[ElevenLabs TTS](https://elevenlabs.io/docs/api-reference/text-to-speech/convert)。
- `feature/settings/VoiceDesignScreen` / `VoiceDesignViewModel` 提供音色库中的设计入口，AI 对话与手动设定共享候选试听。`ai/media/VoiceDesignAssistant` 复用 `AgentLoop.runDetached`，按需调用角色查询、声音设定、生成和获取试听工具；每轮最多创建一个候选，音色库写入只由用户确认触发。`GeminiTtsClient` 接入 Voice design 的创建、试听读取与草稿清理；已保存音色不参与草稿清理，试听由 `VoiceDesignPreviewStore` 缓存。
- ui/components/VoiceChoiceDialog.kt 提供可搜索音色选择；feature/listen/AudiobookRoleViewModel.kt 提供显式 AI 选角与手动调整。
- ai/audiobook/AudiobookRoleExtractor.kt 初次抽样包含当前章，精排前逐章补全角色；AudiobookScriptAgent.kt 使用稳定原文坐标，低置信度对白暂用旁白并提示校对；AudiobookAttributionPlanner.kt 限制单批对白数量。
- core/library/AudiobookRepository.kt 增量合并角色并保留分镜；修改选角及批量改引擎均清理受影响音频。

本地样书回归可通过 MOREAD_EPUB_FIXTURES 配置外部 EPUB，并运行 AudiobookEpubFixtureTest；报告区分候选对白、规则显式命中与待确认内容，不将规则命中率视作 AI 准确率。

### 插图一致性与章节队列

- `ai/media/ImageRecipe` 定义本书画风、按章节生效的人物形象、镜头及配方快照。`RecipeAssembler` 固定拼入外貌；`planReferences` 按主角、画风、其他人物安排参考图，并返回降级与额外费用信息。
- `core/library/ImageConsistencyRepository` 只读取已读范围内的人物与原文依据，编排镜头、导入参考图并缓存 NovelAI Vibe。图片沿用图片库 ID，人物形象使用稳定 identity；重新提取人物不会覆盖手动定妆。Room 32 保存画风、形象版本、插图配方及队列检查点；完整备份携带图片和 Vibe 编码。
- `feature/illustration/ImageStudio` 从选段、伴读、人物页及书籍插图廊进入；页面复用 `MoReadSecondaryPage`，生成面板是叠在当前页之上的 `MoReadBottomSheet`（`StudioState.basePage` / `panelReturn`），从画廊、画风或形象页打开时关闭后回到原页，而不是退出工作室。候选区只放「同配方重来 / 调整」，导出与「设为定妆、画风参考、封面、伴读头像、删除」在面板顶栏；画廊为两列缩略图，长按删除。
- 画风页（`StylePage.kt`）把内置预设与「我的画风」放在同一种卡片网格里，最后一张虚线卡「新建画风」提供「从空白开始 / 以当前画风为基础」；自定义画风的编辑、删除在卡片的更多菜单。底栏在草稿未改动时显示「正在使用」且不可点。编辑页（`StyleEditorPage.kt`）填写名称、描述、参考图与折叠高级参数；试生成使用草稿快照，返回保留草稿，不提前写入本书画风。`image_style_templates` 跨书保存描述、标签、负面词、seed 与参考图快照，模板更改不会改变已应用的书籍。删书和图片库清理保留模板引用的图片，模板与参考图一起纳入备份。
- `ai/media/IllustrationQueue` 在用户确认每章镜头与张数后顺序生成，逐张保存结果；暂停完成当前请求，重启后只继续待处理项。中断且结果未知的请求由用户手动重试；生成前重新验证原文及已读范围。
- `NovelAiImageClient` 使用多角色 caption、seed、Precise Reference 与编码后的 Vibe。V4.5 的人物参考与 Vibe 互斥，多人场景只给主角一张人物参考；V5 不承诺未经文档确认的 Precise Reference。`OpenAiMediaClient` 在有参考图时调用 multipart `/images/edits`，对话生图发送多模态 image parts；gpt-image-2 / 2.5 不发送 `input_fidelity`。
- 接口依据：[NovelAI OpenAPI](https://image.novelai.net/docs/index.html)、[Precise Reference](https://docs.novelai.net/en/image/precisereference/)、[Vibe Transfer](https://docs.novelai.net/en/image/vibetransfer/)、[OpenAI 图像生成](https://developers.openai.com/api/docs/guides/image-generation)。参考图提高一致性，但不保证每次输出完全相同。
- 回归入口：`ImageRecipeTest`、`ImageReferenceProtocolTest`、`ImageConsistencyStorageTest`、`IllustrationQueueTest` 与 `ImageStudioVisualTest`；覆盖版本选择、配方快照、HTTP 请求、迁移、队列续跑和真实父弹层的滚动边界。
