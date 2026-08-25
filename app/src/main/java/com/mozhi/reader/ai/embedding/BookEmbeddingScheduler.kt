package com.mozhi.reader.ai.embedding

/**
 * AI 配置层只声明单本书索引意图，不反向依赖 importer 中的 WorkManager 实现。
 * 每本书使用独立唯一任务，避免一本大书阻塞整个书库，也便于用户单独取消和重建。
 */
interface BookEmbeddingScheduler {
    /** 为单本书建立索引；[resetBookIndex] 为 true 时替换旧任务并先清理该书索引。 */
    fun enqueueForBook(bookId: Long, resetBookIndex: Boolean = false)

    /** 停止这本书尚未完成的索引任务。 */
    fun cancelForBook(bookId: Long)
}
