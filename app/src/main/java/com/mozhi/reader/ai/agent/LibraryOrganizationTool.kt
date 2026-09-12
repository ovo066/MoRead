package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.ToolSpec
import com.mozhi.reader.ai.companion.LibraryOrganizationCoordinator
import com.mozhi.reader.ai.companion.LibraryOrganizationPlans
import kotlinx.serialization.json.*

internal class LibraryOrganizationTool(private val coordinator: LibraryOrganizationCoordinator) : AgentTool {
    override val displayName = "准备书架整理方案"
    override val spec = ToolSpec("propose_library_organization", "为用户准备书架标签/分组整理方案。先 find_books 查真实编号与现有标签。只生成预览，不实际修改；必须由用户在界面确认，不能宣称已整理完成。禁止删除书籍或改正文。每份最多20本。", buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("changes") {
                put("type", "array"); put("maxItems", 20)
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("book_id") { put("type", "integer") }
                        for (key in listOf("add_tags", "remove_tags")) putJsonObject(key) {
                            put("type", "array"); put("maxItems", 8); putJsonObject("items") { put("type", "string") }
                        }
                        putJsonObject("group_name") { put("type", "string"); put("description", "可选，移入同名一级分组；不存在时确认后创建；省略则保留分组") }
                    }
                    putJsonArray("required") { add("book_id") }
                }
            }
        }
        putJsonArray("required") { add("changes") }
    })
    override suspend fun execute(arguments: JsonObject): String = LibraryOrganizationPlans.encode(coordinator.preview(LibraryOrganizationPlans.requests(arguments)))
}
