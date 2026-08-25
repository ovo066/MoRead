package com.mozhi.reader.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.mozhi.reader.core.database.entity.ConversationEntity
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.ui.components.blockSheetDrag
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionConversationSheet(
    conversations: List<ConversationEntity>,
    activeConversationId: Long?,
    isStreaming: Boolean,
    onDismiss: () -> Unit,
    onNewConversation: () -> Unit,
    onSelectConversation: (Long) -> Unit,
    onRenameConversation: (ConversationEntity) -> Unit,
    onDeleteConversation: (ConversationEntity) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "会话历史",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onNewConversation, enabled = !isStreaming) {
                    Text("新会话")
                }
            }
            val formatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
            val conversationListState = rememberLazyListState()
            LazyColumn(
                state = conversationListState,
                modifier = Modifier
                    .blockSheetDrag(conversationListState)
                    .padding(bottom = 24.dp)
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectConversation(conversation.id) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                conversation.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (conversation.id == activeConversationId) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                formatter.format(Date(conversation.updatedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onRenameConversation(conversation) }) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = "重命名",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = { onDeleteConversation(conversation) }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "删除",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun RenameConversationDialog(
    conversationId: Long?,
    title: String,
    onTitleChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Long, String) -> Unit
) {
    conversationId ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(conversationId, title) }, enabled = title.isNotBlank()) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun DeleteConversationDialog(
    conversationId: Long?,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    conversationId ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除会话？") },
        text = { Text("“$title”中的全部消息都会删除，此操作无法撤销。") },
        confirmButton = {
            TextButton(onClick = { onConfirm(conversationId) }) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun EditCompanionMessageDialog(
    message: MessageEntity?,
    text: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (MessageEntity, String) -> Unit
) {
    message ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (message.role == "user") "编辑并重新发送" else "编辑 AI 消息") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    minLines = 3,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
                if (message.role == "user") {
                    Text(
                        "保存后会从这条消息重新生成，当前分支中它后面的内容会移除。需要保留时请先开分支。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(message, text) }, enabled = text.isNotBlank()) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun CompanionImagePreviewDialog(path: String?, onDismiss: () -> Unit) {
    path ?: return
    Dialog(onDismissRequest = onDismiss) {
        AsyncImage(
            model = File(path),
            contentDescription = "插图预览",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onDismiss)
        )
    }
}
