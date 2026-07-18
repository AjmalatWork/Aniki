package com.aniki.anikiai.ui.feed

import com.aniki.anikiai.data.db.ItemStatus
import com.aniki.anikiai.data.db.ItemWithTags

/**
 * Feed's process-scoped session state (M1 of the maintainability audit): everything that must
 * survive a [FeedViewModel] instance being torn down and recreated across Navigation-Compose
 * Feed<->Library tab switches, but should reset only on process death -- "session" means the whole
 * process run, not one screen visit or ViewModel lifecycle (see [FeedViewModel]'s own doc for the
 * product rationale: opening an item and coming back must land on the same card in the same frozen
 * order, not a reshuffled feed).
 *
 * Previously implemented as `private var`s in FeedViewModel's companion object. That worked
 * identically at runtime -- a companion object's members are exactly as process-scoped as an
 * application-lifetime singleton instance -- but made this state impossible to construct fresh (or
 * inject a controlled instance of) in a test, and would have silently become a shared-across-
 * instances footgun the moment two FeedViewModels were ever alive at once for genuinely different
 * purposes, since companion state has no notion of "which instance owns this."
 *
 * Extracted to a plain class instead, instantiated exactly once as an app-level singleton
 * ([com.aniki.anikiai.AnikiApplication.feedSessionState]) and threaded down through
 * [com.aniki.anikiai.ui.feed.FeedScreen]'s `viewModelFactory` into [FeedViewModel]'s constructor --
 * same actual lifetime and sharing semantics the companion object had (one process-wide instance,
 * reset only on cold start), but now a real, independently constructable and testable object
 * instead of anonymous static state. A test can construct a fresh `FeedSessionState()` and pass it
 * into a `FeedViewModel` directly, with no static state to reset between tests.
 */
class FeedSessionState {

    /** Whether [FeedViewModel.onFeedVisible] has resolved the first-run hint flag yet this process run. */
    var prefsLoaded: Boolean = false

    /** Whether this is the true first-ever session (unlocks idle-hint re-trigger for its duration only). */
    var isFirstSessionEver: Boolean = false

    /** The frozen Feed order for this process run -- null until the first
     *  [FeedViewModel]`.applyFreshSnapshot()`. Re-entering the Feed re-projects current item data
     *  onto this exact order (never re-ranks); only an explicit refresh recomputes it. */
    var orderIds: List<String>? = null

    /** The item the pager last settled on this session -- restored as the Feed's initial page when
     *  it's re-entered after a Detail round-trip. */
    var lastSettledItemId: String? = null

    /** The ranking-input baseline captured at the last full refresh: the set of "id:starred" over
     *  ENRICHED items. Staleness (the "Feed updated" pill) = the live DB fingerprint diverging from
     *  this. Deliberately excludes view-tracking fields (lastShownAt/lastViewedAt/engagement) so
     *  ordinary swiping and opening never trip the pill -- only content/preference changes do. */
    var fingerprint: Set<String>? = null

    companion object {
        /** The ranking-input fingerprint of a library snapshot (see [fingerprint]). */
        fun fingerprintOf(items: List<ItemWithTags>): Set<String> =
            items.asSequence()
                .filter { it.item.status == ItemStatus.ENRICHED }
                .map { "${it.item.id}:${it.item.isStarred}" }
                .toSet()
    }
}
