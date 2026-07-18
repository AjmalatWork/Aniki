package com.aniki.anikiai.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Junction
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class ItemWithTags(
    @Embedded val item: ItemEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = ItemTagCrossRef::class,
            parentColumn = "itemId",
            entityColumn = "tagId"
        )
    )
    val tags: List<TagEntity>
)

/** Flat (itemId, tag label) rows for active links — used to attribute engagement events to tags. */
data class ItemTagLabel(
    val itemId: String,
    val label: String
)

@Dao
interface ItemDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItem(item: ItemEntity)

    @Update
    suspend fun updateItem(item: ItemEntity)

    /** Upsert-by-id, used by sync merge (INSERT/OVERWRITE decisions from decideMerge). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(item: ItemEntity)

    @Query("SELECT * FROM items WHERE normalizedUrl = :normalizedUrl AND deletedAt IS NULL LIMIT 1")
    suspend fun getItemByNormalizedUrl(normalizedUrl: String): ItemEntity?

    @Query("SELECT * FROM items WHERE id = :id LIMIT 1")
    suspend fun getItemById(id: String): ItemEntity?

    @Query("SELECT * FROM items WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    fun observeAllItems(): Flow<List<ItemEntity>>

    /** Trash list: every soft-deleted item, most-recently-trashed first. */
    @Query("SELECT * FROM items WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrashedItems(): Flow<List<ItemEntity>>

    @Query("SELECT id FROM items WHERE deletedAt IS NOT NULL")
    suspend fun getTrashedItemIds(): List<String>

    /** [ItemRepository.purgeExpiredTrash]'s candidate pool: trashed past the retention cutoff AND
     *  already synced (dirty=0) — the dirty guard is what stops the 30-day auto-purge from ever
     *  hard-deleting a tombstone the server hasn't received yet. */
    @Query("SELECT id FROM items WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff AND dirty = 0")
    suspend fun getExpiredTrashItemIds(cutoff: Long): List<String>

    /**
     * Self-guarding version of the delete used by [ItemRepository.purgeExpiredTrash]'s automatic
     * sweep: re-checks the exact same trashed/expired/synced condition [getExpiredTrashItemIds]
     * selected on, but at DELETE time instead of at the earlier SELECT. Without this, a restore
     * (which clears deletedAt) landing in the gap between that SELECT and an unconditional DELETE
     * would permanently hard-delete an item the user just un-trashed -- a concurrent restore now
     * simply makes this match zero rows instead. Returns the number of rows actually deleted (0 or
     * 1) so the caller knows whether it's safe to also delete this item's tag cross-refs.
     */
    @Query(
        "DELETE FROM items WHERE id = :id AND deletedAt IS NOT NULL AND deletedAt < :cutoff AND dirty = 0"
    )
    suspend fun hardDeleteExpiredTrashItem(id: String, cutoff: Long): Int

    /** Unconditional hard delete for user-initiated "delete forever" (Trash screen's manual
     *  delete/empty-trash actions) — those are immediate by design, not a background sweep, so
     *  they don't need [hardDeleteExpiredTrashItem]'s re-validated guard. */
    @Query("DELETE FROM items WHERE id = :id")
    suspend fun hardDeleteItem(id: String)

    @Query("DELETE FROM item_tags WHERE itemId = :id")
    suspend fun deleteItemTagsForItem(id: String)

    /**
     * The onboarding demo item (see ItemEntity.isDemo), if it's ever been created on this device
     * and not since deleted. By design it's a normal, visible, user-deletable item in Library and
     * Feed (so the user can see exactly what onboarding's demo share did) -- isDemo only ever
     * gates sync (never pushed) and enrichment (never real-enriched), not visibility.
     */
    @Query("SELECT * FROM items WHERE isDemo = 1 AND deletedAt IS NULL LIMIT 1")
    suspend fun getDemoItem(): ItemEntity?

    /** Marks the Feed's one-time demo-item landing animation played, so it never replays --
     *  called once, from FeedViewModel.takeFreshSnapshot(), the first time it decides to animate the item. */
    @Query("UPDATE items SET demoLandingAnimationShown = 1 WHERE id = :id")
    suspend fun markDemoLandingAnimationShown(id: String)

    /** Items saved locally but never enqueued for enrichment (process died between the write and the enqueue call). */
    @Query("SELECT id FROM items WHERE status = 'PENDING' AND deletedAt IS NULL")
    suspend fun getPendingItemIds(): List<String>

    /** Articles enriched before OG-image extraction existed (or where it found nothing) and not
     *  yet retried this device -- the lazy thumbnail backfill's candidate pool (see
     *  ItemRepository.backfillThumbnails). Capped by the caller, not here, so the query stays
     *  reusable for any batch size. */
    @Query(
        """
        SELECT * FROM items
        WHERE type = 'WEB_ARTICLE' AND thumbnailUrl IS NULL AND thumbnailBackfillAttempted = 0
          AND deletedAt IS NULL
        ORDER BY createdAt ASC
        """
    )
    suspend fun getArticlesNeedingThumbnailBackfill(): List<ItemEntity>

    @Query("UPDATE items SET thumbnailUrl = :thumbnailUrl, thumbnailBackfillAttempted = 1 WHERE id = :id")
    suspend fun applyThumbnailBackfill(id: String, thumbnailUrl: String?)

    /** Notes enriched before title generation existed, whose title is still the creation-time
     *  truncated-body-text stub (see ItemRepository.createNote) -- the note-title backfill's
     *  candidate pool (see work/NoteTitleBackfiller.kt). SQLite's substr(x,1,60) matches Kotlin's
     *  String.take(60) exactly, including the shorter-than-60 case. The onboarding demo item is
     *  already excluded by construction (its hardcoded title never equals its body's first 60
     *  chars -- see OnboardingDemoContent), but isDemo = 0 is kept as an explicit second guard so
     *  it can never be picked up here even if that content ever changes. */
    @Query(
        """
        SELECT * FROM items
        WHERE type = 'NOTE' AND status = 'ENRICHED' AND titleEditedByUser = 0
          AND titleBackfillAttempted = 0 AND deletedAt IS NULL AND isDemo = 0
          AND title = substr(bodyText, 1, 60)
        ORDER BY createdAt ASC
        """
    )
    suspend fun getNotesNeedingTitleBackfill(): List<ItemEntity>

    /** Marks a note's title-backfill attempt made -- called when the re-enrichment is *enqueued*,
     *  not when it completes (the attempt itself, not its outcome, is what must never repeat
     *  more than once per device, to bound Gemini cost on a stubbornly-failing note). */
    @Query("UPDATE items SET titleBackfillAttempted = 1 WHERE id = :id")
    suspend fun markTitleBackfillAttempted(id: String)

    // --- Targeted single-purpose column writes -----------------------------------------------
    // Every method below writes only the columns its own operation legitimately owns, instead of
    // the old getItemById() -> entity.copy(...) -> updateItem(wholeRow) pattern. That pattern
    // read a full row in Kotlin, then wrote EVERY column back (including ones the operation never
    // meant to touch) -- so a star, a delete, or a title edit landing in the gap between another
    // operation's read and its write got silently reverted, because the later write's stale copy
    // of that field overwrote the real change. A targeted UPDATE that never mentions a column
    // can't clobber it no matter what else concurrently wrote to it: SQLite serializes writers, so
    // whichever of two targeted writes actually commits last simply wins on its own column(s),
    // with every other column exactly as some other write left it. See ItemRepository's callers
    // for the full context on each.

    @Query("UPDATE items SET isStarred = :starred, updatedAt = :now, dirty = 1 WHERE id = :id")
    suspend fun setStarredColumn(id: String, starred: Boolean, now: Long)

    @Query("UPDATE items SET deletedAt = :now, updatedAt = :now, dirty = 1 WHERE id = :id")
    suspend fun softDeleteItemRow(id: String, now: Long)

    @Query("UPDATE items SET deletedAt = NULL, updatedAt = :now, dirty = 1 WHERE id = :id")
    suspend fun restoreItemRow(id: String, now: Long)

    @Query("UPDATE items SET title = :title, titleEditedByUser = 1, updatedAt = :now, dirty = 1 WHERE id = :id")
    suspend fun updateTitleColumn(id: String, title: String, now: Long)

    @Query(
        "UPDATE items SET status = 'NEEDS_ATTENTION', updatedAt = :now, dirty = 1, " +
            "errorCode = :errorCode, errorMessage = :errorMessage WHERE id = :id"
    )
    suspend fun markNeedsAttentionRow(id: String, now: Long, errorCode: String?, errorMessage: String?)

    @Query(
        "UPDATE items SET status = 'ENRICHED', updatedAt = :now, dirty = 1, " +
            "errorCode = NULL, errorMessage = NULL WHERE id = :id"
    )
    suspend fun markEnrichmentSkippedRow(id: String, now: Long)

    @Query("UPDATE items SET tagsEditedByUser = 1, updatedAt = :now, dirty = 1 WHERE id = :id")
    suspend fun markTagsEdited(id: String, now: Long)

    /**
     * Applies a successful enrichment result via targeted, edit-lock-aware writes. title/summary
     * are only overwritten when their edit-lock flag (titleEditedByUser/summaryEditedByUser) is
     * unset -- evaluated by SQLite against the row's CURRENT value at write time, not against a
     * value read earlier in Kotlin that could go stale if the user's own title/summary edit
     * committed in the gap between an app-level read and this write (which is exactly how the
     * edit-lock mechanism used to get silently defeated). Every other touched column
     * (category/thumbnailUrl/entities/eventDate/status/errorCode/errorMessage) is unconditional,
     * matching what a successful enrichment always overwrites. Columns this doesn't mention
     * (isStarred, deletedAt, tagsEditedByUser, ...) are untouched by construction.
     */
    @Query(
        """
        UPDATE items SET
            title = CASE WHEN titleEditedByUser = 0 AND :title IS NOT NULL AND :title != '' THEN :title ELSE title END,
            summary = CASE WHEN summaryEditedByUser = 0 THEN :summary ELSE summary END,
            category = :category,
            thumbnailUrl = :thumbnailUrl,
            entities = :entitiesJson,
            eventDate = :eventDate,
            status = 'ENRICHED',
            updatedAt = :now,
            dirty = 1,
            errorCode = NULL,
            errorMessage = NULL
        WHERE id = :id
        """
    )
    suspend fun applyEnrichmentFields(
        id: String,
        title: String?,
        summary: String,
        category: String,
        thumbnailUrl: String?,
        entitiesJson: String?,
        eventDate: Long?,
        now: Long
    )

    /**
     * Targeted note-body write. status only advances to PENDING, and errorCode/errorMessage only
     * clear, when [reEnriching] -- otherwise those columns (and every column this doesn't mention)
     * are left exactly as they were, evaluated live by SQLite rather than copied from a possibly
     * stale Kotlin-side read.
     */
    @Query(
        """
        UPDATE items SET
            bodyText = :body,
            status = CASE WHEN :reEnriching THEN 'PENDING' ELSE status END,
            updatedAt = :now,
            dirty = 1,
            errorCode = CASE WHEN :reEnriching THEN NULL ELSE errorCode END,
            errorMessage = CASE WHEN :reEnriching THEN NULL ELSE errorMessage END
        WHERE id = :id
        """
    )
    suspend fun updateNoteBodyRow(id: String, body: String, reEnriching: Boolean, now: Long)

    @Transaction
    @Query("SELECT * FROM items WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    fun observeAllItemsWithTags(): Flow<List<ItemWithTags>>

    @Transaction
    @Query("SELECT * FROM items WHERE id = :id AND deletedAt IS NULL")
    fun observeItemWithTags(id: String): Flow<ItemWithTags?>

    /** One-shot snapshot for the Feed (ranking freezes a snapshot per session, not a live Flow). */
    @Transaction
    @Query("SELECT * FROM items WHERE deletedAt IS NULL")
    suspend fun getAllItemsWithTags(): List<ItemWithTags>

    @Query("UPDATE items SET lastViewedAt = :timestamp WHERE id = :id")
    suspend fun updateLastViewedAt(id: String, timestamp: Long)

    @Query("UPDATE items SET lastShownAt = :timestamp WHERE id = :id")
    suspend fun updateLastShownAt(id: String, timestamp: Long)

    // --- Full-text search (Slice 4). items_fts (ItemFtsEntity) is a standalone FTS4 virtual
    // table — kept in sync manually by ItemRepository.syncFtsRow, not by Room-managed triggers. ---

    @Query("DELETE FROM items_fts WHERE itemId = :itemId")
    suspend fun deleteFtsRow(itemId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFtsRow(row: ItemFtsEntity)

    /** Backfill support: non-deleted items with no items_fts row yet (self-heals pre-fix synced installs). */
    @Query("SELECT id FROM items WHERE deletedAt IS NULL AND id NOT IN (SELECT itemId FROM items_fts)")
    suspend fun getActiveItemIdsMissingFromFts(): List<String>

    // SQLite forbids using MATCH twice against the same FTS table within a single SELECT (it
    // errors "unable to use function MATCH in the requested context"), so the title-priority
    // signal has to come from a separate SELECT (its own single MATCH) unioned in, not a second
    // MATCH in the same WHERE/ORDER BY — ranked as its own subquery, then joined back to items once.
    @Transaction
    @Query(
        """
        SELECT items.* FROM items
        JOIN (
            SELECT itemId, MIN(priority) AS priority FROM (
                SELECT itemId, 0 AS priority FROM items_fts WHERE items_fts.title MATCH :matchQuery
                UNION ALL
                SELECT itemId, 1 AS priority FROM items_fts WHERE items_fts MATCH :matchQuery
            )
            GROUP BY itemId
        ) ranked ON items.id = ranked.itemId
        WHERE items.deletedAt IS NULL
        ORDER BY ranked.priority, items.createdAt DESC
        """
    )
    fun searchItemsWithTags(matchQuery: String): Flow<List<ItemWithTags>>

    /** Most-used tag first (ties broken by most-recently-touched), for the Library filter chip row. */
    @Query(
        """
        SELECT tags.* FROM tags
        INNER JOIN item_tags ON tags.id = item_tags.tagId
        INNER JOIN items ON items.id = item_tags.itemId
        WHERE tags.deletedAt IS NULL AND item_tags.deletedAt IS NULL AND items.deletedAt IS NULL
        GROUP BY tags.id
        ORDER BY COUNT(*) DESC, MAX(item_tags.updatedAt) DESC
        """
    )
    fun observeActiveTagsByUsage(): Flow<List<TagEntity>>

    // isDemo items are never marked dirty in the first place (see ItemRepository.saveSharedContent's
    // isDemo branch), but this filter is a second guard so the onboarding demo item can never reach
    // the server even if that invariant is ever broken elsewhere.
    @Query("SELECT * FROM items WHERE dirty = 1 AND isDemo = 0")
    suspend fun getDirtyItems(): List<ItemEntity>

    @Query("UPDATE items SET dirty = 0 WHERE id = :id")
    suspend fun clearItemDirty(id: String)

    @Query("SELECT * FROM tags WHERE LOWER(label) = LOWER(:label) AND deletedAt IS NULL LIMIT 1")
    suspend fun getTagByLabel(label: String): TagEntity?

    @Query("SELECT * FROM tags WHERE id = :id LIMIT 1")
    suspend fun getTagById(id: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTag(tag: TagEntity)

    @Query("SELECT * FROM tags WHERE dirty = 1")
    suspend fun getDirtyTags(): List<TagEntity>

    @Query("UPDATE tags SET dirty = 0 WHERE id = :id")
    suspend fun clearTagDirty(id: String)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTagById(id: String)

    /** Re-keys a locally-created tag id to the server's canonical id after a label-collision remap. */
    @Query("UPDATE tags SET id = :newId WHERE id = :oldId")
    suspend fun renameTagId(oldId: String, newId: String)

    @Query("SELECT * FROM item_tags WHERE itemId = :itemId AND deletedAt IS NULL")
    suspend fun getActiveCrossRefsForItem(itemId: String): List<ItemTagCrossRef>

    /** Upsert-by-(itemId,tagId), used both by local retagging and sync merge. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItemTagCrossRef(crossRef: ItemTagCrossRef)

    @Query("SELECT * FROM item_tags WHERE dirty = 1")
    suspend fun getDirtyItemTags(): List<ItemTagCrossRef>

    @Query("UPDATE item_tags SET dirty = 0 WHERE itemId = :itemId AND tagId = :tagId")
    suspend fun clearItemTagDirty(itemId: String, tagId: String)

    @Query("UPDATE item_tags SET tagId = :newTagId WHERE tagId = :oldTagId")
    suspend fun remapItemTagsToTagId(oldTagId: String, newTagId: String)

    @Query("DELETE FROM item_tags WHERE tagId = :tagId")
    suspend fun deleteItemTagsForTag(tagId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEngagementEvent(event: EngagementEventEntity)

    /** All engagement events (local mirror), for the offline per-tag weight derivation. */
    @Query("SELECT * FROM engagement_events")
    suspend fun getAllEngagementEvents(): List<EngagementEventEntity>

    /** Active (itemId, label) pairs, to attribute each engagement event to the item's tags. */
    @Query(
        """
        SELECT item_tags.itemId AS itemId, tags.label AS label
        FROM item_tags
        JOIN tags ON tags.id = item_tags.tagId
        WHERE item_tags.deletedAt IS NULL AND tags.deletedAt IS NULL
        """
    )
    suspend fun getAllActiveItemTagLabels(): List<ItemTagLabel>

    @Query("SELECT * FROM engagement_events WHERE dirty = 1")
    suspend fun getDirtyEngagementEvents(): List<EngagementEventEntity>

    @Query("UPDATE engagement_events SET dirty = 0 WHERE id = :id")
    suspend fun clearEngagementEventDirty(id: String)

    // --- Guest -> account migration: mark everything dirty so the next sync pushes it all ---
    // (isDemo excluded -- the onboarding demo item must never be pushed to the server)

    @Query("UPDATE items SET dirty = 1 WHERE isDemo = 0")
    suspend fun markAllItemsDirty()

    @Query("UPDATE tags SET dirty = 1")
    suspend fun markAllTagsDirty()

    @Query("UPDATE item_tags SET dirty = 1")
    suspend fun markAllItemTagsDirty()

    // --- Sign-out: clear the local cache entirely ---

    @Query("DELETE FROM items")
    suspend fun clearAllItems()

    @Query("DELETE FROM tags")
    suspend fun clearAllTags()

    @Query("DELETE FROM item_tags")
    suspend fun clearAllItemTags()

    @Query("DELETE FROM engagement_events")
    suspend fun clearAllEngagementEvents()
}
