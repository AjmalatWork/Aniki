package com.aniki.anikiai.sync

import com.aniki.anikiai.data.remote.AnikiApi
import com.aniki.anikiai.data.remote.SyncPushRequest
import com.aniki.anikiai.data.repository.SyncRepository
import timber.log.Timber

sealed class SyncResult {
    data object Unauthenticated : SyncResult()
    data class Success(val pulled: Int, val pushed: Int, val conflicts: Int) : SyncResult()
    data class Error(val message: String) : SyncResult()
}

/**
 * pull -> merge -> push -> apply acks -> persist cursor, in that order, matching the spec
 * exactly. The cursor is written exactly once, at the very end: if the process dies anywhere
 * before that, the next run simply re-pulls from the old cursor (a no-op for anything already
 * merged, since decideMerge treats an identical re-pull as NO_OP) and re-pushes anything still
 * dirty (safe: the server's own content-equality check makes a retried push a no-op too).
 *
 * The server pages a pull (SyncPullResponse.hasMore) once a user's backlog exceeds its page size,
 * so a single pullSync() call may not return everything. The loop below keeps pulling pages
 * (merging each incrementally as it arrives -- merges are idempotent, so replaying earlier pages
 * on a retry is harmless) until hasMore is false, still without persisting the cursor until the
 * very end -- a crash mid-pagination just means the whole pull restarts from the old cursor next
 * run, exactly like the single-page case always worked.
 */
class SyncManager(
    private val api: AnikiApi,
    private val repository: SyncRepository,
    private val cursorStore: SyncCursorStore
) {
    suspend fun runSync(): SyncResult {
        return try {
            val cursor = cursorStore.getCursor()

            var pullCursor = cursor
            var pulledItemCount = 0
            var conflicts = 0
            var hasMore = true
            while (hasMore) {
                val pullResponse = api.pullSync(pullCursor)
                if (pullResponse.code() == 401) return SyncResult.Unauthenticated
                if (!pullResponse.isSuccessful) return SyncResult.Error("Pull failed: HTTP ${pullResponse.code()}")
                val pulled = pullResponse.body() ?: return SyncResult.Error("Empty pull response")

                // Tags before items/itemTags: a tag label must resolve locally before anything
                // references its (possibly remapped) id.
                pulled.tags.forEach { repository.mergeTag(it) }

                // P1: collect every item touched by its own row or any of its tag-links across
                // this whole page into one Set, then re-index each unique id exactly once below --
                // instead of each of mergeItem/mergeItemTag indexing synchronously per row, which
                // used to mean an item with T changed tag-links got T+1 full FTS rebuilds in one page.
                val itemsToReindex = mutableSetOf<String>()
                for (item in pulled.items) {
                    val outcome = repository.mergeItem(item)
                    if (outcome.conflicted) conflicts++
                    outcome.reindexItemId?.let { itemsToReindex += it }
                }
                for (itemTag in pulled.itemTags) {
                    repository.mergeItemTag(itemTag)?.let { itemsToReindex += it }
                }
                repository.reindexItems(itemsToReindex)

                pulled.engagementEvents.forEach { repository.mergeEngagementEvent(it) }

                pulledItemCount += pulled.items.size
                pullCursor = maxOf(pullCursor, pulled.nextCursor)
                hasMore = pulled.hasMore
            }

            var finalCursor = pullCursor

            val dirtyItems = repository.getDirtyItemDtos()
            val dirtyTags = repository.getDirtyTagDtos()
            val dirtyItemTags = repository.getDirtyItemTagDtos()
            val dirtyEvents = repository.getDirtyEngagementEventDtos()
            val pushCount = dirtyItems.size + dirtyTags.size + dirtyItemTags.size + dirtyEvents.size

            if (pushCount > 0) {
                val pushResponse = api.pushSync(
                    SyncPushRequest(
                        items = dirtyItems,
                        tags = dirtyTags,
                        itemTags = dirtyItemTags,
                        engagementEvents = dirtyEvents
                    )
                )
                if (pushResponse.code() == 401) return SyncResult.Unauthenticated
                if (!pushResponse.isSuccessful) return SyncResult.Error("Push failed: HTTP ${pushResponse.code()}")
                val pushed = pushResponse.body() ?: return SyncResult.Error("Empty push response")

                // Tags first so a remap (client id -> server canonical id) lands before we clear
                // dirty on anything that might reference the old id.
                pushed.tagAcks.forEach { repository.applyTagAck(it) }
                pushed.itemAcks.forEach { repository.applyItemAck(it) }
                pushed.itemTagAcks.forEach { repository.applyItemTagAck(it) }
                pushed.engagementEventAcks.forEach { repository.applyEngagementEventAck(it) }

                finalCursor = maxOf(finalCursor, pushed.nextCursor)
            }

            cursorStore.setCursor(finalCursor)
            Timber.i("sync run: pulled=%d pushed=%d conflicts=%d", pulledItemCount, pushCount, conflicts)
            SyncResult.Success(pulled = pulledItemCount, pushed = pushCount, conflicts = conflicts)
        } catch (e: Exception) {
            Timber.w(e, "sync run failed")
            SyncResult.Error(e.message ?: "Unknown sync error")
        }
    }
}
