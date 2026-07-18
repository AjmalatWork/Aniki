package com.aniki.anikiai.data.repository

import com.aniki.anikiai.data.db.EngagementEventEntity
import com.aniki.anikiai.data.db.FtsIndexer
import com.aniki.anikiai.data.db.ItemDao
import com.aniki.anikiai.data.db.ItemEntity
import com.aniki.anikiai.data.db.ItemTagCrossRef
import com.aniki.anikiai.data.db.TagEntity
import com.aniki.anikiai.data.remote.SyncAckDto
import com.aniki.anikiai.data.remote.SyncEngagementEventDto
import com.aniki.anikiai.data.remote.SyncEntitiesDto
import com.aniki.anikiai.data.remote.SyncItemDto
import com.aniki.anikiai.data.remote.SyncItemTagAckDto
import com.aniki.anikiai.data.remote.SyncItemTagDto
import com.aniki.anikiai.data.remote.SyncTagDto
import com.aniki.anikiai.feed.signalForEvent
import com.aniki.anikiai.sync.MergeDecision
import com.aniki.anikiai.sync.decideMerge
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.Json

class SyncRepository(
    private val itemDao: ItemDao,
    private val ftsIndexer: FtsIndexer
) {

    // -----------------------------------------------------------------
    // Pull side: apply a pulled row against local state via decideMerge
    // -----------------------------------------------------------------

    /**
     * [conflicted]: true when this pull genuinely raced a local unsynced edit (both sides
     * diverged) -- used for observability's "conflicts resolved" count. [reindexItemId]: this
     * item's id when the merge actually wrote something (INSERT/OVERWRITE), so the caller can
     * batch it into one re-index pass across the whole pull page (see
     * SyncManager.runSync/[reindexItems]) instead of indexing synchronously here -- P1 of the
     * maintainability audit: an item pulled alongside T of its own tag-links used to get
     * (this call) + (T calls from [mergeItemTag]) full FTS rebuilds in the same page.
     */
    data class ItemMergeOutcome(val conflicted: Boolean, val reindexItemId: String?)

    suspend fun mergeItem(pulled: SyncItemDto): ItemMergeOutcome {
        val local = itemDao.getItemById(pulled.id)
        val decision = decideMerge(
            localExists = local != null,
            localUpdatedAt = local?.updatedAt ?: 0,
            pulledUpdatedAt = pulled.updatedAt,
            contentIdentical = local != null && itemContentIdentical(local, pulled)
        )
        when (decision) {
            MergeDecision.INSERT, MergeDecision.OVERWRITE -> {
                // upsertItem is a whole-row REPLACE (OnConflictStrategy.REPLACE), and
                // pulled.toEntity() has no engagementSignal field at all (it's local-only, not
                // part of SyncItemDto -- see ItemEntity's doc) -- so a REPLACE built straight from
                // the DTO would silently reset this item's engagementSignal to its 0.0 default,
                // discarding real engagement signal this device already folded in from its own
                // local events, even though nothing about the pulled content-only update should
                // touch it. Carrying local's current value forward here is what keeps this write
                // targeted to the columns the pull actually owns, matching the same instinct as
                // ItemDao's targeted single-purpose column writes elsewhere in this codebase.
                // INSERT (local == null) correctly starts a genuinely new item at 0.0.
                val entity = pulled.toEntity(dirty = false).copy(engagementSignal = local?.engagementSignal ?: 0.0)
                itemDao.upsertItem(entity)
            }
            MergeDecision.KEEP_LOCAL, MergeDecision.NO_OP -> Unit
        }
        val conflicted = local?.dirty == true && decision != MergeDecision.NO_OP
        val reindexItemId = if (decision == MergeDecision.INSERT || decision == MergeDecision.OVERWRITE) pulled.id else null
        return ItemMergeOutcome(conflicted, reindexItemId)
    }

    suspend fun mergeTag(pulled: SyncTagDto) {
        val local = itemDao.getTagById(pulled.id)
        val decision = decideMerge(
            localExists = local != null,
            localUpdatedAt = local?.updatedAt ?: 0,
            pulledUpdatedAt = pulled.updatedAt,
            contentIdentical = local != null && tagContentIdentical(local, pulled)
        )
        when (decision) {
            MergeDecision.INSERT, MergeDecision.OVERWRITE ->
                itemDao.upsertTag(
                    TagEntity(
                        id = pulled.id,
                        label = pulled.label,
                        origin = pulled.origin,
                        updatedAt = pulled.updatedAt,
                        deletedAt = pulled.deletedAt,
                        dirty = false
                    )
                )
            MergeDecision.KEEP_LOCAL, MergeDecision.NO_OP -> Unit
        }
    }

    /** Returns the linked item's id when this merge actually wrote something (INSERT/OVERWRITE,
     *  meaning its tagsText needs to reflect the (un)link), or null for a no-op -- batched into one
     *  re-index pass per pull page by the caller instead of indexing synchronously here (see
     *  [mergeItem]'s doc and [reindexItems]). */
    suspend fun mergeItemTag(pulled: SyncItemTagDto): String? {
        val local = itemDao.getActiveCrossRefsForItem(pulled.itemId).find { it.tagId == pulled.tagId }
            ?: findCrossRefIncludingDeleted(pulled.itemId, pulled.tagId)
        val decision = decideMerge(
            localExists = local != null,
            localUpdatedAt = local?.updatedAt ?: 0,
            pulledUpdatedAt = pulled.updatedAt,
            contentIdentical = local != null && local.deletedAt == pulled.deletedAt
        )
        when (decision) {
            MergeDecision.INSERT, MergeDecision.OVERWRITE -> {
                itemDao.upsertItemTagCrossRef(
                    ItemTagCrossRef(
                        itemId = pulled.itemId,
                        tagId = pulled.tagId,
                        updatedAt = pulled.updatedAt,
                        deletedAt = pulled.deletedAt,
                        dirty = false
                    )
                )
                return pulled.itemId
            }
            MergeDecision.KEEP_LOCAL, MergeDecision.NO_OP -> return null
        }
    }

    /**
     * P1 (maintainability audit): re-indexes each of [itemIds] exactly once, regardless of how
     * many separate merge writes (the item's own row, any number of its tag-links) contributed it
     * to the set -- callers dedupe via a `Set` before calling this (see SyncManager.runSync). A
     * full sync of an item with T changed tag-links used to trigger 1 (mergeItem) + T
     * (mergeItemTag) independent FTS rebuilds for that one item in a single pull page; this
     * collapses that to exactly 1, turning the old O(items x tags) rebuild cost into O(items).
     * [FtsIndexer.index] already degrades to a remove for a tombstoned item internally, so this is
     * correct to call uniformly regardless of whether an id's underlying item is live or deleted.
     */
    suspend fun reindexItems(itemIds: Collection<String>) {
        itemIds.forEach { ftsIndexer.index(it) }
    }

    /**
     * Append-only and immutable: insert if we don't already have this id, otherwise nothing to
     * do. The rowId != -1 gate is what keeps this idempotent under sync's own replay guarantees
     * (SyncManager's doc: "replaying earlier pages on a retry is harmless") -- a re-delivered
     * event (a retried pull page, an event this device already generated and is now seeing echoed
     * back) hits OnConflictStrategy.IGNORE and returns -1, so its signal is *not* folded a second
     * time into the item's engagementSignal total. Without this gate a retried pull page would
     * silently inflate that item's affinity signal on every retry.
     */
    suspend fun mergeEngagementEvent(pulled: SyncEngagementEventDto) {
        val rowId = itemDao.insertEngagementEvent(
            EngagementEventEntity(
                id = pulled.id,
                itemId = pulled.itemId,
                eventType = pulled.eventType,
                value = pulled.value,
                createdAt = pulled.createdAt,
                dirty = false
            )
        )
        if (rowId != -1L) itemDao.incrementEngagementSignal(pulled.itemId, signalForEvent(pulled.eventType, pulled.value))
    }

    // Room's item_tags PK is (itemId, tagId) with no direct "get one including deleted" query
    // exposed; active-only is what merge normally needs, but a tombstone pulled for a link this
    // device never actively had still needs its (already-deleted) local counterpart considered.
    private suspend fun findCrossRefIncludingDeleted(itemId: String, tagId: String): ItemTagCrossRef? =
        itemDao.getActiveCrossRefsForItem(itemId).find { it.tagId == tagId }

    // -----------------------------------------------------------------
    // Push side: dirty rows -> DTOs
    // -----------------------------------------------------------------

    suspend fun getDirtyItemDtos(): List<SyncItemDto> = itemDao.getDirtyItems().map { it.toDto() }

    suspend fun getDirtyTagDtos(): List<SyncTagDto> = itemDao.getDirtyTags().map { it.toDto() }

    suspend fun getDirtyItemTagDtos(): List<SyncItemTagDto> = itemDao.getDirtyItemTags().map { it.toDto() }

    suspend fun getDirtyEngagementEventDtos(): List<SyncEngagementEventDto> =
        itemDao.getDirtyEngagementEvents().map { it.toDto() }

    // -----------------------------------------------------------------
    // Apply acks: clear dirty; reconcile a tag-label-collision remap if the server
    // returned a canonical id different from what we pushed.
    // -----------------------------------------------------------------

    suspend fun applyItemAck(ack: SyncAckDto) = itemDao.clearItemDirty(ack.id)

    suspend fun applyTagAck(ack: SyncAckDto) {
        if (ack.id == ack.clientId) {
            itemDao.clearTagDirty(ack.id)
            return
        }
        // Label collision: server kept a different (older-created) tag as canonical.
        val remapTargetExistsLocally = itemDao.getTagById(ack.id) != null
        if (remapTargetExistsLocally) {
            itemDao.remapItemTagsToTagId(ack.clientId, ack.id)
            itemDao.deleteItemTagsForTag(ack.clientId)
            itemDao.deleteTagById(ack.clientId)
        } else {
            itemDao.renameTagId(ack.clientId, ack.id)
        }
        itemDao.clearTagDirty(ack.id)
    }

    suspend fun applyItemTagAck(ack: SyncItemTagAckDto) = itemDao.clearItemTagDirty(ack.itemId, ack.tagId)

    suspend fun applyEngagementEventAck(ack: SyncAckDto) = itemDao.clearEngagementEventDirty(ack.id)

    // -----------------------------------------------------------------
    // Guest -> account migration and sign-out
    // -----------------------------------------------------------------

    /** Guest -> account migration: everything local needs pushing under the new uid. */
    suspend fun markAllLocalRowsDirty() {
        itemDao.markAllItemsDirty()
        itemDao.markAllTagsDirty()
        itemDao.markAllItemTagsDirty()
    }

    /** Sign-out clears the local cache per the functional spec. */
    suspend fun clearLocalCache() {
        itemDao.clearAllItems()
        itemDao.clearAllTags()
        itemDao.clearAllItemTags()
        itemDao.clearAllEngagementEvents()
    }

    private fun tagContentIdentical(local: TagEntity, pulled: SyncTagDto): Boolean {
        return local.updatedAt == pulled.updatedAt &&
            local.label == pulled.label &&
            local.origin == pulled.origin &&
            local.deletedAt == pulled.deletedAt
    }
}

// -----------------------------------------------------------------
// Entity <-> DTO mapping. Top-level (not repository members) so a DTO<->Entity round-trip test
// can exercise them directly without standing up a real ItemDao — see SyncRepositoryMapperTest.
// -----------------------------------------------------------------

private val syncJson = Json { ignoreUnknownKeys = true }

/**
 * Client-side content-equality check paired with the server's itemContentEqual (mergeLogic.ts) --
 * both gate a merge/push decision on whether an incoming row is a true no-op, so a field missing
 * from either one silently mis-treats a real change as unchanged (M2 of the maintainability
 * audit). Top-level `internal` (not a private SyncRepository member) specifically so
 * SyncRepositoryMapperTest can exercise it directly and exhaustively, one field at a time --
 * mirroring [toDto]/[toEntity] just below, which are top-level for the exact same testing reason.
 */
internal fun itemContentIdentical(local: ItemEntity, pulled: SyncItemDto): Boolean {
    return local.updatedAt == pulled.updatedAt &&
        local.type == pulled.type &&
        local.sourceUrl == pulled.sourceUrl &&
        local.normalizedUrl == pulled.normalizedUrl &&
        local.title == pulled.title &&
        local.bodyText == pulled.bodyText &&
        local.summary == pulled.summary &&
        local.thumbnailUrl == pulled.thumbnailUrl &&
        local.category == pulled.category &&
        local.eventDate == pulled.eventDate?.let { isoDateToEpochMillis(it) } &&
        local.status == pulled.status &&
        local.isStarred == pulled.isStarred &&
        local.summaryEditedByUser == pulled.summaryLocked &&
        local.tagsEditedByUser == pulled.tagsLocked &&
        local.titleEditedByUser == pulled.titleLocked &&
        local.deletedAt == pulled.deletedAt
}

internal fun ItemEntity.toDto(): SyncItemDto = SyncItemDto(
    id = id,
    type = type,
    sourceUrl = sourceUrl,
    normalizedUrl = normalizedUrl,
    title = title,
    bodyText = bodyText,
    summary = summary,
    thumbnailUrl = thumbnailUrl,
    category = category,
    entities = entities?.let { runCatching { syncJson.decodeFromString<SyncEntitiesDto>(it) }.getOrNull() },
    eventDate = eventDate?.let { epochMillisToIsoDate(it) },
    status = status,
    isStarred = isStarred,
    summaryLocked = summaryEditedByUser,
    tagsLocked = tagsEditedByUser,
    titleLocked = titleEditedByUser,
    updatedAt = updatedAt,
    deletedAt = deletedAt
)

internal fun SyncItemDto.toEntity(dirty: Boolean): ItemEntity = ItemEntity(
    id = id,
    type = type,
    sourceUrl = sourceUrl,
    normalizedUrl = normalizedUrl,
    title = title,
    bodyText = bodyText,
    summary = summary,
    thumbnailUrl = thumbnailUrl,
    category = category,
    entities = entities?.let { syncJson.encodeToString(SyncEntitiesDto.serializer(), it) },
    eventDate = eventDate?.let { isoDateToEpochMillis(it) },
    status = status,
    isStarred = isStarred,
    summaryEditedByUser = summaryLocked,
    tagsEditedByUser = tagsLocked,
    titleEditedByUser = titleLocked,
    createdAt = updatedAt, // best-effort: server doesn't track a separate createdAt
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    dirty = dirty
)

internal fun TagEntity.toDto(): SyncTagDto =
    SyncTagDto(id = id, label = label, origin = origin, updatedAt = updatedAt, deletedAt = deletedAt)

internal fun ItemTagCrossRef.toDto(): SyncItemTagDto =
    SyncItemTagDto(itemId = itemId, tagId = tagId, updatedAt = updatedAt, deletedAt = deletedAt)

internal fun EngagementEventEntity.toDto(): SyncEngagementEventDto =
    SyncEngagementEventDto(id = id, itemId = itemId, eventType = eventType, value = value, createdAt = createdAt)

internal fun epochMillisToIsoDate(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()

internal fun isoDateToEpochMillis(iso: String): Long? =
    runCatching { LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
