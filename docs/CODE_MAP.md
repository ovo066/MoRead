# MoRead 代码地图

本文面向源码阅读者和贡献者，介绍当前仓库的模块边界、主要调用链和回归测试入口。功能介绍与构建前提见 [README（English）](../README.md) / [中文版](../README.zh-CN.md)，依赖许可见 [THIRD_PARTY_NOTICES](../THIRD_PARTY_NOTICES.md)。

下文 Kotlin 路径默认以 `app/src/main/java/com/mozhi/reader/` 为根。文件名是定位入口，不代表该功能只需要修改这一个文件；调整调用链时，请同时检查对应的持久化、UI 和测试。

## 1. 仓库结构

| 路径 | 职责 |
| --- | --- |
| [`app/src/main`](../app/src/main) | Android 应用、资源与清单 |
| [`app/src/main/res`](../app/src/main/res) | 默认中文与英文字符串资源；迁移约定见 [本地化指南](LOCALIZATION.md) |
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
- `ui/theme/AppTheme` 的 `AppearanceSettings` 将配色方案、色彩搭配、质感、导航与形状密度分别保存。`ColorSchemes` 提供三套莫兰迪日夜色板、原版灰阶及 Android 12+ 壁纸取色；`MoReadTheme` 统一装配，`Metrics` 提供随密度变化的尺寸。选择方案会套用推荐质感/形状并恢复方案主色；选择「原版」还恢复悬浮舱与默认色彩搭配，明暗和字体保留。
- `ui/components/MoReadSurfaces` 统一处理玻璃与不透明扁平表面，`MoReadControls` 提供分段、滑条与胶囊按钮；`SemanticPalettes` 是内置标签与角色配色的公共入口。手机导航支持悬浮舱与通栏，平板保持侧栏；阅读目的地通过 `ReaderAppearanceScope` 保留纸色及原有浮层尺寸。
- `core/di/`：应用协程、网络、数据库和向量存储等依赖注入。

## 3. 数据与设置

| 入口 | 职责与修改注意事项 |
| --- | --- |
| [`core/database/MoReadDatabase.kt`](../app/src/main/java/com/mozhi/reader/core/database/MoReadDatabase.kt) | Room 实体/DAO 集合与版本常量；当前 schema 为 **30** |
| [`core/database/DatabaseMigrations.kt`](../app/src/main/java/com/mozhi/reader/core/database/DatabaseMigrations.kt) | 数据库迁移；新增迁移后在 `core/di/StorageModule.kt` 注册，并提交导出的 schema |
| `core/database/entity/`、`core/database/dao/` | 书籍、章节、合集、标签、批注、对话、角色与有声书的数据定义和查询 |
| [`core/datastore/ReaderSettingsRepository.kt`](../app/src/main/java/com/mozhi/reader/core/datastore/ReaderSettingsRepository.kt) | 阅读排版、主题、书架顺序及按书保存的设置 |
| `core/library/` | 书籍、正文、布局、附件、笔记和书架组织的存储访问层 |
| [`core/security/ApiKeyStore.kt`](../app/src/main/java/com/mozhi/reader/core/security/ApiKeyStore.kt) | API Key 的加密存储入口；不要把凭据写入普通设置或测试样例 |
| [`core/library/BookReadProgress.kt`](../app/src/main/java/com/mozhi/reader/core/library/BookReadProgress.kt) | 统一的阅读进度计算，避免各页面自行换算百分比 |

Room 结构、迁移、备份版本校验和实际数据文件要保持一致。向量模型的修改还需检查 ObjectBox 模型文件及索引恢复行为。

- `core/storage/StorageRepository` / `StorageFiles` 统一扫描本地数据并按实体归属统计。`feature/settings/DataSettingsScreen` / `DataSettingsViewModel` 提供按书管理、语音/索引清理及保留记录的管理入口。全局占用按文件计数，不重复累计共享资源；安全清理仅处理过期导出副本、旧版本安装包和可重新提取的封面。无关联资源属于需单独确认的深度清理。
- `core/library/BookRemovalCoordinator` 统一书架单本/批量与存储页的移除策略。`books.removedAt` 非零表示正文已移除、个人记录保留；书架及正文补齐不包含这些书，统计使用包含保留记录的查询。只有明确选择永久删除才触发个人记录级联删除，同时清理新语音目录、附件、插图及相关记忆。原始外部文件与共享图片库素材不属于删书范围。
- 书库伴读话题可能包含多本书，独立于单书删除保留，用户在话题历史中单独确认删除。书籍移除前也检查相关书库话题是否正在生成；正文删除、修改或已读范围缩小后，旧话题只保留历史，不允许继续发送旧上下文或直接沿用引用。
- `ReaderImageAsset.purpose` 区分背景、封面及未分类素材；旧图片无损保留，分类变更不改变使用中的引用。删除检查同时覆盖已移除正文但仍保留封面的书。
- `AppearanceSettings.appFont` 与阅读字体独立，通过 `ui/theme/AppFont` 异步校验并应用到界面 Typography；缺失或损坏的字体回退默认。字体库导入本身不改变阅读字体，阅读页内导入保持原有选择行为。
- `core/media/LocalImageExporter` 与 `ui/components/ImageExportActions` 共用本地图片导出，覆盖书籍插图廊、阅读页和伴读预览。Android 10+ 使用 MediaStore pending 行原子发布到相册，失败移除未完成行；文件导出使用系统文档选择器，不申请广泛相册权限。

## 4. 导入、书架与合集

### 导入链路

`feature/importer/` 的选择/预览页面与 `ImportCoordinator` 负责用户流程；`core/importer/BookImportGateway` 提供导入入口，后台批量工作通过 WorkManager 执行。

- TXT：`TextEncodingDetector` → `TxtChapterSplitter` / `TxtTocRuleLoader` → 正文存储，不生成 EPUB，`epubPath` 为空；`AiChapterRuleAgent` 提供可选的 AI 分章规则辅助。重新分章按章节索引严格读取正文，还原独立保存的章名；旧生成副本只在全部正文验证通过后移除。
- EPUB：`EpubPackageInspector`、`EpubMetadataResolver`、`EpubTextExtractor`、`EpubTocMapper` 与 `EpubLayoutDocumentParser` 分别处理包、元信息、文本、目录与布局文档。
- `core/readium/EpubUriContainer` 为带冒号等特殊资源名的 EPUB 提供 URI 兼容视图：恢复归档条目，并转义清单、目录与文档中的本地引用；原始书包、资源名和正文保持不变，外部 URL 不改写。
- EPUB 导入复用：`EpubLegacyStyleBridge.newDocumentScope` 在章节间共享不可变 CSS 规则索引，但元素样式缓存按文档隔离；`EpubArchiveImageReader` 直接将图片解析为归档条目，同一导入复用 ZIP 索引与别名映射，结束时关闭。
- 正文与资源：`core/library/BookTextStore`、`BookTextWriter`、`BookLayoutStore`、`BookMediaStore`。EPUB 导入只保存正文、包/章节索引及字体；`BookLayoutCache` 首次阅读章节时解析 DOM，并以 gzip 存入应用缓存，每本上限 8 MiB、全局 64 MiB。淘汰不影响原书，解析器版本变化只丢弃缓存；读取时核对规范正文，不能因重新解析而移动字符锚点。旧布局在归档可用时转换为单个索引，保留无归档旧库的兼容读法。
- `BookTextArchive` 将正文按 64 KiB 独立压缩，章节读取只解压相交块。章节字节坐标始终指向解压后的 UTF-8 流，不能用压缩文件大小代替正文长度；正文修订哈希也基于解压内容。新导入直接压缩，旧书由正文维护任务或存储页手动压缩，完整校验后原子替换；EPUB 原书继续保留用于重建。
- `EpubArchiveAsset` / `EpubArchivePool` 将插图、SVG 与 CSS 背景直接交给渲染器按需读取，ZIP 句柄按阅读器生命周期复用，位图按像素预算缓存并降采样。`BookMediaStore` 只为内联数据等例外保存副本，旧副本与归档逐字节校验后才删除。内嵌 CSS 随当前章节解析并保持级联顺序；布局 JSON 省略默认值，但 schema 字段显式必填。
- 局域网传书：`core/importer/lan/` 的 HTTP 服务、请求解析与上传命名，页面入口为 `feature/importer/LanTransferScreen`。

### 书架与合集

- [`feature/bookshelf/BookshelfViewModel.kt`](../app/src/main/java/com/mozhi/reader/feature/bookshelf/BookshelfViewModel.kt)：书籍观察、筛选、选择及书架操作。
- [`feature/bookshelf/BookCollectionModels.kt`](../app/src/main/java/com/mozhi/reader/feature/bookshelf/BookCollectionModels.kt)：书籍/合集展示模型、可见成员与全体成员、排序合并规则。
- [`feature/bookshelf/ShelfCollectionDrag.kt`](../app/src/main/java/com/mozhi/reader/feature/bookshelf/ShelfCollectionDrag.kt)：拖拽目标与状态转换；书籍进入合集卡片任意位置时优先加入合集，卡片间隙仍可排序。`previewShelfEntries` 不对合集目标做实时换位预览，避免目标躲开触点；`BookCollectionComponents.kt` 和 `BookshelfScreen.kt` 负责 UI 接线。
- [`feature/bookshelf/BookLongPressOverlay.kt`](../app/src/main/java/com/mozhi/reader/feature/bookshelf/BookLongPressOverlay.kt)：独立 Popup 窗口显示在根导航之上，而不是依赖局部 `zIndex`。`BookMenuPlacement.kt` 计算上下左右布局及有限重叠回退；短窗口允许覆盖部分封面和 dock，菜单限高可滚动，但仍避让系统安全区并保持触达尺寸。
- [`core/library/ShelfOrganizationRepository.kt`](../app/src/main/java/com/mozhi/reader/core/library/ShelfOrganizationRepository.kt) 与 `core/database/dao/ShelfOrganizationDao.kt`：合集、分组、标签和成员顺序的持久化。

**修改重点：**筛选后的全选、删除和拖拽应使用正确的可见成员集合；不能把隐藏书籍误算为用户已选择。拖拽结束时应按最新列表提交顺序，并保持置顶边界及未显示成员的相对顺序。合集与书架分组是不同的数据概念。

## 5. 阅读器、EPUB 排版与繁简转换

### 阅读与绘制链路

`ReaderScreen` / `ReaderViewModel` → `engine/ReaderContentController` → `ChapterTypesetter` → 页面模型 → `render/PageBitmapRenderer` → `ReaderPane` / `ReaderScrollPane`。

- [`feature/reader/engine/`](../app/src/main/java/com/mozhi/reader/feature/reader/engine)：文本测量、分页、选区、批注几何与正文控制。
- [`core/epub/`](../app/src/main/java/com/mozhi/reader/core/epub)：CSS 解析、级联、DOM 适配与样式解析。
- `engine/EpubBoxLayoutBackend` 与 `engine/epub/`：EPUB 盒树、行内/块布局、分页和排版后端。`EpubLayoutCapability` 记录不支持的布局能力并选择回退（识别竖排不等于已实现竖排）；`EpubDomFragmentLocator` 用 DOM fragment id 定位目录锚点。
- 用户排版在 EPUB 里必须可调。首行缩进统一走 `resolveFirstLineIndent`：原书优先模式照原书声明排，智能模式按用户设置与出厂值的比例缩放原书缩进（保留原书给引文等段落的相对差别），原书声明为 0 时直接用用户设置，接管模式完全采用用户设置，悬挂负缩进在所有模式保留。`text-indent` 是继承属性，祖先上的一条声明会让每个段落都算已声明，不能据此关闭用户设置。字间距按簇补偿：平台按 run 在字符之间分摊字间距，逐簇测量会全部丢失，两个 EPUB 后端都要加上 `TextMeasure.clusterLetterSpacing`。这两项只有真实 Paint 才测得出来，回归见 `engine/epub/EpubUserTypographyTest`。
- `TextPage.fullPageArtwork`、`ImmersiveArtworkFit` 与 `PageBitmapRenderer`：大幅独立插画的整页展示与背景绘制。明确限定尺寸的小图不能被放大全屏；背景、分页和绘制缓存必须一致。
- 背景插画和空的定高盒参与分页；百分比高度基于确定的父容器高度解析，`background-size` 的 `auto` 维度在绘制时按图像比例计算。普通正文的根画布与无装饰的整章容器跟随阅读纸色，插画、边框和内容面板保留；默认不强制段首段尾各留两行，显式 `orphans` / `widows` 仍生效。
- `PageTurnDriver`、`PageTurnCompositor`、`PageFoldGeometry`、`PageBitmapWindow`：翻页驱动、合成、折页几何与位图窗口。
- EPUB 图片长按：`engine/ReaderPageImage` 按实际绘制矩形命中图片并保留每次出现的字符锚点；`ReaderPane` / `ReaderScrollPane` 映射单页、双页和滚动坐标。`ReaderEpubImageDialog` / `ReaderImageTransform` 提供全屏缩放、拖动、旋转、复位及定位原文，打开或关闭不改变阅读位置。
- `core/media/EpubImageFiles` 按需缓存选中的本地/归档图片，预览限制像素预算；未旋转栅格图导出保留原始字节，旋转或 SVG 导出为有像素上限的 PNG 副本，并复用 `ImageExportActions`。原 EPUB 不会被修改。
- `PullBookmarkGesture` / `BookmarkPullIndicator`：方向锁定、阈值与松手提示；只在 `ReaderPane` 的翻页触摸链路启用，不接入 `ReaderScrollPane`。反向回拉、多指、选区拖动与取消事件不能添加书签；`ReaderViewModel` 先捕获当前页，再在互斥区内映射到原文坐标并检查重复，普通书签按钮仍可取消书签。
- `ReaderScreenState` 管理短期弹层状态、排版卡片切换和统一的翻页/自动阅读输入规则；子对话框关闭后保留父弹层。`ReaderUiState` 是阅读展示模型，`ReaderObservedState` 按书籍元数据、批注和偏好组合仓库数据流；通知等副作用由 ViewModel 单独管理。
- `ReaderChrome`、`ReaderNavigationSheets`：阅读工具栏与导航弹层。排版入口为 `ReaderTypographySheet` / `ReaderTypographyMainPanel`；字体、主题背景、阅读交互、语法高亮分别在 `ReaderTypographyFontPage`、`ReaderTypographyThemePage`、`ReaderTypographyBehaviorPages`、`ReaderSyntaxHighlightEditor`。各子页仅接收 `ReaderTypographyActions` 中对应职责的回调组；`ReaderTypographyCard` 负责实时排版预览，`ReaderTypographyControls` / `ReaderTypographyStepper` 提供共享控件。
- `AutoReadSession`、`AutoReadPaging` 与 `ReaderAutoReadControls`：更多菜单中的自动阅读入口，支持匀速滚动、定时翻页和可选固定屏幕参考线。运行态不持久化；触摸、菜单、后台、语音播放与显式导航使会话暂停，必须手动继续。分页复用现有代次校验和位图提交，滚动复用章节条带与原文进度，计时器不直接写已读水位。参考线不生成批注，也不表示逐句跟读。
- `ReaderTableOfContents`、`BookTextSearch`、`ReaderSearchViewModel`：目录与书内搜索。
- `ReaderKnowledgeSheet` / `ReaderKnowledgeViewModel`：目录旁的“大纲”和“人物”页签。大纲展示连贯梗概，原文依据单独展开；人物页有“读到此处 / 全书”两档范围，支持手动提取、断点继续和查找。打开页面不调用模型，生成前显示范围、字数、模型与调用上限；“读到此处”只发送已读正文，末章截到当前进度，“全书”确认明确包含未读内容。
- `ui/components/NavigationSheet` 固定内部内容视口并关闭父层拖动，提供明确关闭入口；ModalBottomSheet 外层保持完整窗口约束，使底边正确贴住窗口，不能在其 modifier 上限制比例高度。内部页面填满视口，列表使用自己的 `blockSheetDrag(state)`。三个页签用独立的可保存状态保留滚动位置，进度栏尺寸固定，同书刷新保留旧快照。新增导航页必须在真实弹层中验证底部贴边、边界滑动、切页与刷新，不能只检查独立列表截图。
- `ai/knowledge/ChapterKnowledgeRepository` / `ChapterKnowledgeAgent`：使用批量任务模型和独立 `AgentLoop`，每段最多两轮提交与纠错，不创建聊天记录。每章最多处理 60000 个 UTF-16 字符，按最多 10000 字的段落整理；长章额外合成为一篇连贯梗概。全部引文核对通过且来源仍有效后才原子替换；取消、失败、正文改版或水位缩小保留旧结果。
- `KnowledgeGenerationRunner` 使用应用级作用域，每章任务和全书人物任务独立开始、停止和报错；`KnowledgeRequestLimiter` 公平限制为两个并发模型请求。离开页面继续运行，进程退出后未完成的章节需重生成，人物任务可从持久化断点继续。
- `BookCharactersRepository` / `BookCharactersCodec`：按章读取正文，逐段提取人物；范围可收到阅读进度（`ReadingScope.uptoProgress`，末章只送已读部分）。分段结果绑定原文修订、模型、提示版本、分段范围和正文哈希，作为持久缓存跨次提取复用，复用时重新逐字核对，读过更多章节后“更新到当前进度”只为新正文计费；仅在全部章节完成后替换已发布人物资料，并把分段归到已发布的那一代，未完成的进度才算断点。按姓名精确合并，保留各人的初始介绍和后续事实，不猜测别名，聚合时不同时持有全书原文。
- `ChapterKnowledgeEntity` / `ChapterKnowledgeDao`：`chapter_knowledge` 保存章节来源、已读终点、模型与提示版本；兼容旧版证据式内容。`BookCharacterGuideEntity` / `BookCharacterPartEntity` 与 `BookCharacterDao` 分别保存人物资料（含本次是否只扫到阅读进度）和分段缓存，独立于有声书角色。章节跳转验证已读范围，人物跳转验证整书修订和逐字引文；永久删书均由外键级联清理。

- `PageAnnotations` 将段评画成原文范围内的划线与末端小点，整个划线文字区域可点击；段评不生成 `InlineMarkerReservation`，新增或删除评论不改变字距、行高和分页。插图按钮仍使用独立占位。`PageWindowRefreshQueue` 合并翻页期间的更新，在翻页提交或取消后按最终窗口刷新，点击几何使用实际显示的批注版本。`ReaderPane.readTrackingEnabled` 在入场完成或恢复前台时触发实际绘制，及时确认已读范围，让符合范围的 AI 段评原地出现。回归参考 `ReaderAnnotationStabilityTest`。
- `engine/LineBreakRules` 供两条 EPUB 排版路径共用中文行首行尾禁则：在已测量宽度内回退到合法断点，闭合标点随前文移到下一行，再按设置均排；不将连续标点追加到正文右边距外。TXT 和 EPUB 均排时，成对符号、开闭标点及西文单词内部不再分配额外字距。极窄容器没有合法断点时保留测量内的硬换行。回归及原生绘制样张参考 `ReaderPunctuationLayoutTest`。

调整排版或高亮时，要一起检查分页缓存、坐标转换、选区几何和绘制，不能只改屏幕上的字符串。

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
| 陪伴足迹 | `feature/companion/CompanionStatistics`、`CompanionStatsScreen` / `CompanionStatsViewModel`；`ChatDao` 只向统计层返回消息元数据和字数，不加载正文 |
| 工具调用 | `ai/agent/AgentLoop`、`AgentToolExecutor`、`ToolResult`、`CompanionToolRouter`、`ReaderToolset`、`ReaderToolsetReadback` |
| 阅读范围与检索 | `core/retrieval/ReadingScope`、`ReadableCorpus`（位于 `ai/agent/`）、`RetrievalPipeline` 与 `BookGrep` |
| 书籍向量 | `ai/embedding/` 负责章节切分后的嵌入、进度与增量续跑；`core/vector/` 提供切分和向量相关数据 |
| 长期记忆 | `ai/memory/` 的滚动总结、记忆固化与仓库；角色配置及导入位于 `ai/persona/` |
| 主动行为 | `core/datastore/CompanionAutonomySettings`、`ProactiveAnnotationLimits` / `Quota` / `ProactiveAnnotationContextSettings` 与 `ai/companion/ProactiveAnnotationService` / `ProactiveAnnotationContext` |
| 媒体与听书 | `ai/media/`、`ai/listen/`、`ai/audiobook/`；缓存、TTS 设置与系统引擎位于 `core/speech/` |

**边界：**AI 读取书籍内容必须遵守阅读进度范围；新增工具应同时检查路由、权限和纯函数测试。会调用外部服务的自动行为应有明确设置和费用提示。对话呈现优先在 `CompanionMessageParts`、`CompanionChatList`、`CompanionProcessCard`、`CompanionComposer` 各自职责内修改，不要把解析规则堆入 Composable。

- 书库伴读从伴读主页的悬浮按钮进入，直接使用阅读页的头像、顶栏、气泡、输入区和滚动规则。用户可以不选书直接聊天，最多 4 本“重点讨论”只是偏好，不是访问白名单。模型先用 `find_books` 查本地书名、作者、标签、分组，再按需读取单书材料；不会自动扫描并上传全书库正文。
- `LibraryConversationSources` 在实际读取时登记来源，每轮最多实际查阅 4 本书，每个话题最多关联 32 本。重点书籍不占用查阅预算，选满重点仍可按需查其他书。`LibraryBookScopes` 将独立已读水位和正文修订指纹写入 `conversations.bookScopesJson`；新一轮可随真实阅读进度扩大范围，单轮内固定。正文移除、修订变化或水位缩小时拒绝重发旧上下文。`AgentLoop.validateContext` 每次模型轮次前后校验，工具读取也前后核验；本地正文读取与删除互斥，网络嵌入请求不持有文件写入锁。
- `AgentTool.execute` 返回 `ToolResult.Success` / `Failure`，状态、部分结果标记和错误码与模型正文分开；禁止通过提示文案判断成败。普通与独立 Agent 循环共用 `AgentToolExecutor`，非法 JSON 参数不会执行工具，取消异常向上传播。执行异常和降级诊断统一记录调用标识、错误码、异常类型与栈位置，正文、参数和异常消息不进入诊断日志；历史工具消息继续保留原有内容格式。
- `LibraryCompanionToolset` 提供按 `book_id` 路由的目录、已有笔记、区分来源的划线、章节读取及 `search_book` / `grep_book`。指定正文单次最多 5 章 / 6000 字，模型循环最多 4 轮。已有向量索引可用于查询；缺失时走本地 BM25，不隐式创建索引。不注入没有跨书来源约束的长期记忆、画像，也不提供联网、生图、语音、删书或改正文工具。用户手写的无锚笔记属于用户提供材料，不当作已验证原文。
- `LibraryOrganizationTool` 只能生成标签及一级分组的待确认方案，不能执行修改。`LibraryOrganizationCoordinator` 由用户界面确认后，在同一个 Room 事务中校验书籍、标签名称/关联和分组快照，应用变更并更新方案状态；元数据已变化则拒绝覆盖。取消不写书架，重复确认不重复应用，失败整体回滚。方案保存在工具消息中，删除话题不会撤销已确认的整理。
- `LibraryCitationParser` / `LibraryCitationVerifier` 仅识别显式书籍编号、章节号和逐字引文；点击来源时才读取对应章，在正确书籍的已读范围内匹配，再携带原文 UTF-16 锚点进入阅读页。不存在或过期的引用不会跳转。书库消息复用 `CompanionChatScroll` 的稳定 key、手势优先和贴底逻辑。
- 书库气泡支持复制、编辑、删除、分支与重新生成，失败或停止后可重试。历史修改由 `LibraryCompanionRunner` 与发送共用互斥区；重生成前核对来源和角色，复用原用户消息，并清掉旧生成事件缓存。用户编辑截断后文，AI 编辑保留后文；分支保留工具结果与消息身份，但清空滚动摘要。已知书籍来源按保留轮次过滤，未知旧记录保守保留范围；已应用的书架整理不因聊天历史修改而撤销。
- “陪伴足迹”展示一起读过的书、阅读时长、聊天字数及交流日，支持全部 / 书内 / 书库与近 7 天 / 30 天 / 全部筛选。保留记录的已移除书籍仍计入，彻底删除书籍记录后不再计入书数和阅读时长，独立保存的跨书对话仍可保留。时长来自关联书籍在所选日期内的 `reading_daily`，不是 AI 同时在线的计时；字数由 SQLite 统计保留的双方正文，不含思考过程、工具结果和系统上限提示。有效交流要求用户在下一条用户消息前收到非空、非工具调用的回复，选段问答和段评不计。可识别分支按消息身份去重，未知旧身份只能按现存记录计。`messages.sourceBookIdsJson` 保存当前轮重点与实际查阅书籍的并集；`null` 为旧记录未知，`[]` 为明确未关联，不能用后续查到的书反填早期闲聊。界面不是模型用量或费用账单。
- `ai/agent/ChapterSearchBounds` 解析 `search_book` / `grep_book` 的章节区间，并与阅读水位求交。正文加载从实际起始章开始；Grep 游标绑定上下界，阅读进度增长不会扩大旧查询。`RetrievalRequest.firstChapterIndex` 在融合前和邻居扩展后都执行下界检查。
- `core/vector/VectorQueries` 在章节范围内不超过 512 个切片时使用精确余弦排序（限制向量复制量）；较大范围使用有界 ANN 补召回，不承诺穷举。
- `AiModelType.RERANK` / `ModelRole.RERANK` 提供独立可选重排模型；自定义供应商使用 `RerankApiClient` 调用 `/rerank` 或模型自定义路径，采用 query/documents 与 index/relevance_score 格式。`ConfiguredChunkReranker` 只重排已过滤候选的有界前缀：最多 24 段、每段 800 字、合计 12000 字、5 秒。未分配时不调用重排模型，异常、超时或不完整排名回落原融合排序；不更改向量阈值，不重建索引。书内与书库检索共用此链路，发送候选前及返回证据前再次核对正文和范围。
- `feature/bookdetail/AnnotationIndex` 明确区分全部、我的与 AI 划线，并支持进一步按 AI 角色筛选。来源以 `personaId` 是否为空为准，不按样式、颜色或是否自动生成推断；删除角色不会将其批注算作用户内容。计数只使用已通过可见性过滤的批注。
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
- `core/update/`：版本查询、安装准备与更新偏好；对应设置 UI 位于 `feature/settings/`。

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
| 本地化资源 | `LocalizationResourcesTest`：英文、默认回退、格式参数与复数 |
| 繁简转换与定位 | `ChineseTextConverterTest`、`ReaderTextAnchorTest`、`ChineseChapterPresenterTest`，特别是词组伸缩与重复文本 |
| 数据结构与恢复 | Android 下的 `MigrationTest`、`BackupArchiveManagerTest`；JVM 下的 `BackupArchivePathsTest` |
| 阅读进度与 AI 检索 | `ReadingScopeTest`、`RetrievalPipelineTest` 及对应 Agent 工具测试 |
| 工具结果与阅读交互契约 | `AgentToolExecutorTest`、`AgentLoopTest`：跨语言状态、真实工具失败、取消、降级诊断及两种循环一致性；`ReaderScreenStateTest`、`ReaderPresentationResolverTest`、`BookNavigationTest`：弹层互斥、子对话框、快照过期及编号兼容 |
| 章节检索范围与来源筛选 | `ChapterSearchRangeTest`、`GrepBookToolTest`、`AnnotationIndexTest` / `AnnotationIndexUiTest`；`RankingMetricsTest` 验证指标计算，真实语料评测为显式启用 |
| 排版与导入 | `EpubLegacyStyleBridgeTest`、`EpubArchiveImageReaderTest`、`EpubDomFragmentLocatorTest`、`EpubLayoutCapabilityTest`、`ImmersiveArtworkFitTest` 与对应引擎测试；真实样书与设备阅读回归 |
| 布局压缩兼容 | `BookLayoutStoreTest`、`GzipTextFilesTest`：旧索引、明文/gzip 混合、缺失或损坏文件、重试及新导入 |
| 按需布局与归档图片 | `BookLayoutArchiveCacheTest`、`BookMediaArchiveTest`、`EpubImportPipelineTest`：缓存淘汰/版本、正文坐标、归档 PNG/SVG 渲染、旧副本校验和缺省 schema |
| 阅读统计 | `StatsViewModelTest`、`StatsSettingsStoreTest`、`StatsScreenVisualTest`、`ReadingTimeSlicesTest`、`BookStorageAndTimeMigrationTest`：周期聚合、整月封面数据、跨周/年连续阅读区间、组件持久化、大格热力、真实日期/时间线弹层、跨午夜/夏令时及历史数据迁移 |
| 页面切换稳定性 | `NavigationMotionTest`、`NavigationStabilityTest`、`BookDetailNavigationTest`：真实 NavHost 内逐帧检查根页不缩放、阅读返回的系统栏留白与列表锚点、书籍详情标题与正文位置、宽屏视口、Dock 退场选中态和快速切页恢复 |
| 正文压缩与设置交互 | `BookTextArchiveTest`、`BookTextStoreCompressionTest`：跨块读取、内容哈希、坐标与中断保护；`AiSettingsVisualTest`：图标、参数编辑、小屏与深色界面；`AnnotationNavigationSheetTest`：真实弹层贴底、边界滑动、筛选与进度刷新 |
| 图标轮廓与存储图表 | `AiIconRenderingTest` 将 Compose 矢量渲染与原始 SVG 独立渲染逐像素比对；`StorageDistributionTest` 检查分类汇总、占比和零释放反馈，`StorageScreenVisualTest` 验证图表选择、刷新、小屏和深色界面 |
| 存储、素材与数据保留 | `StorageFilesTest`、`AssetPreferencesTest`、`LibraryRepositoryRemovalTest`（含真实 25→26→27 迁移和历史保留）、`LocalImageExporterTest`、`DataManagementUiTest`、`AppFontTest` |
| 自动阅读 | `AutoReadSessionTest`、`AutoReadSurfaceTest`、`AutoReadUiTest`；停顿不追赶、取消不提交、加载超时暂停，仍需真机检查滚动手感与生命周期 |
| 书库伴读与统计 | `LibraryCompanionToolsetTest`、`LibraryCatalogToolTest`、`LibraryConversationSourcesTest`、`LibraryCompanionRunnerTest`、`LibraryOrganizationCoordinatorTest`、`CompanionStatisticsTest` / `CompanionStatisticsDatabaseTest`、`ReadingAndCompanionVisualTest`（真实控件、合成示例，不调用外部模型） |
| 章节大纲与人物 | `ChapterKnowledgeTest`、`ChapterKnowledgeRepositoryTest`、`BookCharactersRepositoryTest`、`KnowledgeGenerationRunnerTest`、`ReaderKnowledgeViewModelTest`、`ReaderKnowledgeVisualTest`；覆盖整章合成、未读末章、断点核对、独立取消、并发限流、刷新保留和真实弹层中的边界滑动/切页/进度更新 |
| 重排与历史修改 | `RerankApiClientTest`、`ConfiguredChunkRerankerTest`、`LibraryHistoryMutationTest`；覆盖输入预算、失败回落、取消、引用完整性和分支后的上下文 |

提交改动时，请同步更新失效的地图入口；不要把本地路径、凭据、维护工作笔记或未公开的功能计划放入公共文档。

## 9. 本地化

`res/values/strings.xml` 保留默认中文，`res/values-en/strings.xml` 提供增量英文翻译。当前范围是书籍长按菜单与阅读状态、部分合集提示、书签反馈和导入进度，尚未提供完整英文界面或应用内语言选择器。Compose 使用 `stringResource` / `pluralStringResource`，瞬态阅读消息在 UI 层解析 `ReaderEvent.ShowLocalizedMessage`；不要将本地化字符串作为数据库值。资源命名、占位符、复数和验证约定见 [Localization](LOCALIZATION.md)。

## 10. 伴读稳定性与宽屏阅读边界

- `ui/WindowLayout.kt` 按真实窗口约束区分布局。窗口达到 840dp 使用侧边导航；`ShelfGridLayout.kt` 让书架和合集按可用宽度选择列数，拖拽坐标始终在同一坐标系中换算。
- `ReaderCompanionLayout.kt` 保持正文的组合位置稳定，按需在右侧嵌入同一个阅读页 ViewModel 的 `CompanionChatPane`。窗口缩窄时隐藏侧栏，但保留用户偏好和聊天会话。
- `ReaderPaneHolder` 和 `ScrollPaneHolder` 由阅读页 ViewModel 持有；进入全屏聊天只解绑 UI 回调，返回时复用分页三页位图或滚动章节条带、字体与排版。字体、视口、安全区或阅读模式变化必须重新校验排版环境，主题、批注和背景变化必须使绘制缓存失效。
- `ReaderSafeInsets` 在分页与滚动阅读中共用稳定系统栏和显示切口安全区；沉浸模式只隐藏状态栏，不清零顶部留白，显示/隐藏动画不触发无意义重排。
- `CompanionChatScroll` 在历史消息首次测量前锚定最后一条的底部；`ReaderCompanionViewModel` 缓存聊天标题上下文，首次展示前有界预定位引用。异步历史与会话切换在挂起前后校验会话归属，避免空会话、旧列表和最新消息来回闪现，手动翻阅历史仍优先于自动跟随。
- `PageSpread.kt`、`SpreadGeometry.kt`、`SpreadLeafGeometry.kt` 统一章内页对、触点映射和书脊翻页。逻辑页码、聚焦的正文锚点与双页左页索引互不替代；章末空白页不是加载占位页，也不写入阅读进度。
- 段评引起的重排只影响实际改变的章节；替换布局就绪前保留旧布局。翻页提交校验布局代次，加载失败不能当作空章继续翻动。
- `AgentLoop.RoundStarted` 和 `MessageEntity.clientRoundId` 在持久化之前确定气泡身份；`CompanionStreamingReducer` 的状态由 Main 线程串行管理，Room 与流事件的到达顺序不应改变列表 key。
- 生图/TTS 配置用 `CommittedTextFieldState` 保留本地选择区和输入法组合态，失焦或操作前提交；`SettingsWriteQueue` 顺序保存并提供 flush 屏障，存储回声不覆盖正在输入的草稿。
- `ProactiveAnnotationScheduler` 是全应用唯一的有界串行生成入口。`ProactiveAnnotationJobEntity` 按书籍、章节、角色和正文修订版持久去重；段落结果与已完成段落账本在同一 Room 事务中落库。
- `ProactiveAnnotationParagraphs` 在本地选择段落；生成请求接收截至目标段落的本章前缀，以及 `ProactiveAnnotationContext` 从严格前文章节检索的相关原文和有效梗概。检索复用本地 BM25，不增加模型调用；梗概须同时通过正文修订版、来源范围与原文哈希校验，正文变化后停止旧请求。`AnnotationVisibility` 在正文、详情、聊天工具和完成提示共用来源范围检查；预生成不能拓宽阅读水位。
- `ProactiveAnnotationContextSettings` 提供省流 8,000、均衡 16,000、充分 32,000 字符及 4,000～64,000 自定义预算，默认均衡。预算覆盖本章正文（含单独重复的目标段落）、前文片段与梗概，不含角色设定和写作提示词；全局与单书覆盖共用设置页，修改预算会让在途任务失效。
- `AnnotationDiscussionService` 固定提供只读书内查询工具，不再按发言关键词裁剪。模型可先搜索再回读核对，独立循环最多五轮；提示词与工具共用实际 `ReadingScope`。记忆检索同时遵守全局/角色开关、当前用户面具与跨书对话开关，段评讨论仍不写入聊天记忆。
- 随读段评的 `style` 按内容语义选择：荧光对应金句/精彩段落，波浪线对应已提供原文中的线索/呼应，直线对应知识点/典故。生成、保存和绘制都保留三种样式；不随机分配，也不批量改写已有段评。
- 额度用完必须让用户看得见：调度器在当日额度耗尽时按每本书每天一次推送 `dailyBudgetExhausted` 提示，文案固定且不调用模型；设置页在每日上限下显示当天已生成条数。预生成会把额度提前用在尚未读到的章节上，没有这两处提示，用户只会看到连续多章没有段评。调度器的工作循环只在自身作用域被取消时结束，单次请求的取消不影响后续章节。
- `ProactiveAnnotationNoticeComposer` 默认使用不调用 API 的内置条数提示；显式选择快速模型时，`ModelRole.CHEAP` 仅接收有长度上限的角色名、性格与说话风格，生成一句角色口吻的共读弹幕，不传正文、段评、历史、记忆或条数。超时、失败和不合规输出回落互动短句，不回落统计通知；胶囊不抢焦点，未读结果不提供跳转入口。
- 回退诊断只记录超时、错误类型和输出长度，不记录模型原文、角色资料或异常消息里的连接信息。
- 随读段评设置复用父页面的 `SettingsViewModel`，冷启动先显示稳定页面框架，真实设置加载前不渲染临时默认开关和滑块。
- `CompanionAutonomySettings` 保存多伴读选择和独立段评预设。未单独选角色时跟随当前伴读；多选时按角色分别去重、共享每日额度并预留后续角色份额。`AnnotationPromptSettingsScreen` 复用预设编辑器，支持多条开关、编辑和四种注入位置；`ProactiveAnnotationPrompts` 提供可修改的内置口吻与划线风格，原文定位及输出格式契约独立保留。
- 主动段评通过 `ModelRole.PROACTIVE_ANNOTATION` 分配，未配置时只回落 `CHEAP`，已配置模型的错误不会暗中切换模型。每章“不限制”按全部候选段落生成，没有固定 10 条限制；有限条数按原文字符位置分布。`ProactiveAnnotationParagraphs` 拆分超长段落，保留 UTF-16 边界并限制上下文只到目标结尾；每日限额、已完成段落记录和未读可见性仍适用。

对应回归测试位于 `feature/reader/*Spread*Test`、`engine/ReaderContentControllerTest`、`CompanionChat*Test`、`ReaderPaneRetentionTest`、`settings/ProactiveAnnotationSettingsScreenTest`、`core/retrieval/AnnotationVisibilityTest`、`ai/companion/`、`ui/WindowLayoutTest` 和数据库迁移/可见水位的 Android 测试中。
