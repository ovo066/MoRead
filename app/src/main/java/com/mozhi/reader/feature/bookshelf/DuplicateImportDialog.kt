package com.mozhi.reader.feature.bookshelf

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.mozhi.reader.R
import com.mozhi.reader.core.importer.PreparedImport

/**
 * 导入了已经在库里的书：默认不再复制一份。内容完全相同时可以直接打开已有的书；
 * 只是书名相同（旧书没有指纹）时说明只是「可能」，仍然导入也不会覆盖原书。
 */
@Composable
internal fun DuplicateImportDialog(
    duplicate: PreparedImport.Duplicate,
    onOpenExisting: () -> Unit,
    onImportAnyway: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("duplicate-import"),
        title = { Text(stringResource(if (duplicate.exact) R.string.import_duplicate_title else R.string.import_duplicate_likely_title)) },
        text = {
            Text(stringResource(when {
                duplicate.removed -> R.string.import_duplicate_removed
                duplicate.exact -> R.string.import_duplicate_exact
                else -> R.string.import_duplicate_likely
            }, duplicate.title))
        },
        confirmButton = {
            if (!duplicate.removed) TextButton(onClick = onOpenExisting) { Text(stringResource(R.string.import_duplicate_open)) }
            else TextButton(onClick = onImportAnyway) { Text(stringResource(R.string.import_duplicate_import)) }
        },
        dismissButton = {
            if (!duplicate.removed) TextButton(onClick = onImportAnyway) { Text(stringResource(R.string.import_duplicate_import)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.characters_cancel)) }
        }
    )
}
