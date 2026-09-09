package com.mozhi.reader.feature.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import com.mozhi.reader.ui.components.CommittedDrafts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Save errors are decisions, not swallowed callbacks or implicit permission to navigate. */
@Stable
internal class DraftSaveGate(
    private val scope: CoroutineScope,
    private val prepare: () -> Unit,
    private val flush: suspend (Boolean) -> Boolean,
    private val confirmed: () -> Unit,
    private val discarded: () -> Unit
) {
    var saving by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var canDiscard by mutableStateOf(false)
        private set
    private var pendingAction: (() -> Unit)? = null

    fun run(navigation: Boolean = false, retry: Boolean = false, action: () -> Unit) {
        if (saving) return
        pendingAction = action
        canDiscard = navigation
        failed = false
        saving = true
        prepare()
        scope.launch {
            val success = try { flush(retry) } finally { saving = false }
            if (success) {
                confirmed()
                pendingAction = null
                action()
            } else failed = true
        }
    }

    fun retry() { pendingAction?.let { run(canDiscard, retry = true, action = it) } }
    fun cancel() { failed = false; pendingAction = null }
    fun discard() {
        if (!failed || !canDiscard || saving) return
        val action = pendingAction
        discarded()
        cancel()
        action?.invoke()
    }
}

@Composable
internal fun rememberDraftSaveGate(
    drafts: CommittedDrafts,
    flush: suspend (Boolean) -> Boolean,
    discardFailures: () -> Unit
): DraftSaveGate {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val currentFlush by rememberUpdatedState(flush)
    val currentDiscard by rememberUpdatedState(discardFailures)
    return remember(drafts, scope) {
        DraftSaveGate(scope, { focus.clearFocus(); drafts.flush() },
            { retry -> currentFlush(retry) }, drafts::confirmFlushed,
            { drafts.discard(); currentDiscard() })
    }
}

@Composable
internal fun DraftSaveDialogs(gate: DraftSaveGate) {
    if (gate.saving) AlertDialog(
        onDismissRequest = {}, title = { Text("正在保存修改") },
        text = { CircularProgressIndicator() }, confirmButton = {}
    )
    if (gate.failed) AlertDialog(
        onDismissRequest = gate::cancel,
        title = { Text("修改未能保存") },
        text = { Text("草稿仍保留在此页。请重试，或取消操作继续编辑。" +
            if (gate.canDiscard) "也可以明确放弃尚未保存的修改后离开。" else "") },
        confirmButton = { TextButton(onClick = gate::retry) { Text("重试保存") } },
        dismissButton = {
            TextButton(onClick = gate::cancel) { Text("取消") }
            if (gate.canDiscard) TextButton(onClick = gate::discard) { Text("放弃未保存修改并离开") }
        }
    )
}
