<p align="center">
  <img src="docs/images/icon.svg" width="112" alt="墨知 MoRead 图标" />
</p>

<h1 align="center">墨知 MoRead</h1>

<p align="center">极简的原生 Android 本地小说阅读器，内建可深度定制的 AI 伴读。</p>

<p align="center">
  <a href="https://github.com/ovo066/MoRead/releases"><img src="https://img.shields.io/github/v/release/ovo066/MoRead?label=%E4%B8%8B%E8%BD%BD&color=0a0a0a" alt="Release" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-0a0a0a" alt="Android 8.0+" />
  <img src="https://img.shields.io/badge/License-GPL--3.0-0a0a0a" alt="License GPL-3.0" />
</p>

墨知是一款纯本地、无账号、无数据采集的阅读应用：书籍由你自己导入，AI 功能使用你自己的 API Key（BYOK）直连服务商，不经任何中转服务器。没有网络、没有 Key 时，它就是一个完整可用的本地阅读器。


## 特性

### 阅读

- TXT / EPUB 导入：编码自动探测，正则分章（Legado 同源规则集），导入前可预览并自定义分章规则
- 自绘排版引擎：仿真 / 覆盖 / 滑动三种翻页；原生 EPUB 精细解析 CSS、块级/行内、浮动、表格、背景与图片；支持选词拖柄、段落批注与评论、书签、书内关键词搜索
- 排版自由：字号、行距、页边距、明暗主题、自定义三色阅读主题；无封面书籍自动生成直排文字封面
- 连续听书：系统 TTS 或云端 AI TTS 逐句朗读，自动翻页与跨章续播，通知栏播放控制，当前句正文高亮
- 阅读统计：阅读时长热力图、笔记与 AI 对话计量；笔记可导出 Markdown

### AI 伴读（自带 API Key）

- 四协议客户端自实现：OpenAI 兼容 / OpenAI Responses / Claude / Gemini，全部流式输出；同一服务商可混配对话、向量、语音、生图模型；Base URL 支持 HTTPS 与可信局域网 HTTP
- 角色卡：兼容 SillyTavern PNG / JSON 卡导入，支持世界书与自定义头像
- 陪读 Agent：理解当前章与全书进度，可按卷部目录精确定位章节，检索原文（向量 + 词法双路），并读回已有划线、笔记与剧情梗概
- 伴读创作：可添加批注、写入或更新笔记、滚动维护单份剧情梗概、生成插图与朗读文本；角色的写入能力在独立二级页用开关管理
- 防剧透硬约束：喂给 AI 的书籍内容永远不超过你当前的阅读进度
- 长期记忆：对话自动总结固化为向量记忆，跨会话召回
- 选词即问：翻译、解析、提问；AI 建议回复；一键剧情梗概
- 媒体生成：生图支持 OpenAI images 端点 / chat 端点出图 / NovelAI，语音支持系统引擎 / MiniMax / OpenAI 兼容端点，均可独立于模型分配单独配置

### 隐私

- 书籍、批注、笔记、对话全部保存在本机，应用不含任何自有后台服务
- WebDAV 支持手动完整备份、轻量自动备份、上传/下载进度与恢复前校验，恢复准备在后台完成后再安全重启
- API Key 存于 Android EncryptedSharedPreferences，仅在你主动使用 AI 功能时直连你配置的服务商
- 不申请存储全盘权限，书籍经系统文件选择器导入

## 下载

前往 [Releases](https://github.com/ovo066/MoRead/releases) 下载最新 APK。系统要求 Android 8.0（API 26）及以上。

## 快速上手

1. 书架页导入书籍，或在文件管理器中对 TXT / EPUB 使用「其他应用打开」→ 墨知
2. （可选）设置 → AI 服务商：添加服务商与 API Key，为对话 / 向量 / 语音 / 生图分配模型；语音朗读与生图也可在各自二级页独立配置
3. 阅读页长按选词即可翻译、解析、提问；底部工具栏可打开目录、听书与伴读

## 从源码构建

- 要求 JDK 17 与 Android SDK 37；克隆后用 Android Studio 打开，或命令行执行 `./gradlew :app:assembleDebug`
- Windows 下若仓库位于含中文的路径，可使用 `powershell -ExecutionPolicy Bypass -File scripts/gradle.ps1 <任务>` 规避路径问题
- 正式签名：仓库不包含签名密钥。在根目录放置 `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword` 四项）后执行 `assembleRelease` 即自动签名；未提供时输出未签名包

## 免责声明

墨知不提供、不内置、不推送任何书籍内容，也没有任何在线书源功能；全部阅读内容由用户自行导入本地文件，因此产生的版权责任由使用者自行承担。AI 功能的输出由用户自行配置的第三方模型生成，不代表本项目立场。

## 许可

本项目以 [GPL-3.0](LICENSE) 许可开源。项目复用了 Legado（GPL-3.0）的章节切分规则集并参考了其阅读器渲染设计，完整第三方清单见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
