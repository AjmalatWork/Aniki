package com.aniki.anikiai.work

import com.aniki.anikiai.data.repository.ItemRepository
import java.util.concurrent.TimeUnit
import timber.log.Timber

// 30 days, same window as TrashPurger: once an event is synced (dirty=0), its signal is already
// folded into items.engagementSignal (see ItemRepository.recordEvent / computeUserTagWeights), so
// the raw row is disposable well before the server's own, longer retention window -- a brand-new
// device still bootstraps its aggregate from whatever the server hasn't pruned yet.
private val ENGAGEMENT_EVENT_RETENTION_MS = TimeUnit.DAYS.toMillis(30)

/**
 * Client-side retention half of the S1/S4 scalability pass: once an engagement_events row has
 * been pushed (dirty=0) and its signal folded into its item's engagementSignal total, the raw row
 * is dead weight -- this hard-deletes it locally past [ENGAGEMENT_EVENT_RETENTION_MS]. Piggybacks
 * on a *successful* sync (see SyncWorker), matching TrashPurger/ThumbnailBackfiller/
 * NoteTitleBackfiller, since ItemRepository.pruneOldEngagementEvents only ever touches
 * already-synced rows -- there's nothing to purge until a sync has run.
 */
object EngagementEventPurger {
    suspend fun run(repository: ItemRepository) {
        val purged = repository.pruneOldEngagementEvents(ENGAGEMENT_EVENT_RETENTION_MS)
        if (purged > 0) Timber.i("engagement event purge: removed=%d", purged)
    }
}
