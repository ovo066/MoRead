package com.mozhi.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** The IME owns the draft; asynchronous persistence never owns an active edit. */
@Stable
class CommittedTextFieldState(initial: String) {
    var value by mutableStateOf(TextFieldValue(initial))
        private set
    var dirty by mutableStateOf(false)
        private set
    var focused by mutableStateOf(false)
        private set
    private var persisted = initial
    private var pending: String? = null
    private var awaitingEcho: String? = null
    internal var writeVersion: Long = 0
        private set
    internal var onCommit: (String) -> Unit = {}
    internal var normalize: (String) -> String = { it.trim() }

    fun edit(next: TextFieldValue) {
        value = next // Keep selection AND composition, including intermediate whitespace.
        dirty = next.text != (pending ?: persisted)
    }

    fun receivePersisted(next: String) {
        if (pending != null) return // Only the write result acknowledges/rejects a pending commit.
        awaitingEcho?.let { expected ->
            if (next != expected) return
            awaitingEcho = null
        }
        if (dirty) return
        persisted = next
        if (!focused && !dirty && value.text != persisted) {
            value = TextFieldValue(persisted, TextRange(value.selection.end.coerceAtMost(persisted.length)))
        }
    }

    fun focusChanged(hasFocus: Boolean) {
        val lostFocus = focused && !hasFocus
        focused = hasFocus
        if (lostFocus) commit()
        if (!hasFocus && pending == null && !dirty && value.text != persisted) {
            value = TextFieldValue(persisted, TextRange(value.selection.end.coerceAtMost(persisted.length)))
        }
    }

    fun commit() {
        if (!dirty) return
        val committed = normalize(value.text)
        if (pending == committed) return
        pending = committed
        writeVersion++
        onCommit(committed)
    }

    fun completeWrite(committed: String, succeeded: Boolean, version: Long = writeVersion) {
        if (version != writeVersion || pending != committed) return // A superseded write cannot acknowledge the new draft.
        pending = null
        if (succeeded) {
            persisted = committed
            awaitingEcho = committed
        }
        dirty = normalize(value.text) != persisted
        if (succeeded && !focused && !dirty && value.text != persisted) {
            value = TextFieldValue(persisted, TextRange(value.selection.end.coerceAtMost(persisted.length)))
        }
    }

    fun confirmFlushed() { pending?.let { completeWrite(it, true) } }

    /** A deliberate provider/preset selection replaces clean fields, unlike storage echoes. */
    fun acceptExternalChange() { awaitingEcho = null }

    fun discard() {
        pending = null
        awaitingEcho = null
        dirty = false
        value = TextFieldValue(persisted)
    }

    companion object {
        val Saver = listSaver<CommittedTextFieldState, Any>(
            save = {
                listOf(it.value.text, it.value.selection.start, it.value.selection.end,
                    it.value.composition?.start ?: -1, it.value.composition?.end ?: -1,
                    it.persisted, it.dirty || it.pending != null)
            },
            restore = {
                CommittedTextFieldState(it[5] as String).apply {
                    val compositionStart = it[3] as Int
                    value = TextFieldValue(it[0] as String, TextRange(it[1] as Int, it[2] as Int),
                        if (compositionStart < 0) null else TextRange(compositionStart, it[4] as Int))
                    dirty = it[6] as Boolean
                    // A restored process may not have the old write job. Re-submit on flush.
                    pending = null
                }
            }
        )
    }
}

/** Register all fields outside conditional tabs so leaving a tab never drops its draft. */
class CommittedDrafts {
    internal val fields = linkedMapOf<String, CommittedTextFieldState>()
    fun flush() { fields.values.forEach { it.commit() } }
    fun confirmFlushed() { fields.values.forEach { it.confirmFlushed() } }
    fun discard() { fields.values.forEach { it.discard() } }
    fun acceptExternalChanges() { fields.values.forEach { it.acceptExternalChange() } }
}

@Composable
fun rememberCommittedDrafts(): CommittedDrafts {
    val drafts = remember { CommittedDrafts() }
    DisposableEffect(drafts) { onDispose { drafts.flush() } }
    return drafts
}

@Composable
fun rememberCommittedTextFieldState(
    drafts: CommittedDrafts,
    key: String,
    persistedValue: String,
    trim: Boolean = true,
    onCommit: (String) -> Deferred<Boolean>
): CommittedTextFieldState {
    val scope = rememberCoroutineScope()
    val state = rememberSaveable(key, saver = CommittedTextFieldState.Saver) {
        CommittedTextFieldState(persistedValue)
    }
    SideEffect {
        state.onCommit = { text ->
            val version = state.writeVersion
            val result = onCommit(text) // Enqueue synchronously before any action barrier.
            scope.launch(start = CoroutineStart.UNDISPATCHED) { state.completeWrite(text, result.await(), version) }
        }
        state.normalize = if (trim) ({ it.trim() }) else ({ it })
        drafts.fields[key] = state
        state.receivePersisted(persistedValue)
    }
    return state
}

fun Modifier.committedDraft(state: CommittedTextFieldState): Modifier =
    onFocusChanged { state.focusChanged(it.isFocused) }
