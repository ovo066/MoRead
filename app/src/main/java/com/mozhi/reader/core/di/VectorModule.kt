package com.mozhi.reader.core.di

import android.content.Context
import com.mozhi.reader.core.vector.VectorDb
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.objectbox.BoxStore
import javax.inject.Singleton

/** 向量库（ObjectBox）注入；Hilt 惰性构建，未用到向量能力时不会开库。 */
@Module
@InstallIn(SingletonComponent::class)
object VectorModule {
    @Provides
    @Singleton
    fun provideVectorStore(@ApplicationContext context: Context): BoxStore =
        VectorDb.open(context)
}
