package com.mozhi.reader.ai.chat

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * 正在后台生成回复的会话登记处。
 *
 * 生成不再挂在界面的生命周期上（退出聊天页不该丢掉半条回复），于是「正在生成」这件事
 * 必须活在界面之外：
 * - 用户退出去再回来时，新建的 ViewModel 靠它知道要继续显示等待态、暂时不让再发一条；
 * - 也靠它拿回那轮生成的 Job，「停止」按钮才能真的停下一个不属于自己的任务。
 */
@Singleton
class CompanionGenerationTracker @Inject constructor() {
    private val running = MutableStateFlow<Map<Long, Job>>(emptyMap())

    fun observe(conversationId: Long): Flow<Boolean> =
        running.map { conversationId in it }.distinctUntilChanged()

    fun isActive(conversationId: Long): Boolean = conversationId in running.value

    fun begin(conversationId: Long, job: Job) {
        running.update { it + (conversationId to job) }
    }

    fun end(conversationId: Long) {
        running.update { it - conversationId }
    }

    /** 停止某会话正在进行的生成；返回 true 表示确实有一轮被取消。 */
    fun cancel(conversationId: Long): Boolean {
        val job = running.value[conversationId] ?: return false
        job.cancel()
        end(conversationId)
        return true
    }
}
