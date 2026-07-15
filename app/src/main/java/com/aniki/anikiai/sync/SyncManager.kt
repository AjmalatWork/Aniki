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
 */
class SyncManager(
    private val api: AnikiApi,
    private val repository: SyncRepository,
    private val cursorStore: SyncCursorStore
) {
    suspend fun runSync(): SyncResult {
        return try {
            val cursor = cursorStore.getCursor()

            val pullResponse = api.pullSync(cursor)
            if (pullResponse.code() == 401) return SyncResult.Unauthenticated
            if (!pullResponse.isSuccessful) return SyncResult.Error("Pull failed: HTTP ${pullResponse.code()}")
            val pulled = pullResponse.body() ?: return SyncResult.Error("Empty pull response")

            // Tags before items/itemTags: a tag label must resolve locally before anything
            // references its (possibly remapped) id.
            pulled.tags.forEach { repository.mergeTag(it) }
            val conflicts = pulled.items.count { repository.mergeItem(it) }
            pulled.itemTags.forEach { repository.mergeItemTag(it) }
            pulled.engagementEvents.forEach { repository.mergeEngagementEvent(it) }

            var finalCursor = maxOf(cursor, pulled.nextCursor)

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
            Timber.i("sync run: pulled=%d pushed=%d conflicts=%d", pulled.items.size, pushCount, conflicts)
            SyncResult.Success(pulled = pulled.items.size, pushed = pushCount, conflicts = conflicts)
        } catch (e: Exception) {
            Timber.w(e, "sync run failed")
            SyncResult.Error(e.message ?: "Unknown sync error")
        }
    }
}
