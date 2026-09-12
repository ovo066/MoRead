package com.mozhi.reader.feature.companion

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mozhi.reader.core.database.entity.ConversationEntity
import com.mozhi.reader.feature.reader.CompanionConversationSheet
import com.mozhi.reader.feature.reader.RenameConversationDialog

@Composable
internal fun LibraryChatHistory(
    conversations: List<ConversationEntity>,
    activeId: Long?,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onOpen: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit
) {
    var renaming by remember { mutableStateOf<ConversationEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<ConversationEntity?>(null) }
    CompanionConversationSheet(
        conversations, activeId, isStreaming = false, onDismiss, onNew, onOpen,
        onRenameConversation = { renaming = it; name = it.title },
        onDeleteConversation = { deleting = it }
    )
    RenameConversationDialog(renaming?.id, name, { name = it }, { renaming = null }) { id, title ->
        onRename(id, title)
        renaming = null
    }
    deleting?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("删除「${conversation.title}」？") },
            text = { Text("对话与相关统计将删除，无法撤销。书籍、笔记和已应用的整理保留。") },
            confirmButton = { TextButton(onClick = { onDelete(conversation.id); deleting = null }) { Text("删除话题") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
        )
    }
}
