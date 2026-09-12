package com.mozhi.reader.ai.companion

import com.mozhi.reader.core.database.TagNameNormalizer
import com.mozhi.reader.core.database.entity.MessageEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
data class LibraryOrganizationTag(val id: Long, val name: String)

@Serializable
data class LibraryOrganizationChange(
    val bookId: Long,
    val title: String,
    val beforeTags: List<LibraryOrganizationTag>,
    val beforeGroupId: Long?,
    val beforeGroupName: String,
    val beforeGroupParentId: Long? = null,
    val addTags: List<String> = emptyList(),
    val removeTags: List<String> = emptyList(),
    val groupName: String? = null,
    val existingAddTags: List<LibraryOrganizationTag> = emptyList(),
    val targetGroupId: Long? = null
)

@Serializable
data class LibraryOrganizationPlan(val id: String, val changes: List<LibraryOrganizationChange>, val status: String = "PENDING")
data class LibraryOrganizationMessage(val messageId: Long, val plan: LibraryOrganizationPlan)
data class LibraryOrganizationRequest(val bookId: Long, val addTags: List<String>, val removeTags: List<String>, val groupName: String?)

object LibraryOrganizationPlans {
    private const val PREFIX = "MO_READ_LIBRARY_PLAN\n"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(plan: LibraryOrganizationPlan): String {
        validate(plan)
        return (PREFIX + json.encodeToString(plan)).also { require(it.length <= 48_000) { "方案过大，请减少书籍" } }
    }
    fun decode(raw: String): LibraryOrganizationPlan? = if (!raw.startsWith(PREFIX) || raw.length > 48_000) null else runCatching {
        json.decodeFromString<LibraryOrganizationPlan>(raw.removePrefix(PREFIX)).also(::validate)
    }.getOrNull()
    fun fromMessage(message: MessageEntity): LibraryOrganizationMessage? = message.takeIf { it.role == "tool" }
        ?.let { decode(it.content) }?.let { LibraryOrganizationMessage(message.id, it) }

    fun requests(arguments: JsonObject): List<LibraryOrganizationRequest> {
        val rows = arguments["changes"] as? JsonArray ?: error("请提供 changes")
        require(rows.size in 1..20) { "每份方案最多整理 20 本书" }
        val result = rows.map { element ->
            val row = element as? JsonObject ?: error("changes 格式无效")
            val id = (row["book_id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: error("书籍编号无效")
            fun names(key: String): List<String> {
                val value = row[key] ?: return emptyList()
                val list = value as? JsonArray ?: error("标签应为数组")
                require(list.size <= 8) { "每本书一次最多修改 8 个标签" }
                return list.map {
                    val name = (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: error("标签名称无效")
                    TagNameNormalizer.normalize(name).also { normalized -> require(normalized.length in 1..24) { "标签长度应为 1–24 字" } }
                }.distinctBy { it.lowercase() }
            }
            val add = names("add_tags")
            val remove = names("remove_tags")
            require(add.none { name -> remove.any { it.equals(name, true) } }) { "不能同时添加和移除同一标签" }
            val group = row["group_name"]?.let { value ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.also { require(it.length in 1..30) { "分组名长度应为 1–30 字" } }
                    ?: error("分组名称无效")
            }
            require(add.isNotEmpty() || remove.isNotEmpty() || group != null) { "方案没有变更" }
            LibraryOrganizationRequest(id, add, remove, group)
        }
        return result.also(::validateRequests)
    }

    internal fun validateRequests(requests: List<LibraryOrganizationRequest>) {
        require(requests.size in 1..20) { "每份方案最多整理 20 本书" }
        require(requests.distinctBy { it.bookId }.size == requests.size) { "同一本书请合并为一条变更" }
        requests.forEach { request ->
            require(request.bookId > 0) { "书籍编号无效" }
            listOf(request.addTags, request.removeTags).forEach { names ->
                require(names.size <= 8 && names.distinctBy { it.lowercase() }.size == names.size) { "标签数量无效" }
                require(names.all { it.length in 1..24 && it == TagNameNormalizer.normalize(it) && it.none(Char::isISOControl) }) { "标签名称无效" }
            }
            require(request.addTags.none { name -> request.removeTags.any { TagNameNormalizer.isSame(it, name) } }) { "不能同时添加和移除同一标签" }
            require(request.groupName == null || request.groupName.let { it.length in 1..30 && it == it.trim() && it.none(Char::isISOControl) }) { "分组名称无效" }
            require(request.addTags.isNotEmpty() || request.removeTags.isNotEmpty() || request.groupName != null) { "方案没有变更" }
        }
    }

    private fun validate(plan: LibraryOrganizationPlan) {
        require(plan.id.length in 1..80 && plan.status in setOf("PENDING", "APPLIED", "CANCELLED"))
        validateRequests(plan.changes.map { LibraryOrganizationRequest(it.bookId, it.addTags, it.removeTags, it.groupName) })
        plan.changes.forEach { change ->
            require(change.title.length in 1..120)
            require(change.beforeGroupId == null || change.beforeGroupId > 0)
            require(change.beforeGroupParentId == null || change.beforeGroupParentId > 0)
            require(change.beforeGroupName.length <= 500)
            require(change.beforeGroupId != null || (change.beforeGroupName.isEmpty() && change.beforeGroupParentId == null))
            require(change.targetGroupId == null || (change.targetGroupId > 0 && change.groupName != null))
            listOf(change.beforeTags, change.existingAddTags).forEach { tags ->
                require(tags.size <= 256 && tags.distinctBy { it.id }.size == tags.size)
                require(tags.all { it.id > 0 && it.name.length in 1..500 })
            }
            require(change.removeTags.all { name -> change.beforeTags.any { TagNameNormalizer.isSame(it.name, name) } })
            require(change.addTags.none { name -> change.beforeTags.any { TagNameNormalizer.isSame(it.name, name) } })
            require(change.existingAddTags.all { tag -> change.addTags.any { TagNameNormalizer.isSame(it, tag.name) } })
        }
    }
}
