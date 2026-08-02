package com.mozhi.reader.core.library

import android.content.Context
import android.graphics.BitmapFactory
import com.mozhi.reader.core.database.dao.IllustrationDao
import com.mozhi.reader.core.database.entity.IllustrationEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class IllustrationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: IllustrationDao
) {
    fun observeForBook(bookId: Long): Flow<List<IllustrationEntity>> = dao.observeForBook(bookId)

    suspend fun get(id: Long): IllustrationEntity? = dao.get(id)

    /** v13 之前选区生图只有文件没有元数据；首次进书籍详情时补进插图廊。 */
    suspend fun backfillLegacyFiles(bookId: Long) {
        val known = dao.getForBook(bookId).mapTo(hashSetOf()) { File(it.imagePath).canonicalPath }
        val directory = File(context.filesDir, "illustrations/$bookId")
        directory.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            ?.filterNot { it.canonicalPath in known }
            ?.forEach { file ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                dao.insert(
                    IllustrationEntity(
                        bookId = bookId,
                        prompt = "旧版本生成的书籍插图",
                        imagePath = file.absolutePath,
                        mediaType = when (file.extension.lowercase()) {
                            "png" -> "image/png"
                            "webp" -> "image/webp"
                            else -> "image/jpeg"
                        },
                        pixelWidth = bounds.outWidth.coerceAtLeast(0),
                        pixelHeight = bounds.outHeight.coerceAtLeast(0),
                        createdAt = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
                    )
                )
            }
    }

    suspend fun insert(illustration: IllustrationEntity): IllustrationEntity {
        val id = dao.insert(illustration)
        return illustration.copy(id = id)
    }

    suspend fun delete(illustration: IllustrationEntity) {
        dao.delete(illustration.id)
        val file = File(illustration.imagePath)
        val root = File(context.filesDir, "illustrations").canonicalFile
        runCatching {
            if (file.canonicalFile.toPath().startsWith(root.toPath())) file.delete()
        }
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}
