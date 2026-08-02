package com.mozhi.reader.feature.importer

import com.mozhi.reader.ai.embedding.BookEmbeddingScheduler
import com.mozhi.reader.core.importer.BookImportGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ImportModule {
    @Binds
    abstract fun bindBookImportGateway(implementation: ImportCoordinator): BookImportGateway

    @Binds
    abstract fun bindBookEmbeddingScheduler(
        implementation: WorkBookEmbeddingScheduler
    ): BookEmbeddingScheduler
}
