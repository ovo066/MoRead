package com.mozhi.reader.feature.companion

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.ai.companion.LocatedLibraryCitation
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.feature.reader.EditCompanionMessageDialog
import kotlinx.coroutines.launch

@Composable
fun LibraryCompanionScreen(
    onBack: () -> Unit,
    onOpenStats: () -> Unit,
    onLocate: (LocatedLibraryCitation) -> Unit,
    viewModel: LibraryCompanionViewModel = hiltViewModel()
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val observed by viewModel.messages.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val messages = observed.takeIf { it.conversationId == session.conversation?.id } ?: LibraryChatMessages()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf(false) }
    var books by remember { mutableStateOf(false) }
    var information by remember { mutableStateOf(false) }
    var plans by remember { mutableStateOf(false) }
    var editing by remember(session.conversation?.id) { mutableStateOf<MessageEntity?>(null) }
    var editText by remember { mutableStateOf("") }
    var deleting by remember(session.conversation?.id) { mutableStateOf<MessageEntity?>(null) }
    var rerolling by remember(session.conversation?.id) { mutableStateOf<MessageEntity?>(null) }
    fun fresh() {
        if (session.draft.isBlank()) viewModel.newConversation()
        else scope.launch { snackbar.showSnackbar("请先发送或清空草稿") }
    }
    LaunchedEffect(session.error) { session.error?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(viewModel) {
        viewModel.events.collect {
            when (it) {
                is LibraryChatEvent.Notice -> snackbar.showSnackbar(it.text)
                is LibraryChatEvent.OpenSource -> onLocate(it.location)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        LibraryChatConversation(
            session = session, catalog = catalog, messages = messages,
            onBack = onBack, onHistory = { history = true }, onNew = ::fresh,
            onSelectPersona = viewModel::selectPersona,
            onPickBooks = { books = true }, onOpenStats = onOpenStats, onInfo = { information = true },
            onOpenPlans = { plans = true },
            onInput = viewModel::setDraft, onSend = viewModel::send, onStop = viewModel::stop,
            isLoading = session.conversation != null && messages.conversationId != session.conversation?.id,
            onLocate = viewModel::locate,
            onEdit = { editing = it; editText = it.content }, onDelete = { deleting = it },
            onReroll = { rerolling = it }, onBranch = { viewModel.branchFrom(it.id) }, onRetry = viewModel::retry
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
    }
    EditCompanionMessageDialog(editing, editText, { editText = if (editing?.role == "user") it.take(8_000) else it }, { editing = null }) { message, text ->
        editing = null
        viewModel.editMessage(message.id, text)
    }
    deleting?.let { message -> AlertDialog(
        onDismissRequest = { deleting = null }, title = { Text("删除消息？") },
        text = { Text(if (message.role == "user") "这条消息和本轮回复会删除。已应用的书架整理不会撤销。" else "这条回复会删除，后续对话保留。已应用的书架整理不会撤销。") },
        confirmButton = { TextButton(onClick = { deleting = null; viewModel.deleteMessage(message.id) }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
    ) }
    rerolling?.let { message -> AlertDialog(
        onDismissRequest = { rerolling = null }, title = { Text("重新生成回复？") },
        text = { Text("将从对应的提问重新回复，这条回复及后续内容会移除。想保留时，可以先从这里开分支。已应用的书架整理不会撤销。") },
        confirmButton = { TextButton(onClick = { rerolling = null; viewModel.reroll(message.id) }) { Text("重新生成") } },
        dismissButton = { TextButton(onClick = { rerolling = null }) { Text("取消") } }
    ) }
    if (books) LibraryBookPicker(catalog.books, session.selectedBooks, viewModel::toggleBook) { books = false }
    if (plans) LibraryOrganizationSheet(messages.organizationPlans, session.busy || messages.reply.running,
        { plans = false }, viewModel::confirmOrganization)
    if (history) LibraryChatHistory(
        conversations, session.conversation?.id, { history = false }, { history = false; fresh() },
        onOpen = { id ->
            if (session.draft.isBlank()) { history = false; viewModel.open(id) }
            else scope.launch { snackbar.showSnackbar("请先发送或清空草稿") }
        }, onRename = viewModel::rename, onDelete = viewModel::delete
    )
    if (information) AlertDialog(
        onDismissRequest = { information = false }, title = { Text("书库伴读") },
        text = { Text("不选书也能聊，伴读会按需查书。\n仅检索已读内容，整理书架先确认。\n模型调用按服务商计费。") },
        confirmButton = { TextButton(onClick = { information = false }) { Text("知道了") } }
    )
}
