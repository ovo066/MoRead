package com.mozhi.reader.feature.importer

import com.mozhi.reader.core.importer.DuplicateBookException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Serial, checkpointed execution. A stopped worker leaves the current entry available for retry. */
internal class BatchImportRunner(
    private val importBook: suspend (String, (String, Int, Int) -> Unit) -> Long,
    private val assignGroup: suspend (Long, String) -> Unit,
    private val persist: suspend (BatchImportCheckpoint) -> Unit,
    private val deleteSource: (String) -> Unit,
    private val progress: (index: Int, uri: String, stage: String, completed: Int, total: Int) -> Unit,
    private val labelOf: (String) -> String = { it.substringAfterLast('/').substringAfterLast(':') },
    private val failureMessage: String = "Import failed",
    private val describeFailure: (Exception) -> String = { it.message ?: failureMessage }
) {
    suspend fun run(request: BatchImportRequestPayload): BatchImportCheckpoint {
        var checkpoint = request.checkpoint
        for (index in checkpoint.nextIndex until request.entries.size) {
            currentCoroutineContext().ensureActive()
            val entry = request.entries[index]
            val label = labelOf(entry.uri).take(200)
            progress(index, entry.uri, "", 0, 0)
            var canDelete = false
            val outcome = try {
                val bookId = checkpoint.importedBookId ?: importBook(entry.uri) { message, completed, total ->
                    progress(index, entry.uri, message, completed, total)
                }.also { bookId ->
                    checkpoint = checkpoint.copy(importedBookId = bookId)
                    // Record the committed book before group assignment, source cleanup, or advancing the cursor.
                    try {
                        withContext(NonCancellable) { persist(checkpoint) }
                    } catch (error: Exception) {
                        throw CheckpointException(error)
                    }
                }
                currentCoroutineContext().ensureActive()
                if (entry.groupPath.isNotBlank()) assignGroup(bookId, entry.groupPath)
                canDelete = true
                checkpoint.copy(succeeded = checkpoint.succeeded + 1)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: CheckpointException) {
                // Persistence failure must stop the batch, never be counted as a failed book and skipped.
                throw error
            } catch (duplicate: DuplicateBookException) {
                canDelete = true
                checkpoint.copy(skipped = checkpoint.skipped + 1,
                    skippedLabels = (checkpoint.skippedLabels + label).take(MAX_DETAILS))
            } catch (error: Exception) {
                checkpoint.copy(failed = checkpoint.failed + 1,
                    failures = (checkpoint.failures + "$label：${describeFailure(error)}".take(300)).take(MAX_DETAILS))
            }
            val next = outcome.copy(nextIndex = index + 1, importedBookId = null)
            withContext(NonCancellable) {
                persist(next)
                checkpoint = next
                // Checkpoint first: a retry must not attempt to reopen an already-deleted LAN source.
                if (request.deleteSourceAfterImport && canDelete) runCatching { deleteSource(entry.uri) }
            }
        }
        return checkpoint
    }

    private class CheckpointException(cause: Exception) : IllegalStateException("Could not save batch import progress", cause)
    private companion object { const val MAX_DETAILS = 8 }
}
