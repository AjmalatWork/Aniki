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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

private const val IDLE_HINT_DELAY_MS = 15_000L
private const val HINT_AUTO_DISMISS_MS = 3_000L

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

    // --- Swipe hint (first-run + idle re-trigger) ---

    private val feedPrefsStore = FeedPrefsStore(appContext)

    /** Bumped every time the hint should (re)play; the UI keys its peek-and-settle animation +
     *  3s auto-dismiss timer off this so two triggers in a row restart cleanly. */
    private val _hintTrigger = MutableStateFlow(0)
    val hintTrigger: StateFlow<Int> = _hintTrigger

    private val _showHint = MutableStateFlow(false)
    val showHint: StateFlow<Boolean> = _showHint

    private var hintAutoDismissJob: Job? = null
    private var idleTimerJob: Job? = null
    private var isFeedVisible = false

    companion object {
        // Session-scoped (this process run), not the ViewModel's own lifecycle: Navigation-Compose
        // may retain/recreate this ViewModel across Feed<->Library tab switches within one process
        // run, and "session" here means the whole run, not one screen visit. Resets naturally on
        // process death, which is the only thing allowed to reset it (per the spec).
        private var sessionPrefsLoaded = false
        private var isFirstSessionEver = false
    }

    /** Feed became the visible surface: on the true first-ever visit this process boots the
     *  first-run hint (and unlocks idle re-trigger for the rest of this session only); on any
     *  later visit it just arms the idle timer if this is still that first session. */
    fun onFeedVisible() {
        isFeedVisible = true
        viewModelScope.launch {
            if (!sessionPrefsLoaded) {
                sessionPrefsLoaded = true
                val alreadyShownBefore = feedPrefsStore.hasShownFirstOpenHint()
                isFirstSessionEver = !alreadyShownBefore
                if (!alreadyShownBefore) {
                    feedPrefsStore.markFirstOpenHintShown()
                    triggerHint()
                }
            }
            scheduleIdleTimer()
        }
    }

    /** Feed left the visible surface: the idle timer must not run off-screen, and any hint
     *  currently showing shouldn't linger into whatever screen replaces it. */
    fun onFeedHidden() {
        isFeedVisible = false
        idleTimerJob?.cancel()
        dismissHint()
    }

    /** Any tap/swipe on the Feed: dismisses an in-progress hint early and resets the idle clock. */
    fun onInteraction() {
        dismissHint()
        scheduleIdleTimer()
    }

    private fun scheduleIdleTimer() {
        idleTimerJob?.cancel()
        if (!isFeedVisible || !isFirstSessionEver) return
        idleTimerJob = viewModelScope.launch {
            delay(IDLE_HINT_DELAY_MS)
            triggerHint()
            scheduleIdleTimer() // still the first session -- eligible to replay after another idle gap
        }
    }

    private fun triggerHint() {
        hintAutoDismissJob?.cancel()
        _hintTrigger.value += 1
        _showHint.value = true
        hintAutoDismissJob = viewModelScope.launch {
            delay(HINT_AUTO_DISMISS_MS)
            _showHint.value = false
        }
    }

    private fun dismissHint() {
        hintAutoDismissJob?.cancel()
        _showHint.value = false
    }

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
        onInteraction()
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
