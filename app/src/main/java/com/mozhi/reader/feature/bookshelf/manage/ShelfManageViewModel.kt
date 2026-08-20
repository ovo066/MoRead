package com.mozhi.reader.feature.bookshelf.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.database.ShelfTagBackfill
import com.mozhi.reader.core.database.TagNameNormalizer
import com.mozhi.reader.core.database.entity.BookTagEntity
import com.mozhi.reader.core.database.entity.ShelfGroupEntity
import com.mozhi.reader.core.library.ShelfOrganizationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class ShelfManageUiState(
    val groups: List<ShelfGroupEntity> = emptyList(),
    val groupCounts: Map<Long?, Int> = emptyMap(),
    val tags: List<BookTagEntity> = emptyList(),
    val tagCounts: Map<Long, Int> = emptyMap(),
    val selectedTagIds: Set<Long> = emptySet()
)

sealed interface ShelfManageEvent {
    data class Message(val text: String) : ShelfManageEvent
}

@HiltViewModel
class ShelfManageViewModel @Inject constructor(
    private val repository: ShelfOrganizationRepository
) : ViewModel() {
    private val selectedTagIds = MutableStateFlow<Set<Long>>(emptySet())
    private val eventChannel = Channel<ShelfManageEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    val state = combine(repository.snapshot, selectedTagIds) { snapshot, selected ->
        ShelfManageUiState(
            groups = snapshot.groups,
            groupCounts = snapshot.groupCounts,
            tags = snapshot.tags,
            tagCounts = snapshot.tagCounts,
            selectedTagIds = selected.intersect(snapshot.tags.map(BookTagEntity::id).toSet())
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ShelfManageUiState()
    )

    fun saveGroup(name: String, parentId: Long?, existing: ShelfGroupEntity? = null) {
        val normalized = name.trim()
        if (normalized.isEmpty()) {
            message("分组名不能为空")
            return
        }
        val groups = state.value.groups
        val parent = parentId?.let { id -> groups.firstOrNull { it.id == id } }
        if (parentId != null && parent == null) {
            message("父分组不存在")
            return
        }
        if (parent?.parentId != null) {
            message("分组最多只能有两级")
            return
        }
        if (existing != null && parentId != null && groups.any { it.parentId == existing.id }) {
            message("包含子分组的分组不能再设为二级")
            return
        }
        viewModelScope.launch {
            runCatching {
                repository.saveGroup(
                    (existing ?: ShelfGroupEntity(
                        name = normalized,
                        parentId = parentId,
                        sortOrder = groups.count { it.parentId == parentId },
                        createdAt = System.currentTimeMillis()
                    )).copy(name = normalized, parentId = parentId)
                )
            }.onSuccess {
                eventChannel.send(ShelfManageEvent.Message(if (existing == null) "分组已新建" else "分组已更新"))
            }.onFailure { error ->
                eventChannel.send(ShelfManageEvent.Message(error.message ?: "保存分组失败"))
            }
        }
    }

    fun deleteGroup(group: ShelfGroupEntity, moveBooksToParent: Boolean) {
        viewModelScope.launch {
            val destination = if (moveBooksToParent) group.parentId else null
            runCatching { repository.deleteGroup(group.id, destination) }
                .onSuccess { eventChannel.send(ShelfManageEvent.Message("分组已删除")) }
                .onFailure { error ->
                    eventChannel.send(ShelfManageEvent.Message(error.message ?: "删除分组失败"))
                }
        }
    }

    fun moveGroup(group: ShelfGroupEntity, offset: Int) {
        val siblings = state.value.groups.filter { it.parentId == group.parentId }.toMutableList()
        val index = siblings.indexOfFirst { it.id == group.id }
        val target = index + offset
        if (index < 0 || target !in siblings.indices) return
        siblings[index] = siblings[target].also { siblings[target] = siblings[index] }
        viewModelScope.launch { repository.reorderGroups(siblings) }
    }

    fun createTags(rawNames: String, groupName: String = "") {
        val names = rawNames.split(',', '，', '\n')
            .map(TagNameNormalizer::normalize)
            .filter(String::isNotEmpty)
            .distinctBy(String::lowercase)
        if (names.isEmpty()) {
            message("请输入标签名")
            return
        }
        viewModelScope.launch {
            var created = 0
            names.forEach { name ->
                runCatching { repository.createOrGetTag(name, groupName) }
                    .onSuccess { created += 1 }
            }
            eventChannel.send(ShelfManageEvent.Message("已处理 $created 个标签"))
        }
    }

    fun saveTag(tag: BookTagEntity, name: String, groupName: String, colorTag: String = tag.colorTag) {
        val normalized = TagNameNormalizer.normalize(name)
        if (normalized.isEmpty()) {
            message("标签名不能为空")
            return
        }
        viewModelScope.launch {
            runCatching {
                repository.saveTag(
                    tag.copy(name = normalized, groupName = groupName, colorTag = colorTag)
                )
            }.onSuccess {
                eventChannel.send(ShelfManageEvent.Message("标签已更新"))
            }.onFailure { error ->
                eventChannel.send(ShelfManageEvent.Message(error.message ?: "更新标签失败"))
            }
        }
    }

    fun toggleTagSelection(tagId: Long) {
        selectedTagIds.value = selectedTagIds.value.toMutableSet().apply {
            if (!add(tagId)) remove(tagId)
        }
    }

    fun clearTagSelection() {
        selectedTagIds.value = emptySet()
    }

    fun setSelectedTagGroup(groupName: String) {
        val ids = selectedTagIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.setTagsGroup(ids, groupName)
            selectedTagIds.value = emptySet()
            eventChannel.send(ShelfManageEvent.Message("标签分组已更新"))
        }
    }

    fun deleteSelectedTags() {
        val ids = selectedTagIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.deleteTags(ids)
            selectedTagIds.value = emptySet()
            eventChannel.send(ShelfManageEvent.Message("已删除 ${ids.size} 个标签"))
        }
    }

    fun mergeSelectedTags(targetId: Long) {
        val ids = selectedTagIds.value
        if (targetId !in ids || ids.size < 2) return
        viewModelScope.launch {
            repository.mergeTags(ids, targetId)
            selectedTagIds.value = emptySet()
            eventChannel.send(ShelfManageEvent.Message("标签已合并"))
        }
    }

    fun moveTag(tag: BookTagEntity, offset: Int) {
        val tags = state.value.tags.toMutableList()
        val index = tags.indexOfFirst { it.id == tag.id }
        val target = index + offset
        if (index < 0 || target !in tags.indices) return
        tags[index] = tags[target].also { tags[target] = tags[index] }
        viewModelScope.launch { repository.reorderTags(tags) }
    }

    fun exportTagsJson(): String = JSONArray().apply {
        state.value.tags.forEach { tag ->
            put(JSONObject().apply {
                put("name", tag.name)
                put("colorTag", tag.colorTag)
                put("groupName", tag.groupName)
                put("sortOrder", tag.sortOrder)
            })
        }
    }.toString(2)

    fun importTagsJson(json: String) {
        viewModelScope.launch {
            runCatching {
                val array = JSONArray(json)
                var imported = 0
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val name = TagNameNormalizer.normalize(item.optString("name"))
                    if (name.isEmpty()) continue
                    val tagId = repository.createOrGetTag(name, item.optString("groupName"))
                    state.value.tags.firstOrNull { it.id == tagId }?.let { existing ->
                        repository.saveTag(
                            existing.copy(
                                colorTag = item.optString("colorTag")
                                    .ifBlank { ShelfTagBackfill.colorFor(name) },
                                groupName = item.optString("groupName"),
                                sortOrder = item.optInt("sortOrder", existing.sortOrder)
                            )
                        )
                    }
                    imported += 1
                }
                imported
            }.onSuccess { count ->
                eventChannel.send(ShelfManageEvent.Message("已导入 $count 个标签"))
            }.onFailure { error ->
                eventChannel.send(ShelfManageEvent.Message(error.message ?: "标签文件格式不正确"))
            }
        }
    }

    private fun message(text: String) {
        viewModelScope.launch { eventChannel.send(ShelfManageEvent.Message(text)) }
    }
}
