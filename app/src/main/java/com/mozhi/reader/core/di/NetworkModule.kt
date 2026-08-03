package com.mozhi.reader.core.di

import com.mozhi.reader.core.diag.ApiCallLogInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(apiCallLogInterceptor: ApiCallLogInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // 应用级拦截器：AI 请求诊断日志（设置里默认关闭，关闭时零开销直通）。
            .addInterceptor(apiCallLogInterceptor)
            .build()
}
