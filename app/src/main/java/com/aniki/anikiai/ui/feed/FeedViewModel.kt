package com.aniki.anikiai.ui.feed

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniki.anikiai.data.db.ItemEntity
import com.aniki.anikiai.data.db.ItemWithTags
import com.aniki.anikiai.data.repository.ItemRepository
import com.aniki.anikiai.feed.FeedCandidate
import com.aniki.anikiai.feed.buildFeed
import com.aniki.anikiai.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

sealed interface FeedUiState {
    data object Loading : FeedUiState
    data object Empty : FeedUiState
    data class Content(val items: List<ItemWithTags>) : FeedUiState
}

/**
 * The Feed order is a **frozen per-session snapshot**, not a live Room Flow: engagement writes
 * (lastShownAt, SHOWN/DWELL events) would otherwise re-sort the pager under the user's thumb.
 * refresh() takes a fresh snapshot + recomputes tag weights — called each time the Feed is opened,
 * so seen-penalties recorded this session reshuffle the *next* open ("no immediate repeats").
 * Live per-card state (star) is patched in place without reordering.
 */
class FeedViewModel(
    private val repository: ItemRepository,
    private val appContext: Context
) : ViewModel() {

    private val _state = MutableStateFlow<FeedUiState>(FeedUiState.Loading)
    val state: StateFlow<FeedUiState> = _state

    private var cachedWeights: Map<String, Double> = emptyMap()
    private var focusedItemId: String? = null
    private var focusedAtMs: Long = 0L

    /** Per-session engagement tally for observability — logged on refresh()/onLeaveFeed(), reset each open. */
    private val sessionEventCounts = mutableMapOf<String, Int>()

    private fun tally(eventType: String) {
        sessionEventCounts[eventType] = (sessionEventCounts[eventType] ?: 0) + 1
    }

    fun refresh() {
        logSessionSummary() // in case onLeaveFeed was skipped (e.g. process death) since the last open
        viewModelScope.launch {
            val snapshot = repository.getItemsWithTagsSnapshot()
            cachedWeights = repository.computeUserTagWeights()
            // Ranking is pure CPU work (sort + scoring pass) — off the main dispatcher so a large
            // library doesn't jank the UI thread on every Feed open. Same result either way.
            val items = withContext(Dispatchers.Default) {
                val byId = snapshot.associateBy { it.item.id }
                val ordered = buildFeed(snapshot.map { it.toCandidate() }, cachedWeights, System.currentTimeMillis())
                ordered.mapNotNull { byId[it.id] }
            }
            _state.value = if (items.isEmpty()) FeedUiState.Empty else FeedUiState.Content(items)
            Timber.i("feed opened: candidates=%d", items.size)
        }
    }

    private fun logSessionSummary() {
        if (sessionEventCounts.isEmpty()) return
        Timber.i("feed session ended: %s", sessionEventCounts.entries.joinToString { "${it.key}=${it.value}" })
        sessionEventCounts.clear()
    }

    /** Pager settled on a new card: close out the previous card's dwell, then mark the new one shown. */
    fun onPageSettled(itemId: String) {
        if (focusedItemId == itemId) return
        val now = System.currentTimeMillis()
        val previous = focusedItemId
        if (previous != null) recordDwell(previous, now - focusedAtMs)
        focusedItemId = itemId
        focusedAtMs = now
        tally("SHOWN")
        viewModelScope.launch {
            repository.markShown(itemId)
            enqueueSync()
        }
    }

    /** Leaving the Feed: bank the dwell for whatever card was focused. */
    fun onLeaveFeed() {
        logSessionSummary()
        val id = focusedItemId ?: return
        recordDwell(id, System.currentTimeMillis() - focusedAtMs)
        focusedItemId = null
    }

    fun onOpen(itemId: String) {
        tally("OPENED")
        viewModelScope.launch {
            repository.recordOpen(itemId)
            enqueueSync()
        }
    }

    fun onToggleStar(itemId: String, starred: Boolean) {
        patchItem(itemId) { it.copy(isStarred = starred) } // reflect immediately, no reorder
        if (starred) tally("STARRED")
        viewModelScope.launch {
            repository.setStarred(itemId, starred)
            enqueueSync()
        }
    }

    fun onDismiss(itemId: String) {
        val current = _state.value
        if (current is FeedUiState.Content) {
            val remaining = current.items.filterNot { it.item.id == itemId }
            _state.value = if (remaining.isEmpty()) FeedUiState.Empty else FeedUiState.Content(remaining)
        }
        tally("DISMISSED")
        viewModelScope.launch {
            repository.recordDismiss(itemId)
            enqueueSync()
        }
    }

    private fun recordDwell(itemId: String, dwellMs: Long) {
        tally("DWELL")
        viewModelScope.launch {
            repository.recordDwell(itemId, dwellMs)
            enqueueSync()
        }
    }

    private fun patchItem(itemId: String, transform: (ItemEntity) -> ItemEntity) {
        val current = _state.value
        if (current is FeedUiState.Content) {
            _state.value = FeedUiState.Content(
                current.items.map { if (it.item.id == itemId) it.copy(item = transform(it.item)) else it }
            )
        }
    }

    private fun enqueueSync() = SyncWorker.enqueueOneTime(appContext)
}

private fun ItemWithTags.toCandidate(): FeedCandidate = FeedCandidate(
    id = item.id,
    type = item.type,
    createdAt = item.createdAt,
    lastViewedAt = item.lastViewedAt,
    lastShownAt = item.lastShownAt,
    isStarred = item.isStarred,
    eventDate = item.eventDate,
    status = item.status,
    tags = tags.map { it.label }
)
