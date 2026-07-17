package com.aniki.anikiai.ui.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniki.anikiai.data.db.ItemType
import com.aniki.anikiai.data.db.ItemWithTags
import com.aniki.anikiai.data.repository.ItemRepository
import com.aniki.anikiai.sync.SyncWorker
import com.aniki.anikiai.util.isOnline
import com.aniki.anikiai.util.observeOnline
import com.aniki.anikiai.work.EnrichmentScheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TITLE_AUTOSAVE_DEBOUNCE_MS = 600L
private const val BODY_AUTOSAVE_DEBOUNCE_MS = 900L

class ItemDetailViewModel(
    private val repository: ItemRepository,
    private val appContext: Context,
    private val itemId: String
) : ViewModel() {

    val item: StateFlow<ItemWithTags?> = repository.observeItemWithTags(itemId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Drives the offline-queued status card (see ItemDetailScreen's PENDING branch). */
    val isOnline: StateFlow<Boolean> = observeOnline(appContext)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), isOnline(appContext))

    private var titleSaveJob: Job? = null
    private var bodySaveJob: Job? = null

    init {
        // Slice 5's future ranking depends on this; nothing consumes it yet.
        viewModelScope.launch { repository.markViewed(itemId) }
    }

    /**
     * Direct-edit autosave for titles, all item types (polish pass: no more pencil-tap gate,
     * editing starts the moment the screen opens). Debounced so every keystroke doesn't hit Room;
     * the trailing edit within the debounce window always wins since each call cancels the
     * previous pending job. Title-only edits never trigger re-enrichment -- see
     * ItemRepository.updateNoteBody's doc for why tags/title generation are keyed off body
     * content, not the title, so this is safe to reuse unchanged for articles/videos too.
     */
    fun updateTitleDraft(title: String) {
        titleSaveJob?.cancel()
        titleSaveJob = viewModelScope.launch {
            delay(TITLE_AUTOSAVE_DEBOUNCE_MS)
            if (title.isBlank()) return@launch
            repository.updateTitle(itemId, title)
            SyncWorker.enqueueOneTime(appContext)
        }
    }

    /** Same debounced-autosave shape as [updateTitleDraft], but a body change re-runs enrichment
     *  (same worker/path used at note creation) since tags are derived from body content. */
    fun updateBodyDraft(body: String) {
        bodySaveJob?.cancel()
        bodySaveJob = viewModelScope.launch {
            delay(BODY_AUTOSAVE_DEBOUNCE_MS)
            repository.updateNoteBody(itemId, body)
            SyncWorker.enqueueOneTime(appContext)
            if (body.isNotBlank()) EnrichmentScheduler.enqueue(appContext, itemId)
        }
    }

    /**
     * Called when the detail screen leaves composition -- back navigation (button, gesture, or
     * system key) or process-death risk aside -- so a debounced save still in flight isn't lost.
     * Cancels the pending debounce jobs and saves immediately instead of waiting out their delay.
     * `body` is only meaningful for notes (checked against the loaded item's actual type, not an
     * isNote flag from the caller, so this stays correct even if called defensively for a
     * non-note with an empty/stale body draft). viewModelScope survives past the composable's
     * onDispose (the NavBackStackEntry it's scoped to is destroyed slightly later), which is what
     * this relies on. If the app is killed before this coroutine finishes, the item is left
     * dirty/PENDING exactly like a mid-flight enrichment already is, and picked up by the same
     * cold-start reconciliation AnikiApplication.onCreate already runs for stuck PENDING items
     * (see EnrichmentScheduler.reconcilePending) -- no new failure mode introduced.
     */
    fun flushPendingEdits(title: String, body: String) {
        titleSaveJob?.cancel()
        bodySaveJob?.cancel()
        viewModelScope.launch {
            val current = repository.getItemById(itemId) ?: return@launch
            var changed = false
            if (title.isNotBlank() && title != current.title) {
                repository.updateTitle(itemId, title)
                changed = true
            }
            if (current.type == ItemType.NOTE && body != current.bodyText.orEmpty()) {
                repository.updateNoteBody(itemId, body)
                if (body.isNotBlank()) EnrichmentScheduler.enqueue(appContext, itemId)
                changed = true
            }
            if (changed) SyncWorker.enqueueOneTime(appContext)
        }
    }

    fun addTag(label: String) {
        if (label.isBlank()) return
        viewModelScope.launch {
            repository.addUserTag(itemId, label)
            SyncWorker.enqueueOneTime(appContext)
        }
    }

    fun removeTag(tagId: String) {
        viewModelScope.launch {
            repository.removeTag(itemId, tagId)
            SyncWorker.enqueueOneTime(appContext)
        }
    }

    fun toggleStar(starred: Boolean) {
        viewModelScope.launch {
            repository.setStarred(itemId, starred)
            SyncWorker.enqueueOneTime(appContext)
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deleteItem(itemId)
            SyncWorker.enqueueOneTime(appContext)
            onDeleted()
        }
    }

    fun retry() {
        EnrichmentScheduler.enqueue(appContext, itemId)
    }
}
