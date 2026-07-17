package com.aniki.anikiai.ui.feed

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniki.anikiai.data.db.ItemEntity
import com.aniki.anikiai.data.db.ItemStatus
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
    /**
     * @param demoLandingItemId the onboarding demo item's id, only on the one Feed session where
     *   it should play its one-time "you just shared this" landing animation (see
     *   FeedViewModel.takeFreshSnapshot()); null otherwise, including every later session. Cleared to null
     *   by [FeedViewModel.onLandingAnimationPlayed] once FeedCard finishes playing it, so
     *   scrolling away and back within the same session doesn't replay it either.
     */
    data class Content(val items: List<ItemWithTags>, val demoLandingItemId: String? = null) : FeedUiState
}

/**
 * The Feed order is a **frozen per-session snapshot**, not a live Room Flow: engagement writes
 * (lastShownAt, SHOWN/DWELL events) and background ranking changes would otherwise re-sort the
 * pager under the user's thumb.
 *
 * Slice 2, item 1: "session" is the whole process run (see the companion object). Entering the Feed
 * ([onEnter]) re-projects current item data onto the *existing* frozen order without reordering, so
 * opening an item and coming back lands on the same card. The order is recomputed only on an
 * explicit refresh — the session's first open, or the "Feed updated" pill ([refreshFeed]) — both
 * funneling through [applyFreshSnapshot]. When the underlying ranking inputs drift from the
 * snapshot (a new enrichment, a star toggled, a delete), the pill offers that refresh; ordinary
 * swiping/opening never trips it. Live per-card state (star) is patched in place without reordering.
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

    // --- Refresh-available pill (Slice 2, item 4) ---

    /** True once the live library has diverged from the frozen snapshot's ranking inputs (a newly
     *  enriched item, a star toggled, an item trashed). The Feed shows the "Feed updated" pill;
     *  tapping it ([refreshFeed]) re-ranks and clears this. */
    private val _refreshAvailable = MutableStateFlow(false)
    val refreshAvailable: StateFlow<Boolean> = _refreshAvailable

    init {
        // Watch the live library so a background change while the Feed is open — enrichment
        // finishing, a star toggled from Detail, a delete — flips the pill without re-ranking. Cheap
        // set comparison against the baseline captured at the last refresh; skipped until the first
        // snapshot exists. viewModelScope-bound, so it stops when this ViewModel is destroyed (a
        // change that lands while it's dead is caught instead by reprojectFrozen on the next entry).
        viewModelScope.launch {
            repository.observeAllItemsWithTags().collect { live ->
                val baseline = sessionFingerprint ?: return@collect
                if (fingerprintOf(live) != baseline) _refreshAvailable.value = true
            }
        }
    }

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

        // Slice 2, item 1 — stable feed session ordering. The frozen feed order for THIS process
        // run lives here (not in the ViewModel instance) so it survives ViewModel recreation across
        // Feed<->Library tab switches and Feed->Detail->back, and resets only on process death.
        // "Session = process lifetime, reset only on cold start" — same precedent as the swipe-hint
        // flags above. Re-entering the Feed re-projects current item data onto this exact order
        // (never re-ranks); only an explicit refresh (first open, or the pill) recomputes it.
        private var sessionOrderIds: List<String>? = null

        // The item the pager last settled on this session. Restored as the Feed's initial page when
        // it's re-entered after a Detail round-trip (item 1: come back to the card you opened, not
        // the top). Process-scoped like the order it indexes into, so it survives the round-trip
        // even if the ViewModel is recreated.
        private var sessionLastSettledItemId: String? = null

        // The ranking-input baseline captured at the last full refresh: the set of "id:starred" over
        // ENRICHED items. Staleness (the pill) = the live DB fingerprint diverging from this. It
        // deliberately excludes view-tracking fields (lastShownAt/lastViewedAt/engagement) so
        // ordinary swiping and opening never trip the pill — only content/preference changes do
        // (item 4: "anything that changes the ranking should trigger it").
        private var sessionFingerprint: Set<String>? = null

        /** The ranking-input fingerprint of a library snapshot (see [sessionFingerprint]). */
        private fun fingerprintOf(items: List<ItemWithTags>): Set<String> =
            items.asSequence()
                .filter { it.item.status == ItemStatus.ENRICHED }
                .map { "${it.item.id}:${it.item.isStarred}" }
                .toSet()
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

    /**
     * The Feed entered composition — first open this process, a tab return, or back from Detail.
     * On the session's first entry there's no frozen order yet, so it takes a fresh ranked snapshot.
     * On every later entry it re-projects the current item data onto the *existing* frozen order
     * (so star marks and edits made elsewhere show) but never reorders — that's item 1's whole
     * point: opening an item and coming back must land on the same card, not a reshuffled feed.
     */
    fun onEnter() {
        logSessionSummary() // in case onLeaveFeed was skipped (e.g. process death) since the last open
        viewModelScope.launch {
            val frozen = sessionOrderIds
            if (frozen == null) applyFreshSnapshot() else reprojectFrozen(frozen)
        }
    }

    /**
     * The "Feed updated" pill was tapped (item 4) — the one in-session path that changes the frozen
     * order. Re-ranks from scratch (starred items regroup to the top, newly-enriched items join)
     * and clears the pill.
     *
     * Deliberately a `suspend fun` the caller awaits directly, rather than a fire-and-forget event
     * the ViewModel reacts to: FeedScreen owns the whole blur-then-swap-then-scroll-then-unblur
     * choreography and needs to know exactly when the new order has actually landed in [state], so
     * it can hold off applying it until the screen is already blurred. Racing two independently
     * observed StateFlows (state changing immediately, a separate trigger reacting after) used to
     * let the reordered card flash into view, unblurred, for a frame or two before the hide caught
     * up. Still launched on [viewModelScope] internally (not the caller's own scope) so a refresh
     * survives even if the caller is torn down mid-flight -- e.g. the user switches tabs while the
     * blur is still ramping.
     */
    suspend fun refreshFeed() {
        viewModelScope.launch { applyFreshSnapshot() }.join()
    }

    /** Recompute the ranked order from the live library and make it the new frozen session order. */
    private suspend fun applyFreshSnapshot() {
        val snapshot = repository.getItemsWithTagsSnapshot()
        cachedWeights = repository.computeUserTagWeights()
        // Ranking is pure CPU work (sort + scoring pass) — off the main dispatcher so a large
        // library doesn't jank the UI thread. Same result either way.
        val items = withContext(Dispatchers.Default) {
            val byId = snapshot.associateBy { it.item.id }
            val ordered = buildFeed(snapshot.map { it.toCandidate() }, cachedWeights, System.currentTimeMillis())
            ordered.mapNotNull { byId[it.id] }
        }
        sessionOrderIds = items.map { it.item.id }
        sessionFingerprint = fingerprintOf(snapshot)
        _refreshAvailable.value = false

        // The onboarding demo item's one-time landing animation: eligible exactly once ever, the
        // first fresh snapshot that sees it un-animated (never on a reprojection). Persisted
        // immediately (not deferred to FeedCard playing it) so a re-entry into Feed before this
        // session ends, or a process death mid-animation, can never re-trigger it -- only the
        // in-memory demoLandingItemId (cleared by onLandingAnimationPlayed) gates a same-session replay.
        val demoLandingItemId = items.firstOrNull { it.item.isDemo && !it.item.demoLandingAnimationShown }?.item?.id
        if (demoLandingItemId != null) {
            repository.markDemoLandingAnimationShown(demoLandingItemId)
        }
        _state.value = if (items.isEmpty()) FeedUiState.Empty else FeedUiState.Content(items, demoLandingItemId)
        Timber.i("feed snapshot: candidates=%d", items.size)
    }

    /**
     * Re-render the frozen [orderIds] with current item data (star flags, edits) without reordering
     * or admitting newly-enriched items — those wait for the next explicit refresh (item 1). Also
     * catches a ranking-input change that landed while this ViewModel was dead (so the collector in
     * [init] never saw it), so the pill still shows on return.
     */
    private suspend fun reprojectFrozen(orderIds: List<String>) {
        val snapshot = repository.getItemsWithTagsSnapshot()
        val byId = snapshot.associateBy { it.item.id }
        val items = orderIds.mapNotNull { byId[it] } // preserve order; drop anything trashed/removed
        _state.value = if (items.isEmpty()) FeedUiState.Empty else FeedUiState.Content(items)
        sessionFingerprint?.let { if (fingerprintOf(snapshot) != it) _refreshAvailable.value = true }
        Timber.i("feed reprojected: candidates=%d", items.size)
    }

    /** FeedCard finished playing the demo item's landing animation -- clear it so scrolling away
     *  and back within this same session doesn't replay it. */
    fun onLandingAnimationPlayed() {
        val current = _state.value
        if (current is FeedUiState.Content && current.demoLandingItemId != null) {
            _state.value = current.copy(demoLandingItemId = null)
        }
    }

    private fun logSessionSummary() {
        if (sessionEventCounts.isEmpty()) return
        Timber.i("feed session ended: %s", sessionEventCounts.entries.joinToString { "${it.key}=${it.value}" })
        sessionEventCounts.clear()
    }

    /** The card index the Feed should open on when re-entered (item 1). -1 if none/unknown, which
     *  [FeedScreen] coerces to the first page. */
    fun resumePageIn(items: List<ItemWithTags>): Int =
        sessionLastSettledItemId?.let { id -> items.indexOfFirst { it.item.id == id } } ?: -1

    /** Pager settled on a new card: close out the previous card's dwell, then mark the new one shown. */
    fun onPageSettled(itemId: String) {
        onInteraction()
        sessionLastSettledItemId = itemId // remembered so a Detail round-trip returns to this card
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
        // Drop it from the frozen order too, so a Detail round-trip / tab return doesn't re-project
        // the dismissed card back in (reprojectFrozen rebuilds strictly from sessionOrderIds).
        sessionOrderIds = sessionOrderIds?.filterNot { it == itemId }
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
