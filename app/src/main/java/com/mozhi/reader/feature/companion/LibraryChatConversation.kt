package com.mozhi.reader.feature.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mozhi.reader.ai.companion.LibraryBookScope
import com.mozhi.reader.ai.companion.LibraryCitation
import com.mozhi.reader.ai.companion.LibraryCitationParser
import com.mozhi.reader.core.database.entity.chatAppearance
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.feature.reader.*
import com.mozhi.reader.ui.components.*
import java.io.File
import kotlinx.coroutines.launch

@Composable
internal fun LibraryChatConversation(
    session: LibraryChatSession, catalog: LibraryChatCatalog, messages: LibraryChatMessages,
    onBack: () -> Unit, onHistory: () -> Unit, onNew: () -> Unit, onSelectPersona: (Long) -> Unit,
    onPickBooks: () -> Unit, onOpenStats: () -> Unit, onInfo: () -> Unit,
    onOpenPlans: () -> Unit,
    onInput: (String) -> Unit, onSend: () -> Unit, onStop: () -> Unit,
    isLoading: Boolean, onLocate: (LibraryCitation) -> Unit,
    onEdit: (MessageEntity) -> Unit, onDelete: (MessageEntity) -> Unit,
    onReroll: (MessageEntity) -> Unit, onBranch: (MessageEntity) -> Unit, onRetry: () -> Unit
) {
    val personaId = if (session.conversation != null) session.conversation.personaId else session.selectedPersonaId ?: catalog.activePersonaId
    val persona = catalog.personas.firstOrNull { it.id == personaId }
    val appearance = persona?.chatAppearance() ?: com.mozhi.reader.core.database.entity.PersonaChatAppearance.DEFAULT
    val font = rememberChatFontFamily(appearance.fontId, catalog.settings.fontLibrary)
    val palette = companionChatPalette()
    var personaMenu by remember { mutableStateOf(false) }
    val greeting = persona?.greeting?.ifBlank { null } ?: "今天想聊点什么？找书、聊感受，都可以。"
    val entries = remember(session.scopes, messages, greeting) { libraryChatEntries(session.scopes, messages, greeting) }
    val scroll = rememberCompanionChatScrollState("library:" + session.conversation?.id + ":" + personaId, entries, isLoading)
    val coroutineScope = rememberCoroutineScope()
    MoReadBackdrop {
        catalog.settings.imageLibrary.firstOrNull { it.id == appearance.backgroundImageId }?.let {
            AsyncImage(File(it.filePath), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(palette.background.copy(alpha = appearance.backgroundDim)))
        }
        MoReadBoundedContent {
            Column(Modifier.fillMaxSize().safeTopPadding().imePadding()) {
                CompanionChatHeader(
                    persona, catalog.personas, "书库伴读", personaMenu, messages.reply.running || session.busy, palette,
                    onBack, { personaMenu = true }, { personaMenu = false }, { personaMenu = false; onSelectPersona(it) }, onNew, onHistory
                )
                HorizontalDivider(color = palette.glassBorder)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onPickBooks) {
                        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(16.dp), tint = palette.muted)
                        Text(if (session.selectedBooks.isEmpty()) "选书" else session.selectedBooks.size.toString() + " 本重点", Modifier.padding(start = 6.dp), color = palette.muted)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onInfo, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Info, "说明", Modifier.size(17.dp), tint = palette.muted) }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    CompanionChatMessageList(entries, scroll, Modifier.fillMaxSize().padding(horizontal = 16.dp)) { entry ->
                        when (entry) {
                            is ChatEntry.Scene -> ChatSceneDivider(entry.text, palette)
                            is ChatEntry.Bubble -> {
                                val parsed = remember(entry.part.text) { LibraryCitationParser.parse(entry.part.text) }
                                CompanionChatBubble(
                                    entry.copy(part = CompanionBubblePart.Text(parsed.text)), palette,
                                    persona?.name ?: "伴读", persona?.avatarPath, appearance, font,
                                    readOnlyActions = session.busy || messages.reply.running || isLoading,
                                    onEdit = { scroll.pauseFollowing(); entry.message?.let(onEdit) },
                                    onDelete = { scroll.pauseFollowing(); entry.message?.let(onDelete) },
                                    onReroll = { scroll.requestFollowLatest(); entry.message?.let(onReroll) },
                                    onBranch = { entry.message?.let(onBranch) },
                                    footer = {
                                        if (!entry.fromUser && !entry.streaming) parsed.citations.forEach { citation ->
                                            val book = session.scopes.firstOrNull { it.bookId == citation.bookId }
                                            if (book != null) TextButton(onClick = { onLocate(citation) }) {
                                                Text(book.title.take(12) + " · 第" + (citation.chapterIndex + 1) + "章 ↗", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                                            }
                                        }
                                    }
                                )
                            }
                            is ChatEntry.Status -> Text(entry.text, style = MaterialTheme.typography.labelSmall, color = palette.muted)
                            is ChatEntry.ErrorLine -> Text(entry.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            else -> Unit
                        }
                    }
                    if (!scroll.followingLatest) TextButton(onClick = { coroutineScope.launch { scroll.returnToLatest() } }, modifier = Modifier.align(Alignment.BottomCenter)) { Text("回到底部") }
                }
                if (!messages.reply.running && messages.rows.isNotEmpty() &&
                    (messages.reply.error != null || messages.reply.status == "已停止" || messages.rows.last().role == "user")) {
                    TextButton(onClick = { scroll.requestFollowLatest(); onRetry() }, enabled = !session.busy,
                        modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(if (messages.rows.last().role == "user") "继续回复" else "重新生成")
                    }
                }
                messages.organizationPlans.lastOrNull { it.plan.status == "PENDING" }?.let { pending ->
                    Surface(onClick = onOpenPlans, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        color = palette.glassStrong, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp), tint = palette.accent)
                            Text("整理方案 · " + pending.plan.changes.size + " 本书", Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.labelLarge)
                            Text("待确认 ›", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                        }
                    }
                }
                CompanionComposer(
                    input = session.draft, onInputChange = onInput, attachments = emptyList(), onRemoveAttachment = {},
                    actions = listOf(
                        ComposerAction(Icons.AutoMirrored.Outlined.MenuBook, "选书", onPickBooks),
                        ComposerAction(Icons.Outlined.FolderOpen, "整理书架", { onInput("帮我整理书架，先给我看看方案。") }),
                        ComposerAction(Icons.Outlined.CalendarMonth, "陪伴足迹", onOpenStats)
                    ) + if (messages.organizationPlans.isEmpty()) emptyList() else listOf(ComposerAction(Icons.Outlined.FolderOpen, "整理记录", onOpenPlans)),
                    isStreaming = messages.reply.running, palette = palette,
                    onSend = { scroll.requestFollowLatest(); onSend() }, onStop = onStop,
                    enabled = !session.busy, placeholder = "聊聊书，也聊聊你…"
                )
            }
        }
    }
}

internal fun libraryChatEntries(scopes: List<LibraryBookScope>, messages: LibraryChatMessages, greeting: String = ""): List<ChatEntry> = buildList {
    add(ChatEntry.Scene(if (scopes.isEmpty()) "从这里开始聊" else scopes.take(3).joinToString(" · ") { "《" + it.title.take(12) + "》" }))
    if (messages.rows.isEmpty() && greeting.isNotBlank()) add(ChatEntry.Bubble("library-greeting", CompanionBubblePart.Text(greeting), false))
    messages.rows.forEach { message ->
        add(ChatEntry.Bubble(message.clientRoundId ?: "library-" + message.id, CompanionBubblePart.Text(message.content), message.role == "user", message,
            timestamp = message.createdAt, canReroll = message.role == "assistant" && messages.rows.any { it.role == "user" && it.id < message.id }))
    }
    val reply = messages.reply
    if (reply.text.isNotBlank() && messages.rows.none { it.clientRoundId == reply.roundId }) {
        add(ChatEntry.Bubble(reply.roundId ?: "library-live", CompanionBubblePart.Text(reply.text), false, streaming = true))
    }
    reply.status?.let { add(ChatEntry.Status(it)) }
    reply.error?.let { add(ChatEntry.ErrorLine(it)) }
}.withBubbleGrouping()
