package com.mozhi.reader.ai.embedding

/**
 * AI 配置层只声明「需要补齐书籍向量」这一意图，不反向依赖 importer 中的 WorkManager
 * 实现。索引按需建立：某本书首次需要检索时经 [enqueueForBook] 单书触发；
 * 全库扫描只保留给设置页手动重建。
 */
interface BookEmbeddingScheduler {
    /** 全库扫描；[resetBookIndex] 用于更换向量模型后清掉旧坐标系并完整重建。 */
    fun enqueue(resetBookIndex: Boolean)

    /** 按需为单本书建立索引；重复调用幂等（已排队则忽略）。 */
    fun enqueueForBook(bookId: Long)
}

fun BookEmbeddingScheduler.enqueue() = enqueue(resetBookIndex = false)
