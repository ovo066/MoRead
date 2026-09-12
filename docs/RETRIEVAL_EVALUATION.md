# 本地检索评测

评测代码在 `app/src/test/java/com/mozhi/reader/core/retrieval/evaluation/`。指标计算的确定性单测随普通 CI 运行；真实书籍评测显式启用，不包含在公开测试资源中。

## 当前覆盖范围

- 本地 BM25 召回 → 现有融合与名额分配 → 相关性排序的命中锚点。
- Recall@8、nDCG@8、MRR@8，以及召回池 Recall@64。
- 验证请求章节区间和阅读水位；出现越界结果直接失败。
- **不调用网络、Embedding 或重排服务。**本模式的结果不能用于校准向量距离，也不能代表完整混合检索或伴读回答质量。
- 记录的是 JVM 排序耗时，不包含手机文件读取、网络延迟或模型调用成本。

## 准备私有语料

建议在版本库外创建评测目录，只使用自己有权处理的书籍。不要将书籍、正文快照、含私有引文的标注或报告加入公开提交。

在该目录创建 `manifest.json`，例如：

```json
{
  "books": [
    {"id": 1, "key": "book-a", "title": "样书 A", "epub": "<EPUB 的绝对路径>"}
  ]
}
```

Windows 使用已配置的 JDK 21：

```powershell
./scripts/retrieval-eval.ps1 -ManifestPath <manifest.json路径> -Action export
```

导出会使用应用的 EPUB 正文解析器，生成同目录下的 `book-a.corpus.json`，包括线性 spine 的顺序、href、原文、UTF-16 坐标和文本哈希。图片、字体不会复制出来。正文或解析格式变化后应重新导出，并重新校准标注位置。

## 标注格式

每本书创建对应的 `book-a.cases.json`：

```json
{
  "sourceSha256": "<正文快照中的 sourceSha256>",
  "labelStatus": "draft_ai_needs_human_review",
  "queries": [
    {
      "id": "book-a-01",
      "query": "待评测的独立查询",
      "kind": "paraphrase",
      "evidence": [
        {
          "chapter": 2,
          "start": 100,
          "end": 150,
          "quote": "<该原文范围对应的完整文本>",
          "chapterSha256": "<对应章节的 textSha256>",
          "grade": 3
        }
      ]
    }
  ]
}
```

- `evidence.chapter` 为从 **0** 开始的快照章节索引；字符范围为 UTF-16 `[start, end)`，不是字节、码点或屏幕偏移。
- 可选 `from_chapter` / `to_chapter` 与工具接口相同，从 **1** 开始，包含两端。章号以实际导入目录为准，不按章节标题猜测。
- 可选 `progressChapter`（1 基）和 `progressChar`（章内 UTF-16 末端，不包含）用于限制阅读水位。
- `grade` 是 1～3 的相关等级。标注核心证据片段，不以整个大章节代替精确证据。
- 当前规则把覆盖至少一半证据范围的候选切片标为相关，取其最高证据等级。切片变化不会使标注直接失去位置；仍应人工审查证据是否完整。
- 无正例标签的题不进入正例指标均值。没有标签不等于问题无答案，不可用它直接衡量回答幻觉。

人工复核应检查：问题前提、别名/同义改述、其他同样有效的证据、标注完整性、剧透范围，以及开发集/保留集之间是否存在近重复题。小规模、集中于章首的初始题集不应作为上线质量门槛。

## 运行与解释

```powershell
./scripts/retrieval-eval.ps1 -ManifestPath <manifest.json路径> -Action evaluate
```

脚本只强制重跑评测测试任务，复用已编译依赖，并关闭 Gradle 构建缓存，避免私有报告进入远程缓存。也可通过 `MOREAD_RETRIEVAL_EVAL_MANIFEST` 和 `MOREAD_RETRIEVAL_EVAL_ACTION` 运行 `LocalRetrievalEvaluationTest`。

输出位于清单所在目录的 `lexical-baseline.json`，历史报告保存在 `reports/`。报告包含每题命中位置、标签状态、指标和越界检查结果。图表或汇总必须保留模式与标注状态，不能把词法草稿基线宣传为真实线上准确率。

后续接入固定的模型向量/重排快照、人工复核后的真实题集及无答案评测后，才能可靠比较模型、重排收益和阈值。模型配置、切片版本、语料哈希和评测集版本应一起固定。
