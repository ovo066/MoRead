package com.mozhi.reader.feature.importer

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.importer.BatchImportScheduler
import com.mozhi.reader.core.importer.FolderScanner
import com.mozhi.reader.core.importer.ScannedBookFile
import com.mozhi.reader.core.library.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ImportPickerState(
    val scanning: Boolean = true,
    val folderName: String = "",
    val files: List<ScannedBookFile> = emptyList(),
    /** 文件名（去扩展名）已经出现在书架里的条目，界面上标灰但仍可勾选。 */
    val alreadyImported: Set<Uri> = emptySet(),
    val selected: Set<Uri> = emptySet(),
    val createGroupsFromFolders: Boolean = true,
    val error: String? = null
) {
    val groups: List<Pair<String, List<ScannedBookFile>>>
        get() = FolderScanner.groupByDirectory(files, ScannedBookFile::relativeDirectory)

    val truncated: Boolean get() = files.size >= FolderScanner.MAX_FILES
}

@HiltViewModel
class ImportPickerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val folderScanner: FolderScanner,
    private val libraryRepository: LibraryRepository,
    private val batchImportScheduler: BatchImportScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportPickerState())
    val uiState = _uiState.asStateFlow()

    init {
        val treeUri = savedStateHandle.get<String>(ARG_TREE_URI)?.let(Uri::parse)
        if (treeUri == null) {
            _uiState.value = ImportPickerState(scanning = false, error = "没有拿到文件夹地址")
        } else {
            scan(treeUri)
        }
    }

    private fun scan(treeUri: Uri) {
        viewModelScope.launch {
            val existingTitles = libraryRepository.observeBooks().first()
                .map { it.title.trim() }
                .toSet()
            val files = runCatching { folderScanner.scan(treeUri) }
                .getOrElse { error ->
                    _uiState.value = ImportPickerState(
                        scanning = false,
                        error = error.message ?: "读取文件夹失败"
                    )
                    return@launch
                }
            val imported = files
                .filter { FolderScanner.looksImported(it.name, existingTitles) }
                .map(ScannedBookFile::uri)
                .toSet()
            _uiState.value = ImportPickerState(
                scanning = false,
                folderName = treeUri.lastPathSegment?.substringAfterLast(':').orEmpty(),
                files = files,
                alreadyImported = imported,
                // 默认只勾没导过的，避免一键重复导入一整个已同步过的目录。
                selected = files.map(ScannedBookFile::uri).toSet() - imported,
                error = if (files.isEmpty()) "这个文件夹里没有 TXT 或 EPUB" else null
            )
        }
    }

    fun toggle(uri: Uri) {
        val current = _uiState.value
        _uiState.value = current.copy(
            selected = if (uri in current.selected) current.selected - uri else current.selected + uri
        )
    }

    fun toggleGroup(directory: String) {
        val current = _uiState.value
        val inGroup = current.files.filter { it.relativeDirectory == directory }.map(ScannedBookFile::uri)
        val allSelected = inGroup.all { it in current.selected }
        _uiState.value = current.copy(
            selected = if (allSelected) current.selected - inGroup.toSet() else current.selected + inGroup
        )
    }

    fun selectAll(selected: Boolean) {
        val current = _uiState.value
        _uiState.value = current.copy(
            selected = if (selected) current.files.map(ScannedBookFile::uri).toSet() else emptySet()
        )
    }

    fun setCreateGroupsFromFolders(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(createGroupsFromFolders = enabled)
    }

    /** 勾选的书排进批量导入；SAF 的原文件当然不删。 */
    fun importSelected(): Int {
        val selected = _uiState.value.files
            .filter { it.uri in _uiState.value.selected }
            .map(ScannedBookFile::uri)
        val groupPaths = if (_uiState.value.createGroupsFromFolders) {
            _uiState.value.files
                .filter { it.uri in _uiState.value.selected && it.relativeDirectory.isNotBlank() }
                .associate { it.uri to it.relativeDirectory }
        } else {
            emptyMap()
        }
        batchImportScheduler.enqueue(selected, groupPathsByUri = groupPaths)
        return selected.size
    }

    companion object {
        const val ARG_TREE_URI = "treeUri"
    }
}
