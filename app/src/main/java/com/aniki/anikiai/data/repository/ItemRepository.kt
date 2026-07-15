package com.aniki.anikiai.data.repository

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
    private val ftsIndexer: FtsIndexer
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
     */
    suspend fun saveSharedContent(
        type: String,
        sourceUrl: String?,
        normalizedUrl: String?,
        title: String,
        bodyText: String? = null
    ): ItemEntity {
        val now = System.currentTimeMillis()

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
     * Writes back a successful enrichment result, honoring the user-edit locks (a no-op guard
     * for now since no editing UI exists yet, but must not regress once it does).
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
        val current = itemDao.getItemById(itemId) ?: return
        val now = System.currentTimeMillis()

        val resolvedTitle = if (isPlaceholderTitle(current)) title ?: current.title else current.title

        val updated = current.copy(
            title = resolvedTitle,
            summary = if (current.summaryEditedByUser) current.summary else summary,
            category = category,
            thumbnailUrl = thumbnailUrl,
            entities = entitiesJson,
            eventDate = eventDate,
            status = ItemStatus.ENRICHED,
            updatedAt = now,
            dirty = true
        )
        itemDao.updateItem(updated)

        if (!current.tagsEditedByUser) {
            replaceAiTags(itemId, tags)
        }
        syncFtsRow(itemId)
    }

    suspend fun markNeedsAttention(itemId: String) {
        val current = itemDao.getItemById(itemId) ?: return
        itemDao.updateItem(
            current.copy(status = ItemStatus.NEEDS_ATTENTION, updatedAt = System.currentTimeMillis(), dirty = true)
        )
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
        val current = itemDao.getItemById(itemId) ?: return
        itemDao.updateItem(
            current.copy(isStarred = starred, updatedAt = System.currentTimeMillis(), dirty = true)
        )
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

    suspend fun updateSummary(itemId: String, summary: String) {
        val current = itemDao.getItemById(itemId) ?: return
        val now = System.currentTimeMillis()
        itemDao.updateItem(
            current.copy(summary = summary, summaryEditedByUser = true, updatedAt = now, dirty = true)
        )
        syncFtsRow(itemId)
    }

    suspend fun addUserTag(itemId: String, label: String) {
        val current = itemDao.getItemById(itemId) ?: return
        val normalized = label.trim().lowercase()
        if (normalized.isEmpty()) return
        val now = System.currentTimeMillis()

        val tag = findOrCreateTag(normalized, origin = "USER", now = now)
        itemDao.upsertItemTagCrossRef(
            ItemTagCrossRef(itemId = itemId, tagId = tag.id, updatedAt = now, dirty = true)
        )
        itemDao.updateItem(current.copy(tagsEditedByUser = true, updatedAt = now, dirty = true))
        syncFtsRow(itemId)
    }

    suspend fun removeTag(itemId: String, tagId: String) {
        val current = itemDao.getItemById(itemId) ?: return
        val crossRef = itemDao.getActiveCrossRefsForItem(itemId).find { it.tagId == tagId } ?: return
        val now = System.currentTimeMillis()

        itemDao.upsertItemTagCrossRef(crossRef.copy(deletedAt = now, updatedAt = now, dirty = true))
        itemDao.updateItem(current.copy(tagsEditedByUser = true, updatedAt = now, dirty = true))
        syncFtsRow(itemId)
    }

    /** Soft-delete only — sync propagates the tombstone exactly like Slice 3 already tested. */
    suspend fun deleteItem(itemId: String) {
        val current = itemDao.getItemById(itemId) ?: return
        val now = System.currentTimeMillis()
        itemDao.updateItem(current.copy(deletedAt = now, updatedAt = now, dirty = true))
        ftsIndexer.remove(itemId)
    }

    /** A link's Slice-1 title is just its raw URL until enrichment resolves a real one. */
    private fun isPlaceholderTitle(item: ItemEntity): Boolean =
        item.type != ItemType.NOTE && item.title == item.sourceUrl

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
