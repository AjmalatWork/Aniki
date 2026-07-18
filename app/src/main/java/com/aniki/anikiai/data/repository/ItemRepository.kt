package com.aniki.anikiai.data.repository

import androidx.room.withTransaction
import com.aniki.anikiai.data.db.AnikiDatabase
import com.aniki.anikiai.data.db.EngagementEventEntity
import com.aniki.anikiai.data.db.EngagementEventType
import com.aniki.anikiai.data.db.FtsIndexer
import com.aniki.anikiai.data.db.ItemDao
import com.aniki.anikiai.data.db.ItemEntity
import com.aniki.anikiai.data.db.ItemStatus
import com.aniki.anikiai.data.db.ItemTagCrossRef
import com.aniki.anikiai.data.db.ItemType
import com.aniki.anikiai.data.db.ItemWithTags
import com.aniki.anikiai.data.db.TagEntity
import com.aniki.anikiai.feed.EngagementRecord
import com.aniki.anikiai.feed.computeTagWeights
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ItemRepository(
    private val itemDao: ItemDao,
    private val ftsIndexer: FtsIndexer,
    private val database: AnikiDatabase
) {

    fun observeAllItems(): Flow<List<ItemEntity>> = itemDao.observeAllItems()

    fun observeAllItemsWithTags(): Flow<List<ItemWithTags>> = itemDao.observeAllItemsWithTags()

    fun observeItemWithTags(itemId: String): Flow<ItemWithTags?> = itemDao.observeItemWithTags(itemId)

    fun observeActiveTags(): Flow<List<TagEntity>> = itemDao.observeActiveTagsByUsage()

    suspend fun getItemById(id: String): ItemEntity? = itemDao.getItemById(id)

    /** One-shot snapshot the Feed ranks against (order is frozen per session, not live). */
    suspend fun getItemsWithTagsSnapshot(): List<ItemWithTags> = itemDao.getAllItemsWithTags()

    /**
     * Per-tag affinity weights derived offline from the local engagement mirror (Task 2). Pure math
     * lives in feed/TagWeights; this only assembles events with their items' tags. Empty history ->
     * empty map = cold start.
     */
    suspend fun computeUserTagWeights(): Map<String, Double> {
        val events = itemDao.getAllEngagementEvents()
        if (events.isEmpty()) return emptyMap()
        val labelsByItem = itemDao.getAllActiveItemTagLabels().groupBy({ it.itemId }, { it.label })
        val records = events.map { EngagementRecord(it.eventType, it.value, labelsByItem[it.itemId].orEmpty()) }
        return computeTagWeights(records)
    }

    /**
     * Local FTS4 search (see AnikiDatabase.MIGRATION_2_3 for why items_fts is raw SQL rather
     * than a Room @Fts4 entity). Blank/symbol-only input falls back to the unfiltered list.
     */
    fun searchItems(query: String): Flow<List<ItemWithTags>> {
        val matchQuery = buildFtsMatchQuery(query) ?: return observeAllItemsWithTags()
        return itemDao.searchItemsWithTags(matchQuery)
    }

    private fun buildFtsMatchQuery(raw: String): String? {
        val tokens = raw.split(Regex("\\s+"))
            .map { token -> token.filter(Char::isLetterOrDigit) }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }

    /**
     * Persists a shared link/text. If an existing, non-deleted item already has the same
     * normalizedUrl, the save is treated as a duplicate and only its updatedAt is touched.
     *
     * [isDemo] is set only by the onboarding "how sharing works" step (ShareTipScreen fires a
     * real ACTION_SEND with demo content through this exact same path, per the "reuse the
     * existing share-receive path" requirement). By design a demo save is otherwise a normal,
     * visible item -- it shows up in Library/Feed/search and the user can delete it manually,
     * exactly like any other note, so they can see what onboarding's demo share actually did.
     * isDemo only gates two things: it skips PENDING/real enrichment (never queued -- see
     * ShareReceiverActivity, it's not real content worth spending a Gemini call on) and is never
     * marked dirty (never synced -- an onboarding artifact shouldn't propagate to the server or
     * other devices). Dedupes against any prior demo item by [ItemDao.getDemoItem] rather than by
     * normalizedUrl (demo content is always a plain note, so normalizedUrl is null) -- this keeps
     * retries from piling up duplicate demo rows.
     */
    suspend fun saveSharedContent(
        type: String,
        sourceUrl: String?,
        normalizedUrl: String?,
        title: String,
        bodyText: String? = null,
        isDemo: Boolean = false
    ): ItemEntity {
        val now = System.currentTimeMillis()

        if (isDemo) {
            val existingDemo = itemDao.getDemoItem()
            if (existingDemo != null) {
                val touched = existingDemo.copy(title = title, bodyText = bodyText, updatedAt = now)
                itemDao.updateItem(touched)
                syncFtsRow(touched.id)
                return touched
            }
            val demoItem = ItemEntity(
                id = UUID.randomUUID().toString(),
                type = type,
                sourceUrl = sourceUrl,
                normalizedUrl = null,
                title = title,
                bodyText = bodyText,
                summary = null,
                thumbnailUrl = null,
                category = null,
                eventDate = null,
                status = ItemStatus.ENRICHED,
                createdAt = now,
                updatedAt = now,
                dirty = false,
                isDemo = true
            )
            itemDao.insertItem(demoItem)
            syncFtsRow(demoItem.id)
            return demoItem
        }

        if (normalizedUrl != null) {
            val existing = itemDao.getItemByNormalizedUrl(normalizedUrl)
            if (existing != null) {
                val touched = existing.copy(updatedAt = now, dirty = true)
                itemDao.updateItem(touched)
                return touched
            }
        }

        val item = ItemEntity(
            id = UUID.randomUUID().toString(),
            type = type,
            sourceUrl = sourceUrl,
            normalizedUrl = normalizedUrl,
            title = title,
            bodyText = bodyText,
            summary = null,
            thumbnailUrl = null,
            category = null,
            eventDate = null,
            status = ItemStatus.PENDING,
            createdAt = now,
            updatedAt = now,
            dirty = true
        )
        itemDao.insertItem(item)
        syncFtsRow(item.id)
        return item
    }

    /** Marks the Feed's one-time demo-item landing animation played (see [ItemEntity.isDemo]). */
    suspend fun markDemoLandingAnimationShown(id: String) = itemDao.markDemoLandingAnimationShown(id)

    suspend fun createNote(title: String?, body: String): ItemEntity {
        val now = System.currentTimeMillis()
        val resolvedTitle = title?.takeIf { it.isNotBlank() } ?: body.take(60)

        val item = ItemEntity(
            id = UUID.randomUUID().toString(),
            type = ItemType.NOTE,
            sourceUrl = null,
            normalizedUrl = null,
            title = resolvedTitle,
            bodyText = body,
            summary = null,
            thumbnailUrl = null,
            category = null,
            eventDate = null,
            status = ItemStatus.PENDING,
            createdAt = now,
            updatedAt = now,
            dirty = true
        )
        itemDao.insertItem(item)
        syncFtsRow(item.id)
        return item
    }

    /**
     * Writes back a successful enrichment result, honoring the user-edit locks. Title now uses
     * the same edit-lock pattern as summary/tags (titleEditedByUser) rather than the old
     * isPlaceholderTitle heuristic ("only overwrite if the title still equals the raw URL") --
     * that heuristic only ever let a title update once, on the very first enrichment; this
     * lets every re-enrichment refresh the title (e.g. a Retry after a fetch fix, or the
     * Gemini-generated title arriving for a NOTE) right up until the user edits it themselves.
     *
     * The item-row write ([ItemDao.applyEnrichmentFields]) is a targeted, edit-lock-aware SQL
     * UPDATE rather than a getItemById() -> copy() -> updateItem(wholeRow) -- the old pattern read
     * the full row, then wrote every column back, so a star/delete/title-edit committing in the
     * gap between that read and this write got silently reverted (and for title specifically,
     * titleEditedByUser got copied back to its stale value, defeating the edit lock the whole
     * mechanism exists to enforce). See ItemDao.applyEnrichmentFields's doc for how the targeted
     * version closes that. The one remaining read-then-act step -- deciding whether to replace AI
     * tags based on tagsEditedByUser -- is wrapped in [AnikiDatabase.withTransaction] so that
     * decision can't itself race a concurrent tag edit landing in between.
     */
    suspend fun applyEnrichment(
        itemId: String,
        title: String?,
        summary: String,
        category: String,
        thumbnailUrl: String?,
        entitiesJson: String?,
        eventDate: Long?,
        tags: List<String>
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val current = itemDao.getItemById(itemId) ?: return@withTransaction
            itemDao.applyEnrichmentFields(
                id = itemId,
                title = title,
                summary = summary,
                category = category,
                thumbnailUrl = thumbnailUrl,
                entitiesJson = entitiesJson,
                eventDate = eventDate,
                now = now
            )
            if (!current.tagsEditedByUser) {
                replaceAiTags(itemId, tags)
            }
        }
        syncFtsRow(itemId)
    }

    /** Candidate pool for the lazy OG-image backfill (see work/ThumbnailBackfiller.kt). */
    suspend fun getArticlesNeedingThumbnailBackfill(): List<ItemEntity> =
        itemDao.getArticlesNeedingThumbnailBackfill()

    /** Records a backfill attempt's outcome (thumbnailUrl null on failure/not-found) and marks
     *  it attempted either way, so it's never retried. Local-only -- not synced, no dirty flag. */
    suspend fun applyThumbnailBackfill(itemId: String, thumbnailUrl: String?) {
        itemDao.applyThumbnailBackfill(itemId, thumbnailUrl)
    }

    /** Candidate pool for the throttled note-title backfill (see work/NoteTitleBackfiller.kt). */
    suspend fun getNotesNeedingTitleBackfill(): List<ItemEntity> = itemDao.getNotesNeedingTitleBackfill()

    suspend fun markTitleBackfillAttempted(itemId: String) = itemDao.markTitleBackfillAttempted(itemId)

    /** [errorCode]/[errorMessage] are the server's EnrichmentErrorCode + readable message for this
     *  attempt's failure (both null for a failure with no server response at all -- a raw network
     *  exception, which this deliberately doesn't try to classify further). Overwrites whatever an
     *  earlier attempt recorded, so the UI always reflects the most recent failure reason. */
    suspend fun markNeedsAttention(itemId: String, errorCode: String? = null, errorMessage: String? = null) {
        itemDao.markNeedsAttentionRow(itemId, System.currentTimeMillis(), errorCode, errorMessage)
    }

    /**
     * A note's body was too short to be worth a Gemini call (see EnrichmentWorker's
     * MIN_NOTE_BODY_LENGTH check) -- treated as "done" rather than failed: there's nothing more
     * Aniki can add, so the item goes straight to ENRICHED with whatever title/tags it already had
     * (none, for a brand-new note) rather than sitting in PENDING forever or showing a failure the
     * user didn't cause and can't fix by retrying.
     */
    suspend fun markEnrichmentSkipped(itemId: String) {
        itemDao.markEnrichmentSkippedRow(itemId, System.currentTimeMillis())
    }

    /** Marks the item viewed for the Feed's resurface/seen terms. Deliberately does not touch
     *  dirty/updatedAt: lastViewedAt isn't part of SyncItemDto, so flagging dirty would just
     *  re-push the row for a field that wouldn't even be transmitted. */
    suspend fun markViewed(itemId: String) {
        itemDao.updateLastViewedAt(itemId, System.currentTimeMillis())
    }

    /** Records that a card was surfaced in the Feed: local-only lastShownAt (feeds seenPenalty,
     *  not synced so no dirty) plus a syncable SHOWN engagement event. */
    suspend fun markShown(itemId: String) {
        itemDao.updateLastShownAt(itemId, System.currentTimeMillis())
        recordEvent(itemId, EngagementEventType.SHOWN)
    }

    /** Feed/Detail open: bumps lastViewedAt and logs an OPENED engagement event. */
    suspend fun recordOpen(itemId: String) {
        markViewed(itemId)
        recordEvent(itemId, EngagementEventType.OPENED)
    }

    /** Time spent on a Feed card before moving on (positive durations only). */
    suspend fun recordDwell(itemId: String, dwellMs: Long) {
        if (dwellMs <= 0) return
        recordEvent(itemId, EngagementEventType.DWELL, dwellMs.toDouble())
    }

    suspend fun recordDismiss(itemId: String) {
        recordEvent(itemId, EngagementEventType.DISMISSED)
    }

    suspend fun setStarred(itemId: String, starred: Boolean) {
        itemDao.setStarredColumn(itemId, starred, System.currentTimeMillis())
        // Starring is positive affinity signal; unstarring logs nothing.
        if (starred) recordEvent(itemId, EngagementEventType.STARRED)
    }

    /** Appends a syncable engagement event (dirty=true so Slice 3's push carries it up). */
    private suspend fun recordEvent(itemId: String, type: String, value: Double? = null) {
        itemDao.insertEngagementEvent(
            EngagementEventEntity(
                id = UUID.randomUUID().toString(),
                itemId = itemId,
                eventType = type,
                value = value,
                createdAt = System.currentTimeMillis(),
                dirty = true
            )
        )
    }

    suspend fun updateTitle(itemId: String, title: String) {
        if (title.isBlank()) return
        itemDao.updateTitleColumn(itemId, title, System.currentTimeMillis())
        syncFtsRow(itemId)
    }

    /**
     * Persists a directly-edited note body (polish pass: notes are now editable in place, no
     * pencil-tap gate). Resets status to PENDING when the new body is non-blank so the normal
     * enrichment path (same worker used at creation) re-runs against the updated content, keeping
     * tags relevant and regenerating the title if it isn't user-locked -- see EnrichmentWorker /
     * applyEnrichment's existing titleEditedByUser check, unchanged and reused as-is here. A
     * title-only edit doesn't go through this method (see updateTitle): tags/title generation are
     * both derived from body content, so re-enriching on a pure title change would be a wasted
     * Gemini call. Left as-is (not reset to PENDING) when edited down to blank, since there's
     * nothing meaningful to re-enrich yet -- the caller skips enqueueing enrichment in that case.
     */
    suspend fun updateNoteBody(itemId: String, body: String) {
        // Only read to decide whether this write is even necessary (skip a no-op autosave) --
        // the actual write below (updateNoteBodyRow) doesn't copy any field from this read, so a
        // stale value here can at worst cause one redundant write, never a lost concurrent edit.
        val current = itemDao.getItemById(itemId) ?: return
        if (body == current.bodyText) return
        val now = System.currentTimeMillis()
        val reEnriching = body.isNotBlank()
        // A previous failure no longer describes this content once it's about to be re-enriched --
        // updateNoteBodyRow clears errorCode/errorMessage exactly when reEnriching, evaluated by
        // SQLite against the row's live status/errorCode rather than this possibly-stale read.
        itemDao.updateNoteBodyRow(itemId, body, reEnriching, now)
        syncFtsRow(itemId)
    }

    suspend fun addUserTag(itemId: String, label: String) {
        val normalized = label.trim().lowercase()
        if (normalized.isEmpty()) return
        val now = System.currentTimeMillis()

        val tag = findOrCreateTag(normalized, origin = "USER", now = now)
        itemDao.upsertItemTagCrossRef(
            ItemTagCrossRef(itemId = itemId, tagId = tag.id, updatedAt = now, dirty = true)
        )
        itemDao.markTagsEdited(itemId, now)
        syncFtsRow(itemId)
    }

    suspend fun removeTag(itemId: String, tagId: String) {
        val crossRef = itemDao.getActiveCrossRefsForItem(itemId).find { it.tagId == tagId } ?: return
        val now = System.currentTimeMillis()

        itemDao.upsertItemTagCrossRef(crossRef.copy(deletedAt = now, updatedAt = now, dirty = true))
        itemDao.markTagsEdited(itemId, now)
        syncFtsRow(itemId)
    }

    /** Soft-delete only — sync propagates the tombstone exactly like Slice 3 already tested.
     *  This is also what puts an item in Trash: deletedAt is the same tombstone column Trash
     *  lists against (see observeTrashedItems), so "delete" and "move to Trash" are the same
     *  action, not two separate states to keep in sync. */
    suspend fun deleteItem(itemId: String) {
        itemDao.softDeleteItemRow(itemId, System.currentTimeMillis())
        ftsIndexer.remove(itemId)
    }

    /** Trash list: every soft-deleted item, most-recently-trashed first. */
    fun observeTrashedItems(): Flow<List<ItemEntity>> = itemDao.observeTrashedItems()

    /** Un-tombstones an item (clears deletedAt) so it reappears in Library/Feed/search and syncs
     *  that reversal to other devices, exactly like any other content edit. */
    suspend fun restoreItem(itemId: String) {
        itemDao.restoreItemRow(itemId, System.currentTimeMillis())
        syncFtsRow(itemId)
    }

    /**
     * Immediate, user-requested permanent delete from the Trash view. Physically removes the row
     * (and its tag cross-refs) rather than waiting for [purgeExpiredTrash]'s 30-day window.
     *
     * Known edge case, accepted for this pass: this hard-deletes the local row unconditionally,
     * even if it hasn't been synced yet (dirty=true) or the device is offline. If so, the tombstone
     * that would normally propagate this deletion to the server/other devices never gets pushed —
     * the row disappears here but could still exist elsewhere and resurface via a future sync. The
     * caller (TrashViewModel) enqueues a sync first to give an online device the best chance of
     * pushing the tombstone before this runs, but doesn't block on it. [purgeExpiredTrash]'s
     * automatic 30-day sweep intentionally requires dirty=false to avoid this exact risk; this
     * manual path can't offer the same guarantee since "delete forever" means immediately.
     */
    suspend fun permanentlyDeleteItem(itemId: String) {
        itemDao.deleteItemTagsForItem(itemId)
        itemDao.hardDeleteItem(itemId)
        ftsIndexer.remove(itemId)
    }

    /** Bulk version of [permanentlyDeleteItem] for the Trash view's "Empty Trash" action. Same
     *  offline/unsynced-tombstone caveat applies to every row it touches. */
    suspend fun emptyTrash() {
        itemDao.getTrashedItemIds().forEach { id ->
            itemDao.deleteItemTagsForItem(id)
            itemDao.hardDeleteItem(id)
        }
    }

    /**
     * Client-side mirror of the server's tombstone GC (server/src/index.ts's daily sweep, which
     * purges Postgres tombstones after TOMBSTONE_RETENTION_DAYS, default 90 days) — this purges
     * the local Room row once a soft-deleted (Trash) item has passed [retentionMs]. Unlike
     * [permanentlyDeleteItem], only touches items already confirmed synced (dirty=false), so an
     * item whose tombstone hasn't reached the server yet is never silently dropped locally before
     * other devices learn about the deletion. Returns the count purged, for logging.
     *
     * Deletes each candidate via [ItemDao.hardDeleteExpiredTrashItem], which re-validates the
     * trashed/expired/synced condition at DELETE time rather than trusting the earlier SELECT --
     * a restore landing in between (clearing deletedAt) makes that DELETE match zero rows instead
     * of destroying the just-restored item. Tag cross-refs are only cleaned up when the item row
     * actually deleted (affectedRows > 0), so a restored item's tags aren't wiped out from under it.
     */
    suspend fun purgeExpiredTrash(retentionMs: Long): Int {
        val cutoff = System.currentTimeMillis() - retentionMs
        val ids = itemDao.getExpiredTrashItemIds(cutoff)
        var purged = 0
        ids.forEach { id ->
            val deleted = itemDao.hardDeleteExpiredTrashItem(id, cutoff)
            if (deleted > 0) {
                itemDao.deleteItemTagsForItem(id)
                purged++
            }
        }
        return purged
    }

    /**
     * Reconciles an item's tags to exactly `tagLabels`. Never hard-deletes: a link that's no
     * longer wanted is soft-deleted (tombstoned) so the removal propagates through sync, and
     * links already active for a still-wanted label are left untouched to avoid needless dirty
     * churn on every re-enrichment.
     */
    private suspend fun replaceAiTags(itemId: String, tagLabels: List<String>) {
        val now = System.currentTimeMillis()
        val wantedLabels = tagLabels.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

        val activeCrossRefs = itemDao.getActiveCrossRefsForItem(itemId)
        val activeByLabel = activeCrossRefs.mapNotNull { crossRef ->
            val tag = itemDao.getTagById(crossRef.tagId) ?: return@mapNotNull null
            tag.label to crossRef
        }.toMap()

        for (label in wantedLabels) {
            if (activeByLabel.containsKey(label)) continue // already linked, nothing to do

            val tag = findOrCreateTag(label, origin = "AI", now = now)
            itemDao.upsertItemTagCrossRef(
                ItemTagCrossRef(itemId = itemId, tagId = tag.id, updatedAt = now, dirty = true)
            )
        }

        for ((label, crossRef) in activeByLabel) {
            if (label !in wantedLabels) {
                itemDao.upsertItemTagCrossRef(crossRef.copy(deletedAt = now, updatedAt = now, dirty = true))
            }
        }
    }

    private suspend fun findOrCreateTag(label: String, origin: String, now: Long): TagEntity =
        itemDao.getTagByLabel(label) ?: TagEntity(
            id = UUID.randomUUID().toString(),
            label = label,
            origin = origin,
            updatedAt = now,
            dirty = true
        ).also { itemDao.upsertTag(it) }

    /**
     * Keeps items_fts in sync with an item's searchable content after any write touching
     * title/summary/bodyText/tags, or on tombstone. Delegates to the shared [FtsIndexer] so the
     * local-write and sync-merge paths index identically (see FtsIndexer for why).
     */
    private suspend fun syncFtsRow(itemId: String) = ftsIndexer.index(itemId)
}
