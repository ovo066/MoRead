package com.mozhi.reader.ai.embedding

/**
 * AI 配置层只声明单本书索引意图，不反向依赖 importer 中的 WorkManager 实现。
 * 每本书使用独立唯一任务，避免一本大书阻塞整个书库，也便于用户单独取消和重建。
 */
interface BookEmbeddingScheduler {
    /**
     * 为单本书建立索引；[replaceExisting] 为 true 时替换排队中的旧任务（重建用）。
     *
     * 调度器只负责「建」。清理索引由调用方在入队前一次性完成——任务本身会因网络失败
     * 反复重试，任何写在任务里的清理都会被重放，把已建好的章节周期性清零。
     */
    fun enqueueForBook(bookId: Long, replaceExisting: Boolean = false)

    /** 停止这本书尚未完成的索引任务。 */
    fun cancelForBook(bookId: Long)
}
