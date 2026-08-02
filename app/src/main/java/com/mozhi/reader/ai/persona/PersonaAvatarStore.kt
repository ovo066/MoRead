package com.mozhi.reader.ai.persona

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 角色头像文件仓：filesDir/avatars/persona-<uuid>；删除只碰应用私有目录。 */
@Singleton
class PersonaAvatarStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** 落盘 SillyTavern 卡自带的 PNG 立绘。 */
    suspend fun saveBytes(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val file = newAvatarFile("png")
        file.writeBytes(bytes)
        file.absolutePath
    }

    /** 用户从相册选图；复制进私有目录，原图不动。失败返回 null。 */
    suspend fun saveFromUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val file = newAvatarFile("img")
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use(input::copyTo)
            } ?: return@runCatching null
            file.absolutePath
        }.getOrNull()
    }

    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        val file = File(path)
        val root = context.filesDir.canonicalFile.toPath()
        if (file.isFile && file.canonicalFile.toPath().startsWith(root)) {
            file.delete()
        }
    }

    private fun newAvatarFile(suffix: String): File {
        val dir = File(context.filesDir, "avatars").apply { mkdirs() }
        return File(dir, "persona-${UUID.randomUUID()}.$suffix")
    }
}
