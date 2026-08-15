package com.mozhi.reader.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 与应用进程同寿的协程作用域；不随任何界面销毁。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {

    /**
     * 给「用户离开界面也必须跑完」的工作用：典型是伴读的一轮生成——
     * 挂在 viewModelScope 上时，手滑退出聊天页会连同已经流了一半的回复一起取消掉，
     * 那条消息既没写进库也没留在界面上，用户回来就发现它凭空消失了。
     *
     * SupervisorJob：一处失败不牵连其他正在进行的工作。
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
