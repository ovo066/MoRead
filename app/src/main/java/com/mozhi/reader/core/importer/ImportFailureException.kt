package com.mozhi.reader.core.importer

import com.mozhi.reader.core.i18n.UiText

/** 导入错误跨层保持资源形式，到 Snackbar 或后台任务结果边界才按当前语言解析。 */
class ImportFailureException(val uiText: UiText, cause: Throwable? = null) :
    IllegalStateException("Book import failed", cause)
