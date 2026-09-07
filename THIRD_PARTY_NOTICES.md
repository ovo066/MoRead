# 第三方与参考实现

- Legado 完整历史 fork（GPL-3.0）：`txtTocRule.json` 默认章节规则、TXT 分章设计，以及阅读页仿真/覆盖/滑动翻页的位图与贝塞尔渲染结构参考。本项目因复用该 GPL-3.0 成果，整体按 GPL-3.0 发布。
- Readium Kotlin Toolkit 3.3.0（BSD-3-Clause）：EPUB 解析与流式读取（`licenses/readium-BSD-3-Clause.txt`）。
- juniversalchardet 2.5.0（MPL-1.1）：TXT 编码探测。
- OkHttp / okhttp-sse（Apache-2.0）：网络与 SSE 流式基础设施。
- jsoup（MIT）：EPUB HTML 正文抽取。
- OpenccJava 1.4.2（MIT，Copyright (c) 2025 laisuk）：本地繁简及地区词转换（`licenses/openccjava-MIT.txt`，同时随 APK assets 分发）。
- OpenCC 词库（Apache-2.0，上游 BYVoid/OpenCC）：OpenccJava 随附的 `dicts/**` 转换数据（`licenses/opencc-dictionaries-Apache-2.0.txt`；原始 `dicts/LICENSE` 与 assets 声明均随包保留）。
- Coil（Apache-2.0）：图片加载。
- AndroidSVG（Apache-2.0）：EPUB 内联 SVG 导入期栅格化。
- ZXing core（Apache-2.0）：局域网传书页的二维码矩阵生成（只用纯 Java 的 `core`，绘制由应用自己完成）。
- multiplatform-markdown-renderer（Apache-2.0）：AI 输出的 Markdown 渲染。
- ObjectBox（Java/Android 绑定 Apache-2.0，数据库核心为免费专有二进制）：向量索引存储。
- kotlinx.coroutines / kotlinx.serialization（Apache-2.0）。
- AndroidX、Jetpack Compose、Room、DataStore、WorkManager、Hilt、Material Icons（Apache-2.0）：Android 官方组件。
