package com.mozhi.reader.core

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.util.UUID

/** Keeps repository and backup tests away from the running application's files. */
class IsolatedTestContext(base: Context) : ContextWrapper(base) {
    val root = File(base.cacheDir, "storage-test-${UUID.randomUUID()}").apply { mkdirs() }

    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = directory("files")
    override fun getCacheDir(): File = directory("cache")
    override fun getNoBackupFilesDir(): File = directory("no_backup")
    override fun getDatabasePath(name: String): File =
        if (File(name).isAbsolute) File(name) else File(directory("databases"), name)

    private fun directory(name: String): File = File(root, name).apply { mkdirs() }
}
