package com.mozhi.reader.core.library

import android.content.Context
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/** Called after a book's rows cascade away. Assets shared by another book/theme/persona survive. */
class ImageReferenceCleanup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MoReadDatabase,
    private val settings: ReaderSettingsRepository
) {
    suspend fun forDeletedBook(bookId: Long) = withContext(Dispatchers.IO) {
        val current = settings.settings.first()
        val documents = database.imageConsistencyDao().referenceDocuments()
        val covers = database.bookDao().getAllBooks().mapNotNull { it.coverPath }.toSet()
        val avatars = database.personaDao().getPersonas().mapNotNull { it.avatarPath }.toSet()
        current.imageLibrary.filter { it.ownerBookId == bookId }.forEach { image ->
            val id = JsonPrimitive(image.id).toString()
            if (documents.none { id in it } && image.filePath !in covers && image.filePath !in avatars &&
                image.id != current.selectedBackgroundImageId && image.id != current.nightSelectedBackgroundImageId &&
                image.filePath != current.backgroundImagePath && current.customThemes.none {
                    it.backgroundImageId == image.id || it.backgroundImagePath == image.filePath
                }) {
                settings.removeReaderImage(image.id)
                val file = File(image.filePath).canonicalFile
                if (file.parentFile == File(context.filesDir, "reader-images").canonicalFile) file.delete()
            }
        }
    }
}
