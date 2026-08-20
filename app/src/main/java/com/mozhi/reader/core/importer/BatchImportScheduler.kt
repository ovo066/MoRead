package com.mozhi.reader.core.importer

import android.net.Uri

/**
 * 批量导入的排队入口。接口摆在 core 是为了让书架这类别的 feature 能用它，
 * 而不必反向 import `feature/importer`（feature 之间禁止互相依赖）。
 */
interface BatchImportScheduler {
    /**
     * @param deleteSourceAfterImport 导入成功后删掉源文件；只对应用自己产生的临时文件
     *   （局域网收件箱）为 true，用户通过 SAF 选中的文件永远不删。
     */
    fun enqueue(
        uris: List<Uri>,
        deleteSourceAfterImport: Boolean = false,
        groupPathsByUri: Map<Uri, String> = emptyMap()
    )
}
