package com.aniki.anikiai.work

import com.aniki.anikiai.data.repository.ItemRepository
import java.util.concurrent.TimeUnit
import timber.log.Timber

// 30 days: long enough to cover "I didn't mean to delete that" for typical app-checking cadence,
// short enough that Trash doesn't grow unbounded on a device with heavy churn. Revisit if usage
// data suggests otherwise.
private val TRASH_RETENTION_MS = TimeUnit.DAYS.toMillis(30)

/**
 * Client-side mirror of the server's tombstone GC (server/src/index.ts's daily sweep, which
 * purges Postgres tombstones after TOMBSTONE_RETENTION_DAYS, default 90 days) -- this one hard-
 * deletes the local Room row once a soft-deleted (Trash) item has passed its own, shorter
 * retention window. Piggybacks on a *successful* sync (see SyncWorker), matching
 * ThumbnailBackfiller/NoteTitleBackfiller, since ItemRepository.purgeExpiredTrash only ever
 * touches already-synced (dirty=false) rows -- there's nothing to purge until a sync has run.
 */
object TrashPurger {
    suspend fun run(repository: ItemRepository) {
        val purged = repository.purgeExpiredTrash(TRASH_RETENTION_MS)
        if (purged > 0) Timber.i("trash purge: removed=%d", purged)
    }
}
